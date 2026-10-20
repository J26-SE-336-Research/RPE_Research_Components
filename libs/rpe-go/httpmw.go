package rpe

import (
	"context"
	"io"
	"net/http"
	"time"
)

// Inbound is the validated context of the request being served, with its local receive time.
// All later deadline checks use the local monotonic clock (R-D01).
type Inbound struct {
	Ctx      Context
	Hop      Hop
	Received time.Time
}

// Remaining returns the time left for this request at now.
func (in Inbound) Remaining(now time.Time) time.Duration {
	return time.Duration(in.Ctx.TTLMillis)*time.Millisecond - now.Sub(in.Received)
}

type inboundKey struct{}
type grantKey struct{}

// FromContext returns the inbound RPE context stored by Middleware.
func FromContext(ctx context.Context) (Inbound, bool) {
	in, ok := ctx.Value(inboundKey{}).(Inbound)
	return in, ok
}

// WithInbound stores an inbound context (used by Middleware and by tests).
func WithInbound(ctx context.Context, in Inbound) context.Context {
	return context.WithValue(ctx, inboundKey{}, in)
}

// WithChildGrant sets the retry-budget grant for the next outbound call made with ctx
// (use SplitBudget for fan-out so that grants are conserved, R-T03/R-F05).
func WithChildGrant(ctx context.Context, grant int64) context.Context {
	return context.WithValue(ctx, grantKey{}, grant)
}

// MiddlewareConfig configures the inbound middleware.
type MiddlewareConfig struct {
	// Gateway makes this the trust boundary (R-S01): a fresh root context is always created.
	Gateway bool
	// RouteBudget returns the route key and deadline budget for a gateway request.
	RouteBudget func(r *http.Request) (route string, budget time.Duration)
	// RetryBudget is the root retry budget for gateway requests.
	RetryBudget int64
	// Epoch returns the active policy epoch stamped by the gateway ("" if none).
	Epoch func() string
	// Required rejects non-gateway requests that carry no context (400) instead of passing them through.
	Required bool
	// Now is the clock (tests); defaults to time.Now.
	Now func() time.Time
	// Trail enables the in-band trail (X-RPE-Trail); nil disables it.
	Trail *TrailConfig
}

// Middleware is the standalone variant of SignalMiddleware, for services that run without the RPE sidecar: the
// library itself is the point that detects expiry. It validates or creates the context, strips hop-scoped headers,
// binds traceparent to rid and stores the context. Signal mode (R-D02): an original request that arrives with no
// time left is served with cs=expired set in its context, never rejected; only a retry (X-RPE-Hop att >= 2) of a
// cancelled or expired chain is refused with a terminal 504 (R-R02). The application decides what an expired
// request means (Cancelled, NonCritical, CallIfNotCancelled).
func Middleware(cfg MiddlewareConfig, next http.Handler) http.Handler {
	return inbound{gateway: cfg.Gateway, routeBudget: cfg.RouteBudget, retryBudget: cfg.RetryBudget, epoch: cfg.Epoch,
		now: cfg.Now, trail: cfg.Trail, required: cfg.Required, detect: true, bindTrace: true}.handler(next)
}

// WriteResult writes an RPE response with X-RPE-Result and a short text body.
func WriteResult(w http.ResponseWriter, status int, res Result, msg string) {
	w.Header().Set(HeaderResult, res.Encode())
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, msg+"\n")
}

// Transport is the standalone variant of SignalTransport: it propagates the inbound context to outbound calls (fresh
// iid, pid, ttl reduced by elapsed time and reserve) and, without a sidecar to do it, marks a child that has no time
// left cs=expired. It never refuses a first attempt and never cuts a slow call off; only a retry (WithAttempt >= 2)
// of a cancelled or expired chain is answered locally with a terminal 504 instead of being sent (R-R02).
type Transport struct {
	Base    http.RoundTripper
	Reserve time.Duration // parent post-processing allowance subtracted from the child ttl (R-D07)
	Now     func() time.Time
}

func (t *Transport) RoundTrip(req *http.Request) (*http.Response, error) {
	return outbound{base: t.Base, reserve: t.Reserve, now: t.Now, detect: true}.roundTrip(req)
}

// SplitMode is the fan-out split mode (R-D07, R-F05).
type SplitMode string

const (
	SplitParallel   SplitMode = "parallel"
	SplitSequential SplitMode = "sequential"
)

// SplitBudget divides a retry budget across n children (R-F05). In parallel mode each child gets
// floor((rb-reserve)/n) and the remainder goes to the first children; in sequential mode every child
// may use the whole unallocated budget at dispatch and the parent deducts the reported rbu afterwards.
func SplitBudget(mode SplitMode, rb, reserve int64, n int) []int64 {
	out := make([]int64, n)
	if n <= 0 {
		return out
	}
	pool := rb - reserve
	if pool < 0 {
		pool = 0
	}
	if mode == SplitSequential {
		for i := range out {
			out[i] = pool
		}
		return out
	}
	share, rem := pool/int64(n), pool%int64(n)
	for i := range out {
		out[i] = share
		if int64(i) < rem {
			out[i]++
		}
	}
	return out
}
