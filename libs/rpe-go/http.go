package rpe

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
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
}

// Middleware validates or creates the RPE context, rejects expired or cancelled requests
// before application logic (R-D02, R-T04), strips hop-scoped headers and stores the context.
func Middleware(cfg MiddlewareConfig, next http.Handler) http.Handler {
	now := cfg.Now
	if now == nil {
		now = time.Now
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received := now()
		hop, _ := ParseHop(r.Header)
		StripHop(r.Header)
		var c Context
		var err error
		if cfg.Gateway {
			route, budget := r.Method+" "+r.URL.Path, 5*time.Second
			if cfg.RouteBudget != nil {
				route, budget = cfg.RouteBudget(r)
			}
			epoch := ""
			if cfg.Epoch != nil {
				epoch = cfg.Epoch()
			}
			c, err = NewRoot(route, budget, cfg.RetryBudget, epoch, TraceIDFromTraceparent(r.Header.Get(HeaderTraceparent)))
		} else {
			c, err = ParseContext(r.Header)
			if errors.Is(err, ErrNoContext) && !cfg.Required {
				next.ServeHTTP(w, r)
				return
			}
		}
		if err != nil {
			WriteResult(w, http.StatusBadRequest, Result{Outcome: OutcomeRejected, Reason: ReasonInvalidContext, Terminal: true}, err.Error())
			return
		}
		if c.Cancel != CancelNone {
			WriteResult(w, http.StatusGatewayTimeout, Result{Outcome: OutcomeStopped, Reason: ReasonCancelled, Terminal: true, PolicyEpoch: c.PolicyEpoch}, "request already cancelled")
			return
		}
		if c.TTLMillis <= 0 {
			WriteResult(w, http.StatusGatewayTimeout, Result{Outcome: OutcomeRejected, Reason: ReasonDeadline, Terminal: true, PolicyEpoch: c.PolicyEpoch}, "deadline expired on arrival")
			return
		}
		r.Header.Set(HeaderContext, c.Encode())
		if TraceIDFromTraceparent(r.Header.Get(HeaderTraceparent)) != c.RID {
			r.Header.Set(HeaderTraceparent, newTraceparent(c.RID))
		}
		ctx := WithInbound(r.Context(), Inbound{Ctx: c, Hop: hop, Received: received})
		// The request context expires at the local deadline, so cooperative application code can stop via
		// ctx.Done() (cancellation tier T3) and outbound calls inherit the deadline.
		ctx, cancel := context.WithDeadline(ctx, time.Now().Add(time.Duration(c.TTLMillis)*time.Millisecond))
		defer cancel()
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// WriteResult writes an RPE response with X-RPE-Result and a short text body.
func WriteResult(w http.ResponseWriter, status int, res Result, msg string) {
	w.Header().Set(HeaderResult, res.Encode())
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, msg+"\n")
}

// Transport propagates the inbound context to outbound calls: it derives the child context
// (fresh iid, pid, ttl reduced by elapsed time and reserve) and refuses to send when the
// deadline has already expired or the request is cancelled.
type Transport struct {
	Base    http.RoundTripper
	Reserve time.Duration // parent post-processing allowance subtracted from the child ttl (R-D07)
	Now     func() time.Time
}

func (t *Transport) RoundTrip(req *http.Request) (*http.Response, error) {
	base := t.Base
	if base == nil {
		base = http.DefaultTransport
	}
	in, ok := FromContext(req.Context())
	if !ok {
		return base.RoundTrip(req)
	}
	now := time.Now
	if t.Now != nil {
		now = t.Now
	}
	elapsed := now().Sub(in.Received)
	if in.Ctx.Cancel != CancelNone || in.Remaining(now()) <= 0 {
		return localResult(req, Result{Outcome: OutcomeRejected, Reason: ReasonDeadline, Terminal: true, PolicyEpoch: in.Ctx.PolicyEpoch}), nil
	}
	grant := in.Ctx.RetryBudget
	if g, ok := req.Context().Value(grantKey{}).(int64); ok {
		grant = g
	}
	child, err := in.Ctx.Child(elapsed, t.Reserve, grant)
	if err != nil {
		return nil, err
	}
	out := req.Clone(req.Context())
	out.Header.Set(HeaderContext, child.Encode())
	out.Header.Del(HeaderHop)
	// Trace binding: keep an existing traceparent (e.g. a tracing client span) when it already belongs to
	// this request's trace; otherwise bind a fresh one to rid.
	if TraceIDFromTraceparent(out.Header.Get(HeaderTraceparent)) != child.RID {
		out.Header.Set(HeaderTraceparent, newTraceparent(child.RID))
	}
	resp, err := base.RoundTrip(out)
	if err != nil && errors.Is(req.Context().Err(), context.DeadlineExceeded) {
		// The call outlived the propagated deadline: report it as a terminal deadline result (R-D04).
		return localResult(req, Result{Outcome: OutcomeRejected, Reason: ReasonDeadline, Terminal: true, PolicyEpoch: in.Ctx.PolicyEpoch}), nil
	}
	return resp, err
}

// localResult synthesizes a terminal 504 without touching the network.
func localResult(req *http.Request, res Result) *http.Response {
	h := http.Header{}
	h.Set(HeaderResult, res.Encode())
	return &http.Response{StatusCode: http.StatusGatewayTimeout, Status: "504 Gateway Timeout", Header: h,
		Body: io.NopCloser(strings.NewReader("deadline expired before send\n")), Request: req, ProtoMajor: 1, ProtoMinor: 1}
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
