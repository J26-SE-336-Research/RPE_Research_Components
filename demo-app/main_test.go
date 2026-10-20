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
	if st, out, _ := s.combine([]ChildResult{{Outcome: "ok"}, {Outcome: "skipped"}}); st != 200 || out != "partial" {
		t.Fatalf("a skipped optional child is a partial result: got %d %s", st, out)
	}
	s.fanout = "all"
	st, out, term := s.combine([]ChildResult{{Outcome: "ok"}, {Outcome: "expired"}})
	if st != http.StatusGatewayTimeout || out != "failed" || !term {
		t.Fatalf("all + expired must be a terminal 504 (R-F03): got %d %s term=%v", st, out, term)
	}
}

func TestChainPropagatesContext(t *testing.T) {
	leafSvc := newTestService(t, "", "partial")
	leaf := httptest.NewServer(rpe.SignalMiddleware(rpe.SignalConfig{}, http.HandlerFunc(leafSvc.handle)))
	defer leaf.Close()
	gwSvc := newTestService(t, "leaf="+leaf.URL+"/work", "partial")
	gw := httptest.NewServer(rpe.SignalMiddleware(rpe.SignalConfig{Gateway: true, RetryBudget: 3,
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
	leafCtx, err := rpe.DecodeContext(body.Children[0].Downstream.Context)
	if err != nil {
		t.Fatal(err)
	}
	gwCtx, _ := rpe.DecodeContext(body.Context)
	if leafCtx.RID != gwCtx.RID || leafCtx.PID != gwCtx.IID {
		t.Fatalf("child context does not continue the chain: %s -> %s", body.Context, body.Children[0].Downstream.Context)
	}
}

// Signal, never reject: a request that runs past its deadline is served to the end and reported as late.
func TestLateRequestIsServedNotRejected(t *testing.T) {
	slowSvc := newTestService(t, "", "partial")
	slowSvc.faults = Faults{LatencyMs: 300}
	slowSvc.sideEffect = "cards_charged"
	slow := httptest.NewServer(rpe.SignalMiddleware(rpe.SignalConfig{}, http.HandlerFunc(slowSvc.handle)))
	defer slow.Close()
	gwSvc := newTestService(t, "pay="+slow.URL+"/pay", "all")
	gw := httptest.NewServer(rpe.SignalMiddleware(rpe.SignalConfig{Gateway: true, RouteBudget: routeBudgets("", 200)}, http.HandlerFunc(gwSvc.handle)))
	defer gw.Close()
	start := time.Now()
	resp, err := http.Get(gw.URL + "/api/orders")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	res, ok, _ := rpe.ParseResult(resp.Header)
	if resp.StatusCode != http.StatusOK || !ok || !res.Late || res.Terminal {
		t.Fatalf("want a late 200, got %d %+v", resp.StatusCode, res)
	}
	if time.Since(start) < 300*time.Millisecond {
		t.Fatalf("the slow work must not be cut off (%v)", time.Since(start))
	}
	if slowSvc.counters["cards_charged"] != 1 {
		t.Fatalf("critical work completes even when late: %v", slowSvc.counters)
	}
}

func cancelledContext(t *testing.T) string {
	t.Helper()
	root, err := rpe.NewRoot("GET /api/orders", time.Second, 3, "", "")
	if err != nil {
		t.Fatal(err)
	}
	root.Cancel = rpe.CancelExpired
	return root.Encode()
}

func TestCancelledRequestIsAnsweredAndNonCriticalWorkIsSkipped(t *testing.T) {
	optional := newTestService(t, "", "partial")
	optional.nonCritical = true
	optional.sideEffect = "points_awarded"
	h := rpe.SignalMiddleware(rpe.SignalConfig{}, http.HandlerFunc(optional.handle))
	req := httptest.NewRequest(http.MethodGet, "/award", nil)
	req.Header.Set(rpe.HeaderContext, cancelledContext(t))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || optional.counters["points_awarded"] != 0 || optional.counters["work_skipped"] != 1 {
		t.Fatalf("non-critical work must be skipped but answered: %d %v", rec.Code, optional.counters)
	}

	critical := newTestService(t, "", "partial")
	critical.sideEffect = "cards_charged"
	h = rpe.SignalMiddleware(rpe.SignalConfig{}, http.HandlerFunc(critical.handle))
	req = httptest.NewRequest(http.MethodGet, "/pay", nil)
	req.Header.Set(rpe.HeaderContext, cancelledContext(t))
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || critical.counters["cards_charged"] != 1 {
		t.Fatalf("critical work completes even when the chain is cancelled: %d %v", rec.Code, critical.counters)
	}
}

func TestCancelledChainSkipsOptionalChildren(t *testing.T) {
	called := 0
	child := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { called++ }))
	defer child.Close()
	s := newTestService(t, "req="+child.URL+"/r,opt="+child.URL+"/o?optional", "partial")
	h := rpe.SignalMiddleware(rpe.SignalConfig{}, http.HandlerFunc(s.handle))
	req := httptest.NewRequest(http.MethodGet, "/orders", nil)
	req.Header.Set(rpe.HeaderContext, cancelledContext(t))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || called != 1 || !strings.Contains(rec.Body.String(), `"outcome":"skipped"`) {
		t.Fatalf("only the required child is called: code=%d calls=%d body=%s", rec.Code, called, rec.Body.String())
	}
}
