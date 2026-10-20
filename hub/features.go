package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// ---------- C1 Protocol ----------

type protoErr struct {
	Code   string `json:"code"`
	Rule   string `json:"rule,omitempty"`
	Header string `json:"header,omitempty"`
	Detail string `json:"detail"`
}

func toProtoErr(err error) protoErr {
	var pe *rpe.ProtocolError
	if errors.As(err, &pe) {
		return protoErr{string(pe.Code), pe.Rule, pe.Header, pe.Detail}
	}
	return protoErr{Code: "ERROR", Detail: err.Error()}
}

// validate decodes an X-RPE-Context value with the real rpe-go library.
func (h *hub) validate(w http.ResponseWriter, r *http.Request) {
	var in struct{ Header string }
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	c, err := rpe.DecodeContext(strings.TrimSpace(in.Header))
	if err != nil {
		writeJSON(w, 200, map[string]any{"valid": false, "error": toProtoErr(err)})
		return
	}
	writeJSON(w, 200, map[string]any{"valid": true, "fields": contextFields(c), "unknown": c.Unknown, "canonical": c.Encode()})
}

// transition runs the context guard (R-T01..R-T06) on a parent → child pair.
func (h *hub) transition(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Parent, Child string
		ElapsedMs     int64
	}
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	parent, err := rpe.DecodeContext(in.Parent)
	if err != nil {
		writeJSON(w, 200, map[string]any{"error": "parent: " + err.Error()})
		return
	}
	child, err := rpe.DecodeContext(in.Child)
	if err != nil {
		writeJSON(w, 200, map[string]any{"error": "child: " + err.Error()})
		return
	}
	violations := []protoErr{}
	for _, v := range rpe.ValidateContextTransition(parent, child, time.Duration(in.ElapsedMs)*time.Millisecond) {
		violations = append(violations, toProtoErr(v))
	}
	writeJSON(w, 200, map[string]any{"valid": len(violations) == 0, "violations": violations})
}

func (h *hub) split(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RB, Reserve int64
		N           int
		Mode        string
	}
	if err := decode(r, &in); err != nil || in.N < 1 || in.N > 32 || in.RB < 0 || in.RB > 255 {
		writeErr(w, 400, "rb 0..255 and n 1..32 required")
		return
	}
	grants := rpe.SplitBudget(rpe.SplitMode(in.Mode), in.RB, in.Reserve, in.N)
	var sum int64
	for _, g := range grants {
		sum += g
	}
	writeJSON(w, 200, map[string]any{"grants": grants, "sum": sum, "conserved": in.Mode == "sequential" || sum <= in.RB-in.Reserve})
}

func (h *hub) counters(r *http.Request, name string) map[string]int64 {
	out := map[string]int64{}
	if _, _, b, _, err := h.call(r.Context(), http.MethodGet, svcURL(name, "/admin/counters"), nil); err == nil {
		_ = json.Unmarshal(b, &out)
	}
	return out
}

// delta returns after - before per counter (only counters that changed).
func delta(after, before map[string]int64) map[string]int64 {
	out := map[string]int64{}
	for k, v := range after {
		if d := v - before[k]; d != 0 {
			out[k] = d
		}
	}
	return out
}

