package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// The consistency demo (experiment E4).
//
// One approved decision changes two settings that depend on each other: the chain deadline of GET /api/orders at
// the gateway (1000 → 4000 ms) and the rate of payment-svc's leaky bucket (100 → 6 requests/s). At the low rate a
// burst of chain requests waits in payment's queue for over a second. That is fine with the new 4000 ms deadline, but
// too long for a request that still has the old 1000 ms deadline, so it expires (cs=expired). It is still served,
// only late, because we signal and never reject.
//
// The demo sends the same traffic (a burst of chain requests every few seconds) while the change is delivered
// either naive (each sidecar switches whenever the change reaches it) or two-phase (stage everywhere, the gateway
// loosens first, wait until no old-version request is running, then payment tightens). It counts the requests that
// expired because of the delivery and the requests served under a different version than the one they carry.

const (
	consistencyPolicy  = "chain-orders"
	consistencyRoute   = "GET /api/orders"
	consistencyPayment = "payment-svc"
	maxConsistencyRuns = 30
)

type consistencyConfig struct {
	BaseDeadlineMs int `json:"baseDeadlineMs"`
	NewDeadlineMs  int `json:"newDeadlineMs"`
	BaseRate       int `json:"baseRate"`
	NewRate        int `json:"newRate"`
	Burst          int `json:"burst"`
	BurstEveryMs   int `json:"burstEveryMs"`
	WarmupMs       int `json:"warmupMs"`
	TailMs         int `json:"tailMs"`
}

type consistencyInput struct {
	Mode   string `json:"mode"`   // two-phase | naive
	Order  string `json:"order"`  // naive only: "" (random spread), services-first, gateway-first
	SkewMs int    `json:"skewMs"` // random spread (or, with an order, the gap between the two groups)
	consistencyConfig
}

// demoSample is one chain request of the demo traffic.
type demoSample struct {
	AtMs     int64  `json:"atMs"` // start, relative to the moment the change was published (negative: before)
	Ms       int64  `json:"ms"`
	Status   int    `json:"status"`
	Cancel   string `json:"cs"`
	BudgetMs int64  `json:"bud"` // the chain deadline the gateway stamped
	Epoch    string `json:"pe"`  // the policy version the gateway stamped
}

type demoArrival struct {
	Service string `json:"service"`
	Pod     string `json:"pod"`
	Part    string `json:"part"`
	AtMs    int64  `json:"atMs"` // when the sidecar started enforcing the new version, relative to the change
}

type consistencyRun struct {
	ID          string            `json:"id"`
	Mode        string            `json:"mode"`
	Order       string            `json:"order,omitempty"`
	SkewMs      int               `json:"skewMs"`
	Config      consistencyConfig `json:"config"`
	State       string            `json:"state"` // running | done | failed
	Stage       string            `json:"stage"`
	Error       string            `json:"error,omitempty"`
	StartedAt   time.Time         `json:"startedAt"`
	FinishedAt  *time.Time        `json:"finishedAt,omitempty"`
	RequestedBy string            `json:"requestedBy"`

	Rollout       string `json:"rollout,omitempty"`
	Epoch         string `json:"epoch,omitempty"`
	PreviousEpoch string `json:"previousEpoch,omitempty"`
	// Requests: demo requests sent; During: those that started after the change was published.
	Requests int `json:"requests"`
	During   int `json:"during"`
	// Expired: demo requests after the change that expired (cs=expired); ExpiredOldDeadline: of those, the ones
	// that still carried the old deadline. ExpiredBefore: expired before the change (the baseline; expected 0).
	Expired            int `json:"expired"`
	ExpiredOldDeadline int `json:"expiredOldDeadline"`
	ExpiredBefore      int `json:"expiredBefore"`
	// Failed: demo requests without a 2xx answer (for example 503 from a full queue).
	Failed int `json:"failed"`
	// Mixed: requests the sidecars served under another version of the policy than the one they carry (all traffic).
	Mixed         int64           `json:"mixedPolicyRequests"`
	ConvergenceMs int64           `json:"convergenceMs"`
	GateMs        int64           `json:"gateMs"`
	DrainMs       int64           `json:"drainMs"`
	CompletedAtMs int64           `json:"completedAtMs"`
	Arrivals      []demoArrival   `json:"arrivals"`
	Plan          json.RawMessage `json:"plan,omitempty"`
	Samples       []demoSample    `json:"samples"`
	// Restored: the baseline was delivered again after the run.
	Restored bool `json:"restored"`
}

