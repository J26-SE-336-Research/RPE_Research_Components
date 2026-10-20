package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

type ctxKey struct{}

func withUser(ctx context.Context, u user) context.Context {
	return context.WithValue(ctx, ctxKey{}, u)
}
func userOf(ctx context.Context) user { u, _ := ctx.Value(ctxKey{}).(user); return u }

// Demo topology (Appendix A6). Every demo service gets an RPE sidecar.
var demoServices = []string{"gateway", "order-svc", "inventory-svc", "payment-svc", "loyalty-svc", "fraud-check-svc", "legacy-svc", "shipping-svc"}

var demoEdges = [][2]string{
	{"gateway", "order-svc"}, {"order-svc", "inventory-svc"}, {"order-svc", "payment-svc"},
	{"order-svc", "loyalty-svc"}, {"order-svc", "legacy-svc"}, {"legacy-svc", "shipping-svc"},
}

type hub struct {
	cfg  config
	auth *authn
	kube *kube
	http *http.Client
	demo *consistencyDemo
}

func newHub(cfg config) (*hub, error) {
	return &hub{
		cfg:  cfg,
		auth: newAuthn(cfg.users, cfg.sessionSecret),
		kube: newKube(),
		http: &http.Client{Timeout: 20 * time.Second},
		demo: &consistencyDemo{},
	}, nil
}

func (h *hub) routes() http.Handler {
	api := http.NewServeMux()
	api.HandleFunc("GET /api/hub/me", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, userOf(r.Context())) })
	api.HandleFunc("GET /api/hub/overview", h.overview)
	api.HandleFunc("POST /api/hub/chain", h.chain)
	// Scenario Lab
	api.HandleFunc("GET /api/hub/services", h.services)
	api.HandleFunc("POST /api/hub/services/{name}/faults", h.setFaults)
	api.HandleFunc("POST /api/hub/services/{name}/reset", h.resetService)
	api.HandleFunc("POST /api/hub/services/reset-all", h.resetAll)
	api.HandleFunc("POST /api/hub/load", h.load)
	// C1 Protocol
	api.HandleFunc("POST /api/hub/protocol/validate", h.validate)
	api.HandleFunc("POST /api/hub/protocol/transition", h.transition)
	api.HandleFunc("POST /api/hub/protocol/split", h.split)
	api.HandleFunc("POST /api/hub/demos/deadline", h.demoDeadline)
	api.HandleFunc("POST /api/hub/demos/fanout", h.demoFanout)
	// C2 Interceptor
	api.HandleFunc("GET /api/hub/sidecars", h.sidecars)
	api.HandleFunc("PUT /api/hub/sidecars/{pod}/policy", h.sidecarPolicy)
	api.HandleFunc("POST /api/hub/sidecars/{pod}/activate", h.sidecarActivate)
	api.HandleFunc("DELETE /api/hub/sidecars/{pod}/policy/{epoch}", h.sidecarRetire)
	// C3 Enforcement
	api.HandleFunc("GET /api/hub/fleet", h.fleet)
	api.HandleFunc("POST /api/hub/fleet/simulate", h.simulateAdmission)
	api.HandleFunc("GET /api/hub/fleet/audit", h.fleetAudit)
	api.HandleFunc("POST /api/hub/fleet/reinject", h.fleetReinject)
	api.HandleFunc("GET /api/hub/policies", h.policies)
	api.HandleFunc("POST /api/hub/policies", h.createPolicy)
	api.HandleFunc("GET /api/hub/policies/{id}/status", h.policyStatus)
	api.HandleFunc("GET /api/hub/rollouts", h.rollouts)
	api.HandleFunc("POST /api/hub/rollouts", h.createRollout)
	api.HandleFunc("GET /api/hub/rollouts/{name}", h.rollout)
	api.HandleFunc("GET /api/hub/effective", h.effective)
	api.HandleFunc("GET /api/hub/demos/consistency", h.consistencyRuns)
	api.HandleFunc("POST /api/hub/demos/consistency", h.startConsistency)
	// C4 Intelligence
	api.HandleFunc("GET /api/hub/intel", h.intel)
	api.HandleFunc("POST /api/hub/intel/demo/{scenario}", h.intelDemo)
	api.HandleFunc("GET /api/hub/intel/audit", h.intelAudit)
	api.HandleFunc("GET /api/hub/intel/connections", h.intelConnections)
	api.HandleFunc("POST /api/hub/intel/changes/{id}/review", h.intelReview)
	api.HandleFunc("POST /api/hub/intel/baselines/reset", h.intelResetBaselines)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok\n") })
	mux.HandleFunc("POST /api/hub/login", h.auth.login)
	mux.HandleFunc("POST /api/hub/logout", h.auth.logout)
	mux.Handle("/api/", h.auth.guard(api))
	mux.Handle("/", spa(h.cfg.staticDir))
	return mux
}

