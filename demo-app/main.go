// Command demo-svc is the configurable RPE demo microservice. One binary plays every role in the
// demo topology (gateway, order, inventory, payment, loyalty, fraud-check, legacy, shipping),
// configured through environment variables. It exposes fault knobs and side-effect counters so
// that every RPE feature can be demonstrated and tested with real traffic.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand/v2"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// Faults are the runtime fault knobs (POST /admin/faults).
type Faults struct {
	LatencyMs     int     `json:"latencyMs"`
	JitterMs      int     `json:"jitterMs"`
	ErrorRate     float64 `json:"errorRate"`
	CPUBurnMs     int     `json:"cpuBurnMs"`
	ClockOffsetMs int     `json:"clockOffsetMs"`
}

// Child is one downstream call made by this service.
type Child struct {
	Name     string
	URL      string
	Optional bool
}

type ChildResult struct {
	Name     string `json:"name"`
	Status   int    `json:"status"`
	Outcome  string `json:"outcome"`
	Terminal bool   `json:"terminal"`
	Millis   int64  `json:"ms"`
	Error    string `json:"error,omitempty"`
	// Downstream is the child's own response body, so one request shows the context seen at every hop.
	Downstream *Response `json:"downstream,omitempty"`
}

type Response struct {
	Service     string        `json:"service"`
	Context     string        `json:"context,omitempty"`
	RemainingMs int64         `json:"remainingMs,omitempty"`
	Outcome     string        `json:"outcome"`
	Children    []ChildResult `json:"children,omitempty"`
}

type service struct {
	name        string
	rpeEnabled  bool
	children    []Child
	split       rpe.SplitMode
	fanout      string        // partial | all
	sideEffect  string        // counter incremented when this service commits
	nonCritical bool          // optional work: skipped when the chain is cancelled (the developer decides)
	baseLatency time.Duration // simulated steady work (e.g. a payment provider call)
	baseJitter  time.Duration
	client      *http.Client
	mu          sync.Mutex
	faults      Faults
	counters    map[string]int64
}

