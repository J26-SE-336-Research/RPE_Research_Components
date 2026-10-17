package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
)

func pod(name, app, role, ip string, sidecar bool) *corev1.Pod {
	p := &corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: "rpe-demo", Labels: map[string]string{"app": app}, Annotations: map[string]string{}},
		Spec:   corev1.PodSpec{Containers: []corev1.Container{{Name: "app"}}},
		Status: corev1.PodStatus{Phase: corev1.PodRunning, PodIP: ip}}
	if role != "" {
		p.Annotations["resilience.rpe.dev/role"] = role
	}
	if sidecar {
		p.Spec.Containers = append(p.Spec.Containers, corev1.Container{Name: "rpe-sidecar"})
	}
	return p
}

func TestResolveTargetsGatewaysFirst(t *testing.T) {
	s := runtime.NewScheme()
	_ = clientgoscheme.AddToScheme(s)
	c := fake.NewClientBuilder().WithScheme(s).WithObjects(
		pod("pay-1", "payment-svc", "", "10.0.0.2", true),
		pod("pay-2", "payment-svc", "", "10.0.0.3", true),
		pod("gw-1", "gateway", "gateway", "10.0.0.1", true),
		pod("inv-1", "inventory-svc", "", "10.0.0.4", true),
		pod("legacy-1", "payment-svc", "", "10.0.0.5", false), // no proxy: never a target
	).Build()
	got, err := resolveTargets(context.Background(), c, "rpe-demo", "payment-svc")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 || got[0].Role != "gateway" {
		t.Fatalf("want gateway first then 2 payment proxies, got %+v", got)
	}
	for _, tg := range got {
		if tg.Service == "inventory-svc" || tg.Pod == "legacy-1" {
			t.Fatalf("unexpected target %+v", tg)
		}
	}
}

func TestServiceNameFollowsTheWebhook(t *testing.T) {
	p := pod("x", "app-label", "", "", true)
	p.Annotations["resilience.rpe.dev/service"] = "annotated"
	if serviceOf(*p) != "annotated" {
		t.Fatal("the resilience.rpe.dev/service annotation wins")
	}
	p = pod("x", "", "", "", true)
	p.Labels["app.kubernetes.io/name"] = "named"
	if serviceOf(*p) != "named" {
		t.Fatal("app.kubernetes.io/name is the fallback")
	}
}

func TestCreatePolicyRequiresHumanApproval(t *testing.T) {
	a := &api{waiters: newWaiters()}
	h := a.routes()
	for _, body := range []string{
		`{"policyId":"p1","scope":{"service":"payment-svc"},"approval":{"decision":"approved"}}`,
		`{"policyId":"p1","scope":{"service":"payment-svc"},"approval":{"approvedBy":"sre","decision":"pending"}}`,
	} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/k8s/policies", strings.NewReader(body)))
		if rec.Code != 422 {
			t.Fatalf("policy without a human approval must be refused (422), got %d for %s", rec.Code, body)
		}
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/k8s/policies", strings.NewReader(`{"policyId":"Bad_Name","scope":{"service":"x"}}`)))
	if rec.Code != 400 {
		t.Fatalf("invalid policy id must be rejected, got %d", rec.Code)
	}
}

func TestAckRejectsUnknownStates(t *testing.T) {
	h := (&api{waiters: newWaiters()}).routes()
	for _, body := range []string{`{"epoch":"p-v1"}`, `{"pod":"p","epoch":"p-v1","state":"exploded"}`} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/k8s/acks", strings.NewReader(body)))
		if rec.Code != 400 {
			t.Fatalf("ack %s: want 400, got %d", body, rec.Code)
		}
	}
}

func TestWaitersMatchEpoch(t *testing.T) {
	w := newWaiters()
	ch := w.expect()
	go func() {
		w.notify(`{"policyId":"other","version":1,"epoch":"other-v1"}`)
		w.notify(`{"policyId":"p","version":2,"epoch":"p-v2"}`)
	}()
	got := w.await(ch, "p-v2", time.Second)
	if got == nil || !strings.Contains(got["payload"], "p-v2") {
		t.Fatalf("expected the matching notification, got %v", got)
	}
	if w.await(w.expect(), "never", 50*time.Millisecond) != nil {
		t.Fatal("await must time out without a matching notification")
	}
}