// ---------- shared helpers ----------

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func decode(r *http.Request, v any) error {
	if r.Body == nil {
		return nil
	}
	err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(v)
	if err == io.EOF {
		return nil
	}
	return err
}

func svcURL(name, path string) string { return "http://" + name + ".rpe-demo:8080" + path }

// call performs an HTTP request and returns status, headers, body and elapsed time.
func (h *hub) call(ctx context.Context, method, url string, body any) (int, http.Header, []byte, time.Duration, error) {
	return h.callWith(ctx, method, url, body, nil)
}

// callWith is call with extra request headers (e.g. the C4 control token).
func (h *hub) callWith(ctx context.Context, method, url string, body any, headers map[string]string) (int, http.Header, []byte, time.Duration, error) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = strings.NewReader(string(b))
	}
	req, err := http.NewRequestWithContext(ctx, method, url, rd)
	if err != nil {
		return 0, nil, nil, 0, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	start := time.Now()
	resp, err := h.http.Do(req)
	if err != nil {
		return 0, nil, nil, time.Since(start), err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	return resp.StatusCode, resp.Header, data, time.Since(start), nil
}

func (h *hub) demoPods(ctx context.Context) (map[string]Pod, error) {
	pods, err := h.kube.pods(ctx, h.cfg.demoNamespace)
	if err != nil {
		return nil, err
	}
	out := map[string]Pod{}
	for _, p := range pods {
		if app := p.Metadata.Labels["app"]; app != "" {
			out[app] = p
		}
	}
	return out, nil
}

func hasContainer(p Pod, name string) bool {
	for _, c := range p.Spec.Containers {
		if c.Name == name {
			return true
		}
	}
	return false
}

func podReady(p Pod) bool {
	if p.Status.Phase != "Running" || len(p.Status.ContainerStatuses) == 0 {
		return false
	}
	for _, c := range p.Status.ContainerStatuses {
		if !c.Ready {
			return false
		}
	}
	return true
}

// ---------- Overview ----------

func (h *hub) overview(w http.ResponseWriter, r *http.Request) {
	type svc struct {
		Name     string `json:"name"`
		Pod      string `json:"pod"`
		Ready    bool   `json:"ready"`
		Sidecar  bool   `json:"sidecar"`
		RPE      bool   `json:"rpe"`
		Restarts int    `json:"restarts"`
	}
	out := map[string]any{"edges": demoEdges}
	pods, err := h.demoPods(r.Context())
	if err != nil {
		out["error"] = err.Error()
	}
	var services []svc
	injected, eligible := 0, 0
	for _, name := range demoServices {
		p, ok := pods[name]
		s := svc{Name: name, RPE: true}
		if ok {
			s.Pod, s.Ready, s.Sidecar = p.Metadata.Name, podReady(p), hasContainer(p, "rpe-sidecar")
			for _, c := range p.Status.ContainerStatuses {
				s.Restarts += c.RestartCount
			}
		}
		if s.RPE {
			eligible++
			if s.Sidecar {
				injected++
			}
		}
		services = append(services, s)
	}
	out["services"] = services
	out["coverage"] = map[string]int{"injected": injected, "eligible": eligible}
	platform := map[string]bool{}
	if sys, err := h.kube.pods(r.Context(), h.cfg.systemNamespace); err == nil {
		for _, p := range sys {
			platform[p.Metadata.Labels["app"]] = podReady(p)
		}
	}
	out["platform"] = platform
	writeJSON(w, 200, out)
}

// ---------- Chain request (C1 inspector, Overview) ----------

type chainResult struct {
	Status int               `json:"status"`
	Ms     int64             `json:"ms"`
	Result map[string]string `json:"result"`
	Raw    string            `json:"resultRaw"`
	Hops   []Hop             `json:"hops"`
	Error  string            `json:"error,omitempty"`
}

func (h *hub) runChain(ctx context.Context, path string) chainResult {
	if path == "" || !strings.HasPrefix(path, "/") {
		path = "/api/orders"
	}
	code, hdr, body, d, err := h.call(ctx, http.MethodGet, h.cfg.gatewayURL+path, nil)
	res := chainResult{Status: code, Ms: d.Milliseconds(), Hops: []Hop{}}
	if err != nil {
		res.Error = err.Error()
		return res
	}
	res.Raw = hdr.Get("X-RPE-Result")
	res.Result = resultFields(res.Raw)
	var root demoResponse
	if json.Unmarshal(body, &root) == nil && root.Service != "" {
		res.Hops = flattenChain(&root)
		res.Hops[0].Status, res.Hops[0].Millis = code, d.Milliseconds()
	}
	return res
}

func (h *hub) chain(w http.ResponseWriter, r *http.Request) {
	var in struct{ Path string }
	_ = decode(r, &in)
	writeJSON(w, 200, h.runChain(r.Context(), in.Path))
}

// ---------- Scenario Lab ----------

func (h *hub) services(w http.ResponseWriter, r *http.Request) {
	type state struct {
		Name     string           `json:"name"`
		Faults   map[string]any   `json:"faults"`
		Counters map[string]int64 `json:"counters"`
		Error    string           `json:"error,omitempty"`
	}
	out := make([]state, len(demoServices))
	var wg sync.WaitGroup
	for i, name := range demoServices {
		wg.Add(1)
		go func(i int, name string) {
			defer wg.Done()
			s := state{Name: name, Faults: map[string]any{}, Counters: map[string]int64{}}
			if _, _, b, _, err := h.call(r.Context(), http.MethodGet, svcURL(name, "/admin/faults"), nil); err == nil {
				_ = json.Unmarshal(b, &s.Faults)
			} else {
				s.Error = err.Error()
			}
			if _, _, b, _, err := h.call(r.Context(), http.MethodGet, svcURL(name, "/admin/counters"), nil); err == nil {
				_ = json.Unmarshal(b, &s.Counters)
			}
			out[i] = s
		}(i, name)
	}
	wg.Wait()
	writeJSON(w, 200, out)
}

func knownService(name string) bool {
	for _, s := range demoServices {
		if s == name {
			return true
		}
	}
	return false
}

func (h *hub) setFaults(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if !knownService(name) {
		writeErr(w, 404, "unknown service")
		return
	}
	var f map[string]any
	if err := decode(r, &f); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	code, _, b, _, err := h.call(r.Context(), http.MethodPost, svcURL(name, "/admin/faults"), f)
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func (h *hub) resetService(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if !knownService(name) {
		writeErr(w, 404, "unknown service")
		return
	}
	if _, _, _, _, err := h.call(r.Context(), http.MethodPost, svcURL(name, "/admin/reset"), nil); err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	w.WriteHeader(204)
}

func (h *hub) resetAllServices(ctx context.Context) {
	var wg sync.WaitGroup
	for _, s := range demoServices {
		wg.Add(1)
		go func(s string) {
			defer wg.Done()
			_, _, _, _, _ = h.call(ctx, http.MethodPost, svcURL(s, "/admin/reset"), nil)
		}(s)
	}
	wg.Wait()
}

func (h *hub) resetAll(w http.ResponseWriter, r *http.Request) {
	h.resetAllServices(r.Context())
	w.WriteHeader(204)
}

func (h *hub) load(w http.ResponseWriter, r *http.Request) {
	var in struct{ Count, Concurrency int }
	_ = decode(r, &in)
	in.Count = clamp(in.Count, 1, 500, 50)
	in.Concurrency = clamp(in.Concurrency, 1, 25, 5)
	type one struct {
		code int
		ms   int64
		term bool
	}
	results := make([]one, in.Count)
	jobs := make(chan int)
	var wg sync.WaitGroup
	start := time.Now()
	for c := 0; c < in.Concurrency; c++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range jobs {
				code, hdr, _, d, err := h.call(r.Context(), http.MethodGet, h.cfg.gatewayURL+"/api/orders", nil)
				if err != nil {
					code = 0
				}
				results[i] = one{code, d.Milliseconds(), strings.Contains(hdr.Get("X-RPE-Result"), "term=?1")}
			}
		}()
	}
	for i := 0; i < in.Count; i++ {
		jobs <- i
	}
	close(jobs)
	wg.Wait()
	statuses := map[string]int{}
	lat := make([]int64, 0, len(results))
	terminal := 0
	for _, x := range results {
		statuses[fmt.Sprint(x.code)]++
		lat = append(lat, x.ms)
		if x.term {
			terminal++
		}
	}
	sort.Slice(lat, func(i, j int) bool { return lat[i] < lat[j] })
	pct := func(p float64) int64 { return lat[min(len(lat)-1, int(p*float64(len(lat))))] }
	writeJSON(w, 200, map[string]any{
		"count": in.Count, "concurrency": in.Concurrency, "durationMs": time.Since(start).Milliseconds(),
		"statuses": statuses, "terminal": terminal, "p50Ms": pct(0.5), "p95Ms": pct(0.95), "p99Ms": pct(0.99), "maxMs": lat[len(lat)-1],
	})
}

func clamp(v, lo, hi, def int) int {
	if v == 0 {
		return def
	}
	return max(lo, min(hi, v))
}
