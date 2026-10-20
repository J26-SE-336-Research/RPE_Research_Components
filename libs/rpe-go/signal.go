package rpe

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"time"
)

// Signal-only helpers for application developers (RPE rule "signal, never reject").
//
// The RPE sidecar detects that a request chain's deadline has passed and signals it in the context (cs=expired /
// cancel). These helpers let a service read that signal, pass the context on, and shed its own non-critical work.
// They never reject a request, never refuse an outbound call and never invent an error or a 504 because of the
// deadline: the developer decides what a cancelled chain means for the service.
//
//	handler := rpe.SignalMiddleware(rpe.SignalConfig{}, mux)        // read the context (all endpoints)
//	client := &http.Client{Transport: &rpe.SignalTransport{}}        // write it on every outbound call
//
//	if rpe.Cancelled(r.Context()) { ... }                           // check the signal
//	res := rpe.CallIfNotCancelled(ctx, client, optionalRequest)     // optional call: skipped when cancelled
//	rpe.NonCritical(ctx, func(ctx context.Context) { ... })         // optional work: skipped or stopped when cancelled
//
// The older Middleware / Transport enforce deadlines by rejecting; they are kept unchanged for existing users.

// SignalConfig configures SignalMiddleware.
type SignalConfig struct {
	// Gateway makes this the trust boundary (R-S01): a fresh root context is always created.
	Gateway bool
	// RouteBudget returns the route key and deadline budget for a gateway request.
	RouteBudget func(r *http.Request) (route string, budget time.Duration)
	// RetryBudget is the root retry budget for gateway requests.
	RetryBudget int64
	// Epoch returns the active policy epoch stamped by the gateway ("" if none).
	Epoch func() string
	// Now is the clock (tests); defaults to time.Now.
	Now func() time.Time
}

// chainSignal is the cancellation state of the request being served. It only ever moves from none to cancelled
// (monotonic). It is set from the incoming context and from cs reported on downstream responses.
type chainSignal struct {
	mu     sync.Mutex
	state  CancelState
	closed chan struct{} // closed once the chain is cancelled
}

func newChainSignal(initial CancelState) *chainSignal {
	s := &chainSignal{state: CancelNone, closed: make(chan struct{})}
	s.cancel(initial)
	return s
}

func (s *chainSignal) cancel(state CancelState) {
	if state == CancelNone || state == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state != CancelNone {
		return // never changes once set (R-T04)
	}
	s.state = state
	close(s.closed)
}

func (s *chainSignal) current() CancelState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state
}

type signalKey struct{}

// SignalMiddleware reads (or, at the gateway, creates) the RPE context of every request and makes the cancellation
// signal available to the handler. A late or cancelled request is still served. A request without a context is
// served as it is; only a context that cannot be read at all is answered with 400 (rsn=invalid-context).
func SignalMiddleware(cfg SignalConfig, next http.Handler) http.Handler {
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
			if errors.Is(err, ErrNoContext) {
				next.ServeHTTP(w, r)
				return
			}
		}
		if err != nil {
			WriteResult(w, http.StatusBadRequest, Result{Outcome: OutcomeRejected, Reason: ReasonInvalidContext, Terminal: true}, err.Error())
			return
		}
		r.Header.Set(HeaderContext, c.Encode())
		ctx := WithInbound(r.Context(), Inbound{Ctx: c, Hop: hop, Received: received})
		ctx = context.WithValue(ctx, signalKey{}, newChainSignal(c.Cancel))
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func signalFrom(ctx context.Context) *chainSignal {
	s, _ := ctx.Value(signalKey{}).(*chainSignal)
	return s
}

// Cancelled reports whether the request chain has been cancelled: the sidecar signalled it in the incoming context,
// or a downstream response reported it. A request without an RPE context is never cancelled.
func Cancelled(ctx context.Context) bool {
	return Cancellation(ctx) != CancelNone
}

// Cancellation returns the chain's cancellation state: none, cancel or expired.
func Cancellation(ctx context.Context) CancelState {
	if s := signalFrom(ctx); s != nil {
		return s.current()
	}
	if in, ok := FromContext(ctx); ok {
		return in.Ctx.Cancel
	}
	return CancelNone
}

// NonCritical runs work the service can do without. When the chain is already cancelled the work is not started and
// false is returned; otherwise it runs with a context that is cancelled as soon as the chain is cancelled (for
// example by a downstream response), so cooperative work can stop early. It never returns an error of its own.
func NonCritical(ctx context.Context, work func(ctx context.Context)) (ran bool) {
	if Cancelled(ctx) {
		return false
	}
	s := signalFrom(ctx)
	if s == nil {
		work(ctx)
		return true
	}
	workCtx, stop := context.WithCancel(ctx)
	defer stop()
	go func() {
		select {
		case <-s.closed:
			stop()
		case <-workCtx.Done():
		}
	}()
	work(workCtx)
	return true
}

// CallResult is the outcome of CallIfNotCancelled. Skipped means the call was not sent because the chain is
// cancelled; it is information for the developer, not an error.
type CallResult struct {
	Response *http.Response
	Err      error
	Skipped  bool
}

// CallIfNotCancelled sends an optional outbound call only while the chain is not cancelled. A skipped call returns
// Skipped=true with no error and no response; a sent call returns the client's normal response or error.
func CallIfNotCancelled(ctx context.Context, client *http.Client, req *http.Request) CallResult {
	if Cancelled(ctx) {
		return CallResult{Skipped: true}
	}
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req.WithContext(ctx))
	return CallResult{Response: resp, Err: err}
}

// SignalTransport propagates the context to every outbound call made with its client: a child context with a fresh
// iid, the remaining time (negative when late) and the current cancellation state. It never refuses to send and
// never turns a slow call into an error. A cancellation reported on a response (cs in X-RPE-Result) is recorded, so
// later calls carry it and running NonCritical work stops.
type SignalTransport struct {
	Base    http.RoundTripper
	Reserve time.Duration // post-processing allowance subtracted from the child ttl (R-D07)
	Now     func() time.Time
}

func (t *SignalTransport) RoundTrip(req *http.Request) (*http.Response, error) {
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
	grant := in.Ctx.RetryBudget
	if g, ok := req.Context().Value(grantKey{}).(int64); ok {
		grant = g
	}
	child, err := in.Ctx.Child(now().Sub(in.Received), t.Reserve, grant)
	if err != nil {
		return nil, err
	}
	if child.Cancel == CancelNone {
		child.Cancel = Cancellation(req.Context())
	}
	out := req.Clone(req.Context())
	out.Header.Set(HeaderContext, child.Encode())
	out.Header.Del(HeaderHop)
	if TraceIDFromTraceparent(out.Header.Get(HeaderTraceparent)) != child.RID {
		out.Header.Set(HeaderTraceparent, newTraceparent(child.RID))
	}
	resp, err := base.RoundTrip(out)
	if resp != nil {
		if s := signalFrom(req.Context()); s != nil {
			s.cancel(resultCancelState(resp.Header))
		}
	}
	return resp, err
}

// resultCancelState reads the cs member the sidecar adds to X-RPE-Result ("none" when absent or unreadable).
func resultCancelState(h http.Header) CancelState {
	raw := h.Get(HeaderResult)
	if raw == "" {
		return CancelNone
	}
	members, err := parseDictionary(HeaderResult, raw)
	if err != nil {
		return CancelNone
	}
	for _, m := range members {
		if m.key == "cs" && m.item.kind == 't' {
			switch CancelState(m.item.s) {
			case CancelRequest, CancelExpired:
				return CancelState(m.item.s)
			}
		}
	}
	return CancelNone
}
