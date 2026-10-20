package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// gateway(1) 100 ms -> order(2) 90 ms -> {inventory(3) 20 ms, payment(4) 70 ms in parallel, legacy(7) unknown 10 ms}
const sampleTrail = `rv=1, inc=?1, h="1:0:100000:F:0;2:1:90000:F:4;3:2:20000:F:6;4:2:70000:X:6;7:2:10000:U:6"`

func testServer() *server {
	return &server{reg: newRegistry(1, []string{"gateway", "order-svc", "inventory-svc", "payment-svc", "loyalty-svc", "fraud-check-svc", "legacy-svc", "shipping-svc"}),
		trails: newStore(3), truth: parseTruth("gateway>order-svc,order-svc>inventory-svc,order-svc>payment-svc,order-svc>legacy-svc,legacy-svc>shipping-svc")}
}

func TestAttribution(t *testing.T) {
	s := testServer()
	if !s.trails.add(rpe.TrailRecord{RID: "r1", Route: "GET /api/orders", BudgetMs: 60, Trail: sampleTrail, Status: 200}) {
		t.Fatal("trail rejected")
	}
	a := attribute(s.trails.get("r1"), s.reg)
	if len(a.Hops) != 5 || !a.Incomplete || !a.Late {
		t.Fatalf("attribution %+v", a)
	}
	// order's own time: 90 - union(20, 70, 10 starting at the same point) = 20 ms; payment is the bottleneck (70 ms own)
	if a.Hops[1].SelfMs != 20 || a.Hops[0].SelfMs != 10 || a.Hops[3].SelfMs != 70 || a.Hops[a.Bottleneck-1].Service != "payment-svc" {
		t.Fatalf("self times %+v", a.Hops)
	}
	if a.Hops[4].Service != "legacy-svc" || !a.Hops[4].Unknown || a.Hops[2].Depth != 2 {
		t.Fatalf("hops %+v", a.Hops)
	}
}

func TestTopologyAndCompare(t *testing.T) {
	s := testServer()
	for _, rid := range []string{"a", "b", "c", "d"} { // capacity 3: the oldest is evicted
		s.trails.add(rpe.TrailRecord{RID: rid, Route: "GET /api/orders", BudgetMs: 1000, Trail: sampleTrail, At: time.Now()})
	}
	if s.trails.get("a") != nil || s.trails.get("d") == nil {
		t.Fatal("the ring keeps the newest trails")
	}
	topo := rebuild(s.trails.since(time.Now().Add(-time.Minute)), s.reg, time.Minute)
	if topo.Trails != 3 || topo.Incomplete != 3 || len(topo.Edges) != 4 || len(topo.Nodes) != 5 {
		t.Fatalf("topology %+v", topo)
	}
	c := compare(topo, s.truth)
	if c.Precision != 100 || c.Recall != 80 || len(c.Missing) != 1 || c.Missing[0] != "legacy-svc → shipping-svc" || c.Note == "" {
		t.Fatalf("comparison %+v", c)
	}
}

func TestHTTP(t *testing.T) {
	s := testServer()
	srv := httptest.NewServer(s.routes())
	defer srv.Close()
	body, _ := json.Marshal(map[string]any{"trails": []rpe.TrailRecord{{RID: "r9", Route: "GET /x", BudgetMs: 500, Trail: sampleTrail}, {RID: "bad", Trail: "rv=0"}}})
	resp, err := http.Post(srv.URL+"/api/protocol/trails", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]int
	_ = json.NewDecoder(resp.Body).Decode(&got)
	resp.Body.Close()
	if got["accepted"] != 1 || got["rejected"] != 1 {
		t.Fatalf("post %+v", got)
	}
	for path, want := range map[string]int{"/api/protocol/attribution/r9": 200, "/api/protocol/attribution/nope": 404,
		"/api/protocol/topology?window=60": 200, "/api/protocol/topology/compare": 200, "/api/protocol/registry": 200, "/api/protocol/trails": 200} {
		resp, err := http.Get(srv.URL + path)
		if err != nil || resp.StatusCode != want {
			t.Fatalf("%s: %v %v", path, resp.StatusCode, err)
		}
		resp.Body.Close()
	}
	// go-only validation does not need rpe-java
	resp, _ = http.Post(srv.URL+"/api/protocol/validate", "application/json", bytes.NewReader([]byte(`{"kind":"trail","impl":"go","value":"rv=1, inc=?0"}`)))
	var v map[string]Verdict
	_ = json.NewDecoder(resp.Body).Decode(&v)
	resp.Body.Close()
	if !v["go"].Valid || v["go"].Canonical != "rv=1, inc=?0" {
		t.Fatalf("validate %+v", v)
	}
	// registry: new services get the next id and a new version; existing ids never change
	req, _ := http.NewRequest(http.MethodPut, srv.URL+"/api/protocol/registry", bytes.NewReader([]byte(`{"services":["gateway","search-svc"]}`)))
	resp, _ = http.DefaultClient.Do(req)
	var reg struct {
		Version  int64            `json:"version"`
		Services map[string]int64 `json:"services"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&reg)
	resp.Body.Close()
	if reg.Version != 2 || reg.Services["search-svc"] != 9 || reg.Services["gateway"] != 1 {
		t.Fatalf("registry %+v", reg)
	}
}
