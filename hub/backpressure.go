package main

// The backpressure demo (C2): it makes payment-svc's inbound leaky bucket the bottleneck and lets the sidecars react.
//   1. payment-svc's rate is lowered through a normal C3 rate-limit policy (delivered two-phase, like any approved
//      change), so the sidecar's own mechanism is not touched;
//   2. many chain requests are sent at once: payment's queue fills, its sidecar signals pressure at 50/65/80/95 %,
//      order-svc's sidecar cuts its concurrency to payment, a full queue answers 503 and repeated failures open the
//      breaker;
//   3. the previous rate is delivered again and the queue drains: below 30 % payment's sidecar sends the clear
//      signal and order-svc's limit recovers step by step.
// The Backpressure page shows all of it live from the sidecars' metrics; this run only drives the stages.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	backpressureService = "payment-svc"
	maxBackpressureRuns = 20
)

type backpressureConfig struct {
	LowRate         int `json:"lowRate"`         // payment's rate during the overload (requests/s)
	Concurrency     int `json:"concurrency"`     // chain requests in flight at once
	LoadSeconds     int `json:"loadSeconds"`     // how long the load runs
	RecoverySeconds int `json:"recoverySeconds"` // how long the run keeps watching after the restore
}

func defaultBackpressureConfig() backpressureConfig {
	return backpressureConfig{LowRate: 50, Concurrency: 150, LoadSeconds: 30, RecoverySeconds: 30}
}

func (c *backpressureConfig) normalize() {
	d := defaultBackpressureConfig()
	c.LowRate = clamp(c.LowRate, 1, 1000, d.LowRate)
	c.Concurrency = clamp(c.Concurrency, 1, 300, d.Concurrency)
	c.LoadSeconds = clamp(c.LoadSeconds, 5, 120, d.LoadSeconds)
	c.RecoverySeconds = clamp(c.RecoverySeconds, 0, 120, d.RecoverySeconds)
}

type backpressureRun struct {
	ID          string             `json:"id"`
	Config      backpressureConfig `json:"config"`
	BaseRate    int                `json:"baseRate"` // payment's rate before the run, delivered again at the end
	State       string             `json:"state"`    // running | done | failed
	Stage       string             `json:"stage"`
	Error       string             `json:"error,omitempty"`
	RequestedBy string             `json:"requestedBy"`
	StartedAt   time.Time          `json:"startedAt"`
	LoadFrom    *time.Time         `json:"loadFrom,omitempty"`
	LoadUntil   *time.Time         `json:"loadUntil,omitempty"`
	RestoredAt  *time.Time         `json:"restoredAt,omitempty"`
	FinishedAt  *time.Time         `json:"finishedAt,omitempty"`
	Sent        int                `json:"sent"`
	Statuses    map[string]int     `json:"statuses"` // "0" = no response (timeout or connection error)
	Cancelled   int                `json:"cancelled"`
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
	c.normalize()
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

	// 1. lower payment's rate (a normal C3 rate-limit policy)
	stage(fmt.Sprintf("lowering %s's rate %d → %d requests/s (C3 rate-limit policy, two-phase)", backpressureService, base, c.LowRate))
	if err := h.deliverRate(ctx, run.RequestedBy, c.LowRate); err != nil {
		fail(err)
		return
	}

	// 2. overload: many chain requests at once
	stage(fmt.Sprintf("sending %d chain requests at a time for %d s", c.Concurrency, c.LoadSeconds))
	h.updateBackpressure(run, func(r *backpressureRun) { r.LoadFrom = now() })
	h.backpressureLoad(ctx, run, c)
	h.updateBackpressure(run, func(r *backpressureRun) { r.LoadUntil = now() })

	// 3. deliver the previous rate again and keep watching the recovery
	stage(fmt.Sprintf("restoring %s's rate %d → %d requests/s", backpressureService, c.LowRate, base))
	if err := h.deliverRate(ctx, run.RequestedBy, base); err != nil {
		fail(fmt.Errorf("the rate could not be restored: %w", err))
		return
	}
	h.updateBackpressure(run, func(r *backpressureRun) { r.RestoredAt = now() })
	stage(fmt.Sprintf("watching the recovery for %d s (clear signal below 30 %%, limits grow back)", c.RecoverySeconds))
	select {
	case <-ctx.Done():
	case <-time.After(time.Duration(c.RecoverySeconds) * time.Second):
	}
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

// backpressureLoad keeps c.Concurrency chain requests in flight through the gateway for c.LoadSeconds.
func (h *hub) backpressureLoad(ctx context.Context, run *backpressureRun, c backpressureConfig) {
	loadCtx, stop := context.WithTimeout(ctx, time.Duration(c.LoadSeconds)*time.Second)
	defer stop()
	// requests may wait long in the queues; a request still waiting when the load ends is abandoned
	client := &http.Client{Timeout: 60 * time.Second}
	var wg sync.WaitGroup
	for i := 0; i < c.Concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for loadCtx.Err() == nil {
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
				h.updateBackpressure(run, func(r *backpressureRun) {
					r.Sent++
					r.Statuses[fmt.Sprint(code)]++
					if cancelled {
						r.Cancelled++
					}
				})
			}
		}()
	}
	wg.Wait()
}
