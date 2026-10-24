package rpe

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestDecideRetryOrder(t *testing.T) {
	ok := RetryCheck{Attempt: 1, MaxAttempts: 3, ReplaySafe: true, RemainingMs: 500, BackoffMs: 50, AttemptCostMs: 200, RetryBudget: 2}
	if v := DecideRetry(ok); !v.Retry || v.NextAttempt != 2 || v.BudgetLeft != 1 {
		t.Fatalf("allowed retry: %+v", v)
	}
	for _, tc := range []struct {
		mut    func(c *RetryCheck)
		reason string
	}{
		{func(c *RetryCheck) { c.Cancel = CancelExpired; c.Terminal = true }, RetryRefusedDeadline},
		{func(c *RetryCheck) { c.Cancel = CancelRequest }, RetryRefusedCancelled},
		{func(c *RetryCheck) { c.Terminal = true; c.ReplaySafe = false }, RetryRefusedTerminal},
		{func(c *RetryCheck) { c.ReplaySafe = false; c.RemainingMs = 0 }, RetryRefusedNotReplaySafe},
		{func(c *RetryCheck) { c.RemainingMs = 0 }, RetryRefusedDeadline},
		{func(c *RetryCheck) { c.RemainingMs = 249 }, RetryRefusedInsufficientTime},
		{func(c *RetryCheck) { c.RetryBudget = 0 }, RetryRefusedBudget},
		{func(c *RetryCheck) { c.Attempt = 3 }, RetryRefusedAttempts},
	} {
		c := ok
		tc.mut(&c)
		if v := DecideRetry(c); v.Retry || v.Reason != tc.reason || v.Rule != "R-R02" {
			t.Errorf("want %s, got %+v", tc.reason, v)
		}
	}
}

// TestDoWithRetryStopsOnAnExpiredChain: the first attempt times out and by then there isn't enough time left for
// another one, so the retry is refused and the side effect only happens once.
func TestDoWithRetryStopsOnAnExpiredChain(t *testing.T) {
	var charged atomic.Int64
	pay := httptest.NewServer(SignalMiddleware(SignalConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(150 * time.Millisecond)
		charged.Add(1)
	})))
	defer pay.Close()
	client := &http.Client{Transport: &SignalTransport{}}
	var out RetryOutcome
	gw := SignalMiddleware(SignalConfig{Gateway: true, RetryBudget: 3,
		RouteBudget: func(r *http.Request) (string, time.Duration) { return "POST /pay", 200 * time.Millisecond }},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			out = DoWithRetry(r.Context(), client, func(ctx context.Context) (*http.Request, error) {
				return http.NewRequestWithContext(ctx, "POST", pay.URL, nil)
			}, RetryPolicy{MaxAttempts: 3, AttemptTimeout: 100 * time.Millisecond, Backoff: 10 * time.Millisecond, ReplaySafe: true})
		}))
	gw.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("POST", "/pay", nil))
	time.Sleep(200 * time.Millisecond) // the abandoned first attempt still completes on the server
	if len(out.Attempts) != 1 || out.Refused == nil || out.Refused.Reason != RetryRefusedInsufficientTime || charged.Load() != 1 {
		t.Fatalf("attempts %+v refused %+v charged %d", out.Attempts, out.Refused, charged.Load())
	}
}

func TestDoWithRetryRetriesWhileAllowed(t *testing.T) {
	var calls atomic.Int64
	var attempts []string
	svc := httptest.NewServer(SignalMiddleware(SignalConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if in, ok := FromContext(r.Context()); ok {
			attempts = append(attempts, in.Hop.Encode())
		}
		if calls.Add(1) < 3 {
			w.WriteHeader(http.StatusServiceUnavailable)
		}
	})))
	defer svc.Close()
	client := &http.Client{Transport: &SignalTransport{}}
	var out RetryOutcome
	gw := SignalMiddleware(SignalConfig{Gateway: true, RetryBudget: 3,
		RouteBudget: func(r *http.Request) (string, time.Duration) { return "GET /x", 2 * time.Second }},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			out = DoWithRetry(r.Context(), client, func(ctx context.Context) (*http.Request, error) {
				return http.NewRequestWithContext(ctx, "GET", svc.URL, nil)
			}, RetryPolicy{MaxAttempts: 5, Backoff: time.Millisecond, AttemptCost: 10 * time.Millisecond, ReplaySafe: true})
		}))
	gw.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/x", nil))
	if len(out.Attempts) != 3 || out.Response == nil || out.Response.StatusCode != 200 || out.Refused != nil {
		t.Fatalf("attempts %+v refused %+v", out.Attempts, out.Refused)
	}
	if len(attempts) != 3 || attempts[0] != "" || attempts[1] != "att=2" || attempts[2] != "att=3" {
		t.Fatalf("retries are marked with att: %q", attempts)
	}
	// a budget of 1 allows one retry only
	calls.Store(-10)
	gw = SignalMiddleware(SignalConfig{Gateway: true, RetryBudget: 1}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		out = DoWithRetry(r.Context(), client, func(ctx context.Context) (*http.Request, error) {
			return http.NewRequestWithContext(ctx, "GET", svc.URL, nil)
		}, RetryPolicy{Backoff: time.Millisecond, ReplaySafe: true})
	}))
	gw.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/x", nil))
	if len(out.Attempts) != 2 || out.Refused == nil || out.Refused.Reason != RetryRefusedBudget {
		t.Fatalf("budget: attempts %+v refused %+v", out.Attempts, out.Refused)
	}
}
