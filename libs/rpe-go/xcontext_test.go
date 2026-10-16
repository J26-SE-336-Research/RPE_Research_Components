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

func TestUnknownMembersAreForwarded(t *testing.T) { // R-V01
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

func TestSplitBudget(t *testing.T) { // R-F05
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
	// a client-supplied context must be ignored at the gateway (trust boundary)
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

func TestMiddlewareRejectsExpiredAndCancelled(t *testing.T) { // R-D02, R-T04
	h := Middleware(MiddlewareConfig{}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("application must not run")
	}))
	for _, in := range []string{
		strings.Replace(sampleCtx, "ttl=287", "ttl=0", 1),
		strings.Replace(sampleCtx, "cs=none", "cs=expired", 1),
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/", nil)
		req.Header.Set(HeaderContext, in)
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusGatewayTimeout {
			t.Fatalf("want 504, got %d", rec.Code)
		}
		res, ok, err := ParseResult(rec.Header())
		if err != nil || !ok || !res.Terminal {
			t.Fatalf("expected terminal result, got %+v %v", res, err)
		}
	}
}

func TestTransportEnforcesPropagatedDeadline(t *testing.T) {
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(2 * time.Second):
		case <-r.Context().Done():
		}
	}))
	defer slow.Close()
	client := &http.Client{Transport: &Transport{}}
	gw := httptest.NewServer(Middleware(MiddlewareConfig{Gateway: true,
		RouteBudget: func(r *http.Request) (string, time.Duration) { return "GET /x", 150 * time.Millisecond }},
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			req, _ := http.NewRequestWithContext(r.Context(), "GET", slow.URL, nil)
			resp, err := client.Do(req)
			if err != nil {
				t.Errorf("transport must convert the deadline into a result, got %v", err)
				return
			}
			res, _, _ := ParseResult(resp.Header)
			resp.Body.Close()
			if resp.StatusCode != http.StatusGatewayTimeout || !res.Terminal {
				t.Errorf("want terminal 504, got %d %+v", resp.StatusCode, res)
			}
			w.WriteHeader(resp.StatusCode)
		})))
	defer gw.Close()
	start := time.Now()
	resp, err := http.Get(gw.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if took := time.Since(start); took > time.Second {
		t.Fatalf("deadline not enforced: took %v", took)
	}
}

func TestTransportRefusesAfterDeadline(t *testing.T) {
	c, _ := DecodeContext(sampleCtx)
	ctx := WithInbound(context.Background(), Inbound{Ctx: c, Received: time.Now().Add(-time.Second)})
	req, _ := http.NewRequestWithContext(ctx, "GET", "http://127.0.0.1:1/never", nil)
	resp, err := (&Transport{}).RoundTrip(req)
	if err != nil || resp.StatusCode != http.StatusGatewayTimeout {
		t.Fatalf("want local 504, got %v %v", resp, err)
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
