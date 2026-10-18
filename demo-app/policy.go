package main

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// policyFollower keeps the gateway's root-context parameters in line with the policy its sidecar enforces
// (contract A3.4): the active epoch is stamped into new contexts (pe) and spec.routes[route].deadlineMs
// overrides the configured route budget. Without an active policy the static configuration applies.
type policyFollower struct {
	url    string
	client *http.Client
	mu     sync.RWMutex
	epoch  string
	routes map[string]time.Duration
}

type activePolicy struct {
	Epoch  string `json:"epoch"`
	Policy struct {
		Routes map[string]struct {
			DeadlineMs int64 `json:"deadlineMs"`
		} `json:"routes"`
	} `json:"policy"`
}

func newPolicyFollower(url string) *policyFollower {
	return &policyFollower{url: url, client: &http.Client{Timeout: time.Second}, routes: map[string]time.Duration{}}
}

func (f *policyFollower) run(ctx context.Context, every time.Duration) {
	for {
		f.refresh(ctx)
		select {
		case <-ctx.Done():
			return
		case <-time.After(every):
		}
	}
}

func (f *policyFollower) refresh(ctx context.Context) {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, f.url, nil)
	resp, err := f.client.Do(req)
	if err != nil {
		return // sidecar not reachable: keep the last known policy
	}
	defer resp.Body.Close()
	var p activePolicy
	if resp.StatusCode == http.StatusNotFound {
		f.set("", nil)
		return
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&p) != nil {
		return
	}
	routes := map[string]time.Duration{}
	for route, r := range p.Policy.Routes {
		if r.DeadlineMs > 0 {
			routes[route] = time.Duration(r.DeadlineMs) * time.Millisecond
		}
	}
	f.set(p.Epoch, routes)
}

func (f *policyFollower) set(epoch string, routes map[string]time.Duration) {
	f.mu.Lock()
	f.epoch, f.routes = epoch, routes
	f.mu.Unlock()
}

// Epoch returns the active policy epoch, or fallback when no policy is active.
func (f *policyFollower) Epoch(fallback string) string {
	f.mu.RLock()
	defer f.mu.RUnlock()
	if f.epoch != "" {
		return f.epoch
	}
	return fallback
}

// RouteBudget wraps the static route budgets with the active policy's route deadlines.
func (f *policyFollower) RouteBudget(static func(*http.Request) (string, time.Duration)) func(*http.Request) (string, time.Duration) {
	return func(r *http.Request) (string, time.Duration) {
		route, budget := static(r)
		f.mu.RLock()
		defer f.mu.RUnlock()
		if d, ok := f.routes[route]; ok {
			return route, d
		}
		return route, budget
	}
}

// stampedBudget makes the gateway application start each chain with the deadline its sidecar stamped into the root
// context the application received (X-RPE-Context bud). The sidecar applies a new policy the moment it is
// activated; the followed copy is polled and can lag behind it, so without this an application could start chains
// with an older deadline than the policy version they are stamped with. Without a received context the next rule
// applies (the followed policy, then the static budgets).
func stampedBudget(next func(*http.Request) (string, time.Duration)) func(*http.Request) (string, time.Duration) {
	return func(r *http.Request) (string, time.Duration) {
		route, budget := next(r)
		if c, err := rpe.DecodeContext(r.Header.Get(rpe.HeaderContext)); err == nil && c.BudgetMillis > 0 {
			return route, time.Duration(c.BudgetMillis) * time.Millisecond
		}
		return route, budget
	}
}
