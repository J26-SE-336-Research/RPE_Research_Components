package main

// The backpressure demo (C2): it makes payment-svc's inbound leaky bucket the bottleneck and lets the sidecars react.
//   1. the sidecars are set to the chosen backpressure mode: "full" (pressure signals and adaptive concurrency, the
//      default) or "basic" (only the inbound leaky bucket and the circuit breaker), for the comparison;
//   2. payment-svc's rate is lowered through a normal C3 rate-limit policy (delivered two-phase, like any approved
//      change), so the sidecar's own mechanism is not touched;
//   3. many chain requests are sent at once while the sidecars' counters are sampled;
//   4. the previous rate is delivered again, the run watches the recovery, and every sidecar is set back to "full"
//      (always, also when the run fails).
// Each run records what happened during the load, measured by the sidecars themselves, so runs in the two modes can
// be compared side by side.

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
)

const (
	backpressureService = "payment-svc"
	backpressureCaller  = "order-svc"
	maxBackpressureRuns = 20
	// after a failed request the load waits like a client honouring Retry-After, so a fast failure does not loop
	failureBackoff = 200 * time.Millisecond
)

type backpressureConfig struct {
	Mode            string `json:"mode"`            // full | basic
	LowRate         int    `json:"lowRate"`         // payment's rate during the overload (requests/s)
	Concurrency     int    `json:"concurrency"`     // chain requests in flight at once
	LoadSeconds     int    `json:"loadSeconds"`     // how long the load runs
	RecoverySeconds int    `json:"recoverySeconds"` // how long the run keeps watching after the restore
}

func defaultBackpressureConfig() backpressureConfig {
	return backpressureConfig{Mode: "full", LowRate: 50, Concurrency: 150, LoadSeconds: 30, RecoverySeconds: 30}
}

func (c *backpressureConfig) normalize() string {
	d := defaultBackpressureConfig()
	switch c.Mode {
	case "":
		c.Mode = d.Mode
	case "full", "basic":
	default:
		return "mode must be full or basic"
	}
	c.LowRate = clamp(c.LowRate, 1, 1000, d.LowRate)
	c.Concurrency = clamp(c.Concurrency, 1, 600, d.Concurrency)
	c.LoadSeconds = clamp(c.LoadSeconds, 5, 120, d.LoadSeconds)
	c.RecoverySeconds = clamp(c.RecoverySeconds, 0, 120, d.RecoverySeconds)
	return ""
}

// backpressureResults is what happened during the load, from the sidecars' own counters (differences from the start
// of the load to its end) and from samples taken every half second.
type backpressureResults struct {
	ChainOK             int     `json:"chainOk"`
	ChainFailed         int     `json:"chainFailed"` // any other status, or no response
	P50Ms               int64   `json:"p50Ms"`
	P95Ms               int64   `json:"p95Ms"`
	PaymentRejected     float64 `json:"paymentRejected"`     // 503 from payment-svc's full inbound queue
	InboundRejected     float64 `json:"inboundRejected"`     // 503 from a full inbound queue, all services
	OutboundRejected    float64 `json:"outboundRejected"`    // failed fast by a caller: breaker open or outbound queue full
	SignalsSent         float64 `json:"signalsSent"`         // pressure notifications delivered
	PeakPaymentQueue    float64 `json:"peakPaymentQueue"`    // highest fill of payment-svc's queue (%)
	PeakOpenBreakers    float64 `json:"peakOpenBreakers"`    // most breakers open at the same time
	MinLimitToPayment   float64 `json:"minLimitToPayment"`   // lowest order-svc -> payment-svc concurrency limit (-1: unknown)
	PeakWaitingAtCaller float64 `json:"peakWaitingAtCaller"` // most calls waiting in order-svc's outbound queue for payment
}

type backpressureRun struct {
	ID          string               `json:"id"`
	Config      backpressureConfig   `json:"config"`
	BaseRate    int                  `json:"baseRate"` // payment's rate before the run, delivered again at the end
	State       string               `json:"state"`    // running | done | failed
	Stage       string               `json:"stage"`
	Error       string               `json:"error,omitempty"`
	RequestedBy string               `json:"requestedBy"`
	StartedAt   time.Time            `json:"startedAt"`
	LoadFrom    *time.Time           `json:"loadFrom,omitempty"`
	LoadUntil   *time.Time           `json:"loadUntil,omitempty"`
	RestoredAt  *time.Time           `json:"restoredAt,omitempty"`
	FinishedAt  *time.Time           `json:"finishedAt,omitempty"`
	Sent        int                  `json:"sent"`
	Statuses    map[string]int       `json:"statuses"` // "0" = no response (timeout or connection error)
	Cancelled   int                  `json:"cancelled"`
	Results     *backpressureResults `json:"results,omitempty"`
}