func TestSpecHashVectors(t *testing.T) {
	// the same vectors as netty-sidecar-proxy PolicyStoreTest: both sides must agree byte for byte
	for spec, want := range map[string]string{
		`{"deadlineMs":1200,"retry":{"budget":2,"budgetRatio":0.1},"leakyBucket":{"ratePerSec":0}}`:          "f114f22e7849eefa",
		`{"b":[1,2.5,{"z":null,"a":true}],"a":"x\"y","n":1.0,"m":0.1,"e":1e-7,"big":1e16,"u":"café \u0001"}`: "6538ffb2b2a4413d",
		``: "74234e98afe7498f",
	} {
		if got := specHash(json.RawMessage(spec)); got != want {
			t.Errorf("specHash(%s) = %s, want %s", spec, got, want)
		}
	}
	if specHash(json.RawMessage(`{"a":1,"b":2}`)) != specHash(json.RawMessage(`{"b":2, "a":1.0}`)) {
		t.Fatal("spec hash must not depend on key order, whitespace or 1 vs 1.0")
	}
}

func TestSidecarClientVerifiesTheHash(t *testing.T) {
	px := newFakeProxy(t, nil)
	s := newSidecars()
	spec := json.RawMessage(`{"deadlineMs":800}`)
	held, err := s.put(context.Background(), px.addr(), stagedPolicy{Epoch: "p-v2", Version: 2, State: "staged", Policy: spec})
	if err != nil {
		t.Fatal(err)
	}
	if err := held.verify("p-v2", specHash(spec)); err != nil {
		t.Fatalf("the proxy holds the approved content: %v", err)
	}
	if err := held.verify("p-v2", specHash(json.RawMessage(`{"deadlineMs":900}`))); err == nil || !strings.Contains(err.Error(), "hash mismatch") {
		t.Fatalf("other content must not verify: %v", err)
	}
	if err := held.verify("p-v9", "x"); err == nil {
		t.Fatal("an epoch the proxy does not hold must not verify")
	}
}

func TestDriftOf(t *testing.T) {
	p := Policy{PolicyID: "pay", Epoch: "pay-v2", Spec: json.RawMessage(`{"deadlineMs":800}`)}
	held := func(state, hash string) sidecarEpochs {
		var e sidecarEpochs
		e.Epochs = append(e.Epochs, struct {
			Epoch   string `json:"epoch"`
			Version int    `json:"version"`
			Hash    string `json:"hash"`
			State   string `json:"state"`
		}{"pay-v2", 2, hash, state})
		return e
	}
	good := specHash(p.Spec)
	if d := driftOf("service", held("active", good), []Policy{p}); len(d) != 0 {
		t.Fatalf("no drift expected: %v", d)
	}
	if d := driftOf("service", sidecarEpochs{}, []Policy{p}); len(d) != 1 || !strings.Contains(d[0], "missing") {
		t.Fatalf("a restarted proxy holds nothing: %v", d)
	}
	if d := driftOf("service", held("active", "tampered"), []Policy{p}); len(d) != 1 || !strings.Contains(d[0], "hash mismatch") {
		t.Fatalf("tampered content is drift: %v", d)
	}
	if d := driftOf("service", held("staged", good), []Policy{p}); len(d) != 1 || !strings.Contains(d[0], "not active") {
		t.Fatalf("a service proxy must enforce the version: %v", d)
	}
	if d := driftOf("gateway", held("staged", good), []Policy{p}); len(d) != 0 {
		t.Fatalf("a gateway holding the epoch serves it: %v", d)
	}
}

func TestNamesAndSince(t *testing.T) {
	p := Policy{PolicyID: "payment-deadline", Version: 3, Epoch: "payment-deadline-v3"}
	n := rolloutName(p)
	if !strings.HasPrefix(n, "payment-deadline-v3-") || len(n) > 63 {
		t.Fatalf("rollout name %q", n)
	}
	if since("2026-10-17T10:00:00.250Z", "2026-10-17T10:00:01Z") != 750 || since("", "2026-10-17T10:00:01Z") != 0 {
		t.Fatal("since() must return milliseconds, or 0 when a timestamp is missing")
	}
}
