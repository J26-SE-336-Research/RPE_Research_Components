package main

import (
	"encoding/json"
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

func TestSummarizeBackpressure(t *testing.T) {
	text := `rpe_backpressure_inbound_rate_per_second 5.0
rpe_backpressure_inbound_queue_capacity 200
rpe_backpressure_inbound_queue_depth 130
rpe_backpressure_inbound_queue_utilization_percent 65
rpe_backpressure_inbound_pressure_active 1
rpe_backpressure_inbound_rejected_total 4
rpe_backpressure_peer_pressure_notifications_sent_total 3
rpe_circuit_breaker_open_peers 1
rpe_backpressure_peer_concurrency_limit{peer="payment-svc:8080"} 11
rpe_backpressure_peer_healthy_concurrency_limit{peer="payment-svc:8080"} 32
rpe_backpressure_peer_pressure_active{peer="payment-svc:8080"} 1
rpe_backpressure_peer_reported_utilization_percent{peer="payment-svc:8080"} 65
rpe_circuit_breaker_peer_state{peer="payment-svc:8080"} 1
rpe_backpressure_peer_concurrency_limit{peer="inventory-svc:8080"} 32
`
	b := summarize(parseProm(text)).Backpressure
	if b.InboundRatePerSec != 5 || b.QueueCapacity != 200 || b.QueueDepth != 130 || b.QueueUtilization != 65 || !b.PressureActive {
		t.Fatalf("bad inbound bucket: %+v", b)
	}
	if b.InboundRejected != 4 || b.NotificationsSent != 3 || b.OpenBreakers != 1 {
		t.Fatalf("bad counters: %+v", b)
	}
	if len(b.Peers) != 2 || b.Peers[0].Peer != "inventory-svc:8080" || b.Peers[1].Peer != "payment-svc:8080" {
		t.Fatalf("peers = %+v", b.Peers)
	}
	if inv := b.Peers[0]; inv.ConcurrencyLimit != 32 || inv.Breaker != "closed" || inv.ReportedUtilization != -1 || inv.PressureActive {
		t.Fatalf("a destination without pressure: %+v", inv)
	}
	if pay := b.Peers[1]; pay.ConcurrencyLimit != 11 || pay.HealthyLimit != 32 || !pay.PressureActive ||
		pay.ReportedUtilization != 65 || pay.Breaker != "open" {
		t.Fatalf("the pressured destination: %+v", pay)
	}
}

func TestBackpressureConfigMode(t *testing.T) {
	c := backpressureConfig{}
	if problem := c.normalize(); problem != "" || c.Mode != "full" || c.LowRate != 50 || c.Concurrency != 150 {
		t.Fatalf("defaults: %q %+v", problem, c)
	}
	c = backpressureConfig{Mode: "basic", Concurrency: 5000}
	if problem := c.normalize(); problem != "" || c.Mode != "basic" || c.Concurrency != 600 {
		t.Fatalf("basic, clamped: %q %+v", problem, c)
	}
	c = backpressureConfig{Mode: "off"}
	if problem := c.normalize(); problem == "" {
		t.Fatal("an unknown mode must be refused")
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

// A policy of one control is built from the service's current policy with only that control changed, and goes to
// the service the control belongs to: the chain deadline to the gateway, the rate limit and the retry ratio to the
// target service.
func TestSingleControlPoliciesAreScopedAndMerged(t *testing.T) {
	var posted []map[string]any
	c3 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == "GET" && r.URL.Path == "/api/k8s/effective":
			_, _ = w.Write([]byte(`{"payment-svc":{"policyId":"pay-policy","route":"","spec":"{\"retry\":{\"budgetRatio\":0.1}}"},` +
				`"gateway":{"policyId":"gw-policy","route":"GET /api/orders","spec":"{\"deadlineMs\":1000,\"routes\":{\"GET /api/orders\":{\"deadlineMs\":1000},\"POST /api/pay\":{\"deadlineMs\":2000}}}"}}`))
		case r.Method == "POST" && r.URL.Path == "/api/k8s/policies":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			posted = append(posted, body)
			_, _ = w.Write([]byte(`{"policy":{"policyId":"x","version":3,"epoch":"x-v3"},"notification":null}`))
		default:
			w.WriteHeader(404)
		}
	}))
	defer c3.Close()
	h, _ := newHub(config{policyAPIURL: c3.URL, users: "alice:pw:sre", sessionSecret: "s"})
	srv := h.routes()
	post := func(body string) int {
		req := httptest.NewRequest("POST", "/api/hub/policies", strings.NewReader(body))
		req.AddCookie(&http.Cookie{Name: sessionCookie, Value: h.auth.issue(h.auth.users["alice"], time.Hour)})
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec.Code
	}
	scopeOf := func(p map[string]any) string { return p["scope"].(map[string]any)["service"].(string) }
	specOf := func(p map[string]any) map[string]any { return p["spec"].(map[string]any) }

	// rate limit: only the target service; its current retry ratio is kept
	if code := post(`{"control":"rate-limit","service":"payment-svc","ratePerSec":250}`); code != 201 {
		t.Fatalf("rate limit = %d", code)
	}
	p := posted[0]
	if scopeOf(p) != "payment-svc" || p["policyId"] != "pay-policy" {
		t.Fatalf("rate limit scope/id: %v", p)
	}
	if specOf(p)["leakyBucket"].(map[string]any)["ratePerSec"] != float64(250) || specOf(p)["retry"].(map[string]any)["budgetRatio"] != 0.1 {
		t.Fatalf("rate limit spec must change only the rate: %v", specOf(p))
	}
	// chain deadline: always the gateway; only that route changes, the other route and settings are kept
	if code := post(`{"control":"chain-deadline","route":"GET /api/orders","deadlineMs":1500}`); code != 201 {
		t.Fatalf("chain deadline = %d", code)
	}
	p = posted[1]
	routes := specOf(p)["routes"].(map[string]any)
	if scopeOf(p) != "gateway" || p["policyId"] != "gw-policy" ||
		routes["GET /api/orders"].(map[string]any)["deadlineMs"] != float64(1500) || routes["POST /api/pay"].(map[string]any)["deadlineMs"] != float64(2000) {
		t.Fatalf("chain deadline must go to the gateway and change only its route: %v", p)
	}
	// a retry ratio for a service without a current policy gets a new one
	if code := post(`{"control":"retry-ratio","service":"inventory-svc","retryRatio":0.2}`); code != 201 {
		t.Fatalf("retry ratio = %d", code)
	}
	p = posted[2]
	if scopeOf(p) != "inventory-svc" || p["policyId"] != "inventory-svc-resilience" || specOf(p)["retry"].(map[string]any)["budgetRatio"] != 0.2 {
		t.Fatalf("retry ratio: %v", p)
	}
	// misdirected or invalid controls are refused before anything is sent
	before := len(posted)
	for _, body := range []string{
		`{"control":"chain-deadline","service":"payment-svc","route":"GET /x","deadlineMs":100}`, // not the gateway
		`{"control":"chain-deadline","deadlineMs":100}`,                                          // no route
		`{"control":"rate-limit","service":"gateway","ratePerSec":10}`,                           // the gateway has no service rate limit
		`{"control":"rate-limit","service":"payment-svc","ratePerSec":2000000}`,                  // above what a sidecar accepts
		`{"control":"rate-limit","service":"payment-svc"}`,                                       // no rate
		`{"control":"retry-ratio","service":"payment-svc","retryRatio":1.5}`,                     // out of range
		`{"control":"mystery","service":"payment-svc"}`,
	} {
		if code := post(body); code != 400 {
			t.Fatalf("%s = %d, want 400", body, code)
		}
	}
	if len(posted) != before {
		t.Fatal("an invalid control must not reach the policy API")
	}
}