type backpressureDemo struct {
	mu   sync.Mutex
	busy bool
	seq  int
	runs []*backpressureRun
}

func (h *hub) backpressureRuns(w http.ResponseWriter, _ *http.Request) {
	h.bp.mu.Lock()
	defer h.bp.mu.Unlock()
	runs := make([]*backpressureRun, 0, len(h.bp.runs))
	for i := len(h.bp.runs) - 1; i >= 0; i-- {
		runs = append(runs, h.bp.runs[i])
	}
	writeJSON(w, 200, map[string]any{"busy": h.bp.busy, "defaults": defaultBackpressureConfig(), "runs": runs})
}

func (h *hub) startBackpressure(w http.ResponseWriter, r *http.Request) {
	var c backpressureConfig
	if err := decode(r, &c); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if problem := c.normalize(); problem != "" {
		writeErr(w, 400, problem)
		return
	}
	h.bp.mu.Lock()
	if h.bp.busy {
		h.bp.mu.Unlock()
		writeErr(w, 409, "a backpressure run is in progress")
		return
	}
	h.bp.busy = true
	h.bp.seq++
	run := &backpressureRun{ID: fmt.Sprintf("bp-%d", h.bp.seq), Config: c, State: "running", Stage: "starting",
		RequestedBy: userOf(r.Context()).Name, StartedAt: time.Now().UTC(), Statuses: map[string]int{}}
	h.bp.runs = append(h.bp.runs, run)
	if len(h.bp.runs) > maxBackpressureRuns {
		h.bp.runs = h.bp.runs[len(h.bp.runs)-maxBackpressureRuns:]
	}
	h.bp.mu.Unlock()
	go h.runBackpressure(run)
	writeJSON(w, 202, run)
}

func (h *hub) updateBackpressure(run *backpressureRun, fn func(*backpressureRun)) {
	h.bp.mu.Lock()
	defer h.bp.mu.Unlock()
	fn(run)
}

func (h *hub) runBackpressure(run *backpressureRun) {
	c := run.Config
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(c.LoadSeconds+c.RecoverySeconds)*time.Second+5*time.Minute)
	defer cancel()
	defer func() {
		// every sidecar goes back to the full mechanism, whatever happened during the run
		restore, done := context.WithTimeout(context.Background(), 30*time.Second)
		defer done()
		_ = h.setBackpressureMode(restore, "full")
		h.updateBackpressure(run, func(r *backpressureRun) {
			now := time.Now().UTC()
			r.FinishedAt = &now
			if r.State == "running" {
				r.State, r.Stage = "done", "finished"
			}
		})
		h.bp.mu.Lock()
		h.bp.busy = false
		h.bp.mu.Unlock()
	}()
	stage := func(s string) { h.updateBackpressure(run, func(r *backpressureRun) { r.Stage = s }) }
	fail := func(err error) {
		h.updateBackpressure(run, func(r *backpressureRun) { r.State, r.Error, r.Stage = "failed", err.Error(), "failed" })
	}
	now := func() *time.Time { t := time.Now().UTC(); return &t }

	// payment's current rate is what the run delivers again at the end (the sidecar default when none is set)
	base := 100
	if _, _, spec := h.effectivePolicy(ctx, backpressureService); spec != nil {
		if lb, ok := spec["leakyBucket"].(map[string]any); ok {
			if v, ok := lb["ratePerSec"].(float64); ok && v >= 1 {
				base = int(v)
			}
		}
	}
	h.updateBackpressure(run, func(r *backpressureRun) { r.BaseRate = base })

	// 1. the backpressure mode on every sidecar
	stage(fmt.Sprintf("setting every sidecar to the %q backpressure mode", c.Mode))
	if err := h.setBackpressureMode(ctx, c.Mode); err != nil {
		fail(err)
		return
	}

	// 2. lower payment's rate (a normal C3 rate-limit policy)
	stage(fmt.Sprintf("lowering %s's rate %d → %d requests/s (C3 rate-limit policy, two-phase)", backpressureService, base, c.LowRate))
	if err := h.deliverRate(ctx, run.RequestedBy, c.LowRate); err != nil {
		fail(err)
		return
	}

	// 3. overload: many chain requests at once, while the sidecars are sampled
	stage(fmt.Sprintf("sending %d chain requests at a time for %d s (%s mode)", c.Concurrency, c.LoadSeconds, c.Mode))
	before := h.backpressureSnapshot(ctx)
	h.updateBackpressure(run, func(r *backpressureRun) { r.LoadFrom = now() })
	res := h.backpressureLoad(ctx, run, c)
	h.updateBackpressure(run, func(r *backpressureRun) { r.LoadUntil = now() })
	after := h.backpressureSnapshot(ctx)
	res.PaymentRejected = after.service(backpressureService).InboundRejected - before.service(backpressureService).InboundRejected
	res.InboundRejected = after.sum(func(b Backpressure) float64 { return b.InboundRejected }) - before.sum(func(b Backpressure) float64 { return b.InboundRejected })
	res.OutboundRejected = after.sum(func(b Backpressure) float64 { return b.OutboundRejected }) - before.sum(func(b Backpressure) float64 { return b.OutboundRejected })
	res.SignalsSent = after.sum(func(b Backpressure) float64 { return b.NotificationsSent }) - before.sum(func(b Backpressure) float64 { return b.NotificationsSent })
	h.updateBackpressure(run, func(r *backpressureRun) { r.Results = &res })

	// 4. deliver the previous rate again and keep watching the recovery
	stage(fmt.Sprintf("restoring %s's rate %d → %d requests/s", backpressureService, c.LowRate, base))
	if err := h.deliverRate(ctx, run.RequestedBy, base); err != nil {
		fail(fmt.Errorf("the rate could not be restored: %w", err))
		return
	}
	h.updateBackpressure(run, func(r *backpressureRun) { r.RestoredAt = now() })
	if c.Mode == "basic" {
		stage("switching every sidecar back to the full backpressure mode")
		if err := h.setBackpressureMode(ctx, "full"); err != nil {
			fail(err)
			return
		}
	}
	stage(fmt.Sprintf("watching the recovery for %d s (clear signal below 30 %%, limits grow back)", c.RecoverySeconds))
	select {
	case <-ctx.Done():
	case <-time.After(time.Duration(c.RecoverySeconds) * time.Second):
	}
}

