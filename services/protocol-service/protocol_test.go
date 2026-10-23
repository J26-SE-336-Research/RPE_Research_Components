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
		trails: newStore(3), truth: &groundTruth{edges: parseTruth("gateway>order-svc,order-svc>inventory-svc,order-svc>payment-svc,order-svc>legacy-svc,legacy-svc>shipping-svc"), source: "test"}, autoRegister: true}
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
	edges, source := s.truth.get()
	c := compare(topo, edges, source)
	if c.Precision != 100 || c.Recall != 80 || len(c.Missing) != 1 || c.Missing[0] != "legacy-svc → shipping-svc" || c.Note == "" || c.Source != "test" {
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
	if reg.Version == 1 || reg.Services["search-svc"] != 9 || reg.Services["gateway"] != 1 {
		t.Fatalf("registry %+v", reg)
	}
}

func TestAutoRegistration(t *testing.T) {
	s := testServer()
	srv := httptest.NewServer(s.routes())
	defer srv.Close()
	register := func(name string) (int, map[string]any) {
		resp, err := http.Post(srv.URL+"/api/protocol/registry/register", "application/json", bytes.NewReader([]byte(`{"service":"`+name+`"}`)))
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var out map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&out)
		return resp.StatusCode, out
	}
	// a known service keeps its id and the registry its version (idempotent)
	if code, out := register("payment-svc"); code != 200 || out["id"].(float64) != 4 || out["version"].(float64) != 1 {
		t.Fatalf("known service: %d %+v", code, out)
	}
	// a new service gets the next free id; the version becomes the checksum of the mapping
	code, first := register("search-svc")
	if code != 200 || first["id"].(float64) != 9 || first["version"].(float64) == 1 {
		t.Fatalf("new service: %d %+v", code, first)
	}
	if _, again := register("search-svc"); again["id"] != first["id"] || again["version"] != first["version"] {
		t.Fatalf("registering twice changes nothing: %+v %+v", first, again)
	}
	// the same mapping always gets the same version (a restart that registers in the same order is consistent)
	other := testServer()
	if v, _, _ := other.reg.add([]string{"search-svc"}); float64(v) != first["version"].(float64) {
		t.Fatalf("same mapping, different version: %d vs %v", v, first["version"])
	}
	// a mapping that gives the new service another id gets another version (never merged with the first)
	other2 := testServer()
	_, _, _ = other2.reg.add([]string{"cart-svc", "search-svc"})
	if v, _ := other2.reg.snapshot(); float64(v) == first["version"].(float64) {
		t.Fatal("a different mapping must have a different version")
	}
	if code, _ := register("Not_A_Name"); code != 400 {
		t.Fatalf("invalid names are refused, got %d", code)
	}
	s.autoRegister = false
	if code, _ := register("cart-svc"); code != 403 {
		t.Fatalf("registration can be switched off, got %d", code)
	}
}

func TestEdgesFromDeployments(t *testing.T) {
	raw := `{"items":[
	 {"metadata":{"name":"gateway"},"spec":{"template":{"spec":{"containers":[
	   {"name":"app","env":[{"name":"SERVICE_NAME","value":"gateway"},{"name":"CHILDREN","value":"order=http://order-svc:8080/orders"}]},
	   {"name":"rpe-sidecar","env":[{"name":"CHILDREN","value":"x=http://ignored:1"}]}]}}}},
	 {"metadata":{"name":"order-svc"},"spec":{"template":{"spec":{"containers":[{"name":"app","env":[{"name":"CHILDREN",
	   "value":"inventory=http://inventory-svc:8080/reserve, legacy=http://legacy-svc.rpe-demo.svc:8080/ship?optional, inventory=http://inventory-svc:8080/again"}]}]}}}},
	 {"metadata":{"name":"payment-svc"},"spec":{"template":{"spec":{"containers":[{"name":"app","env":[{"name":"SERVICE_NAME","value":"payment-svc"}]}]}}}}]}`
	var list struct {
		Items []deployment `json:"items"`
	}
	if err := json.Unmarshal([]byte(raw), &list); err != nil {
		t.Fatal(err)
	}
	got := edgesFromDeployments(list.Items)
	want := [][2]string{{"gateway", "order-svc"}, {"order-svc", "inventory-svc"}, {"order-svc", "legacy-svc"}}
	if len(got) != len(want) {
		t.Fatalf("edges %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("edges %v", got)
		}
	}
}