// demoDeadline: slow payment beyond the gateway budget. Signal, never reject: the late request is still served,
// the sidecars signal the cancellation (cs=expired in X-RPE-Result) and payment's critical work completes.
func (h *hub) demoDeadline(w http.ResponseWriter, r *http.Request) {
	var in struct{ LatencyMs int }
	_ = decode(r, &in)
	in.LatencyMs = clamp(in.LatencyMs, 1, 10000, 1500)
	h.resetAllServices(r.Context())
	_, _, _, _, _ = h.call(r.Context(), http.MethodPost, svcURL("payment-svc", "/admin/faults"), map[string]any{"latencyMs": in.LatencyMs})
	// Background traffic keeps flowing: let requests that started before the fault finish, then report only
	// what changed during this demo (counter deltas).
	time.Sleep(500 * time.Millisecond)
	payBefore, invBefore := h.counters(r, "payment-svc"), h.counters(r, "inventory-svc")
	res := h.runChain(r.Context(), "/api/orders")
	time.Sleep(300 * time.Millisecond) // let the counters settle
	pay, inv := delta(h.counters(r, "payment-svc"), payBefore), delta(h.counters(r, "inventory-svc"), invBefore)
	_, _, _, _, _ = h.call(r.Context(), http.MethodPost, svcURL("payment-svc", "/admin/faults"), map[string]any{})
	writeJSON(w, 200, map[string]any{"latencyMs": in.LatencyMs, "chain": res, "payment": pay, "inventory": inv})
}

// demoFanout: make one child fail and show partial (optional child) vs failed (required child).
func (h *hub) demoFanout(w http.ResponseWriter, r *http.Request) {
	var in struct{ Failing string }
	_ = decode(r, &in)
	if in.Failing != "loyalty-svc" && in.Failing != "payment-svc" && in.Failing != "inventory-svc" {
		writeErr(w, 400, "failing must be loyalty-svc (optional), payment-svc or inventory-svc (required)")
		return
	}
	h.resetAllServices(r.Context())
	_, _, _, _, _ = h.call(r.Context(), http.MethodPost, svcURL(in.Failing, "/admin/faults"), map[string]any{"errorRate": 1.0})
	res := h.runChain(r.Context(), "/api/orders")
	_, _, _, _, _ = h.call(r.Context(), http.MethodPost, svcURL(in.Failing, "/admin/faults"), map[string]any{})
	writeJSON(w, 200, map[string]any{"failing": in.Failing, "chain": res})
}

// ---------- C2 Interceptor ----------

func (h *hub) sidecars(w http.ResponseWriter, r *http.Request) {
	pods, err := h.kube.pods(r.Context(), h.cfg.demoNamespace)
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	type sc struct {
		Pod     string          `json:"pod"`
		Service string          `json:"service"`
		IP      string          `json:"ip"`
		Health  map[string]any  `json:"health"`
		Policy  json.RawMessage `json:"policy"`
		Stats   SidecarStats    `json:"stats"`
		Error   string          `json:"error,omitempty"`
	}
	var list []*sc
	for _, p := range pods {
		if hasContainer(p, "rpe-sidecar") && p.Status.PodIP != "" {
			list = append(list, &sc{Pod: p.Metadata.Name, Service: p.Metadata.Labels["app"], IP: p.Status.PodIP})
		}
	}
	var wg sync.WaitGroup
	for _, s := range list {
		wg.Add(1)
		go func(s *sc) {
			defer wg.Done()
			if _, _, b, _, err := h.call(r.Context(), http.MethodGet, "http://"+s.IP+":15020/rpe/v1/health", nil); err == nil {
				_ = json.Unmarshal(b, &s.Health)
			} else {
				s.Error = err.Error()
				return
			}
			if _, _, b, _, err := h.call(r.Context(), http.MethodGet, "http://"+s.IP+":15020/rpe/v1/policy", nil); err == nil {
				s.Policy = b
			}
			if _, _, b, _, err := h.call(r.Context(), http.MethodGet, "http://"+s.IP+":15090/metrics", nil); err == nil {
				s.Stats = summarize(parseProm(string(b)))
			}
		}(s)
	}
	wg.Wait()
	writeJSON(w, 200, list)
}

func (h *hub) sidecarIP(r *http.Request) (string, error) {
	pods, err := h.kube.pods(r.Context(), h.cfg.demoNamespace)
	if err != nil {
		return "", err
	}
	for _, p := range pods {
		if p.Metadata.Name == r.PathValue("pod") && hasContainer(p, "rpe-sidecar") {
			return p.Status.PodIP, nil
		}
	}
	return "", fmt.Errorf("no sidecar pod %q", r.PathValue("pod"))
}

