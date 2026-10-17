package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

var testCfg = config{sidecarImage: "rpe/sidecar:test", sidecarPullPolicy: "IfNotPresent",
	defaultOutboundPorts: "80,8080", defaultInboundMode: "redirect", defaultDeadlineMs: "1000"}

func podJSON(annotations map[string]string, ports ...int) json.RawMessage {
	var cports []map[string]any
	for _, p := range ports {
		cports = append(cports, map[string]any{"containerPort": p})
	}
	p := map[string]any{
		"metadata": map[string]any{"generateName": "payment-7f9c-", "namespace": "rpe-demo",
			"labels": map[string]string{"app": "payment-svc"}, "annotations": annotations},
		"spec": map[string]any{"containers": []any{map[string]any{"name": "app", "ports": cports}}},
	}
	b, _ := json.Marshal(p)
	return b
}

func findContainer(t *testing.T, patch []patchOperation, path string) map[string]any {
	t.Helper()
	for _, op := range patch {
		if op.Path == path {
			if m, ok := op.Value.(map[string]any); ok {
				return m
			}
			if l, ok := op.Value.([]any); ok {
				return l[0].(map[string]any)
			}
		}
	}
	t.Fatalf("no patch for %s", path)
	return nil
}

func envMap(c map[string]any) map[string]string {
	out := map[string]string{}
	for _, e := range c["env"].([]any) {
		m := e.(map[string]any)
		if v, ok := m["value"].(string); ok {
			out[m["name"].(string)] = v
		}
	}
	return out
}

func TestInjectsSidecarWithDefaults(t *testing.T) {
	patch, err := mutatePod(podJSON(nil, 8080), testCfg)
	if err != nil {
		t.Fatal(err)
	}
	if len(patch) != 3 || patch[0].Path != "/metadata/annotations" {
		t.Fatalf("unexpected patch: %+v", patch)
	}
	sc := findContainer(t, patch, "/spec/containers/-")
	if sc["name"] != sidecarName || sc["image"] != "rpe/sidecar:test" {
		t.Fatalf("bad sidecar: %v", sc)
	}
	env := envMap(sc)
	if env["RPE_SERVICE_NAME"] != "payment-svc" || env["RPE_ROLE"] != "service" || env["RPE_APP_PORT"] != "8080" || env["RPE_INBOUND_MODE"] != "redirect" {
		t.Fatalf("bad sidecar env: %v", env)
	}
	sec := sc["securityContext"].(map[string]any)
	if sec["runAsUser"] != proxyID || sec["runAsNonRoot"] != true {
		t.Fatalf("redirect-mode sidecar must run as UID 1337 non-root: %v", sec)
	}
	ic := findContainer(t, patch, "/spec/initContainers")
	if ie := envMap(ic); ie["RPE_INBOUND_PORTS"] != "8080" || ie["RPE_OUTBOUND_PORTS"] != "80,8080" {
		t.Fatalf("bad init env: %v", ie)
	}
}

// Regression test for the legacy bug: egress exclusion used --uid-owner 0, so root app containers bypassed the proxy.
func TestRoutingScriptDoesNotExcludeRoot(t *testing.T) {
	if strings.Contains(routingScript, "--uid-owner 0") {
		t.Fatal("routing script must not exclude UID 0 (root application containers would bypass the sidecar)")
	}
	for _, want := range []string{"--uid-owner 1337", "--gid-owner 1337", "--to-ports 15001", "--to-ports 15006", "--on-port 15006"} {
		if !strings.Contains(routingScript, want) {
			t.Errorf("routing script missing %q", want)
		}
	}
}

