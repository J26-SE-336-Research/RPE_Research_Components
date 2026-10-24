package rpe

import (
	"context"
	"io"
	"net/http"
	"time"
)

// Inbound is the checked context of the request being served, plus the time we received it.
// Later deadline checks use the local clock, so other machines' clocks don't matter.
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

// FromContext returns the inbound RPE context saved by the middleware.
func FromContext(ctx context.Context) (Inbound, bool) {
	in, ok := ctx.Value(inboundKey{}).(Inbound)
	return in, ok
}

// WithInbound saves an inbound context. Used by the middleware and in tests.
func WithInbound(ctx context.Context, in Inbound) context.Context {
	return context.WithValue(ctx, inboundKey{}, in)
}

// WithChildGrant sets how much retry budget the next outbound call gets. For fan-out use SplitBudget so the
// children never get more than the parent had.
func WithChildGrant(ctx context.Context, grant int64) context.Context {
	return context.WithValue(ctx, grantKey{}, grant)
}

// MiddlewareConfig configures the inbound middleware.
type MiddlewareConfig struct {
	// Gateway means this service is the entry point, so it always starts a new root context.
	Gateway bool
	// RouteBudget returns the route key and deadline budget for a gateway request.
	RouteBudget func(r *http.Request) (route string, budget time.Duration)
	// RetryBudget is the root retry budget for gateway requests.
	RetryBudget int64
	// Epoch returns the active policy epoch, or "" if there is none.
	Epoch func() string
	// Required answers 400 to requests with no context instead of letting them through. Ignored at the gateway.
	Required bool
	// Now is the clock, mainly for tests. Defaults to time.Now.
	Now func() time.Time
	// Trail turns on the X-RPE-Trail recording. Leave it nil to turn it off.
	Trail *TrailConfig
}

// Middleware is SignalMiddleware for services that run without the sidecar, so the library itself has to notice
// when the deadline has passed. It checks or creates the context, removes the per-hop headers, ties traceparent to
// rid and saves the context. A request that arrives late is still served, with cs=expired in its context. Only a
// retry of a cancelled or expired chain gets a 504. The app decides what to do with a late request.
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

// Transport is SignalTransport for services without the sidecar. It passes the context on to outbound calls with a
// new iid and pid and a smaller ttl, and since there is no sidecar it marks a child with no time left as
// cs=expired itself. First attempts always go out and slow calls are never cut off. Only a retry of a cancelled or
// expired chain is answered locally with a 504 instead of being sent.
type Transport struct {
	Base    http.RoundTripper
	Reserve time.Duration // time kept back for our own work after the call, taken off the child ttl
	Now     func() time.Time
}

func (t *Transport) RoundTrip(req *http.Request) (*http.Response, error) {
	return outbound{base: t.Base, reserve: t.Reserve, now: t.Now, detect: true}.roundTrip(req)
}

// SplitMode says how the budget is split when a service calls several children.
type SplitMode string

const (
	SplitParallel   SplitMode = "parallel"
	SplitSequential SplitMode = "sequential"
)

// SplitBudget splits a retry budget over n children. In parallel mode each child gets (rb-reserve)/n and the
// leftover goes to the first ones. In sequential mode each child can use all of what is left when it is called,
// and the parent takes off what the child reports it used.
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