type consistencyDemo struct {
	mu   sync.Mutex
	runs []*consistencyRun // oldest first
	busy bool
	seq  int
}

func defaultConsistencyConfig() consistencyConfig {
	return consistencyConfig{BaseDeadlineMs: 1000, NewDeadlineMs: 4000, BaseRate: 100, NewRate: 6, Burst: 8, BurstEveryMs: 4000,
		WarmupMs: 6500, TailMs: 9000}
}

func (in *consistencyInput) normalize() string {
	d := defaultConsistencyConfig()
	c := &in.consistencyConfig
	c.BaseDeadlineMs = clamp(c.BaseDeadlineMs, 100, 60000, d.BaseDeadlineMs)
	c.NewDeadlineMs = clamp(c.NewDeadlineMs, 100, 60000, d.NewDeadlineMs)
	c.BaseRate = clamp(c.BaseRate, 1, 1_000_000, d.BaseRate)
	c.NewRate = clamp(c.NewRate, 1, 1_000_000, d.NewRate)
	c.Burst = clamp(c.Burst, 1, 40, d.Burst)
	c.BurstEveryMs = clamp(c.BurstEveryMs, 500, 30000, d.BurstEveryMs)
	c.WarmupMs = clamp(c.WarmupMs, 1000, 30000, d.WarmupMs)
	c.TailMs = clamp(c.TailMs, 2000, 60000, d.TailMs)
	if in.Mode == "" {
		in.Mode = "two-phase"
	}
	switch {
	case in.Mode != "two-phase" && in.Mode != "naive":
		return "mode must be two-phase or naive"
	case in.Order != "" && in.Order != "services-first" && in.Order != "gateway-first":
		return "order must be services-first or gateway-first"
	case in.Order != "" && in.Mode != "naive":
		return "an arrival order applies to naive delivery only"
	case in.SkewMs < 0 || in.SkewMs > 10000:
		return "skewMs must be from 0 to 10000"
	}
	return ""
}

// consistencyRuns lists the runs, newest first; ?id=<run> returns that run only.
func (h *hub) consistencyRuns(w http.ResponseWriter, r *http.Request) {
	h.demo.mu.Lock()
	defer h.demo.mu.Unlock()
	if id := r.URL.Query().Get("id"); id != "" {
		for _, run := range h.demo.runs {
			if run.ID == id {
				writeJSON(w, 200, run)
				return
			}
		}
		writeErr(w, 404, "no such run")
		return
	}
	runs := make([]*consistencyRun, 0, len(h.demo.runs))
	for i := len(h.demo.runs) - 1; i >= 0; i-- {
		runs = append(runs, h.demo.runs[i])
	}
	writeJSON(w, 200, map[string]any{"busy": h.demo.busy, "defaults": defaultConsistencyConfig(), "runs": runs})
}

func (h *hub) startConsistency(w http.ResponseWriter, r *http.Request) {
	var in consistencyInput
	if err := decode(r, &in); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if problem := in.normalize(); problem != "" {
		writeErr(w, 400, problem)
		return
	}
	h.demo.mu.Lock()
	if h.demo.busy {
		h.demo.mu.Unlock()
		writeErr(w, 409, "a consistency run is in progress")
		return
	}
	h.demo.busy = true
	h.demo.seq++
	run := &consistencyRun{ID: fmt.Sprintf("run-%d", h.demo.seq), Mode: in.Mode, Order: in.Order, SkewMs: in.SkewMs, Config: in.consistencyConfig,
		State: "running", Stage: "starting", StartedAt: time.Now().UTC(), RequestedBy: userOf(r.Context()).Name, Arrivals: []demoArrival{}, Samples: []demoSample{}}
	h.demo.runs = append(h.demo.runs, run)
	if len(h.demo.runs) > maxConsistencyRuns {
		h.demo.runs = h.demo.runs[len(h.demo.runs)-maxConsistencyRuns:]
	}
	h.demo.mu.Unlock()
	go h.runConsistency(run)
	writeJSON(w, 202, run)
}

func (h *hub) update(run *consistencyRun, fn func(*consistencyRun)) {
	h.demo.mu.Lock()
	defer h.demo.mu.Unlock()
	fn(run)
}

