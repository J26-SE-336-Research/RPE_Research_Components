package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

func TestSessionTokens(t *testing.T) {
	a := newAuthn("admin:pw:admin,bad:entry", "secret")
	if len(a.users) != 1 {
		t.Fatalf("users = %v", a.users)
	}
	tok := a.issue(a.users["admin"], time.Hour)
	if u, ok := a.verify(tok); !ok || u.Role != "admin" {
		t.Fatalf("valid token rejected: %v %v", u, ok)
	}
	if _, ok := a.verify(tok[:len(tok)-2] + "xx"); ok {
		t.Fatal("tampered token accepted")
	}
	if _, ok := a.verify(a.issue(a.users["admin"], -time.Minute)); ok {
		t.Fatal("expired token accepted")
	}
	if _, ok := newAuthn("admin:pw:admin", "other").verify(tok); ok {
		t.Fatal("token signed with another secret accepted")
	}
}

func TestGuardEnforcesRoles(t *testing.T) {
	a := newAuthn("admin:pw:admin,viewer:pw:viewer", "secret")
	h := a.guard(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
	do := func(method, who string) int {
		req := httptest.NewRequest(method, "/api/hub/x", nil)
		if who != "" {
			req.AddCookie(&http.Cookie{Name: sessionCookie, Value: a.issue(a.users[who], time.Hour)})
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code
	}
	cases := []struct {
		method, who string
		want        int
	}{{"GET", "", 401}, {"GET", "viewer", 200}, {"POST", "viewer", 403}, {"POST", "admin", 200}}
	for _, c := range cases {
		if got := do(c.method, c.who); got != c.want {
			t.Errorf("%s as %q = %d, want %d", c.method, c.who, got, c.want)
		}
	}
}

func TestSummarizeMetrics(t *testing.T) {
	text := `# TYPE rpe_requests_total counter
rpe_requests_total{service="pay",pod="p",direction="inbound",outcome="ok"} 8
rpe_requests_total{service="pay",pod="p",direction="inbound",outcome="failed"} 2
rpe_request_duration_seconds_bucket{service="pay",pod="p",direction="inbound",le="0.01"} 5
rpe_request_duration_seconds_bucket{service="pay",pod="p",direction="inbound",le="0.1"} 10
rpe_request_duration_seconds_bucket{service="pay",pod="p",direction="inbound",le="+Inf"} 10
rpe_proxy_overhead_seconds_sum{service="pay",pod="p"} 0.02
rpe_proxy_overhead_seconds_count{service="pay",pod="p"} 10
rpe_inflight_requests{service="pay",pod="p"} 1
`
	st := summarize(parseProm(text))
	if st.Requests["inbound"]["ok"] != 8 || st.Requests["inbound"]["failed"] != 2 || st.Inflight != 1 {
		t.Fatalf("bad summary: %+v", st)
	}
	if st.OverheadAvgMs < 1.99 || st.OverheadAvgMs > 2.01 {
		t.Fatalf("overhead avg = %v", st.OverheadAvgMs)
	}
	if p50 := st.P50Ms["inbound"]; p50 < 9.9 || p50 > 10.1 {
		t.Fatalf("p50 = %v ms, want 10", p50)
	}
}

func ctxString(t *testing.T, mutate func(*rpe.Context)) (rpe.Context, string) {
	t.Helper()
	c, err := rpe.NewRoot("GET /api/orders", time.Second, 3, "e0", "")
	if err != nil {
		t.Fatal(err)
	}
	if mutate != nil {
		mutate(&c)
	}
	return c, c.Encode()
}

func TestFlattenChainChecksRules(t *testing.T) {
	root, rootStr := ctxString(t, nil)
	good, _ := root.Child(10*time.Millisecond, 0, 1)
	good2, _ := root.Child(10*time.Millisecond, 0, 1)
	bad, _ := root.Child(0, 0, 1)
	bad.TTLMillis = 5000 // R-T02 violation (and above bud)
	bad.Route = "GET /other"
	tree := &demoResponse{Service: "gateway", Context: rootStr, Children: []demoChild{
		{Name: "a", Status: 200, Downstream: &demoResponse{Service: "a", Context: good.Encode()}},
		{Name: "b", Status: 200, Downstream: &demoResponse{Service: "b", Context: good2.Encode()}},
		{Name: "legacy", Status: 200, Downstream: &demoResponse{Service: "legacy", Children: []demoChild{
			{Name: "ship", Status: 200, Downstream: &demoResponse{Service: "ship"}}}}},
	}}
	hops := flattenChain(tree)
	if len(hops) != 5 {
		t.Fatalf("hops = %d", len(hops))
	}
	pass := func(h Hop, rule string) bool {
		for _, c := range h.Checks {
			if c.Rule == rule {
				return c.Pass
			}
		}
		t.Fatalf("hop %s has no %s check: %+v", h.Service, rule, h.Checks)
		return false
	}
	if !pass(hops[0], "R-S01") || !pass(hops[0], "R-F05") || !pass(hops[1], "R-T02") || !pass(hops[1], "R-T06") {
		t.Fatalf("valid chain flagged: %+v", hops)
	}
	if !strings.Contains(hops[3].Checks[0].Rule, "unknown-edge") {
		t.Fatalf("legacy hop should be an unknown edge: %+v", hops[3])
	}
	// a child that breaks the rules is reported with the library's rule IDs
	tree.Children[0].Downstream.Context = bad.Encode()
	hops = flattenChain(tree)
	if hops[1].Checks[0].Rule != "decode" { // ttl > bud is rejected while decoding (R-T02 inside Validate)
		if pass(hops[1], "R-T02") || pass(hops[1], "R-T01") {
			t.Fatalf("violations not detected: %+v", hops[1].Checks)
		}
	}
}

func TestIntelReviewUsesSignedInReviewerAndControlToken(t *testing.T) {
	var gotToken, gotBody string
	c4 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotToken = r.Header.Get("X-RPE-Control-Token")
		b := new(strings.Builder)
		_, _ = io.Copy(b, r.Body)
		gotBody = b.String()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"decision":"APPROVED"}`))
	}))
	defer c4.Close()
	h, _ := newHub(config{intelligenceURL: c4.URL, intelToken: "tok", users: "alice:pw:sre,vic:pw:viewer", sessionSecret: "s"})
	srv := h.routes()
	review := func(who, body string) int {
		req := httptest.NewRequest("POST", "/api/hub/intel/changes/2df36743-8827-45df-9852-202af595ff13/review", strings.NewReader(body))
		req.AddCookie(&http.Cookie{Name: sessionCookie, Value: h.auth.issue(h.auth.users[who], time.Hour)})
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec.Code
	}
	if code := review("alice", `{"approve":true,"reviewer":"someone-else"}`); code != 200 {
		t.Fatalf("approve = %d", code)
	}
	if gotToken != "tok" || !strings.Contains(gotBody, `"reviewer":"alice"`) || strings.Contains(gotBody, "someone-else") {
		t.Fatalf("token %q body %s", gotToken, gotBody)
	}
	if code := review("alice", `{"approve":false}`); code != 400 {
		t.Fatalf("reject without a reason = %d, want 400", code)
	}
	if code := review("vic", `{"approve":true}`); code != 403 {
		t.Fatalf("viewer approve = %d, want 403", code)
	}
}

func TestCounterDelta(t *testing.T) {
	d := delta(map[string]int64{"cards_charged": 5, "work_abandoned": 3, "same": 2}, map[string]int64{"cards_charged": 5, "work_abandoned": 1, "same": 2})
	if len(d) != 1 || d["work_abandoned"] != 2 {
		t.Fatalf("delta = %v", d)
	}
}