func TestJointChangeIsOnePolicyWithAPartPerService(t *testing.T) {
	var posted []map[string]any
	c3 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == "GET" && r.URL.Path == "/api/k8s/effective":
			_, _ = w.Write([]byte(`{"payment-svc":{"policyId":"pay-policy","route":"","spec":"{\"retry\":{\"budgetRatio\":0.1}}"},` +
				`"gateway":{"policyId":"gw-policy","route":"GET /api/orders","spec":"{\"routes\":{\"GET /api/orders\":{\"deadlineMs\":1000},\"POST /api/pay\":{\"deadlineMs\":2000}}}"}}`))
		case r.Method == "POST" && r.URL.Path == "/api/k8s/policies":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			posted = append(posted, body)
			_, _ = w.Write([]byte(`{"policy":{"policyId":"chain-orders","version":2,"epoch":"chain-orders-v2"},"notification":null}`))
		case r.Method == "POST" && r.URL.Path == "/api/k8s/rollouts":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			posted = append(posted, body)
			_, _ = w.Write([]byte(`{"metadata":{"name":"chain-orders-v2-abc"}}`))
		default:
			w.WriteHeader(404)
		}
	}))
	defer c3.Close()
	h, _ := newHub(config{policyAPIURL: c3.URL, users: "alice:pw:sre", sessionSecret: "s"})
	req := httptest.NewRequest("POST", "/api/hub/policies", strings.NewReader(`{"policyId":"chain-orders","rollout":"naive","order":"services-first","skewMs":4000,
		"changes":[{"control":"chain-deadline","route":"GET /api/orders","deadlineMs":4000},{"control":"rate-limit","service":"payment-svc","ratePerSec":6}]}`))
	req.AddCookie(&http.Cookie{Name: sessionCookie, Value: h.auth.issue(h.auth.users["alice"], time.Hour)})
	rec := httptest.NewRecorder()
	h.routes().ServeHTTP(rec, req)
	if rec.Code != 201 || len(posted) != 2 {
		t.Fatalf("code %d, posted %v", rec.Code, posted)
	}
	parts, _ := posted[0]["parts"].([]any)
	if posted[0]["policyId"] != "chain-orders" || len(parts) != 2 || posted[0]["spec"] != nil {
		t.Fatalf("one policy with two parts: %v", posted[0])
	}
	gw, pay := parts[0].(map[string]any), parts[1].(map[string]any)
	gwRoutes := gw["spec"].(map[string]any)["routes"].(map[string]any)
	if gw["scope"].(map[string]any)["service"] != "gateway" || gwRoutes["GET /api/orders"].(map[string]any)["deadlineMs"] != float64(4000) ||
		gwRoutes["POST /api/pay"].(map[string]any)["deadlineMs"] != float64(2000) {
		t.Fatalf("the gateway part changes only its route deadline: %v", gw)
	}
	paySpec := pay["spec"].(map[string]any)
	if pay["scope"].(map[string]any)["service"] != "payment-svc" || paySpec["leakyBucket"].(map[string]any)["ratePerSec"] != float64(6) ||
		paySpec["retry"].(map[string]any)["budgetRatio"] != 0.1 || paySpec["routes"] != nil {
		t.Fatalf("the payment part changes only the rate and holds no gateway setting: %v", pay)
	}
	if posted[1]["order"] != "services-first" || posted[1]["mode"] != "naive" || posted[1]["skewMs"] != float64(4000) {
		t.Fatalf("the rollout request carries the arrival order: %v", posted[1])
	}
}