// sidecarIPs returns service -> pod IP for every demo pod that runs the RPE sidecar.
func (h *hub) sidecarIPs(ctx context.Context) (map[string]string, error) {
	pods, err := h.kube.pods(ctx, h.cfg.demoNamespace)
	if err != nil {
		return nil, err
	}
	out := map[string]string{}
	for _, p := range pods {
		if hasContainer(p, "rpe-sidecar") && p.Status.PodIP != "" {
			out[p.Metadata.Labels["app"]] = p.Status.PodIP
		}
	}
	return out, nil
}

// setBackpressureMode sets the backpressure mode on every sidecar (control API, PUT /rpe/v1/backpressure/mode).
func (h *hub) setBackpressureMode(ctx context.Context, mode string) error {
	ips, err := h.sidecarIPs(ctx)
	if err != nil {
		return err
	}
	var failed []string
	for service, ip := range ips {
		code, _, b, _, err := h.call(ctx, http.MethodPut, "http://"+ip+":15020/rpe/v1/backpressure/mode", map[string]string{"mode": mode})
		if err != nil || code != http.StatusOK {
			failed = append(failed, fmt.Sprintf("%s (%s)", service, nonEmpty(strings.TrimSpace(string(b)), fmt.Sprint(err))))
		}
	}
	if len(failed) > 0 {
		sort.Strings(failed)
		return fmt.Errorf("could not set the %s mode on: %s", mode, strings.Join(failed, ", "))
	}
	return nil
}

// bpSnapshot is every sidecar's backpressure state at one moment.
type bpSnapshot map[string]Backpressure

func (s bpSnapshot) service(name string) Backpressure { return s[name] }

func (s bpSnapshot) sum(f func(Backpressure) float64) float64 {
	total := 0.0
	for _, b := range s {
		total += f(b)
	}
	return total
}