func main() {
	s := newService()
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	mux.HandleFunc("/admin/faults", s.handleFaults)
	mux.HandleFunc("/admin/counters", s.handleCounters)
	mux.HandleFunc("/admin/reset", s.handleReset)
	var app http.Handler = http.HandlerFunc(s.handle)
	if s.rpeEnabled {
		budgets := routeBudgets(env("ROUTE_BUDGETS", ""), envInt("DEFAULT_BUDGET_MS", 1000))
		epoch := func() string { return env("POLICY_EPOCH", "") }
		if url := env("POLICY_FOLLOW_URL", ""); url != "" {
			follower := newPolicyFollower(url)
			go follower.run(context.Background(), 2*time.Second)
			budgets = follower.RouteBudget(budgets)
			epoch = func() string { return follower.Epoch(env("POLICY_EPOCH", "")) }
		}
		if env("ROLE", "service") == "gateway" {
			budgets = stampedBudget(budgets)
		}
		cfg := rpe.SignalConfig{
			Gateway:     env("ROLE", "service") == "gateway",
			RouteBudget: budgets,
			RetryBudget: int64(envInt("RETRY_BUDGET", 3)),
			Epoch:       epoch,
			Now:         s.now,
		}
		if cfg.Gateway {
			app = stampedEpoch(cfg, app)
		} else {
			app = rpe.SignalMiddleware(cfg, app)
		}
	}
	// Metrics and traces are produced by the RPE sidecar next to this service, not by the service itself.
	mux.Handle("/", app)

	srv := &http.Server{Addr: ":" + env("PORT", "8080"), Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() {
		log.Printf("demo-svc %s listening on %s (rpe=%v, children=%d)", s.name, srv.Addr, s.rpeEnabled, len(s.children))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
}

func newService() *service {
	s := &service{
		name:        env("SERVICE_NAME", "demo-svc"),
		rpeEnabled:  env("RPE_ENABLED", "true") == "true",
		split:       rpe.SplitMode(env("SPLIT_MODE", string(rpe.SplitParallel))),
		fanout:      env("FANOUT", "partial"),
		sideEffect:  env("SIDE_EFFECT", ""),
		nonCritical: env("NON_CRITICAL", "false") == "true",
		counters:    map[string]int64{},
		baseLatency: time.Duration(envInt("BASE_LATENCY_MS", 0)) * time.Millisecond,
		baseJitter:  time.Duration(envInt("BASE_JITTER_MS", 0)) * time.Millisecond,
	}
	s.children = parseChildren(env("CHILDREN", ""))
	var transport http.RoundTripper = http.DefaultTransport
	if s.rpeEnabled {
		transport = &rpe.SignalTransport{Reserve: time.Duration(envInt("RESERVE_MS", 5)) * time.Millisecond, Now: s.now}
	}
	s.client = &http.Client{Transport: transport, Timeout: 30 * time.Second}
	return s
}

// now applies the clock-offset fault knob (used by the clock-skew experiments).
func (s *service) now() time.Time {
	s.mu.Lock()
	off := s.faults.ClockOffsetMs
	s.mu.Unlock()
	return time.Now().Add(time.Duration(off) * time.Millisecond)
}

func (s *service) handle(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	f := s.faults
	s.mu.Unlock()
	start := time.Now()
	resp := Response{Service: s.name, Outcome: string(rpe.OutcomeOK)}
	in, hasCtx := rpe.FromContext(r.Context())
	if hasCtx {
		resp.Context = in.Ctx.Encode()
	}
	// The developer decides what the cancellation signal means for this service: optional work is skipped, critical
	// work (for example charging the card) always completes. The request is answered either way.
	if hasCtx && s.nonCritical && rpe.Cancelled(r.Context()) {
		s.count("work_skipped", 1)
		resp.Outcome = string(rpe.OutcomeStopped)
		w.Header().Set(rpe.HeaderResult, rpe.Result{Outcome: rpe.OutcomeStopped, Reason: rpe.ReasonCancelled,
			PolicyEpoch: in.Ctx.PolicyEpoch}.Encode())
		writeJSON(w, http.StatusOK, resp)
		return
	}

	// Simulated work: steady base latency, injected latency, CPU burn, injected errors.
	delay := s.baseLatency + time.Duration(f.LatencyMs)*time.Millisecond
	if s.baseJitter > 0 {
		delay += time.Duration(rand.Int64N(int64(s.baseJitter)))
	}
	if f.JitterMs > 0 {
		delay += time.Duration(rand.IntN(f.JitterMs)) * time.Millisecond
	}
	// The work runs to completion: the deadline is a signal, it never cuts work off here.
	time.Sleep(delay)
	burnCPU(r.Context(), time.Duration(f.CPUBurnMs)*time.Millisecond)
	if f.ErrorRate > 0 && rand.Float64() < f.ErrorRate {
		s.count("errors_injected", 1)
		writeJSON(w, http.StatusServiceUnavailable, Response{Service: s.name, Outcome: string(rpe.OutcomeFailed)})
		return
	}

	// Downstream calls (optional ones are skipped once the chain is cancelled).
	if len(s.children) > 0 {
		results := s.callChildren(r)
		resp.Children = results
		status, outcome, terminal := s.combine(results)
		resp.Outcome = outcome
		if status != http.StatusOK {
			if hasCtx {
				reason := rpe.ReasonBusiness
				if status == http.StatusGatewayTimeout {
					reason = rpe.ReasonDeadline
				}
				w.Header().Set(rpe.HeaderResult, rpe.Result{Outcome: rpe.Outcome(outcome), Reason: reason, Terminal: terminal,
					Children: annotate(results), PolicyEpoch: in.Ctx.PolicyEpoch}.Encode())
			}
			writeJSON(w, status, resp)
			return
		}
	}

	// Commit: a critical side effect completes even when the request is late (the developer's decision).
	if s.sideEffect != "" {
		s.count(s.sideEffect, 1)
	}
	if hasCtx {
		resp.RemainingMs = in.Remaining(s.now()).Milliseconds()
		late := resp.RemainingMs < 0 || in.Ctx.Cancel != rpe.CancelNone
		w.Header().Set(rpe.HeaderResult, rpe.Result{Outcome: rpe.Outcome(resp.Outcome), Reason: rpe.ReasonNone, Late: late,
			Children: annotate(resp.Children), PolicyEpoch: in.Ctx.PolicyEpoch}.Encode())
	}
	s.count("requests_ok", 1)
	s.countDuration(time.Since(start))
	writeJSON(w, http.StatusOK, resp)
}

// callChildren calls the downstream services. Optional children are this demo's non-critical work: they go through
// rpe.CallIfNotCancelled, which does not send them once the chain is cancelled (skipped, not an error). Required
// children are always called.
func (s *service) callChildren(r *http.Request) []ChildResult {
	results := make([]ChildResult, len(s.children))
	in, hasCtx := rpe.FromContext(r.Context())
	grants := make([]int64, len(s.children))
	if hasCtx {
		grants = rpe.SplitBudget(s.split, in.Ctx.RetryBudget, 0, len(s.children))
	}
	call := func(i int) {
		c := s.children[i]
		ctx := r.Context()
		if hasCtx {
			ctx = rpe.WithChildGrant(ctx, grants[i])
		}
		start := time.Now()
		req, err := http.NewRequestWithContext(ctx, r.Method, c.URL, nil)
		if err != nil {
			results[i] = ChildResult{Name: c.Name, Status: 0, Outcome: string(rpe.OutcomeFailed), Error: err.Error()}
			return
		}
		var res *http.Response
		if c.Optional && s.fanout == "partial" {
			optional := rpe.CallIfNotCancelled(ctx, s.client, req)
			if optional.Skipped {
				results[i] = ChildResult{Name: c.Name, Outcome: "skipped"}
				return
			}
			res, err = optional.Response, optional.Err
		} else {
			res, err = s.client.Do(req)
		}
		cr := ChildResult{Name: c.Name, Millis: time.Since(start).Milliseconds()}
		if err != nil {
			cr.Outcome, cr.Error = string(rpe.OutcomeFailed), err.Error()
		} else {
			cr.Status = res.StatusCode
			cr.Outcome = string(rpe.OutcomeOK)
			if res.StatusCode >= 400 {
				cr.Outcome = string(rpe.OutcomeFailed)
			}
			if result, ok, _ := rpe.ParseResult(res.Header); ok {
				cr.Terminal = result.Terminal
				if result.Reason == rpe.ReasonDeadline || result.Reason == rpe.ReasonInsufficientTime {
					cr.Outcome = "expired"
				}
			}
			var down Response
			if json.NewDecoder(res.Body).Decode(&down) == nil && down.Service != "" {
				cr.Downstream = &down
			}
			res.Body.Close()
		}
		results[i] = cr
	}
	if s.split == rpe.SplitSequential {
		for i := range s.children {
			call(i)
		}
		return results
	}
	var wg sync.WaitGroup
	for i := range s.children {
		wg.Add(1)
		go func(i int) { defer wg.Done(); call(i) }(i)
	}
	wg.Wait()
	return results
}

// combine applies the fan-out policy (R-F01..R-F03).
func (s *service) combine(results []ChildResult) (status int, outcome string, terminal bool) {
	failedRequired, failedOptional, expired := false, false, false
	for i, r := range results {
		if r.Outcome == string(rpe.OutcomeOK) {
			continue
		}
		if r.Outcome == "expired" || r.Terminal {
			expired = true
		}
		if s.children[i].Optional && s.fanout == "partial" {
			failedOptional = true
		} else {
			failedRequired = true
		}
	}
	switch {
	case failedRequired && expired:
		return http.StatusGatewayTimeout, string(rpe.OutcomeFailed), true // R-F03: terminal, never retried
	case failedRequired:
		return http.StatusBadGateway, string(rpe.OutcomeFailed), false
	case failedOptional:
		return http.StatusOK, string(rpe.OutcomePartial), false
	}
	return http.StatusOK, string(rpe.OutcomeOK), false
}

func annotate(results []ChildResult) string {
	parts := make([]string, 0, len(results))
	for _, r := range results {
		parts = append(parts, fmt.Sprintf("%s:%s:%d", r.Name, r.Outcome, r.Millis))
	}
	return strings.Join(parts, ";")
}

func (s *service) handleFaults(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		var f Faults
		if err := json.NewDecoder(r.Body).Decode(&f); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		s.faults = f
		s.mu.Unlock()
	}
	s.mu.Lock()
	f := s.faults
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, f)
}

