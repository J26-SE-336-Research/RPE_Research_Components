package rpe

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const sampleCtx = `v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=287, bud=300, rb=3, cs=none, pe="e42", rt="POST /api/orders"`

func TestDecodeEncodeRoundTrip(t *testing.T) {
	c, err := DecodeContext(sampleCtx)
	if err != nil {
		t.Fatal(err)
	}
	if c.TTLMillis != 287 || c.RetryBudget != 3 || c.Route != "POST /api/orders" || c.PolicyEpoch != "e42" || c.Cancel != CancelNone {
		t.Fatalf("unexpected decode: %+v", c)
	}
	if got := c.Encode(); got != sampleCtx {
		t.Fatalf("round trip mismatch:\n got %s\nwant %s", got, sampleCtx)
	}
}

func TestUnknownMembersAreForwarded(t *testing.T) {
	c, err := DecodeContext(sampleCtx + `, zz="future", zy=7`)
	if err != nil {
		t.Fatal(err)
	}
	child, err := c.Child(10*time.Millisecond, 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	enc := child.Encode()
	if !strings.Contains(enc, `zz="future"`) || !strings.Contains(enc, "zy=7") {
		t.Fatalf("unknown members lost: %s", enc)
	}
}

func TestDecodeRejectsInvalid(t *testing.T) {
	cases := map[string]string{
		"duplicate":    sampleCtx + `, ttl=5`,
		"bad cs":       strings.Replace(sampleCtx, "cs=none", "cs=maybe", 1),
		"ttl>bud":      strings.Replace(sampleCtx, "ttl=287", "ttl=301", 1),
		"bad rid":      strings.Replace(sampleCtx, "4bf92f35", "XXXXXXXX", 1),
		"version":      strings.Replace(sampleCtx, "v=1", "v=2", 1),
		"rb range":     strings.Replace(sampleCtx, "rb=3", "rb=999", 1),
		"params":       sampleCtx + `;x=1`,
		"trailing":     sampleCtx + `,`,
		"missing rt":   strings.Replace(sampleCtx, `, rt="POST /api/orders"`, "", 1),
		"unterminated": strings.Replace(sampleCtx, `rt="POST /api/orders"`, `rt="POST`, 1),
	}
	for name, in := range cases {
		if _, err := DecodeContext(in); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestVersionRuleID(t *testing.T) {
	_, err := DecodeContext(strings.Replace(sampleCtx, "v=1", "v=2", 1))
	var pe *ProtocolError
	if !errors.As(err, &pe) || pe.Rule != "R-V02" {
		t.Fatalf("want R-V02, got %v", err)
	}
}

func TestChildAndGuard(t *testing.T) {
	parent, _ := DecodeContext(sampleCtx)
	child, err := parent.Child(20*time.Millisecond, 5*time.Millisecond, 1)
	if err != nil {
		t.Fatal(err)
	}
	if child.TTLMillis != 262 || child.PID != parent.IID || child.IID == parent.IID || child.RetryBudget != 1 {
		t.Fatalf("bad child: %+v", child)
	}
	if v := ValidateContextTransition(parent, child, 20*time.Millisecond); len(v) != 0 {
		t.Fatalf("valid child flagged: %v", v)
	}
	bad := child
	bad.TTLMillis = 500
	bad.RetryBudget = 9
	bad.Route = "GET /other"
	rules := map[string]bool{}
	for _, e := range ValidateContextTransition(parent, bad, 20*time.Millisecond) {
		var pe *ProtocolError
		if errors.As(e, &pe) {
			rules[pe.Rule] = true
		}
	}
	for _, r := range []string{"R-T01", "R-T02", "R-T03"} {
		if !rules[r] {
			t.Errorf("expected violation %s, got %v", r, rules)
		}
	}
	if _, err := parent.Child(0, 0, 4); err == nil {
		t.Fatal("grant above parent budget must fail (R-T03)")
	}
}

func TestSplitBudget(t *testing.T) {
	got := SplitBudget(SplitParallel, 7, 1, 4)
	want := []int64{2, 2, 1, 1}
	var sum int64
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("parallel split = %v, want %v", got, want)
		}
		sum += got[i]
	}
	if sum > 7-1 {
		t.Fatalf("conservation violated: %d", sum)
	}
	if seq := SplitBudget(SplitSequential, 3, 1, 2); seq[0] != 2 || seq[1] != 2 {
		t.Fatalf("sequential split = %v", seq)
	}
}

func TestResultRoundTrip(t *testing.T) {
	r := Result{Version: 1, Outcome: OutcomePartial, Reason: ReasonDeadline, Terminal: true, BPLevel: 2, BPUtil: 78, BudgetUsed: 1, PolicyEpoch: "e42", Children: "inv:ok:80;loy:expired:90"}
	h := http.Header{}
	h.Set(HeaderResult, r.Encode())
	got, ok, err := ParseResult(h)
	if err != nil || !ok || got != r {
		t.Fatalf("result round trip: %+v ok=%v err=%v", got, ok, err)
	}
}

func TestMiddlewareGatewayAndPropagation(t *testing.T) {
	var seen []Context
	// downstream service: records the context it receives
	down := httptest.NewServer(Middleware(MiddlewareConfig{Required: true}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		in, _ := FromContext(r.Context())
		seen = append(seen, in.Ctx)
		if r.Header.Get(HeaderHop) != "" {
			t.Error("hop header must be stripped")
		}
		w.WriteHeader(204)
	})))
	defer down.Close()
	client := &http.Client{Transport: &Transport{}}
	// gateway: creates the root context and calls downstream
	gw := httptest.NewServer(Middleware(MiddlewareConfig{Gateway: true, RetryBudget: 3,
		RouteBudget: func(r *http.Request) (string, time.Duration) { return "GET /x", 300 * time.Millisecond },
		Epoch:       func() string { return "e7" }},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			req, _ := http.NewRequestWithContext(WithChildGrant(r.Context(), 1), "GET", down.URL, nil)
			req.Header.Set(HeaderHop, `att=1`)
			resp, err := client.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			resp.Body.Close()
			w.WriteHeader(resp.StatusCode)
		})))
	defer gw.Close()
	// the gateway must ignore a context sent by the client
	req, _ := http.NewRequest("GET", gw.URL, nil)
	req.Header.Set(HeaderContext, strings.Replace(sampleCtx, "ttl=287, bud=300", "ttl=99999, bud=99999", 1))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 204 || len(seen) != 1 {
		t.Fatalf("status %d seen %d", resp.StatusCode, len(seen))
	}
	c := seen[0]
	if c.BudgetMillis != 300 || c.TTLMillis > 300 || c.RetryBudget != 1 || c.PolicyEpoch != "e7" || c.Route != "GET /x" || c.PID == RootParentID {
		t.Fatalf("unexpected downstream context: %+v", c)
	}
}

