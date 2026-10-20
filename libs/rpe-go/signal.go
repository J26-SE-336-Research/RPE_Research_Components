package rpe

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Signal-only helpers for application developers (RPE rule "signal, never reject").
//
// The RPE sidecar detects that a request chain's deadline has passed and signals it in the context (cs=expired /
// cancel). These helpers let a service read that signal, pass the context on, and shed its own non-critical work.
// They never reject an original request, never refuse a first attempt of an outbound call and never invent an error
// or a 504 because of the deadline: the developer decides what a cancelled chain means for the service.
//
// The one thing RPE refuses is a retry of a chain that is already cancelled or expired (R-R02): a retry would repeat
// side effects (a second card charge) and add load exactly when a dependency is slow. Retries are marked with
// X-RPE-Hop att >= 2 (WithAttempt, DoWithRetry).
//
// WARNING for application developers: nothing stops an expired original request. If the application does not read
// the signal (Cancelled, NonCritical, CallIfNotCancelled), its work runs to completion after the deadline.
//
//	handler := rpe.SignalMiddleware(rpe.SignalConfig{}, mux)        // read the context (all endpoints)
//	client := &http.Client{Transport: &rpe.SignalTransport{}}        // write it on every outbound call
//
//	if rpe.Cancelled(r.Context()) { ... }                           // check the signal
//	res := rpe.CallIfNotCancelled(ctx, client, optionalRequest)     // optional call: skipped when cancelled
//	rpe.NonCritical(ctx, func(ctx context.Context) { ... })         // optional work: skipped or stopped when cancelled
//	out := rpe.DoWithRetry(ctx, client, newRequest, policy)          // retries only while the gate allows (R-R02)

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
	// Trail enables the in-band trail (X-RPE-Trail); nil disables it.
	Trail *TrailConfig
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
type attemptKey struct{}

// inbound is the common inbound step of SignalMiddleware and Middleware.
type inbound struct {
	gateway     bool
	routeBudget func(r *http.Request) (string, time.Duration)
	retryBudget int64
	epoch       func() string
	now         func() time.Time
	trail       *TrailConfig
	required    bool // a request without a context gets 400 instead of passing through
	detect      bool // standalone mode (no sidecar): the library itself marks an expired arrival cs=expired
	bindTrace   bool // rebind traceparent to rid for the application
}

