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

// TestRetrySafety is the duplicate-charge demo in miniature: payment is slower than the chain budget, order gives up
// on an attempt after 150 ms. A plain retry charges the card twice; the same retry policy through the RPE retry gate
// charges it once, because the gate sees that the chain cannot finish another attempt in time (R-R02).
func TestRetrySafety(t *testing.T) {
	for _, tc := range []struct {
		mode        string
		wantCharges int64
		wantRefused bool
	}{{"plain", 2, false}, {"rpe", 1, true}} {
		t.Run(tc.mode, func(t *testing.T) {
			t.Setenv("SIDE_EFFECT", "cards_charged")
			t.Setenv("BASE_LATENCY_MS", "250")
			pay := newTestService(t, "", "partial")
			paySrv := httptest.NewServer(rpe.SignalMiddleware(rpe.SignalConfig{}, http.HandlerFunc(pay.handle)))
			defer paySrv.Close()
			t.Setenv("SIDE_EFFECT", "")
			t.Setenv("BASE_LATENCY_MS", "0")
			order := newTestService(t, "payment="+paySrv.URL+"/pay", "partial")
			order.retry = RetrySettings{Mode: tc.mode, AttemptTimeoutMs: 150, MaxAttempts: 2, BackoffMs: 10}.normalized()
			gw := httptest.NewServer(rpe.SignalMiddleware(rpe.SignalConfig{Gateway: true, RetryBudget: 3,
				RouteBudget: routeBudgets("GET /api/orders=250", 1000)}, http.HandlerFunc(order.handle)))
			defer gw.Close()

			resp, err := http.Get(gw.URL + "/api/orders")
			if err != nil {
				t.Fatal(err)
			}
			var body Response
			_ = json.NewDecoder(resp.Body).Decode(&body)
			resp.Body.Close()
			time.Sleep(400 * time.Millisecond) // abandoned attempts still complete on the payment side
			rid := ""
			if c, err := rpe.DecodeContext(body.Context); err == nil {
				rid = c.RID
			}
			rec := httptest.NewRecorder()
			pay.handleCounters(rec, httptest.NewRequest("GET", "/admin/counters?rid="+rid, nil))
			var per map[string]int64
			_ = json.NewDecoder(rec.Body).Decode(&per)
			if per["cards_charged"] != tc.wantCharges {
				t.Fatalf("%s: charged %d times, want %d (body %+v)", tc.mode, per["cards_charged"], tc.wantCharges, body)
			}
			child := body.Children[0]
			if (child.RetryRefused != "") != tc.wantRefused {
				t.Fatalf("%s: child %+v", tc.mode, child)
			}
			if tc.wantRefused && (child.Attempts != 1 || !strings.Contains(resp.Header.Get(rpe.HeaderResult), "term=?1")) {
				t.Fatalf("a refused retry is terminal for the caller too: %+v %s", child, resp.Header.Get(rpe.HeaderResult))
			}
		})
	}
}

func TestRetryKnobDefaultsOff(t *testing.T) {
	s := newTestService(t, "", "partial")
	if s.retrySettings().Mode != "off" {
		t.Fatal("the retry knob must be off by default")
	}
	rec := httptest.NewRecorder()
	s.handleRetry(rec, httptest.NewRequest("POST", "/admin/retry", strings.NewReader(`{"mode":"rpe"}`)))
	if got := s.retrySettings(); got.Mode != "rpe" || got.Child != "payment" || got.AttemptTimeoutMs != 600 || got.MaxAttempts != 2 {
		t.Fatalf("settings %+v", got)
	}
	s.handleReset(httptest.NewRecorder(), httptest.NewRequest("POST", "/admin/reset", nil))
	if s.retrySettings().Mode != "off" {
		t.Fatal("reset turns the retry knob off")
	}
}