func TestMiddlewareSignalsExpiryAndServesOriginals(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want CancelState
	}{
		{strings.Replace(sampleCtx, "ttl=287", "ttl=0", 1), CancelExpired}, // the library notices it and marks it
		{strings.Replace(sampleCtx, "cs=none", "cs=expired", 1), CancelExpired},
		{strings.Replace(sampleCtx, "cs=none", "cs=cancel", 1), CancelRequest},
	} {
		var got CancelState
		served := false
		h := Middleware(MiddlewareConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			served, got = true, Cancellation(r.Context())
			if c, _ := DecodeContext(r.Header.Get(HeaderContext)); c.Cancel != tc.want {
				t.Errorf("the application must see cs=%s, got %s", tc.want, c.Cancel)
			}
		}))
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/", nil)
		req.Header.Set(HeaderContext, tc.in)
		h.ServeHTTP(rec, req)
		if !served || rec.Code != http.StatusOK || got != tc.want {
			t.Fatalf("an original request is served with the signal: served=%v code=%d cs=%s", served, rec.Code, got)
		}
	}
}

func TestMiddlewareRefusesExpiredRetries(t *testing.T) {
	h := Middleware(MiddlewareConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("an expired retry must not reach the application")
	}))
	for _, in := range []string{
		strings.Replace(sampleCtx, "ttl=287", "ttl=0", 1),
		strings.Replace(sampleCtx, "cs=none", "cs=expired", 1),
		strings.Replace(sampleCtx, "cs=none", "cs=cancel", 1),
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/", nil)
		req.Header.Set(HeaderContext, in)
		req.Header.Set(HeaderHop, "att=2")
		h.ServeHTTP(rec, req)
		res, ok, err := ParseResult(rec.Header())
		if rec.Code != http.StatusGatewayTimeout || err != nil || !ok || !res.Terminal || resultCancelState(rec.Header()) == CancelNone {
			t.Fatalf("want terminal 504 with cs, got %d %+v %v", rec.Code, res, err)
		}
	}
	// a retry with time left is served
	served := false
	ok := Middleware(MiddlewareConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { served = true }))
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set(HeaderContext, sampleCtx)
	req.Header.Set(HeaderHop, "att=2")
	ok.ServeHTTP(httptest.NewRecorder(), req)
	if !served {
		t.Fatal("a retry with time left must be served")
	}
}

