package rpe

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestTrailRoundTrip(t *testing.T) {
	raw := `rv=7, inc=?0, h="3:0:1840:F:12;9:1:950:F:40;11:1:-1:A:0"`
	tr, err := DecodeTrail(raw)
	if err != nil {
		t.Fatal(err)
	}
	if tr.RegistryVersion != 7 || tr.Incomplete || len(tr.Entries) != 3 || tr.Entries[2].Decision != DecisionActive || tr.Entries[1].Parent != 1 {
		t.Fatalf("unexpected trail %+v", tr)
	}
	if got := tr.Encode(); got != raw {
		t.Fatalf("canonical encoding %q", got)
	}
	empty, err := DecodeTrail("inc=?1, rv=2")
	if err != nil || empty.Encode() != "rv=2, inc=?1" {
		t.Fatalf("empty trail: %+v %v", empty, err)
	}
}

func TestTrailRejectsInvalid(t *testing.T) {
	for _, raw := range []string{
		`rv=1, h="1:0:5:F:0"`,          // inc missing
		`rv=0, inc=?0`,                 // rv out of range
		`rv=1, inc=?0, h="1:1:5:F:0"`,  // parent is not an earlier entry
		`rv=1, inc=?0, h="1:0:5:Z:0"`,  // unknown decision
		`rv=1, inc=?0, h="1:0:-1:F:0"`, // only active entries have -1
		`rv=1, inc=?0, h="1:0:5:A:0"`,  // an active entry must have -1
		`rv=1, inc=?0, h="1:0:05:F:0"`, // noncanonical
		`rv=1, inc=?0, h="70000:0:5:F:0"`,
		`rv=1, inc=?0, h="1:0:5:F"`,
		`rv=1, inc=1`,
	} {
		if _, err := DecodeTrail(raw); err == nil {
			t.Errorf("%s: expected an error", raw)
		}
	}
	long := `rv=1, inc=?0, h="` + strings.TrimSuffix(strings.Repeat("1:0:5:F:0;", 17), ";") + `"`
	if _, err := DecodeTrail(long); err == nil {
		t.Error("more than 16 entries must be rejected")
	}
}

func TestTrailAppendReindexesAndBounds(t *testing.T) {
	base := Trail{RegistryVersion: 1, Entries: []TrailEntry{{Service: 1, ElapsedMicros: 10, Decision: DecisionForward}}}
	sub := Trail{RegistryVersion: 1, Entries: []TrailEntry{
		{Service: 2, ElapsedMicros: 5, Decision: DecisionForward},
		{Service: 3, Parent: 1, ElapsedMicros: 2, Decision: DecisionForward},
	}}
	got := base.Append(sub, 1)
	if got.Encode() != `rv=1, inc=?0, h="1:0:10:F:0;2:1:5:F:0;3:2:2:F:0"` {
		t.Fatalf("got %s", got.Encode())
	}
	if other := base.Append(Trail{RegistryVersion: 2, Entries: sub.Entries}, 1); !other.Incomplete || len(other.Entries) != 1 {
		t.Fatalf("a subtree of another registry version must only mark the trail incomplete: %s", other.Encode())
	}
	big := base
	for i := 0; i < 20; i++ {
		big = big.Append(sub, 1)
	}
	if len(big.Entries) != MaxTrailEntries || !big.Incomplete || len(big.Encode()) > MaxTrailBytes {
		t.Fatalf("bounds not applied: %d entries inc=%v", len(big.Entries), big.Incomplete)
	}
	if _, err := DecodeTrail(big.Encode()); err != nil {
		t.Fatalf("a bounded trail must stay decodable: %v", err)
	}
}