func (h *hub) runConsistency(run *consistencyRun) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	c := run.Config
	defer func() {
		h.demo.mu.Lock()
		h.demo.busy = false
		now := time.Now().UTC()
		run.FinishedAt = &now
		if run.State == "running" {
			run.State = "done"
		}
		h.demo.mu.Unlock()
	}()
	fail := func(err error) {
		h.update(run, func(r *consistencyRun) { r.State, r.Error, r.Stage = "failed", err.Error(), "failed" })
	}
	stage := func(s string) { h.update(run, func(r *consistencyRun) { r.Stage = s }) }

	// 1. start from the baseline (delivered two-phase, so the way back is safe as well)
	stage(fmt.Sprintf("restoring the baseline: chain deadline %d ms, payment rate %d requests/s", c.BaseDeadlineMs, c.BaseRate))
	if !h.atBaseline(ctx, c) {
		name, err := h.publishConsistency(ctx, run.RequestedBy, c.BaseDeadlineMs, c.BaseRate, "two-phase", "", 0)
		if err == nil {
			_, err = h.awaitRollout(ctx, name, true)
		}
		if err != nil {
			fail(fmt.Errorf("baseline: %w", err))
			return
		}
		time.Sleep(2 * time.Second) // let payment's queue empty at the baseline rate
	}

	// 2. traffic, then the change in the middle of it
	stage(fmt.Sprintf("traffic: a burst of %d chain requests every %d ms", c.Burst, c.BurstEveryMs))
	load := h.startDemoLoad(ctx, c)
	time.Sleep(time.Duration(c.WarmupMs) * time.Millisecond)
	change := time.Now()
	load.mark(change)
	desc := run.Mode
	if run.Order != "" {
		desc += ", " + run.Order
	}
	stage(fmt.Sprintf("delivering the joint change (%s): chain deadline %d → %d ms, payment rate %d → %d requests/s", desc,
		c.BaseDeadlineMs, c.NewDeadlineMs, c.BaseRate, c.NewRate))
	name, err := h.publishConsistency(ctx, run.RequestedBy, c.NewDeadlineMs, c.NewRate, run.Mode, run.Order, run.SkewMs)
	if err != nil {
		load.stop()
		fail(err)
		return
	}
	h.update(run, func(r *consistencyRun) { r.Rollout = name })
	ro, err := h.awaitRollout(ctx, name, false)
	if err != nil {
		load.stop()
		fail(err)
		return
	}
	completedAt := time.Now()
	stage("the new settings are in force; observing the traffic")
	time.Sleep(time.Duration(c.TailMs) * time.Millisecond)
	samples := load.stop()
	if settled, err := h.awaitRollout(ctx, name, true); err == nil {
		ro = settled // the mixed-policy count is final once the rollout settled
	}

	h.update(run, func(r *consistencyRun) {
		r.Epoch, r.PreviousEpoch = ro.Spec.Epoch, ro.Spec.PreviousEpoch
		r.Mixed, r.ConvergenceMs, r.GateMs, r.DrainMs = ro.Status.MixedPolicyRequests, ro.Status.ConvergenceMs, ro.Status.GateMs, ro.Status.DrainMs
		r.CompletedAtMs = completedAt.Sub(change).Milliseconds()
		r.Plan = ro.Status.Plan
		for _, p := range ro.Status.Pods {
			if at, err := time.Parse(time.RFC3339Nano, p.ActiveAt); err == nil {
				r.Arrivals = append(r.Arrivals, demoArrival{Service: p.Service, Pod: p.Name, Part: p.Part, AtMs: at.Sub(change).Milliseconds()})
			}
		}
		sort.Slice(r.Arrivals, func(i, j int) bool { return r.Arrivals[i].AtMs < r.Arrivals[j].AtMs })
		r.Samples = samples
		r.Requests = len(samples)
		for _, s := range samples {
			if s.Status < 200 || s.Status >= 300 {
				r.Failed++
			}
			if s.AtMs < 0 {
				if s.Cancel == "expired" {
					r.ExpiredBefore++
				}
				continue
			}
			r.During++
			if s.Cancel == "expired" {
				r.Expired++
				if s.BudgetMs == int64(c.BaseDeadlineMs) {
					r.ExpiredOldDeadline++
				}
			}
		}
	})

	// 3. leave the baseline in place (delivered two-phase), so the other demos see the usual settings
	stage(fmt.Sprintf("restoring the baseline: chain deadline %d ms, payment rate %d requests/s", c.BaseDeadlineMs, c.BaseRate))
	name, err = h.publishConsistency(ctx, run.RequestedBy, c.BaseDeadlineMs, c.BaseRate, "two-phase", "", 0)
	if err == nil {
		_, err = h.awaitRollout(ctx, name, true)
	}
	h.update(run, func(r *consistencyRun) {
		r.Restored, r.Stage = err == nil, "done"
		if err != nil {
			r.Error = "the baseline could not be restored: " + err.Error()
		}
	})
}