func (h *hub) proxySidecar(w http.ResponseWriter, r *http.Request, method, path string, body any) {
	ip, err := h.sidecarIP(r)
	if err != nil {
		writeErr(w, 404, err.Error())
		return
	}
	code, _, b, _, err := h.call(r.Context(), method, "http://"+ip+":15020"+path, body)
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func (h *hub) sidecarPolicy(w http.ResponseWriter, r *http.Request) {
	var in map[string]any
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	h.proxySidecar(w, r, http.MethodPut, "/rpe/v1/policy", in)
}

func (h *hub) sidecarActivate(w http.ResponseWriter, r *http.Request) {
	var in map[string]any
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	h.proxySidecar(w, r, http.MethodPost, "/rpe/v1/policy/activate", in)
}

func (h *hub) sidecarRetire(w http.ResponseWriter, r *http.Request) {
	h.proxySidecar(w, r, http.MethodDelete, "/rpe/v1/policy/"+r.PathValue("epoch"), nil)
}

// ---------- C3 Enforcement ----------

type fleetPod struct {
	Service    string   `json:"service"`
	Pod        string   `json:"pod"`
	Ready      bool     `json:"ready"`
	Containers []string `json:"containers"`
	Inits      []string `json:"initContainers"`
	Injected   bool     `json:"injected"`
	Sidecar    bool     `json:"sidecar"`
	OptedOut   bool     `json:"optedOut"`
	SidecarUID *int64   `json:"sidecarUid"`
	InitExit   *int     `json:"initExitCode"`
	Role       string   `json:"role"`
	Mode       string   `json:"inboundMode"`
}

func describePod(p Pod) fleetPod {
	f := fleetPod{Service: p.Metadata.Labels["app"], Pod: p.Metadata.Name, Ready: podReady(p),
		Injected: p.Metadata.Annotations["resilience.rpe.dev/injected"] == "true",
		OptedOut: p.Metadata.Annotations["resilience.rpe.dev/inject"] == "disabled"}
	for _, c := range p.Spec.Containers {
		f.Containers = append(f.Containers, c.Name)
		if c.Name == "rpe-sidecar" {
			f.Sidecar = true
			if c.SecurityContext != nil {
				f.SidecarUID = c.SecurityContext.RunAsUser
			}
			for _, e := range c.Env {
				switch e.Name {
				case "RPE_ROLE":
					f.Role = e.Value
				case "RPE_INBOUND_MODE":
					f.Mode = e.Value
				}
			}
		}
	}
	for _, c := range p.Spec.InitContainers {
		f.Inits = append(f.Inits, c.Name)
	}
	for _, s := range p.Status.InitContainerStatuses {
		if s.Name == "rpe-init" && s.State.Terminated != nil {
			code := s.State.Terminated.ExitCode
			f.InitExit = &code
		}
	}
	return f
}

func (h *hub) fleet(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{}
	var nss []map[string]any
	for _, name := range []string{h.cfg.demoNamespace, "rpe-e2e", h.cfg.systemNamespace} {
		if ns, err := h.kube.namespace(r.Context(), name); err == nil {
			nss = append(nss, map[string]any{"name": name, "injection": ns.Metadata.Labels["rpe-injection"] == "enabled"})
		}
	}
	out["namespaces"] = nss
	pods, err := h.kube.pods(r.Context(), h.cfg.demoNamespace)
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	var list []fleetPod
	eligible, injected := 0, 0
	for _, p := range pods {
		f := describePod(p)
		list = append(list, f)
		if !f.OptedOut {
			eligible++
			if f.Sidecar {
				injected++
			}
		}
	}
	out["pods"] = list
	out["coverage"] = map[string]int{"eligible": eligible, "injected": injected}
	writeJSON(w, 200, out)
}

// simulateAdmission sends a Pod through the real admission chain with dryRun=All.
func (h *hub) simulateAdmission(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name        string
		Port        int
		Annotations map[string]string
	}
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if in.Name == "" {
		in.Name = "admission-preview"
	}
	container := map[string]any{"name": "app", "image": "rpe/demo-app:dev"}
	if in.Port > 0 {
		container["ports"] = []any{map[string]any{"containerPort": in.Port}}
	}
	pod := map[string]any{"apiVersion": "v1", "kind": "Pod",
		"metadata": map[string]any{"name": in.Name, "labels": map[string]string{"app": in.Name}, "annotations": in.Annotations},
		"spec":     map[string]any{"containers": []any{container}}}
	p, code, msg, err := h.kube.dryRunPod(r.Context(), h.cfg.demoNamespace, pod)
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	if code >= 300 {
		writeJSON(w, 200, map[string]any{"allowed": false, "message": msg})
		return
	}
	writeJSON(w, 200, map[string]any{"allowed": true, "pod": describePod(p)})
}

