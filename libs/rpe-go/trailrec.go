package rpe

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Trail recording by the HTTP adapters (SignalMiddleware, SignalTransport, Middleware, Transport). It is opt-in:
// without a TrailConfig, or before the service registry is loaded, nothing is added and nothing changes.

// ServiceRegistry maps service names to their 2-byte trail ids (R-P03). ok is false when the name is not
// registered or the registry is not loaded yet.
type ServiceRegistry interface {
	Lookup(name string) (version, id int64, ok bool)
}

// StaticRegistry is a fixed registry (tests, single-binary deployments).
type StaticRegistry struct {
	Version int64
	IDs     map[string]int64
}

func (s StaticRegistry) Lookup(name string) (int64, int64, bool) {
	id, ok := s.IDs[name]
	return s.Version, id, ok && s.Version > 0
}

// RegistryClient follows the registry published by the protocol service (GET /api/protocol/registry:
// {"version": 1, "services": {"gateway": 1, ...}}).
type RegistryClient struct {
	url    string
	client *http.Client
	mu     sync.RWMutex
	reg    StaticRegistry
}

func NewRegistryClient(url string) *RegistryClient {
	return &RegistryClient{url: url, client: &http.Client{Timeout: 3 * time.Second}}
}

func (c *RegistryClient) Lookup(name string) (int64, int64, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.reg.Lookup(name)
}

