package rpe

import (
	"bytes"
	"context"
	"io"
	"math"
	"net/http"
	"time"
)

// RetryPolicy is the caller's own retry policy for one logical call. RPE does not invent retries: it decides,
// between the attempts the caller wants, whether one more attempt is still allowed (R-R02).
type RetryPolicy struct {
	MaxAttempts    int64         // including the first attempt; 0 means the retry budget is the only limit
	AttemptTimeout time.Duration // the caller gives up on one attempt after this long (0: no per-attempt timeout)
	Backoff        time.Duration // wait before each retry
	AttemptCost    time.Duration // time one attempt is expected to need; defaults to AttemptTimeout
	ReplaySafe     bool          // the operation may be repeated
	// Retryable reports whether a failed attempt is worth retrying. Default: a transport error or timeout, and
	// 502/503/504 responses.
	Retryable func(resp *http.Response, err error) bool
}

// RetryAttempt is one attempt of a logical call.
type RetryAttempt struct {
	Attempt int64  `json:"attempt"`
	Status  int    `json:"status"`
	Error   string `json:"error,omitempty"`
	Millis  int64  `json:"ms"`
}

// RetryOutcome is the result of DoWithRetry. Response is the last response received (its body fully read and
// replayable), Refused the gate's verdict when it stopped a retry.
type RetryOutcome struct {
	Response *http.Response
	Err      error
	Attempts []RetryAttempt
	Refused  *RetryVerdict
}

func defaultRetryable(resp *http.Response, err error) bool {
	if err != nil {
		return true
	}
	switch resp.StatusCode {
	case http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		return true
	}
	return false
}

// DoWithRetry runs a logical call with the caller's retry policy and the RPE retry gate between attempts. newRequest
// builds a fresh request for each attempt (with the given context). Attempts after the first are marked as retries
// (X-RPE-Hop att=n) and spend one unit of the retry budget; a retry is never sent once the chain is cancelled or
// expired, after a terminal response, for an operation that is not replay-safe, when the time left is less than the
// backoff plus one attempt, or when the budget is used up. Without an RPE context only MaxAttempts and ReplaySafe
// apply.
func DoWithRetry(ctx context.Context, client *http.Client, newRequest func(ctx context.Context) (*http.Request, error), p RetryPolicy) RetryOutcome {
	if client == nil {
		client = http.DefaultClient
	}
	retryable := p.Retryable
	if retryable == nil {
		retryable = defaultRetryable
	}
	cost := p.AttemptCost
	if cost == 0 {
		cost = p.AttemptTimeout
	}
	in, hasCtx := FromContext(ctx)
	budget := int64(math.MaxInt32)
	if hasCtx {
		budget = in.Ctx.RetryBudget
		if g, ok := ctx.Value(grantKey{}).(int64); ok {
			budget = g
		}
	} else if p.MaxAttempts > 0 {
		budget = p.MaxAttempts - 1
	}
	var out RetryOutcome
	for attempt := int64(1); ; attempt++ {
		resp, err, ms := doAttempt(WithAttempt(ctx, attempt), client, newRequest, p.AttemptTimeout)
		a := RetryAttempt{Attempt: attempt, Millis: ms}
		if err != nil {
			a.Error = err.Error()
		} else {
			a.Status = resp.StatusCode
		}
		out.Attempts = append(out.Attempts, a)
		out.Response, out.Err = resp, err
		if !retryable(resp, err) {
			return out
		}
		check := RetryCheck{Attempt: attempt, MaxAttempts: p.MaxAttempts, Cancel: Cancellation(ctx), ReplaySafe: p.ReplaySafe,
			RemainingMs: math.MaxInt32, BackoffMs: p.Backoff.Milliseconds(), AttemptCostMs: cost.Milliseconds(), RetryBudget: budget}
		if resp != nil {
			if res, ok, _ := ParseResult(resp.Header); ok {
				check.Terminal = res.Terminal
			}
			if cs := resultCancelState(resp.Header); check.Cancel == CancelNone {
				check.Cancel = cs
			}
		}
		if hasCtx {
			check.RemainingMs = in.Remaining(time.Now()).Milliseconds()
		}
		v := DecideRetry(check)
		if !v.Retry {
			out.Refused = &v
			return out
		}
		budget = v.BudgetLeft
		select {
		case <-ctx.Done():
			out.Err = ctx.Err()
			return out
		case <-time.After(p.Backoff):
		}
	}
}

// doAttempt sends one attempt; a response body is read fully so the attempt's timeout can be released.
func doAttempt(ctx context.Context, client *http.Client, newRequest func(ctx context.Context) (*http.Request, error), timeout time.Duration) (*http.Response, error, int64) {
	start := time.Now()
	actx, cancel := ctx, context.CancelFunc(func() {})
	if timeout > 0 {
		actx, cancel = context.WithTimeout(ctx, timeout)
	}
	defer cancel()
	req, err := newRequest(actx)
	if err != nil {
		return nil, err, 0
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err, time.Since(start).Milliseconds()
	}
	body, err := io.ReadAll(resp.Body)
	resp.Body.Close()
	if err != nil {
		return nil, err, time.Since(start).Milliseconds()
	}
	resp.Body = io.NopCloser(bytes.NewReader(body))
	return resp, nil, time.Since(start).Milliseconds()
}
