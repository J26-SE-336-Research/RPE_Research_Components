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

// Trail recording for the HTTP middleware and transports. It is off unless a TrailConfig is given, and nothing
// is added until the service registry has loaded.

// ServiceRegistry maps service names to their small trail ids. ok is false if the name isn't registered or the
// registry hasn't loaded yet.
type ServiceRegistry interface {
	Lookup(name string) (version, id int64, ok bool)
}

// StaticRegistry is a fixed registry, handy for tests.
type StaticRegistry struct {
	Version int64
	IDs     map[string]int64
}

func (s StaticRegistry) Lookup(name string) (int64, int64, bool) {
	id, ok := s.IDs[name]
	return s.Version, id, ok && s.Version > 0
}

// RegistryClient keeps a copy of the registry from the protocol service, read from GET /api/protocol/registry.
type RegistryClient struct {
	url    string
	self   string // our own name, registered if the registry doesn't have it yet
	client *http.Client
	mu     sync.RWMutex
	reg    StaticRegistry
}

func NewRegistryClient(url string) *RegistryClient {
	return &RegistryClient{url: url, client: &http.Client{Timeout: 3 * time.Second}}
}

// RegisterAs makes the client register this service if the registry doesn't list it yet, so a new service shows up
// in the trail without anyone editing the registry by hand. If registering is refused, the service just records
// no trail.
func (c *RegistryClient) RegisterAs(service string) *RegistryClient {
	c.self = service
	return c
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
	if err := c.load(resp); err != nil {
		return err
	}
	if c.self == "" {
		return nil
	}
	if _, _, ok := c.Lookup(c.self); ok {
		return nil
	}
	b, _ := json.Marshal(map[string]string{"service": c.self})
	reg, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimSuffix(c.url, "/")+"/register", bytes.NewReader(b))
	if err != nil {
		return err
	}
	reg.Header.Set("Content-Type", "application/json")
	rresp, err := c.client.Do(reg)
	if err != nil {
		return err
	}
	defer rresp.Body.Close()
	if rresp.StatusCode != http.StatusOK {
		return fmt.Errorf("registry: registering %s: %s", c.self, rresp.Status)
	}
	return c.load(rresp)
}

// load reads a registry response.
func (c *RegistryClient) load(resp *http.Response) error {
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

// Run reloads the registry every interval until ctx is done. Until the first load works it retries every 2 s.
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

// TrailRecord is a finished trail, reported by the gateway before the response goes back to the client.
type TrailRecord struct {
	RID      string    `json:"rid"`
	Route    string    `json:"route"`
	BudgetMs int64     `json:"budgetMs"`
	Epoch    string    `json:"epoch,omitempty"`
	Status   int       `json:"status"`
	At       time.Time `json:"at"`
	Trail    string    `json:"trail"`
}

// TrailConfig turns on the trail.
type TrailConfig struct {
	// Service is this service's name in the registry.
	Service string
	// Registry maps names to ids. Nothing is recorded until it knows Service.
	Registry ServiceRegistry
	// Sink gets the finished trail at the gateway. It must not block, TrailCollector.Add works well here.
	Sink func(TrailRecord)
}

// TrailCollector collects finished trails and posts them to the protocol service in batches. Add never blocks,
// if the buffer is full the trail is dropped.
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

// Run posts the buffered trails every interval until ctx is done.
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
	path     Trail // the way from the gateway down to us, sent with requests
	subtree  Trail // our own entry and what our callees sent back
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
	if !gateway { // the gateway ignores any trail sent by the client
		switch t, err := DecodeTrail(incoming); {
		case incoming == "":
			// we got a context but no trail, so some hop above us doesn't speak RPE
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

// requestHeader is the trail we send to a callee.
func (r *trailRecorder) requestHeader() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.path.Encode()
}

// response adds what a callee sent back. If it sent no trail, for example because it doesn't speak RPE, we add
// one unknown entry for it.
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

// complete fills in our own entry when the response starts. decision 0 means work it out from the response.
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
	h.Del(HeaderTrail) // don't leak the trail to the client
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

// trailWriter fills in the trail when the handler starts writing its response.
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
