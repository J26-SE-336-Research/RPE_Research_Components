package main

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"
)

// policyFollower keeps the gateway's root-context parameters in line with the policy its sidecar enforces
// The active epoch is stamped into new contexts (pe) and spec.routes[route].deadlineMs
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