func (cfg inbound) handler(next http.Handler) http.Handler {
	now := cfg.now
	if now == nil {
		now = time.Now
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received := now()
		hop, _ := ParseHop(r.Header)
		StripHop(r.Header)
		incomingTrail := r.Header.Get(HeaderTrail)
		r.Header.Del(HeaderTrail)
		var c Context
		var err error
		if cfg.gateway {
			route, budget := r.Method+" "+r.URL.Path, 5*time.Second
			if cfg.routeBudget != nil {
				route, budget = cfg.routeBudget(r)
			}
			epoch := ""
			if cfg.epoch != nil {
				epoch = cfg.epoch()
			}
			c, err = NewRoot(route, budget, cfg.retryBudget, epoch, TraceIDFromTraceparent(r.Header.Get(HeaderTraceparent)))
		} else {
			c, err = ParseContext(r.Header)
			if errors.Is(err, ErrNoContext) && !cfg.required {
				next.ServeHTTP(w, r)
				return
			}
		}
		if err != nil {
			WriteResult(w, http.StatusBadRequest, Result{Outcome: OutcomeRejected, Reason: ReasonInvalidContext, Terminal: true}, err.Error())
			return
		}
		if cfg.detect && c.Cancel == CancelNone && c.TTLMillis <= 0 {
			c.Cancel = CancelExpired // signal it (R-D02); the request is still served
		}
		rec := newTrailRecorder(cfg.trail, c, hop, incomingTrail, cfg.gateway, received)
		// R-R02 at the receiver: a retry of an expired or cancelled chain is refused. An original request never is.
		if hop.Attempt >= 2 && (c.Cancel != CancelNone || c.TTLMillis <= 0) {
			cs, reason := CancelExpired, ReasonDeadline
			if c.Cancel == CancelRequest {
				cs, reason = CancelRequest, ReasonCancelled
			}
			if rec != nil {
				rec.complete(w.Header(), http.StatusGatewayTimeout, cs, DecisionEarly)
			}
			writeRefusal(w, Result{Outcome: OutcomeRejected, Reason: reason, Terminal: true, PolicyEpoch: c.PolicyEpoch}, cs,
				"retry refused (R-R02): the request chain is "+string(cs))
			return
		}
		r.Header.Set(HeaderContext, c.Encode())
		if cfg.bindTrace && TraceIDFromTraceparent(r.Header.Get(HeaderTraceparent)) != c.RID {
			r.Header.Set(HeaderTraceparent, newTraceparent(c.RID))
		}
		ctx := WithInbound(r.Context(), Inbound{Ctx: c, Hop: hop, Received: received})
		ctx = context.WithValue(ctx, signalKey{}, newChainSignal(c.Cancel))
		if rec != nil {
			ctx = context.WithValue(ctx, trailKey{}, rec)
			tw := &trailWriter{ResponseWriter: w, rec: rec, ctx: ctx}
			next.ServeHTTP(tw, r.WithContext(ctx))
			if !tw.wrote {
				tw.WriteHeader(http.StatusOK)
			}
			return
		}
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// writeRefusal writes a terminal 504 that also carries the chain's cancellation state (the cs member).
func writeRefusal(w http.ResponseWriter, res Result, cs CancelState, msg string) {
	w.Header().Set(HeaderResult, res.Encode()+", cs="+string(cs))
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusGatewayTimeout)
	_, _ = io.WriteString(w, msg+"\n")
}

// SignalMiddleware reads (or, at the gateway, creates) the RPE context of every request and makes the cancellation
// signal available to the handler. A late or cancelled request is still served. A request without a context is
// served as it is; only a context that cannot be read at all is answered with 400 (rsn=invalid-context), and a
// retry (X-RPE-Hop att >= 2) of a cancelled or expired chain with 504 (R-R02). Expiry itself is detected by the
// sidecar, which signals it as cs=expired.
func SignalMiddleware(cfg SignalConfig, next http.Handler) http.Handler {
	return inbound{gateway: cfg.Gateway, routeBudget: cfg.RouteBudget, retryBudget: cfg.RetryBudget, epoch: cfg.Epoch,
		now: cfg.Now, trail: cfg.Trail}.handler(next)
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

// WithAttempt marks the next outbound call made with ctx as attempt n of its logical call (X-RPE-Hop att=n).
// Attempts from 2 on are retries: the transports never send them for a cancelled or expired chain (R-R02).
func WithAttempt(ctx context.Context, n int64) context.Context {
	return context.WithValue(ctx, attemptKey{}, n)
}

func attemptFrom(ctx context.Context) int64 {
	n, _ := ctx.Value(attemptKey{}).(int64)
	return n
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

// outbound is the common outbound step of SignalTransport and Transport.
type outbound struct {
	base    http.RoundTripper
	reserve time.Duration
	now     func() time.Time
	detect  bool // standalone mode: the library marks a child with no time left cs=expired
}

func (t outbound) roundTrip(req *http.Request) (*http.Response, error) {
	base := t.base
	if base == nil {
		base = http.DefaultTransport
	}
	in, ok := FromContext(req.Context())
	if !ok {
		return base.RoundTrip(req)
	}
	now := time.Now
	if t.now != nil {
		now = t.now
	}
	cs := Cancellation(req.Context())
	attempt := attemptFrom(req.Context())
	if attempt >= 2 && (cs != CancelNone || in.Remaining(now()) <= 0) {
		// R-R02: a retry of a cancelled or expired chain is never sent
		state, reason := CancelExpired, ReasonDeadline
		if cs == CancelRequest {
			state, reason = CancelRequest, ReasonCancelled
		}
		res := Result{Outcome: OutcomeRejected, Reason: reason, Terminal: true, PolicyEpoch: in.Ctx.PolicyEpoch}
		return localResult(req, res.Encode()+", cs="+string(state), "retry refused (R-R02): the request chain is "+string(state)), nil
	}
	grant := in.Ctx.RetryBudget
	if g, ok := req.Context().Value(grantKey{}).(int64); ok {
		grant = g
	}
	child, err := in.Ctx.Child(now().Sub(in.Received), t.reserve, grant)
	if err != nil {
		return nil, err
	}
	if child.Cancel == CancelNone {
		child.Cancel = cs
	}
	if t.detect && child.Cancel == CancelNone && child.TTLMillis <= 0 {
		child.Cancel = CancelExpired
	}
	out := req.Clone(req.Context())
	out.Header.Set(HeaderContext, child.Encode())
	out.Header.Del(HeaderHop)
	if attempt >= 2 {
		out.Header.Set(HeaderHop, Hop{Attempt: attempt}.Encode())
	}
	if TraceIDFromTraceparent(out.Header.Get(HeaderTraceparent)) != child.RID {
		out.Header.Set(HeaderTraceparent, newTraceparent(child.RID))
	}
	rec := trailFrom(req.Context())
	if rec != nil {
		out.Header.Set(HeaderTrail, rec.requestHeader())
	} else {
		out.Header.Del(HeaderTrail)
	}
	start := time.Now()
	resp, err := base.RoundTrip(out)
	if resp != nil {
		if s := signalFrom(req.Context()); s != nil {
			s.cancel(resultCancelState(resp.Header))
		}
	}
	if rec != nil {
		var h http.Header
		if resp != nil {
			h = resp.Header
		}
		rec.response(h, req.URL.Host, time.Since(start), child.TTLMillis)
	}
	return resp, err
}

// localResult synthesizes a terminal 504 without touching the network.
func localResult(req *http.Request, result, msg string) *http.Response {
	h := http.Header{}
	h.Set(HeaderResult, result)
	return &http.Response{StatusCode: http.StatusGatewayTimeout, Status: "504 Gateway Timeout", Header: h,
		Body: io.NopCloser(strings.NewReader(msg + "\n")), Request: req, ProtoMajor: 1, ProtoMinor: 1}
}

// SignalTransport propagates the context to every outbound call made with its client: a child context with a fresh
// iid, the remaining time (negative when late) and the current cancellation state. It never refuses a first attempt
// and never turns a slow call into an error; only a retry (WithAttempt >= 2) of a cancelled or expired chain is not
// sent (R-R02). A cancellation reported on a response (cs in X-RPE-Result) is recorded, so later calls carry it and
// running NonCritical work stops.
type SignalTransport struct {
	Base    http.RoundTripper
	Reserve time.Duration // post-processing allowance subtracted from the child ttl (R-D07)
	Now     func() time.Time
}

func (t *SignalTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	return outbound{base: t.Base, reserve: t.Reserve, now: t.Now}.roundTrip(req)
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