func TestTransportNeverCutsASlowCallOff(t *testing.T) {
	var seen Context
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen, _ = DecodeContext(r.Header.Get(HeaderContext))
		time.Sleep(300 * time.Millisecond)
		w.WriteHeader(204)
	}))
	defer slow.Close()
	client := &http.Client{Transport: &Transport{}}
	gw := httptest.NewServer(Middleware(MiddlewareConfig{Gateway: true,
		RouteBudget: func(r *http.Request) (string, time.Duration) { return "GET /x", 100 * time.Millisecond }},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			time.Sleep(150 * time.Millisecond) // the chain is late before the call
			req, _ := http.NewRequestWithContext(r.Context(), "GET", slow.URL, nil)
			resp, err := client.Do(req)
			if err != nil {
				t.Errorf("a late first attempt is still sent: %v", err)
				return
			}
			resp.Body.Close()
			w.WriteHeader(resp.StatusCode)
		})))
	defer gw.Close()
	resp, err := http.Get(gw.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 204 || seen.Cancel != CancelExpired || seen.TTLMillis > 0 {
		t.Fatalf("the late call completes and carries the signal: %d %+v", resp.StatusCode, seen)
	}
}

func TestTransportRefusesExpiredRetries(t *testing.T) {
	c, _ := DecodeContext(sampleCtx)
	expired := WithInbound(context.Background(), Inbound{Ctx: c, Received: time.Now().Add(-time.Second)})
	req, _ := http.NewRequestWithContext(WithAttempt(expired, 2), "GET", "http://127.0.0.1:1/never", nil)
	resp, err := (&Transport{}).RoundTrip(req)
	if err != nil || resp.StatusCode != http.StatusGatewayTimeout {
		t.Fatalf("want local 504, got %v %v", resp, err)
	}
	if res, _, _ := ParseResult(resp.Header); !res.Terminal || res.Reason != ReasonDeadline {
		t.Fatalf("want a terminal deadline result, got %+v", res)
	}
	// the first attempt of the same late chain still goes out, it only fails because nothing is listening
	req, _ = http.NewRequestWithContext(expired, "GET", "http://127.0.0.1:1/never", nil)
	if _, err := (&Transport{}).RoundTrip(req); err == nil || errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("the first attempt must be sent, got %v", err)
	}
}

func TestTransportKeepsMatchingTraceparent(t *testing.T) {
	c, _ := DecodeContext(sampleCtx)
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { got = r.Header.Get(HeaderTraceparent) }))
	defer srv.Close()
	ctx := WithInbound(context.Background(), Inbound{Ctx: c, Received: time.Now()})
	tp := "00-" + c.RID + "-1111111111111111-01" // a client span of the same trace
	req, _ := http.NewRequestWithContext(ctx, "GET", srv.URL, nil)
	req.Header.Set(HeaderTraceparent, tp)
	resp, err := (&Transport{}).RoundTrip(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if got != tp {
		t.Fatalf("matching traceparent must be preserved, got %q", got)
	}
	req2, _ := http.NewRequestWithContext(ctx, "GET", srv.URL, nil)
	req2.Header.Set(HeaderTraceparent, "00-0af7651916cd43dd8448eb211c80319c-1111111111111111-01")
	resp, _ = (&Transport{}).RoundTrip(req2)
	resp.Body.Close()
	if TraceIDFromTraceparent(got) != c.RID {
		t.Fatalf("foreign traceparent must be rebound to rid, got %q", got)
	}
}