func TestConsistencyInputIsValidated(t *testing.T) {
	for in, want := range map[consistencyInput]string{
		{Mode: "two-phase"}:                          "",
		{Mode: "naive", Order: "services-first"}:     "",
		{Mode: "two-phase", Order: "services-first"}: "an arrival order applies to naive delivery only",
		{Mode: "fast"}:                               "mode must be two-phase or naive",
		{Mode: "naive", SkewMs: 20000}:               "skewMs must be from 0 to 10000",
		{Mode: "naive", Order: "sideways"}:           "order must be services-first or gateway-first",
	} {
		if got := in.normalize(); got != want {
			t.Errorf("%+v: %q, want %q", in, got, want)
		}
	}
	in := consistencyInput{}
	in.normalize()
	if in.Mode != "two-phase" || in.NewRate != 6 || in.NewDeadlineMs != 4000 || in.BaseDeadlineMs != 1000 {
		t.Fatalf("defaults: %+v", in)
	}
}

func TestIntelDemoAllowsTheC4ScenariosAndRejectsUnknownNames(t *testing.T) {
	var got []string
	c4 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = append(got, r.Method+" "+r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"scenario":"ok"}`))
	}))
	defer c4.Close()
	h, _ := newHub(config{intelligenceURL: c4.URL, intelToken: "tok", users: "alice:pw:sre", sessionSecret: "s"})
	srv := h.routes()
	demo := func(s string) int {
		req := httptest.NewRequest("POST", "/api/hub/intel/demo/"+s, nil)
		req.AddCookie(&http.Cookie{Name: sessionCookie, Value: h.auth.issue(h.auth.users["alice"], time.Hour)})
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec.Code
	}
	for _, s := range []string{"healthy", "deadline-too-short-day-one", "rate-real-overload", "joint-recovery", "joint-capacity-drop"} {
		if code := demo(s); code != 200 {
			t.Fatalf("%s = %d, want 200", s, code)
		}
	}
	if code := demo("not-a-scenario"); code != 404 {
		t.Fatalf("unknown scenario = %d, want 404", code)
	}
	if len(got) != 5 || got[4] != "POST /api/v2/intelligence/demo/joint-capacity-drop" {
		t.Fatalf("forwarded to C4: %v", got)
	}
}

func TestIntelPracticeCardsAreForwardedToC4(t *testing.T) {
	var got []string
	var token string
	c4 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = append(got, r.Method+" "+r.URL.RequestURI())
		if strings.HasSuffix(r.URL.Path, "/practice/clear") {
			token = r.Header.Get("X-RPE-Control-Token")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{}`))
	}))
	defer c4.Close()
	h, _ := newHub(config{intelligenceURL: c4.URL, intelToken: "tok", users: "alice:pw:sre", sessionSecret: "s"})
	srv := h.routes()
	post := func(path string) int {
		req := httptest.NewRequest("POST", path, nil)
		req.AddCookie(&http.Cookie{Name: sessionCookie, Value: h.auth.issue(h.auth.users["alice"], time.Hour)})
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, req)
		return rec.Code
	}
	for _, p := range []string{"/api/hub/intel/demo/joint-recovery?to=queue", "/api/hub/intel/demo/joint-recovery", "/api/hub/intel/demo/joint-recovery?to=other", "/api/hub/intel/practice/clear"} {
		if code := post(p); code != 200 {
			t.Fatalf("%s = %d, want 200", p, code)
		}
	}
	want := []string{"POST /api/v2/intelligence/demo/joint-recovery?to=queue", "POST /api/v2/intelligence/demo/joint-recovery",
		"POST /api/v2/intelligence/demo/joint-recovery", "POST /api/v2/intelligence/practice/clear"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("forwarded to C4: %v, want %v", got, want)
	}
	if token != "tok" {
		t.Fatalf("practice clear token = %q, want the control token", token)
	}
}

func TestBackpressureTestRates(t *testing.T) {
	current := map[string]float64{"gateway": 100, "payment-svc": 87.5, "order-svc": 120, "inventory-svc": 100, "loyalty-svc": 64.2}
	got := backpressureTestRates(current, 40)
	want := map[string]float64{"gateway": 300, "payment-svc": 40, "order-svc": 100, "inventory-svc": 100, "loyalty-svc": 100}
	if len(got) != len(want) {
		t.Fatalf("rates = %v, want %v", got, want)
	}
	for service, rate := range want {
		if got[service] != rate {
			t.Errorf("%s: rate %g, want %g", service, got[service], rate)
		}
	}
	if _, ok := backpressureTestRates(map[string]float64{"order-svc": 90}, 40)["payment-svc"]; ok {
		t.Error("a service without a known current rate must not get a test rate")
	}
	order := strings.Join(backpressureRateOrder(got), ",")
	if order != "gateway,inventory-svc,loyalty-svc,order-svc,payment-svc" {
		t.Errorf("order = %s: the gateway first, the services by name, payment-svc last", order)
	}
}