// atBaseline reports whether the gateway and payment-svc enforce the demo policy at its baseline settings.
func (h *hub) atBaseline(ctx context.Context, c consistencyConfig) bool {
	_, _, b, _, err := h.call(ctx, http.MethodGet, h.cfg.policyAPIURL+"/api/k8s/effective", nil)
	if err != nil {
		return false
	}
	var all map[string]map[string]string
	if json.Unmarshal(b, &all) != nil {
		return false
	}
	spec := func(service string) (map[string]any, bool) {
		e, ok := all[service]
		if !ok || e["policyId"] != consistencyPolicy {
			return nil, false
		}
		var s map[string]any
		return s, json.Unmarshal([]byte(e["spec"]), &s) == nil
	}
	gw, ok1 := spec("gateway")
	pay, ok2 := spec(consistencyPayment)
	if !ok1 || !ok2 {
		return false
	}
	routes, _ := gw["routes"].(map[string]any)
	route, _ := routes[consistencyRoute].(map[string]any)
	bucket, _ := pay["leakyBucket"].(map[string]any)
	deadline, _ := route["deadlineMs"].(float64)
	rate, _ := bucket["ratePerSec"].(float64)
	return int(deadline) == c.BaseDeadlineMs && int(rate) == c.BaseRate
}

// publishConsistency approves one version of the demo's joint policy (the chain deadline and payment's rate) and
// starts its rollout. It returns the rollout's name.
func (h *hub) publishConsistency(ctx context.Context, approver string, deadlineMs, rate int, mode, order string, skewMs int) (string, error) {
	id, parts, problem := h.jointParts(ctx, consistencyPolicy, []policyInput{
		{Control: controlChainDeadline, Route: consistencyRoute, DeadlineMs: deadlineMs},
		{Control: controlRateLimit, Service: consistencyPayment, RatePerSec: rate},
	})
	if problem != "" {
		return "", errors.New(problem)
	}
	body := map[string]any{"policyId": id, "parts": parts, "approval": map[string]string{"approvedBy": approver, "decision": "approved"}}
	out, code, b, err := h.publish(ctx, approver, body, mode, skewMs, order)
	switch {
	case err != nil:
		return "", fmt.Errorf("policy API unavailable: %w", err)
	case code >= 300:
		return "", fmt.Errorf("policy API: HTTP %d %s", code, strings.TrimSpace(string(b)))
	}
	raw, _ := out["rollout"].(json.RawMessage)
	var ro struct {
		Metadata struct{ Name string } `json:"metadata"`
		Error    string                `json:"error"`
	}
	_ = json.Unmarshal(raw, &ro)
	if ro.Metadata.Name == "" {
		return "", fmt.Errorf("the rollout did not start: %s", nonEmpty(ro.Error, string(raw)))
	}
	return ro.Metadata.Name, nil
}

// jointParts builds the parts of a joint decision: for each service its current policy with the changes applied, so
// its other settings stay. It returns the policy id (id, or a name made from the services).
func (h *hub) jointParts(ctx context.Context, id string, changes []policyInput) (string, []map[string]any, string) {
	var order []string
	specs := map[string]map[string]any{}
	routes := map[string]string{}
	for _, c := range changes {
		service, problem := controlService(c)
		if problem != "" {
			return "", nil, problem
		}
		if _, ok := specs[service]; !ok {
			_, route, spec := h.effectivePolicy(ctx, service)
			if spec == nil {
				spec = map[string]any{}
			}
			if service == "gateway" {
				// Nothing sets a rate on the gateway (see controlService), but an older policy may still have one.
				// Drop it so it does not travel in the gateway's part; the sidecar keeps its current rate.
				delete(spec, "leakyBucket")
			}
			specs[service], routes[service] = spec, route
			order = append(order, service)
		}
		routes[service] = applyControl(specs[service], routes[service], c)
	}
	if len(order) == 0 {
		return "", nil, "a joint change needs at least one control"
	}
	if id == "" {
		id = "joint-" + strings.Join(order, "-")
	}
	parts := make([]map[string]any, 0, len(order))
	for _, s := range order {
		scope := map[string]string{"service": s}
		if routes[s] != "" {
			scope["route"] = routes[s]
		}
		parts = append(parts, map[string]any{"scope": scope, "spec": specs[s]})
	}
	return id, parts, ""
}

