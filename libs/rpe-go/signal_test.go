package rpe

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func cancelledHeader(t *testing.T, cs CancelState, ttl time.Duration) string {
	t.Helper()
	c, err := NewRoot("GET /api/orders", time.Second, 3, "", "")
	if err != nil {
		t.Fatal(err)
	}
	c.Cancel = cs
	c.TTLMillis = ttl.Milliseconds()
	return c.Encode()
}

func serve(t *testing.T, h http.Handler, ctxHeader string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/work", nil)
	if ctxHeader != "" {
		req.Header.Set(HeaderContext, ctxHeader)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestSignalMiddlewareServesLateAndCancelledRequests(t *testing.T) {
	for _, tc := range []struct {
		name string
		cs   CancelState
		ttl  time.Duration
		want bool
	}{
		{"on time", CancelNone, 500 * time.Millisecond, false},
		{"expired on arrival", CancelNone, 0, false}, // the sidecar signals expiry; the library does not decide it
		{"signalled expired", CancelExpired, 0, true},
		{"signalled cancel", CancelRequest, 300 * time.Millisecond, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			served, cancelled := false, false
			h := SignalMiddleware(SignalConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				served, cancelled = true, Cancelled(r.Context())
			}))
			rec := serve(t, h, cancelledHeader(t, tc.cs, tc.ttl))
			if !served || rec.Code != http.StatusOK || cancelled != tc.want {
				t.Fatalf("served=%v code=%d cancelled=%v (want %v)", served, rec.Code, cancelled, tc.want)
			}
		})
	}
}

func TestSignalMiddlewareWithoutOrInvalidContext(t *testing.T) {
	served := false
	h := SignalMiddleware(SignalConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		served = true
		if Cancelled(r.Context()) {
			t.Error("a request without context is never cancelled")
		}
	}))
	if rec := serve(t, h, ""); !served || rec.Code != http.StatusOK {
		t.Fatalf("a request without context must be served")
	}
	if rec := serve(t, h, "v=1, rid=nonsense"); rec.Code != http.StatusBadRequest {
		t.Fatalf("an unreadable context is answered with 400, got %d", rec.Code)
	}
}

func TestCallIfNotCancelledSkipsWithoutAnError(t *testing.T) {
	var calls atomic.Int32
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }))
	defer backend.Close()
	client := &http.Client{Transport: &SignalTransport{}}
	for _, tc := range []struct {
		cs          CancelState
		wantSkipped bool
	}{{CancelNone, false}, {CancelExpired, true}} {
		h := SignalMiddleware(SignalConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			req, _ := http.NewRequest(http.MethodGet, backend.URL, nil)
			res := CallIfNotCancelled(r.Context(), client, req)
			if res.Err != nil || res.Skipped != tc.wantSkipped || (res.Response == nil) != tc.wantSkipped {
				t.Errorf("cs=%s: %+v", tc.cs, res)
			}
			if res.Response != nil {
				res.Response.Body.Close()
			}
		}))
		serve(t, h, cancelledHeader(t, tc.cs, 500*time.Millisecond))
	}
	if calls.Load() != 1 {
		t.Fatalf("only the call on the live chain is sent, got %d", calls.Load())
	}
}

func TestSignalTransportPropagatesTheChainAndNeverRefuses(t *testing.T) {
	var got Context
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got, _ = ParseContext(r.Header)
	}))
	defer backend.Close()
	client := &http.Client{Transport: &SignalTransport{}}
	var parent Context
	h := SignalMiddleware(SignalConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		in, _ := FromContext(r.Context())
		parent = in.Ctx
		resp, err := client.Do(mustRequest(t, r.Context(), backend.URL))
		if err != nil {
			t.Fatalf("a late call is still sent: %v", err)
		}
		resp.Body.Close()
	}))
	serve(t, h, cancelledHeader(t, CancelExpired, 0))
	if got.RID != parent.RID || got.PID != parent.IID || got.IID == parent.IID {
		t.Fatalf("child context does not continue the chain: parent %+v child %+v", parent, got)
	}
	if got.Cancel != CancelExpired || got.TTLMillis > 0 {
		t.Fatalf("the signal and the remaining time travel downstream: %+v", got)
	}
}

func TestDownstreamCancellationStopsRunningNonCriticalWork(t *testing.T) {
	downstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set(HeaderResult, "v=1, out=ok, rsn=none, cs=expired")
	}))
	defer downstream.Close()
	client := &http.Client{Transport: &SignalTransport{}}
	var stopped, skippedLater bool
	h := SignalMiddleware(SignalConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		started := make(chan struct{})
		done := make(chan struct{})
		go func() {
			defer close(done)
			NonCritical(ctx, func(work context.Context) {
				close(started)
				select {
				case <-work.Done():
					stopped = true
				case <-time.After(2 * time.Second):
				}
			})
		}()
		<-started
		resp, err := client.Do(mustRequest(t, ctx, downstream.URL)) // the response reports cs=expired
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		<-done
		skippedLater = !NonCritical(ctx, func(context.Context) { t.Error("must not run on a cancelled chain") })
		if Cancellation(ctx) != CancelExpired {
			t.Errorf("the chain state follows the downstream report: %s", Cancellation(ctx))
		}
	}))
	serve(t, h, cancelledHeader(t, CancelNone, time.Second))
	if !stopped || !skippedLater {
		t.Fatalf("running non-critical work stops and later work is skipped: stopped=%v skipped=%v", stopped, skippedLater)
	}
}

func mustRequest(t *testing.T, ctx context.Context, url string) *http.Request {
	t.Helper()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	return req
}
