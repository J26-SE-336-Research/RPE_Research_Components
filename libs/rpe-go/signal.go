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

// Helpers for services that only want the deadline signal.
//
// When the chain deadline passes, the sidecar marks the context with cs=expired. These helpers let a service read
// that flag, pass the context on and skip work it does not really need. They never reject a request and never
// make up a 504 for a late one. What a cancelled chain means for the service is up to the developer.
//
// The only thing that is refused is a retry of a chain that is already cancelled or expired. Retrying then would
// repeat side effects, like charging a card twice, and add load right when a dependency is slow. Retries carry
// X-RPE-Hop att >= 2, see WithAttempt and DoWithRetry.
//
// Note that nothing stops a late original request. If the service never checks the signal, its work just runs to
// the end after the deadline.
//
//	handler := rpe.SignalMiddleware(rpe.SignalConfig{}, mux)        // read the context on every endpoint
//	client := &http.Client{Transport: &rpe.SignalTransport{}}        // send it on every outbound call
//
//	if rpe.Cancelled(r.Context()) { ... }                           // check the signal
//	res := rpe.CallIfNotCancelled(ctx, client, optionalRequest)     // optional call, skipped when cancelled
//	rpe.NonCritical(ctx, func(ctx context.Context) { ... })         // optional work, skipped or stopped when cancelled
//	out := rpe.DoWithRetry(ctx, client, newRequest, policy)          // retries only while the gate allows it

// SignalConfig configures SignalMiddleware.
type SignalConfig struct {
	// Gateway means this service is the entry point, so it always starts a new root context.
	Gateway bool
	// RouteBudget returns the route key and deadline budget for a gateway request.
	RouteBudget func(r *http.Request) (route string, budget time.Duration)
	// RetryBudget is the root retry budget for gateway requests.
	RetryBudget int64
	// Epoch returns the active policy epoch, or "" if there is none.
	Epoch func() string
	// Now is the clock, mainly for tests. Defaults to time.Now.
	Now func() time.Time
	// Trail turns on the X-RPE-Trail recording. Leave it nil to turn it off.
	Trail *TrailConfig
}

// chainSignal holds the cancel state of the request being served. Once it is cancelled it stays cancelled.
// It is set from the incoming context and from the cs value on downstream responses.
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
		return // once set it never changes
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

// inbound is the shared inbound part of SignalMiddleware and Middleware.
type inbound struct {
	gateway     bool
	routeBudget func(r *http.Request) (string, time.Duration)
	retryBudget int64
	epoch       func() string
	now         func() time.Time
	trail       *TrailConfig
	required    bool // no context means 400 instead of passing it through
	detect      bool // no sidecar: the library marks a late arrival as cs=expired itself
	bindTrace   bool // point traceparent at rid for the app
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
			c.Cancel = CancelExpired // mark it, the request is still served
		}
		rec := newTrailRecorder(cfg.trail, c, hop, incomingTrail, cfg.gateway, received)
		// refuse a retry of an expired or cancelled chain, but never the original request
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

// writeRefusal writes a final 504 that also carries the cs value of the chain.
func writeRefusal(w http.ResponseWriter, res Result, cs CancelState, msg string) {
	w.Header().Set(HeaderResult, res.Encode()+", cs="+string(cs))
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusGatewayTimeout)
	_, _ = io.WriteString(w, msg+"\n")
}

// SignalMiddleware reads the RPE context of every request, or creates it at the gateway, and gives the handler
// access to the cancel signal. Late and cancelled requests are still served, and so are requests with no context.
// A context that can't be read gets a 400, and a retry of a cancelled or expired chain gets a 504.
// The sidecar is the one that notices the deadline has passed and sets cs=expired.
func SignalMiddleware(cfg SignalConfig, next http.Handler) http.Handler {
	return inbound{gateway: cfg.Gateway, routeBudget: cfg.RouteBudget, retryBudget: cfg.RetryBudget, epoch: cfg.Epoch,
		now: cfg.Now, trail: cfg.Trail}.handler(next)
}

func signalFrom(ctx context.Context) *chainSignal {
	s, _ := ctx.Value(signalKey{}).(*chainSignal)
	return s
}

// Cancelled tells if the chain has been cancelled, either by the incoming context or by a downstream response.
// A request with no RPE context is never cancelled.
func Cancelled(ctx context.Context) bool {
	return Cancellation(ctx) != CancelNone
}

// Cancellation returns the cancel state of the chain: none, cancel or expired.
func Cancellation(ctx context.Context) CancelState {
	if s := signalFrom(ctx); s != nil {
		return s.current()
	}
	if in, ok := FromContext(ctx); ok {
		return in.Ctx.Cancel
	}
	return CancelNone
}

// WithAttempt marks the next outbound call made with ctx as attempt n (sent as X-RPE-Hop att=n).
// Attempt 2 and up are retries, and the transports don't send those once the chain is cancelled or expired.
func WithAttempt(ctx context.Context, n int64) context.Context {
	return context.WithValue(ctx, attemptKey{}, n)
}

func attemptFrom(ctx context.Context) int64 {
	n, _ := ctx.Value(attemptKey{}).(int64)
	return n
}

// NonCritical runs work the service can live without. If the chain is already cancelled it doesn't run and returns
// false. Otherwise the work gets a context that is cancelled as soon as the chain is, so it can stop early.
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

// CallResult is what CallIfNotCancelled returns. Skipped means the call wasn't sent because the chain is
// cancelled. That is not an error.
type CallResult struct {
	Response *http.Response
	Err      error
	Skipped  bool
}

// CallIfNotCancelled sends an optional call only while the chain is not cancelled. A skipped call has Skipped set
// and no response or error. A sent call gives back whatever the client returned.
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

// outbound is the shared outbound part of SignalTransport and Transport.
type outbound struct {
	base    http.RoundTripper
	reserve time.Duration
	now     func() time.Time
	detect  bool // no sidecar: mark a child with no time left as cs=expired
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
		// don't send a retry once the chain is cancelled or expired
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

// localResult builds a 504 response locally, without calling anything.
func localResult(req *http.Request, result, msg string) *http.Response {
	h := http.Header{}
	h.Set(HeaderResult, result)
	return &http.Response{StatusCode: http.StatusGatewayTimeout, Status: "504 Gateway Timeout", Header: h,
		Body: io.NopCloser(strings.NewReader(msg + "\n")), Request: req, ProtoMajor: 1, ProtoMinor: 1}
}

// SignalTransport adds the context to every outbound call: a child context with a new iid, the time left (negative
// when late) and the current cancel state. It never blocks a first attempt and never turns a slow call into an
// error. Only a retry of a cancelled or expired chain is held back. If a response says the chain is cancelled,
// that is remembered, so later calls carry it and running NonCritical work stops.
type SignalTransport struct {
	Base    http.RoundTripper
	Reserve time.Duration // time kept back for our own work after the call, taken off the child ttl
	Now     func() time.Time
}

func (t *SignalTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	return outbound{base: t.Base, reserve: t.Reserve, now: t.Now}.roundTrip(req)
}

// resultCancelState reads the cs value the sidecar puts in X-RPE-Result. Missing or broken means none.
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