func TestTproxyModeRunsWithGroup1337(t *testing.T) {
	patch, err := mutatePod(podJSON(map[string]string{annotationMode: "tproxy", annotationRole: "gateway"}, 8080), testCfg)
	if err != nil {
		t.Fatal(err)
	}
	sc := findContainer(t, patch, "/spec/containers/-")
	sec := sc["securityContext"].(map[string]any)
	if sec["runAsGroup"] != proxyID || envMap(sc)["RPE_ROLE"] != "gateway" {
		t.Fatalf("tproxy sidecar must run with GID 1337: %v", sec)
	}
	if patch[0].Path != "/metadata/annotations/"+escapeJSONPointer(annotationDone) {
		t.Fatalf("existing annotations must be extended, got %s", patch[0].Path)
	}
}

func TestPassesRouteDeadlinesAndMissingContextMode(t *testing.T) {
	ann := map[string]string{annotationRole: "gateway", annotationRoutes: "GET /api/orders=1000;POST /api/orders=2000"}
	patch, err := mutatePod(podJSON(ann, 8080), testCfg)
	if err != nil {
		t.Fatal(err)
	}
	env := envMap(findContainer(t, patch, "/spec/containers/-"))
	if env["RPE_ROUTE_DEADLINES"] != "GET /api/orders=1000;POST /api/orders=2000" {
		t.Fatalf("route deadlines not passed to the sidecar: %v", env)
	}
	if env["RPE_MISSING_CONTEXT"] != "passthrough" {
		t.Fatalf("requests without context (probes, non-RPE callers) must pass through by default: %v", env)
	}
	strict := testCfg
	strict.defaultMissingContext = "reject"
	patch, err = mutatePod(podJSON(nil, 8080), strict)
	if err != nil {
		t.Fatal(err)
	}
	if env := envMap(findContainer(t, patch, "/spec/containers/-")); env["RPE_MISSING_CONTEXT"] != "reject" {
		t.Fatalf("webhook default not applied: %v", env)
	}
}

func TestOptOutAndIdempotency(t *testing.T) {
	for _, ann := range []map[string]string{{annotationInject: "disabled"}, {annotationDone: "true"}} {
		patch, err := mutatePod(podJSON(ann, 8080), testCfg)
		if err != nil || patch != nil {
			t.Fatalf("expected no mutation for %v, got %v %v", ann, patch, err)
		}
	}
}

func TestRejectsInvalidPods(t *testing.T) {
	cases := []json.RawMessage{
		podJSON(nil), // no ports declared and no annotation
		podJSON(map[string]string{annotationIn: "15001"}), // reserved port
		podJSON(map[string]string{annotationRole: "edge"}, 8080),
		podJSON(map[string]string{annotationMode: "magic"}, 8080),
		podJSON(map[string]string{annotationBudget: "0"}, 8080),
		podJSON(map[string]string{annotationOut: "70000"}, 8080),
		podJSON(map[string]string{annotationRoutes: "GET /api/orders"}, 8080),
		podJSON(map[string]string{annotationRoutes: "GET api/orders=100"}, 8080),
		podJSON(map[string]string{annotationRoutes: "GET /api/orders=0"}, 8080),
		podJSON(map[string]string{annotationRoutes: "/api/orders=100"}, 8080),
	}
	for i, c := range cases {
		if _, err := mutatePod(c, testCfg); err == nil {
			t.Errorf("case %d: expected error", i)
		}
	}
}

func TestAdmissionHandler(t *testing.T) {
	review := map[string]any{"apiVersion": "admission.k8s.io/v1", "kind": "AdmissionReview",
		"request": map[string]any{"uid": "u1", "operation": "CREATE", "kind": map[string]string{"kind": "Pod"}, "object": podJSON(nil, 8080)}}
	body, _ := json.Marshal(review)
	rec := httptest.NewRecorder()
	handleAdmission(rec, httptest.NewRequest(http.MethodPost, "/mutate", bytes.NewReader(body)), testCfg)
	var out admissionReview
	if err := json.NewDecoder(rec.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if out.Response == nil || !out.Response.Allowed || out.Response.UID != "u1" || len(out.Response.Patch) == 0 || *out.Response.PatchType != "JSONPatch" {
		t.Fatalf("unexpected response: %+v", out.Response)
	}
}