// TestTrailThroughAChain runs gateway -> order -> inventory and legacy, where legacy has no RPE. Every RPE hop
// should add one entry, legacy should show up as unknown and mark the trail incomplete, and the gateway should
// not send the trail back to the client.
func TestTrailThroughAChain(t *testing.T) {
	reg := StaticRegistry{Version: 3, IDs: map[string]int64{"gateway": 1, "order": 2, "inventory": 3, "legacy": 4}}
	client := &http.Client{Transport: &SignalTransport{}}
	var mu sync.Mutex
	var records []TrailRecord
	var requestTrail string

	inventory := httptest.NewServer(SignalMiddleware(SignalConfig{Trail: &TrailConfig{Service: "inventory", Registry: reg}},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			time.Sleep(5 * time.Millisecond)
			w.WriteHeader(200)
		})))
	defer inventory.Close()
	legacy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		requestTrail = r.Header.Get(HeaderTrail)
		mu.Unlock()
		w.WriteHeader(200)
	}))
	defer legacy.Close()
	order := httptest.NewServer(SignalMiddleware(SignalConfig{Trail: &TrailConfig{Service: "order", Registry: reg}},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			for _, u := range []string{inventory.URL, legacy.URL} { // legacy isn't in the registry, so its id is 0
				req, _ := http.NewRequestWithContext(r.Context(), "GET", u, nil)
				if resp, err := client.Do(req); err == nil {
					resp.Body.Close()
				}
			}
			w.WriteHeader(200)
		})))
	defer order.Close()
	gateway := httptest.NewServer(SignalMiddleware(SignalConfig{Gateway: true,
		Trail: &TrailConfig{Service: "gateway", Registry: reg, Sink: func(r TrailRecord) { mu.Lock(); records = append(records, r); mu.Unlock() }}},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			req, _ := http.NewRequestWithContext(r.Context(), "GET", order.URL, nil)
			resp, err := client.Do(req)
			if err != nil {
				t.Error(err)
				return
			}
			resp.Body.Close()
			w.WriteHeader(resp.StatusCode)
		})))
	defer gateway.Close()

	req, _ := http.NewRequest("GET", gateway.URL, nil)
	req.Header.Set(HeaderTrail, `rv=3, inc=?0, h="4:0:1:F:0"`) // a client-supplied trail is ignored
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.Header.Get(HeaderTrail) != "" {
		t.Fatal("the trail must not leave the chain (R-P04)")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(records) != 1 {
		t.Fatalf("want one completed trail, got %d", len(records))
	}
	tr, err := DecodeTrail(records[0].Trail)
	if err != nil {
		t.Fatal(err)
	}
	// gateway(1) -> order(2) -> inventory(3) F, unregistered host -> unknown U
	if len(tr.Entries) != 4 || !tr.Incomplete || tr.RegistryVersion != 3 {
		t.Fatalf("unexpected trail %s", records[0].Trail)
	}
	want := []struct {
		svc    int64
		parent int
		dec    TrailDecision
	}{{1, 0, DecisionForward}, {2, 1, DecisionForward}, {3, 2, DecisionForward}, {0, 2, DecisionUnknown}}
	for i, w := range want {
		e := tr.Entries[i]
		if e.Service != w.svc || e.Parent != w.parent || e.Decision != w.dec || e.ElapsedMicros < 0 {
			t.Fatalf("entry %d: %+v (trail %s)", i+1, e, records[0].Trail)
		}
	}
	if tr.Entries[0].ElapsedMicros < tr.Entries[1].ElapsedMicros || tr.Entries[1].ElapsedMicros < tr.Entries[2].ElapsedMicros {
		t.Fatalf("a caller's elapsed time includes its callees: %s", records[0].Trail)
	}
	if records[0].RID == "" || records[0].Route == "" || records[0].Status != 200 {
		t.Fatalf("record %+v", records[0])
	}
	// the request side carries the path to the caller: gateway and order, both active
	if !strings.HasPrefix(requestTrail, `rv=3, inc=?0, h="1:0:-1:A:0;2:1:-1:A:`) {
		t.Fatalf("request trail %q", requestTrail)
	}
}

func TestTrailOffWithoutRegistry(t *testing.T) {
	h := SignalMiddleware(SignalConfig{Trail: &TrailConfig{Service: "x", Registry: StaticRegistry{}}},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
	rec := serve(t, h, cancelledHeader(t, CancelNone, 500*time.Millisecond))
	if rec.Header().Get(HeaderTrail) != "" {
		t.Fatal("no trail before the registry is loaded")
	}
}

func TestRegistryClientRegistersItself(t *testing.T) {
	var mu sync.Mutex
	version, ids := int64(1), map[string]int64{"gateway": 1}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.Method == http.MethodPost && r.URL.Path == "/api/protocol/registry/register" {
			var in struct{ Service string }
			_ = json.NewDecoder(r.Body).Decode(&in)
			if _, ok := ids[in.Service]; !ok {
				ids[in.Service] = int64(len(ids) + 1)
				version = 77
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"version": version, "services": ids})
	}))
	defer srv.Close()
	c := NewRegistryClient(srv.URL + "/api/protocol/registry").RegisterAs("search-svc")
	if err := c.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	if v, id, ok := c.Lookup("search-svc"); !ok || id != 2 || v != 77 {
		t.Fatalf("registered: %d %d %v", v, id, ok)
	}
	// a known service does not register again
	known := NewRegistryClient(srv.URL + "/api/protocol/registry").RegisterAs("gateway")
	if err := known.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	if v, id, _ := known.Lookup("gateway"); id != 1 || v != 77 {
		t.Fatalf("known: %d %d", v, id)
	}
	if len(ids) != 2 {
		t.Fatalf("no extra registration: %v", ids)
	}
}