// Refresh loads the registry once.
func (c *RegistryClient) Refresh(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url, nil)
	if err != nil {
		return err
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("registry: %s", resp.Status)
	}
	var body struct {
		Version  int64            `json:"version"`
		Services map[string]int64 `json:"services"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return err
	}
	if body.Version < 1 {
		return fmt.Errorf("registry: invalid version %d", body.Version)
	}
	c.mu.Lock()
	c.reg = StaticRegistry{Version: body.Version, IDs: body.Services}
	c.mu.Unlock()
	return nil
}

// Run refreshes the registry every interval (every 2 s until the first load succeeds) until ctx ends.
func (c *RegistryClient) Run(ctx context.Context, interval time.Duration) {
	for {
		wait := interval
		if err := c.Refresh(ctx); err != nil && c.version() == 0 {
			wait = 2 * time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
	}
}

func (c *RegistryClient) version() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.reg.Version
}

// TrailRecord is a completed trail, reported at the trust boundary where the trail leaves the chain (R-P04).
type TrailRecord struct {
	RID      string    `json:"rid"`
	Route    string    `json:"route"`
	BudgetMs int64     `json:"budgetMs"`
	Epoch    string    `json:"epoch,omitempty"`
	Status   int       `json:"status"`
	At       time.Time `json:"at"`
	Trail    string    `json:"trail"`
}

// TrailConfig enables the in-band trail.
type TrailConfig struct {
	// Service is this service's name in the registry.
	Service string
	// Registry maps names to ids; the trail is recorded only while it knows Service.
	Registry ServiceRegistry
	// Sink receives the completed trail at the gateway (where it is removed from the response, R-P04). It must not
	// block; TrailCollector.Add is a suitable sink.
	Sink func(TrailRecord)
}

// TrailCollector batches completed trails and posts them to the protocol service's collector
// (POST /api/protocol/trails, body {"trails": [...]}). Add never blocks: when the buffer is full, trails are dropped.
type TrailCollector struct {
	url    string
	client *http.Client
	ch     chan TrailRecord
}

func NewTrailCollector(url string, capacity int) *TrailCollector {
	return &TrailCollector{url: url, client: &http.Client{Timeout: 3 * time.Second}, ch: make(chan TrailRecord, capacity)}
}

func (c *TrailCollector) Add(r TrailRecord) {
	select {
	case c.ch <- r:
	default:
	}
}

// Run posts the buffered trails every interval until ctx ends.
func (c *TrailCollector) Run(ctx context.Context, interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		var batch []TrailRecord
	drain:
		for len(batch) < 500 {
			select {
			case r := <-c.ch:
				batch = append(batch, r)
			default:
				break drain
			}
		}
		if len(batch) == 0 {
			continue
		}
		b, _ := json.Marshal(map[string]any{"trails": batch})
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewReader(b))
		if err != nil {
			continue
		}
		req.Header.Set("Content-Type", "application/json")
		if resp, err := c.client.Do(req); err == nil {
			resp.Body.Close()
		}
	}
}

// trailRecorder holds the trail of the request being served.
type trailRecorder struct {
	mu       sync.Mutex
	cfg      *TrailConfig
	path     Trail // request side: the path from the gateway to this hop (own entry active)
	subtree  Trail // response side: own entry first, then the callees' subtrees
	received time.Time
	ctx      Context
	attempt  int64
	gateway  bool
	done     bool
}

func newTrailRecorder(cfg *TrailConfig, c Context, hop Hop, incoming string, gateway bool, received time.Time) *trailRecorder {
	if cfg == nil || cfg.Registry == nil {
		return nil
	}
	version, id, ok := cfg.Registry.Lookup(cfg.Service)
	if !ok {
		return nil
	}
	path := Trail{RegistryVersion: version}
	if !gateway { // at the gateway any client-supplied trail is ignored (trust boundary)
		switch t, err := DecodeTrail(incoming); {
		case incoming == "":
			// a context without a trail below the gateway: an upstream hop did not carry it (R-P05)
			path.Incomplete = c.PID != RootParentID
		case err != nil || t.RegistryVersion != version:
			path.Incomplete = true
		default:
			path = t
		}
	}
	self := TrailEntry{Service: id, ElapsedMicros: TopologyActiveElapsedMicros, Decision: DecisionActive,
		ConsumedMs: min(max(c.BudgetMillis-c.TTLMillis, 0), maxConsumedMillis)}
	path = path.Append(Trail{RegistryVersion: version, Entries: []TrailEntry{self}}, len(path.Entries))
	return &trailRecorder{cfg: cfg, path: path, received: received, ctx: c, attempt: hop.Attempt, gateway: gateway,
		subtree: Trail{RegistryVersion: version, Entries: []TrailEntry{self}}}
}

type trailKey struct{}

func trailFrom(ctx context.Context) *trailRecorder {
	r, _ := ctx.Value(trailKey{}).(*trailRecorder)
	return r
}

// requestHeader is the trail sent to a callee.
func (r *trailRecorder) requestHeader() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.path.Encode()
}

// response merges what a callee returned: its subtree, or one unknown entry when it returned none (a hop that does
// not speak RPE, or a call without a response).
func (r *trailRecorder) response(h http.Header, host string, elapsed time.Duration, childTTL int64) {
	raw := ""
	if h != nil {
		raw = h.Get(HeaderTrail)
		h.Del(HeaderTrail)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.done {
		return
	}
	if raw != "" {
		if t, err := DecodeTrail(raw); err == nil {
			r.subtree = r.subtree.Append(t, 1)
			return
		}
	}
	_, id, _ := r.cfg.Registry.Lookup(serviceOfHost(host))
	unknown := TrailEntry{Service: id, ElapsedMicros: max(elapsed.Microseconds(), 0), Decision: DecisionUnknown,
		ConsumedMs: min(max(r.ctx.BudgetMillis-childTTL, 0), maxConsumedMillis)}
	r.subtree = r.subtree.Append(Trail{RegistryVersion: r.subtree.RegistryVersion, Incomplete: true, Entries: []TrailEntry{unknown}}, 1)
}

// serviceOfHost turns "legacy-svc", "legacy-svc:8080" or "legacy-svc.rpe-demo.svc" into "legacy-svc".
func serviceOfHost(host string) string {
	if i := strings.IndexAny(host, ".:"); i > 0 {
		return host[:i]
	}
	return host
}

// complete finalizes the own entry when the response starts. decision 0 derives it from the response.
func (r *trailRecorder) complete(h http.Header, status int, cs CancelState, decision TrailDecision) {
	r.mu.Lock()
	if r.done {
		r.mu.Unlock()
		return
	}
	r.done = true
	if decision == 0 {
		decision = r.decide(h, status, cs)
	}
	t := r.subtree
	t.Entries = append([]TrailEntry(nil), t.Entries...)
	t.Entries[0].ElapsedMicros = max(time.Since(r.received).Microseconds(), 0)
	t.Entries[0].Decision = decision
	r.mu.Unlock()
	if !r.gateway {
		h.Set(HeaderTrail, t.Encode())
		return
	}
	h.Del(HeaderTrail) // the trail never leaves the chain (R-P04)
	if r.cfg.Sink != nil {
		r.cfg.Sink(TrailRecord{RID: r.ctx.RID, Route: r.ctx.Route, BudgetMs: r.ctx.BudgetMillis, Epoch: r.ctx.PolicyEpoch,
			Status: status, At: time.Now().UTC(), Trail: t.Encode()})
	}
}

func (r *trailRecorder) decide(h http.Header, status int, cs CancelState) TrailDecision {
	res, _, _ := ParseResult(h)
	switch {
	case status == http.StatusTooManyRequests || status == http.StatusServiceUnavailable:
		return DecisionOverload
	case res.Outcome == OutcomeStopped || res.Reason == ReasonCancelled || cs == CancelRequest:
		return DecisionCancelled
	case res.Late || cs == CancelExpired || r.ctx.Cancel == CancelExpired:
		return DecisionExpired
	case res.Outcome == OutcomePartial:
		return DecisionPartial
	case r.attempt >= 2:
		return DecisionRetry
	}
	return DecisionForward
}

// trailWriter finalizes the trail when the handler starts its response.
type trailWriter struct {
	http.ResponseWriter
	rec   *trailRecorder
	ctx   context.Context
	wrote bool
}

func (w *trailWriter) WriteHeader(code int) {
	if !w.wrote {
		w.wrote = true
		w.rec.complete(w.ResponseWriter.Header(), code, Cancellation(w.ctx), 0)
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *trailWriter) Write(b []byte) (int, error) {
	if !w.wrote {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(b)
}

func (w *trailWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		if !w.wrote {
			w.WriteHeader(http.StatusOK)
		}
		f.Flush()
	}
}

func (w *trailWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
