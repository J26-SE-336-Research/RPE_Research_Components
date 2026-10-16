package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

func newTestService(t *testing.T, children string, fanout string) *service {
	t.Setenv("CHILDREN", children)
	t.Setenv("FANOUT", fanout)
	return newService()
}

func TestCombineFanoutPolicy(t *testing.T) {
	s := &service{fanout: "partial", children: []Child{{Name: "inv"}, {Name: "loy", Optional: true}}}
	if st, out, _ := s.combine([]ChildResult{{Outcome: "ok"}, {Outcome: "expired"}}); st != 200 || out != "partial" {
		t.Fatalf("partial: got %d %s", st, out)
	}
	s.fanout = "all"
	st, out, term := s.combine([]ChildResult{{Outcome: "ok"}, {Outcome: "expired"}})
	if st != http.StatusGatewayTimeout || out != "failed" || !term {
		t.Fatalf("all + expired must be a terminal 504 (R-F03): got %d %s term=%v", st, out, term)
	}
}

func TestChainPropagatesContext(t *testing.T) {
	leafSvc := newTestService(t, "", "partial")
	leaf := httptest.NewServer(rpe.Middleware(rpe.MiddlewareConfig{}, http.HandlerFunc(leafSvc.handle)))
	defer leaf.Close()
	gwSvc := newTestService(t, "leaf="+leaf.URL+"/work", "partial")
	gw := httptest.NewServer(rpe.Middleware(rpe.MiddlewareConfig{Gateway: true, RetryBudget: 3,
		RouteBudget: routeBudgets("GET /api/orders=800", 1000)}, http.HandlerFunc(gwSvc.handle)))
	defer gw.Close()

	resp, err := http.Get(gw.URL + "/api/orders")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var body Response
	_ = json.NewDecoder(resp.Body).Decode(&body)
	if resp.StatusCode != 200 || len(body.Children) != 1 || body.Children[0].Status != 200 {
		t.Fatalf("unexpected response %d %+v", resp.StatusCode, body)
	}
	if !strings.Contains(body.Context, "bud=800") || !strings.Contains(body.Context, `rt="GET /api/orders"`) {
		t.Fatalf("gateway context not created from route budget: %s", body.Context)
	}
}

func TestExpiredChildMakesAllFanoutTerminal(t *testing.T) {
	slowSvc := newTestService(t, "", "partial")
	slowSvc.faults = Faults{LatencyMs: 300}
	slow := httptest.NewServer(rpe.Middleware(rpe.MiddlewareConfig{}, http.HandlerFunc(slowSvc.handle)))
	defer slow.Close()
	// the slow child holds the request past the deadline; the next hop (a fresh call) is refused locally
	midSvc := newTestService(t, "slow="+slow.URL+"/x,after="+slow.URL+"/y", "all")
	midSvc.split = rpe.SplitSequential
	mid := httptest.NewServer(rpe.Middleware(rpe.MiddlewareConfig{Gateway: true,
		RouteBudget: routeBudgets("", 200)}, http.HandlerFunc(midSvc.handle)))
	defer mid.Close()
	start := time.Now()
	resp, err := http.Get(mid.URL + "/api/orders")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	res, ok, _ := rpe.ParseResult(resp.Header)
	if resp.StatusCode != http.StatusGatewayTimeout || !ok || !res.Terminal {
		t.Fatalf("want terminal 504, got %d %+v (after %v)", resp.StatusCode, res, time.Since(start))
	}
}
