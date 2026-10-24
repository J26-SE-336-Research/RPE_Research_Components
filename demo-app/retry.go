package main

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// RetrySettings is the retry switch for one downstream call, set with POST /admin/retry. It is off by default, so
// the call is made once like before. "plain" retries the way a normal client does, give up after a timeout and try
// again. "rpe" uses the same settings through rpe.DoWithRetry, which won't retry a chain that can't finish in time.
type RetrySettings struct {
	Mode             string `json:"mode"`  // off | plain | rpe
	Child            string `json:"child"` // which downstream call from CHILDREN, payment by default
	AttemptTimeoutMs int    `json:"attemptTimeoutMs"`
	MaxAttempts      int    `json:"maxAttempts"`
	BackoffMs        int    `json:"backoffMs"`
}

func (r RetrySettings) normalized() RetrySettings {
	if r.Mode != "plain" && r.Mode != "rpe" {
		r.Mode = "off"
	}
	if r.Child == "" {
		r.Child = "payment"
	}
	if r.AttemptTimeoutMs <= 0 {
		r.AttemptTimeoutMs = 600
	}
	if r.MaxAttempts <= 0 {
		r.MaxAttempts = 2
	}
	if r.BackoffMs < 0 {
		r.BackoffMs = 0
	}
	return r
}

func (s *service) retrySettings() RetrySettings {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.retry
}

func (s *service) handleRetry(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		var in RetrySettings
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		s.retry = in.normalized()
		s.mu.Unlock()
	}
	writeJSON(w, http.StatusOK, s.retrySettings())
}

// retryOutcome is the result of one call with retries.
type retryOutcome struct {
	resp     *http.Response
	err      error
	attempts int
	refused  *rpe.RetryVerdict
}

// callWithRetry makes one downstream call using the retry settings.
func (s *service) callWithRetry(ctx context.Context, method, url string, cfg RetrySettings) retryOutcome {
	newRequest := func(ctx context.Context) (*http.Request, error) {
		return http.NewRequestWithContext(ctx, method, url, nil)
	}
	timeout := time.Duration(cfg.AttemptTimeoutMs) * time.Millisecond
	backoff := time.Duration(cfg.BackoffMs) * time.Millisecond
	if cfg.Mode == "rpe" {
		out := rpe.DoWithRetry(ctx, s.client, newRequest, rpe.RetryPolicy{MaxAttempts: int64(cfg.MaxAttempts),
			AttemptTimeout: timeout, Backoff: backoff, ReplaySafe: true})
		return retryOutcome{resp: out.Response, err: out.Err, attempts: len(out.Attempts), refused: out.Refused}
	}
	// plain is the usual retry loop that knows nothing about the deadline
	var out retryOutcome
	for attempt := 1; attempt <= cfg.MaxAttempts; attempt++ {
		out.attempts = attempt
		actx, cancel := context.WithTimeout(ctx, timeout)
		req, err := newRequest(actx)
		if err == nil {
			out.resp, out.err = s.client.Do(req)
			if out.resp != nil {
				var down Response
				_ = json.NewDecoder(out.resp.Body).Decode(&down)
				out.resp.Body.Close()
				b, _ := json.Marshal(down)
				out.resp.Body = jsonBody(b)
			}
		} else {
			out.resp, out.err = nil, err
		}
		cancel()
		if out.err == nil && out.resp.StatusCode < 500 {
			return out
		}
		time.Sleep(backoff)
	}
	return out
}
