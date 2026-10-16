package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
)

func pod(name, app, role, ip string, sidecar bool) *corev1.Pod {
	p := &corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: "rpe-demo", Labels: map[string]string{"app": app}, Annotations: map[string]string{}},
		Spec:   corev1.PodSpec{Containers: []corev1.Container{{Name: "app"}}},
		Status: corev1.PodStatus{Phase: corev1.PodRunning, PodIP: ip}}
	if role != "" {
		p.Annotations["resilience.rpe.dev/role"] = role
	}
	if sidecar {
		p.Spec.Containers = append(p.Spec.Containers, corev1.Container{Name: "rpe-sidecar"})
	}
	return p
}

func TestResolveTargetsGatewaysFirst(t *testing.T) {
	s := runtime.NewScheme()
	_ = clientgoscheme.AddToScheme(s)
	c := fake.NewClientBuilder().WithScheme(s).WithObjects(
		pod("pay-1", "payment-svc", "", "10.0.0.2", true),
		pod("pay-2", "payment-svc", "", "10.0.0.3", true),
		pod("gw-1", "gateway", "gateway", "10.0.0.1", true),
		pod("inv-1", "inventory-svc", "", "10.0.0.4", true),
		pod("legacy-1", "payment-svc", "", "10.0.0.5", false), // no proxy: never a target
	).Build()
	got, err := resolveTargets(context.Background(), c, "rpe-demo", "payment-svc")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 || got[0].Role != "gateway" {
		t.Fatalf("want gateway first then 2 payment proxies, got %+v", got)
	}
	for _, tg := range got {
		if tg.Service == "inventory-svc" || tg.Pod == "legacy-1" {
			t.Fatalf("unexpected target %+v", tg)
		}
	}
}

func TestCreatePolicyRequiresHumanApproval(t *testing.T) {
	a := &api{waiters: newWaiters()}
	h := a.routes()
	for _, body := range []string{
		`{"policyId":"p1","scope":{"service":"payment-svc"},"approval":{"decision":"approved"}}`,
		`{"policyId":"p1","scope":{"service":"payment-svc"},"approval":{"approvedBy":"sre","decision":"pending"}}`,
	} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/k8s/policies", strings.NewReader(body)))
		if rec.Code != 422 {
			t.Fatalf("policy without a human approval must be refused (422), got %d for %s", rec.Code, body)
		}
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/k8s/policies", strings.NewReader(`{"policyId":"Bad_Name","scope":{"service":"x"}}`)))
	if rec.Code != 400 {
		t.Fatalf("invalid policy id must be rejected, got %d", rec.Code)
	}
}

func TestWaitersMatchEpoch(t *testing.T) {
	w := newWaiters()
	ch := w.expect()
	go func() {
		w.notify(`{"policyId":"other","version":1,"epoch":"other-v1"}`)
		w.notify(`{"policyId":"p","version":2,"epoch":"p-v2"}`)
	}()
	got := w.await(ch, "p-v2", time.Second)
	if got == nil || !strings.Contains(got["payload"], "p-v2") {
		t.Fatalf("expected the matching notification, got %v", got)
	}
	if w.await(w.expect(), "never", 50*time.Millisecond) != nil {
		t.Fatal("await must time out without a matching notification")
	}
}

func TestSidecarClientAndHolds(t *testing.T) {
	var got stagedPolicy
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPut:
			_ = json.NewDecoder(r.Body).Decode(&got)
		case r.Method == http.MethodGet:
			_, _ = w.Write([]byte(`{"epochs":[{"epoch":"p-v1","state":"active"},{"epoch":"p-v2","state":"staged"}],"activeEpoch":"p-v1"}`))
		}
	}))
	defer srv.Close()
	host, port, _ := strings.Cut(strings.TrimPrefix(srv.URL, "http://"), ":")
	s := newSidecars()
	s.port = atoi(port)
	if err := s.put(context.Background(), host, stagedPolicy{Epoch: "p-v2", Version: 2, State: "staged", Policy: json.RawMessage(`{"deadlineMs":800}`)}); err != nil {
		t.Fatal(err)
	}
	if got.Epoch != "p-v2" || got.State != "staged" {
		t.Fatalf("sidecar received %+v", got)
	}
	e, err := s.epochs(context.Background(), host)
	if err != nil || !e.holds("p-v1", "active") || e.holds("p-v2", "active") || !e.holds("p-v2", "staged") {
		t.Fatalf("holds() wrong: %+v %v", e, err)
	}
}

func TestNamesAndHash(t *testing.T) {
	p := Policy{PolicyID: "payment-deadline", Version: 3, Epoch: "payment-deadline-v3"}
	n := rolloutName(p)
	if !strings.HasPrefix(n, "payment-deadline-v3-") || len(n) > 63 {
		t.Fatalf("rollout name %q", n)
	}
	if specHash(json.RawMessage(`{"a":1,"b":2}`)) != specHash(json.RawMessage(`{"b":2, "a":1}`)) {
		t.Fatal("spec hash must not depend on key order or whitespace")
	}
}

func atoi(s string) int {
	n := 0
	for _, c := range s {
		n = n*10 + int(c-'0')
	}
	return n
}
