package rpe

import (
	"bytes"
	"context"
	"io"
	"math"
	"net/http"
	"time"
)

// RetryPolicy is the caller's own retry settings for one call. RPE doesn't add retries of its own, it only decides
// between attempts whether one more is still allowed.
type RetryPolicy struct {
	MaxAttempts    int64         // counts the first attempt too, 0 means only the retry budget limits it
	AttemptTimeout time.Duration // give up on one attempt after this long, 0 means no limit
	Backoff        time.Duration // wait before each retry
	AttemptCost    time.Duration // how long one attempt usually takes, defaults to AttemptTimeout
	ReplaySafe     bool          // true if it is safe to do the operation twice
	// Retryable says if a failed attempt is worth retrying. By default that is a network error, a timeout or a
	// 502, 503 or 504.
	Retryable func(resp *http.Response, err error) bool
}

// RetryAttempt is one attempt of a logical call.
type RetryAttempt struct {
	Attempt int64  `json:"attempt"`
	Status  int    `json:"status"`
	Error   string `json:"error,omitempty"`
	Millis  int64  `json:"ms"`
}

// RetryOutcome is what DoWithRetry returns. Response is the last response we got, with the body already read so
// it can be read again. Refused is set when the gate stopped a retry.
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

// DoWithRetry makes a call using the caller's retry policy and asks the retry gate before every retry. newRequest
// builds a new request for each attempt. Every attempt after the first is marked as a retry and uses one unit of
// the retry budget. No retry is sent if the chain is cancelled or expired, the last answer said not to retry, the
// operation isn't safe to repeat, there isn't enough time for the backoff plus one more attempt, or the budget is
// gone. Without an RPE context only MaxAttempts and ReplaySafe are used.
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

// doAttempt sends one attempt. The body is read fully so the attempt timeout can be cancelled.
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