func (h *hub) backpressureSnapshot(ctx context.Context) bpSnapshot {
	snap := bpSnapshot{}
	ips, err := h.sidecarIPs(ctx)
	if err != nil {
		return snap
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	for service, ip := range ips {
		wg.Add(1)
		go func(service, ip string) {
			defer wg.Done()
			if _, _, b, _, err := h.call(ctx, http.MethodGet, "http://"+ip+":15090/metrics", nil); err == nil {
				st := summarize(parseProm(string(b)))
				mu.Lock()
				snap[service] = st.Backpressure
				mu.Unlock()
			}
		}(service, ip)
	}
	wg.Wait()
	return snap
}

// backpressureLoad keeps c.Concurrency chain requests in flight through the gateway for c.LoadSeconds, samples the
// sidecars every half second and returns the chain results and the peaks.
func (h *hub) backpressureLoad(ctx context.Context, run *backpressureRun, c backpressureConfig) backpressureResults {
	loadCtx, stop := context.WithTimeout(ctx, time.Duration(c.LoadSeconds)*time.Second)
	defer stop()
	res := backpressureResults{MinLimitToPayment: -1}
	var mu sync.Mutex
	var durations []int64

	// sampling: payment's queue, open breakers, and order-svc's limit and waiting calls towards payment
	sampled := make(chan struct{})
	go func() {
		defer close(sampled)
		t := time.NewTicker(500 * time.Millisecond)
		defer t.Stop()
		for {
			snap := h.backpressureSnapshot(loadCtx)
			mu.Lock()
			if q := snap.service(backpressureService).QueueUtilization; q > res.PeakPaymentQueue {
				res.PeakPaymentQueue = q
			}
			if open := snap.sum(func(b Backpressure) float64 { return b.OpenBreakers }); open > res.PeakOpenBreakers {
				res.PeakOpenBreakers = open
			}
			for _, p := range snap.service(backpressureCaller).Peers {
				if !strings.HasPrefix(p.Peer, backpressureService) {
					continue
				}
				if res.MinLimitToPayment < 0 || p.ConcurrencyLimit < res.MinLimitToPayment {
					res.MinLimitToPayment = p.ConcurrencyLimit
				}
				if p.Queued > res.PeakWaitingAtCaller {
					res.PeakWaitingAtCaller = p.Queued
				}
			}
			mu.Unlock()
			select {
			case <-loadCtx.Done():
				return
			case <-t.C:
			}
		}
	}()

	// requests may wait long in the queues; a request still waiting when the load ends is abandoned
	client := &http.Client{Timeout: 60 * time.Second}
	var wg sync.WaitGroup
	for i := 0; i < c.Concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for loadCtx.Err() == nil {
				start := time.Now()
				code, cancelled := 0, false
				req, _ := http.NewRequestWithContext(loadCtx, http.MethodGet, h.cfg.gatewayURL+"/api/orders", nil)
				if resp, err := client.Do(req); err == nil {
					code = resp.StatusCode
					cs := resultFields(resp.Header.Get("X-RPE-Result"))["cs"]
					cancelled = cs != "" && cs != "none"
					_ = resp.Body.Close()
				} else if loadCtx.Err() != nil {
					return // abandoned when the load ended: not a result
				}
				ms := time.Since(start).Milliseconds()
				mu.Lock()
				durations = append(durations, ms)
				if code == http.StatusOK {
					res.ChainOK++
				} else {
					res.ChainFailed++
				}
				mu.Unlock()
				h.updateBackpressure(run, func(r *backpressureRun) {
					r.Sent++
					r.Statuses[fmt.Sprint(code)]++
					if cancelled {
						r.Cancelled++
					}
				})
				if code != http.StatusOK {
					select {
					case <-loadCtx.Done():
					case <-time.After(failureBackoff):
					}
				}
			}
		}()
	}
	wg.Wait()
	<-sampled
	mu.Lock()
	defer mu.Unlock()
	if len(durations) > 0 {
		sort.Slice(durations, func(i, j int) bool { return durations[i] < durations[j] })
		res.P50Ms = durations[len(durations)*50/100]
		res.P95Ms = durations[min(len(durations)-1, len(durations)*95/100)]
	}
	return res
}

// deliverRate delivers payment's rate as a new version of its current policy (its other settings are kept) and
// waits until the rollout is complete.
func (h *hub) deliverRate(ctx context.Context, approver string, rate int) error {
	in := policyInput{Control: controlRateLimit, Service: backpressureService, RatePerSec: rate}
	id, route, spec := h.effectivePolicy(ctx, backpressureService)
	if spec == nil {
		spec = map[string]any{}
	}
	route = applyControl(spec, route, in)
	if id == "" {
		id = backpressureService + "-resilience"
	}
	body := map[string]any{"policyId": id, "scope": map[string]string{"service": backpressureService, "route": route}, "spec": spec,
		"approval": map[string]string{"approvedBy": approver, "decision": "approved"}}
	out, code, b, err := h.publish(ctx, approver, body, "two-phase", 0, "")
	switch {
	case err != nil:
		return fmt.Errorf("policy API unavailable: %w", err)
	case code >= 300:
		return fmt.Errorf("policy API: HTTP %d %s", code, strings.TrimSpace(string(b)))
	}
	raw, _ := out["rollout"].(json.RawMessage)
	var ro struct {
		Metadata struct{ Name string } `json:"metadata"`
		Error    string                `json:"error"`
	}
	_ = json.Unmarshal(raw, &ro)
	if ro.Metadata.Name == "" {
		return errors.New("the rollout did not start: " + nonEmpty(ro.Error, string(raw)))
	}
	_, err = h.awaitRollout(ctx, ro.Metadata.Name, false)
	return err
}