func (s *service) handleCounters(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	out := make(map[string]int64, len(s.counters))
	for k, v := range s.counters {
		out[k] = v
	}
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, out)
}

func (s *service) handleReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST required", http.StatusMethodNotAllowed)
		return
	}
	s.mu.Lock()
	s.counters = map[string]int64{}
	s.faults = Faults{}
	s.mu.Unlock()
	w.WriteHeader(http.StatusNoContent)
}

func (s *service) count(name string, n int64) {
	s.mu.Lock()
	s.counters[name] += n
	s.mu.Unlock()
}

func (s *service) countDuration(d time.Duration) { s.count("busy_ms", d.Milliseconds()) }

// parseChildren parses CHILDREN="name=url[?optional],name=url".
func parseChildren(spec string) []Child {
	var out []Child
	for _, part := range strings.Split(spec, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		name, url, ok := strings.Cut(part, "=")
		if !ok {
			log.Fatalf("CHILDREN entry %q must be name=url", part)
		}
		optional := strings.HasSuffix(url, "?optional")
		out = append(out, Child{Name: name, URL: strings.TrimSuffix(url, "?optional"), Optional: optional})
	}
	return out
}

// routeBudgets parses ROUTE_BUDGETS="GET /api/orders=1000;POST /api/orders=2000".
func routeBudgets(spec string, defMs int) func(*http.Request) (string, time.Duration) {
	rules := map[string]int{}
	for _, rule := range strings.Split(spec, ";") {
		if k, v, ok := strings.Cut(strings.TrimSpace(rule), "="); ok {
			if ms, err := strconv.Atoi(v); err == nil {
				rules[k] = ms
			}
		}
	}
	return func(r *http.Request) (string, time.Duration) {
		route := r.Method + " " + r.URL.Path
		if ms, ok := rules[route]; ok {
			return route, time.Duration(ms) * time.Millisecond
		}
		return route, time.Duration(defMs) * time.Millisecond
	}
}

func burnCPU(ctx context.Context, d time.Duration) {
	end := time.Now().Add(d)
	for x := 0; time.Now().Before(end) && ctx.Err() == nil; x++ {
		_ = x
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(k)); err == nil {
		return v
	}
	return def
}