// demoRollout is the part of an RPERollout the demo reads.
type demoRollout struct {
	Spec struct {
		Epoch         string `json:"epoch"`
		PreviousEpoch string `json:"previousEpoch"`
	} `json:"spec"`
	Status struct {
		Phase               string          `json:"phase"`
		Message             string          `json:"message"`
		Settled             bool            `json:"settled"`
		MixedPolicyRequests int64           `json:"mixedPolicyRequests"`
		ConvergenceMs       int64           `json:"convergenceMs"`
		GateMs              int64           `json:"gateMs"`
		DrainMs             int64           `json:"drainMs"`
		Plan                json.RawMessage `json:"plan"`
		Pods                []struct {
			Name     string `json:"name"`
			Service  string `json:"service"`
			Part     string `json:"part"`
			ActiveAt string `json:"activeAt"`
		} `json:"pods"`
	} `json:"status"`
}

// awaitRollout polls a rollout until it is complete (and, with settled, its measurements are final) or failed.
func (h *hub) awaitRollout(ctx context.Context, name string, settled bool) (demoRollout, error) {
	var ro demoRollout
	for {
		_, _, b, _, err := h.call(ctx, http.MethodGet, h.cfg.policyAPIURL+"/api/k8s/rollouts/"+name, nil)
		if err == nil && json.Unmarshal(b, &ro) == nil {
			switch {
			case ro.Status.Phase == "Failed":
				return ro, fmt.Errorf("rollout %s failed: %s", name, ro.Status.Message)
			case ro.Status.Phase == "Complete" && (!settled || ro.Status.Settled):
				return ro, nil
			}
		}
		select {
		case <-ctx.Done():
			return ro, fmt.Errorf("rollout %s did not finish in time", name)
		case <-time.After(150 * time.Millisecond):
		}
	}
}

// demoLoad sends a burst of chain requests through the gateway every BurstEveryMs until stopped.
type demoLoad struct {
	mu      sync.Mutex
	samples []demoSample
	change  time.Time
	starts  []time.Time
	done    chan struct{}
	wg      sync.WaitGroup
}

func (l *demoLoad) mark(t time.Time) {
	l.mu.Lock()
	l.change = t
	l.mu.Unlock()
}

// stop ends the traffic, waits for the requests in flight and returns every request relative to the change.
func (l *demoLoad) stop() []demoSample {
	close(l.done)
	l.wg.Wait()
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]demoSample, len(l.samples))
	for i, s := range l.samples {
		s.AtMs = l.starts[i].Sub(l.change).Milliseconds()
		out[i] = s
	}
	sort.Slice(out, func(i, j int) bool { return out[i].AtMs < out[j].AtMs })
	return out
}

func (h *hub) startDemoLoad(ctx context.Context, c consistencyConfig) *demoLoad {
	l := &demoLoad{done: make(chan struct{}), change: time.Now()}
	client := &http.Client{Timeout: 30 * time.Second}
	one := func() {
		defer l.wg.Done()
		start := time.Now()
		s := demoSample{}
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, h.cfg.gatewayURL+"/api/orders", nil)
		resp, err := client.Do(req)
		if err == nil {
			var root demoResponse
			_ = json.NewDecoder(resp.Body).Decode(&root)
			_ = resp.Body.Close()
			s.Status = resp.StatusCode
			s.Cancel = resultFields(resp.Header.Get("X-RPE-Result"))["cs"]
			if rc, err := rpe.DecodeContext(root.Context); err == nil {
				s.BudgetMs, s.Epoch = rc.BudgetMillis, rc.PolicyEpoch
			}
		}
		s.Ms = time.Since(start).Milliseconds()
		l.mu.Lock()
		l.samples = append(l.samples, s)
		l.starts = append(l.starts, start)
		l.mu.Unlock()
	}
	l.wg.Add(1)
	go func() {
		defer l.wg.Done()
		t := time.NewTicker(time.Duration(c.BurstEveryMs) * time.Millisecond)
		defer t.Stop()
		for {
			for i := 0; i < c.Burst; i++ {
				l.wg.Add(1)
				go one()
			}
			select {
			case <-l.done:
				return
			case <-ctx.Done():
				return
			case <-t.C:
			}
		}
	}()
	return l
}

func nonEmpty(s, def string) string {
	if s == "" {
		return def
	}
	return s
}