// Policies go through the C3 policy API (Operator): it is the only intake, it enforces the human
// approval record and it owns the PostgreSQL store and the Redis fast path.

func (h *hub) relay(w http.ResponseWriter, r *http.Request, method, path string, body any) {
	code, _, b, _, err := h.call(r.Context(), method, h.cfg.policyAPIURL+path, body)
	if err != nil {
		writeErr(w, 502, "policy API unavailable: "+err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func (h *hub) policies(w http.ResponseWriter, r *http.Request) {
	_, _, pb, _, err := h.call(r.Context(), http.MethodGet, h.cfg.policyAPIURL+"/api/k8s/policies", nil)
	if err != nil {
		writeErr(w, 502, "policy API unavailable: "+err.Error())
		return
	}
	_, _, eb, _, _ := h.call(r.Context(), http.MethodGet, h.cfg.policyAPIURL+"/api/k8s/events", nil)
	if len(eb) == 0 || eb[0] != '[' {
		eb = []byte("[]")
	}
	writeJSON(w, 200, map[string]json.RawMessage{"policies": pb, "events": eb})
}

type policyInput struct {
	PolicyID    string  `json:"policyId"`
	Service     string  `json:"service"`
	Route       string  `json:"route"`
	DeadlineMs  int     `json:"deadlineMs"`
	RetryBudget int     `json:"retryBudget"`
	RetryRatio  float64 `json:"retryRatio"`
	RatePerSec  int     `json:"ratePerSec"`
	Rollout     string  `json:"rollout"` // "", two-phase, naive
	SkewMs      int     `json:"skewMs"`  // simulated propagation spread per proxy (same for every mode)
	Order       string  `json:"order"`   // naive only: services-first or gateway-first instead of the random spread
	// Control selects one independent control: chain-deadline (gateway), rate-limit or retry-ratio (a service).
	// Empty keeps the combined form below.
	Control string `json:"control"`
	// Changes makes one joint decision of several controls (for example a chain deadline and a rate limit): one
	// policy version with a part per service, delivered under one epoch.
	Changes []policyInput `json:"changes"`
}

// Independent controls, each its own policy version and delivered to its own sidecars (A3.4): the chain deadline
// to the gateway, the rate limit and the retry ratio to the target service.
const (
	controlChainDeadline = "chain-deadline"
	controlRateLimit     = "rate-limit"
	controlRetryRatio    = "retry-ratio"
)

// effectivePolicy returns the policy a service currently enforces according to Component 3 (id, route, spec), so a
// change to one control keeps the service's other settings, exactly as Component 4's bridge does.
func (h *hub) effectivePolicy(ctx context.Context, service string) (id, route string, spec map[string]any) {
	_, _, b, _, err := h.call(ctx, http.MethodGet, h.cfg.policyAPIURL+"/api/k8s/effective", nil)
	if err != nil {
		return
	}
	var all map[string]map[string]string
	if json.Unmarshal(b, &all) != nil {
		return
	}
	e, ok := all[service]
	if !ok {
		return
	}
	id, route = e["policyId"], e["route"]
	_ = json.Unmarshal([]byte(e["spec"]), &spec)
	return
}

// controlService validates one control change and returns the service whose sidecars apply it.
func controlService(in policyInput) (service, problem string) {
	switch in.Control {
	case controlChainDeadline:
		if in.Service != "" && in.Service != "gateway" {
			return "", "a chain deadline is applied by the gateway: its service must be gateway"
		}
		if in.Route == "" || in.DeadlineMs <= 0 {
			return "", "a chain deadline needs a route (METHOD /path) and deadlineMs > 0"
		}
		return "gateway", ""
	case controlRateLimit:
		if in.Service == "" || in.Service == "gateway" || in.RatePerSec <= 0 || in.RatePerSec > 1_000_000 {
			return "", "a rate limit needs a target service (not the gateway) and ratePerSec from 1 to 1000000"
		}
		return in.Service, ""
	case controlRetryRatio:
		if in.Service == "" || in.Service == "gateway" || in.RetryRatio < 0 || in.RetryRatio > 1 {
			return "", "a retry ratio needs a target service (not the gateway) and a ratio from 0 to 1"
		}
		return in.Service, ""
	}
	return "", "control must be chain-deadline, rate-limit or retry-ratio"
}

// controlSpec builds the policy of a single control: the spec (the service's current one with only that control
// changed), the scope and the policy id. It returns an error message for an invalid request.
func (h *hub) controlSpec(r *http.Request, in policyInput) (id, service, route string, spec map[string]any, problem string) {
	if service, problem = controlService(in); problem != "" {
		return "", "", "", nil, problem
	}
	id, route, spec = h.effectivePolicy(r.Context(), service)
	if spec == nil {
		spec = map[string]any{}
	}
	route = applyControl(spec, route, in)
	if in.PolicyID != "" {
		id = in.PolicyID
	}
	if id == "" {
		id = service + "-resilience"
	}
	return id, service, route, spec, ""
}

// applyControl writes one control change into a service's spec and returns the policy's route scope.
func applyControl(spec map[string]any, route string, in policyInput) string {
	object := func(parent map[string]any, key string) map[string]any {
		if m, ok := parent[key].(map[string]any); ok {
			return m
		}
		m := map[string]any{}
		parent[key] = m
		return m
	}
	switch in.Control {
	case controlChainDeadline:
		// the gateway creates root contexts: a route deadline is the budget it stamps for that route
		object(object(spec, "routes"), in.Route)["deadlineMs"] = in.DeadlineMs
		if route == "" || route == in.Route {
			route = in.Route
			spec["deadlineMs"] = in.DeadlineMs
		}
	case controlRateLimit:
		object(spec, "leakyBucket")["ratePerSec"] = in.RatePerSec
	case controlRetryRatio:
		object(spec, "retry")["budgetRatio"] = in.RetryRatio
	}
	return route
}

// createPolicy publishes a new approved version (the signed-in user is the approver) and optionally
// starts its rollout.
func (h *hub) createPolicy(w http.ResponseWriter, r *http.Request) {
	var in policyInput
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	u := userOf(r.Context())
	body := map[string]any{"approval": map[string]string{"approvedBy": u.Name, "decision": "approved"}}
	switch {
	case len(in.Changes) > 0:
		id, parts, problem := h.jointParts(r.Context(), in.PolicyID, in.Changes)
		if problem != "" {
			writeErr(w, 400, problem)
			return
		}
		body["policyId"], body["parts"] = id, parts
	case in.Control != "":
		id, service, route, spec, problem := h.controlSpec(r, in)
		if problem != "" {
			writeErr(w, 400, problem)
			return
		}
		body["policyId"], body["scope"], body["spec"] = id, map[string]string{"service": service, "route": route}, spec
	default:
		spec := map[string]any{"deadlineMs": in.DeadlineMs, "retry": map[string]any{"budget": in.RetryBudget, "budgetRatio": in.RetryRatio},
			"leakyBucket": map[string]any{"ratePerSec": in.RatePerSec}}
		if in.Service == "gateway" && in.Route != "" && in.DeadlineMs > 0 {
			// the gateway creates root contexts: a route deadline is the budget it stamps for that route (A3.4)
			spec["routes"] = map[string]any{in.Route: map[string]any{"deadlineMs": in.DeadlineMs}}
		}
		body["policyId"], body["scope"], body["spec"] = in.PolicyID, map[string]string{"service": in.Service, "route": in.Route}, spec
	}
	out, code, b, err := h.publish(r.Context(), u.Name, body, in.Rollout, in.SkewMs, in.Order)
	if err != nil {
		writeErr(w, 502, "policy API unavailable: "+err.Error())
		return
	}
	if code >= 300 {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(code)
		_, _ = w.Write(b)
		return
	}
	writeJSON(w, 201, out)
}

// publish sends one approved policy version to the policy API and, for rollout two-phase or naive, starts its
// rollout. For an error answer of the policy API it returns that status and body.
func (h *hub) publish(ctx context.Context, approver string, body map[string]any, rollout string, skewMs int, order string) (map[string]any, int, []byte, error) {
	code, _, b, _, err := h.call(ctx, http.MethodPost, h.cfg.policyAPIURL+"/api/k8s/policies", body)
	if err != nil || code >= 300 {
		return nil, code, b, err
	}
	var created struct {
		Policy struct {
			PolicyID   string `json:"policyId"`
			Version    int    `json:"version"`
			Epoch      string `json:"epoch"`
			ApprovedBy string `json:"approvedBy"`
		} `json:"policy"`
		Notification map[string]string `json:"notification"`
	}
	_ = json.Unmarshal(b, &created)
	out := map[string]any{"policyId": created.Policy.PolicyID, "version": created.Policy.Version, "epoch": created.Policy.Epoch,
		"approvedBy": created.Policy.ApprovedBy, "notification": created.Notification}
	if rollout == "two-phase" || rollout == "naive" {
		req := map[string]any{"policyId": created.Policy.PolicyID, "version": created.Policy.Version, "mode": rollout, "requestedBy": approver, "skewMs": skewMs}
		if order != "" {
			req["order"] = order
		}
		_, _, rb, _, err := h.call(ctx, http.MethodPost, h.cfg.policyAPIURL+"/api/k8s/rollouts", req)
		if err == nil {
			out["rollout"] = json.RawMessage(rb)
		}
	}
	return out, 201, nil, nil
}

func (h *hub) rollouts(w http.ResponseWriter, r *http.Request) {
	h.relay(w, r, http.MethodGet, "/api/k8s/rollouts", nil)
}

func (h *hub) rollout(w http.ResponseWriter, r *http.Request) {
	h.relay(w, r, http.MethodGet, "/api/k8s/rollouts/"+r.PathValue("name"), nil)
}

func (h *hub) createRollout(w http.ResponseWriter, r *http.Request) {
	var in struct {
		PolicyID string `json:"policyId"`
		Version  int    `json:"version"`
		Mode     string `json:"mode"`
		SkewMs   int    `json:"skewMs"`
		Order    string `json:"order"`
	}
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	h.relay(w, r, http.MethodPost, "/api/k8s/rollouts", map[string]any{"policyId": in.PolicyID, "version": in.Version, "mode": in.Mode,
		"requestedBy": userOf(r.Context()).Name, "skewMs": in.SkewMs, "order": in.Order})
}

func (h *hub) policyStatus(w http.ResponseWriter, r *http.Request) {
	h.relay(w, r, http.MethodGet, "/api/k8s/policies/"+url.PathEscape(r.PathValue("id"))+"/status", nil)
}

// fleetAudit is the Operator's fleet view: coverage audit over every RPE-enabled namespace, the epochs and
// content hashes each proxy holds, and drift.
func (h *hub) fleetAudit(w http.ResponseWriter, r *http.Request) {
	h.relay(w, r, http.MethodGet, "/api/k8s/fleet", nil)
}

// fleetReinject recreates a pod that runs without the sidecar (coverage gap) so admission injects it.
func (h *hub) fleetReinject(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Namespace string `json:"namespace"`
		Pod       string `json:"pod"`
	}
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	h.relay(w, r, http.MethodPost, "/api/k8s/fleet/reinject", in)
}

func (h *hub) effective(w http.ResponseWriter, r *http.Request) {
	h.relay(w, r, http.MethodGet, "/api/k8s/effective", nil)
}

// ---------- C4 Intelligence ----------

func (h *hub) intel(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{}
	if code, _, b, _, err := h.call(r.Context(), http.MethodGet, h.cfg.intelligenceURL+"/actuator/health", nil); err == nil {
		var health any
		_ = json.Unmarshal(b, &health)
		out["health"], out["healthCode"] = health, code
	} else {
		out["error"] = err.Error()
	}
	if _, _, b, _, err := h.call(r.Context(), http.MethodGet, h.cfg.intelligenceURL+"/api/v2/intelligence", nil); err == nil {
		out["status"] = json.RawMessage(b)
	}
	writeJSON(w, 200, out)
}

var intelScenarios = map[string]bool{"healthy": true, "stressed": true, "overloaded": true, "retry-stressed": true,
	"recovery": true, "deadline-increase": true, "deadline-recovery": true, "multiple-chains": true}

func (h *hub) intelDemo(w http.ResponseWriter, r *http.Request) {
	s := r.PathValue("scenario")
	if !intelScenarios[s] {
		writeErr(w, 404, "unknown scenario")
		return
	}
	code, _, b, _, err := h.call(r.Context(), http.MethodPost, h.cfg.intelligenceURL+"/api/v2/intelligence/demo/"+s, nil)
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

// relayIntel forwards a C4 response unchanged.
func (h *hub) relayIntel(w http.ResponseWriter, r *http.Request, method, path string, body any) {
	code, _, b, _, err := h.callWith(r.Context(), method, h.cfg.intelligenceURL+path, body, map[string]string{"X-RPE-Control-Token": h.cfg.intelToken})
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func (h *hub) intelAudit(w http.ResponseWriter, r *http.Request) {
	limit, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || limit <= 0 || limit > 1000 {
		limit = 200
	}
	h.relayIntel(w, r, http.MethodGet, "/api/v2/intelligence/audit?limit="+strconv.Itoa(limit), nil)
}

func (h *hub) intelConnections(w http.ResponseWriter, r *http.Request) {
	h.relayIntel(w, r, http.MethodGet, "/api/v2/intelligence/connections", nil)
}

// intelReview records a human decision on a C4 recommendation. The reviewer is always the signed-in user
// (never taken from the request body), so the C4 audit trail and the C3 approval record name a real person.
func (h *hub) intelReview(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidPattern.MatchString(id) {
		writeErr(w, 400, "invalid recommendation id")
		return
	}
	var in struct {
		Approve *bool  `json:"approve"`
		Comment string `json:"comment"`
	}
	if err := decode(r, &in); err != nil || in.Approve == nil {
		writeErr(w, 400, "approve (true or false) is required")
		return
	}
	if !*in.Approve && strings.TrimSpace(in.Comment) == "" {
		writeErr(w, 400, "a reason is required to reject a recommendation")
		return
	}
	h.relayIntel(w, r, http.MethodPost, "/api/v2/intelligence/changes/"+id+"/review",
		map[string]any{"reviewer": userOf(r.Context()).Name, "approve": *in.Approve, "comment": strings.TrimSpace(in.Comment)})
}

func (h *hub) intelResetBaselines(w http.ResponseWriter, r *http.Request) {
	h.relayIntel(w, r, http.MethodPost, "/api/v2/intelligence/baselines/reset", nil)
}

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
