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
