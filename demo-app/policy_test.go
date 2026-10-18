package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestPolicyFollowerAppliesActiveRouteDeadline(t *testing.T) {
	active := true
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !active {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write([]byte(`{"epoch":"e5","version":2,"policy":{"routes":{"GET /api/orders":{"deadlineMs":750}}}}`))
	}))
	defer srv.Close()
	f := newPolicyFollower(srv.URL)
	f.refresh(context.Background())
	budget := f.RouteBudget(routeBudgets("GET /api/orders=1000;POST /api/orders=2000", 1000))
	if _, d := budget(httptest.NewRequest("GET", "/api/orders", nil)); d != 750*time.Millisecond {
		t.Fatalf("active route deadline not applied: %v", d)
	}
	if _, d := budget(httptest.NewRequest("POST", "/api/orders", nil)); d != 2*time.Second {
		t.Fatalf("other routes keep the static budget: %v", d)
	}
	if f.Epoch("e0") != "e5" {
		t.Fatalf("active epoch not stamped")
	}
	active = false
	f.refresh(context.Background())
	if _, d := budget(httptest.NewRequest("GET", "/api/orders", nil)); d != time.Second || f.Epoch("e0") != "e0" {
		t.Fatalf("without an active policy the static configuration applies: %v %s", d, f.Epoch("e0"))
	}
}

func TestGatewayUsesTheDeadlineItsSidecarStamped(t *testing.T) {
	budget := stampedBudget(routeBudgets("GET /api/orders=1000", 1000))
	r := httptest.NewRequest("GET", "/api/orders", nil)
	if _, d := budget(r); d != time.Second {
		t.Fatalf("without a received context the configured budget applies: %v", d)
	}
	r.Header.Set("X-RPE-Context", `v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=4000, bud=4000, rb=3, cs=none, pe="chain-orders-v2", rt="GET /api/orders"`)
	if route, d := budget(r); d != 4*time.Second || route != "GET /api/orders" {
		t.Fatalf("the sidecar's stamped deadline wins over a lagging copy: %s %v", route, d)
	}
	r.Header.Set("X-RPE-Context", "garbage")
	if _, d := budget(r); d != time.Second {
		t.Fatalf("an unreadable context falls back: %v", d)
	}
}
