package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
)

// fakeProxy implements the sidecar control API (A3.5 v0.3) like netty-sidecar-proxy PolicyStore: the hash is
// computed from the received policy, epochs are held until retired, and the active epoch cannot be retired.
type fakeProxy struct {
	mu         sync.Mutex
	held       map[string][2]string // epoch -> {state, hash}
	active     string
	inflight   map[string]int64
	mismatched int64
	log        []string // "<seq> <op> <epoch>"
	corrupt    bool     // stores other content than it received (hash mismatch)
	srv        *httptest.Server
	seq        *atomic.Int64
}

func newFakeProxy(t *testing.T, seq *atomic.Int64) *fakeProxy {
	if seq == nil {
		seq = &atomic.Int64{}
	}
	p := &fakeProxy{held: map[string][2]string{}, inflight: map[string]int64{}, seq: seq}
	p.srv = httptest.NewServer(http.HandlerFunc(p.serve))
	t.Cleanup(p.srv.Close)
	return p
}

func (p *fakeProxy) addr() string { return strings.TrimPrefix(p.srv.URL, "http://") }

func (p *fakeProxy) record(op, epoch string) {
	p.log = append(p.log, fmt.Sprintf("%d %s %s", p.seq.Add(1), op, epoch))
}

func (p *fakeProxy) did(op, epoch string) int64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, l := range p.log {
		var seq int64
		var o, e string
		_, _ = fmt.Sscanf(l, "%d %s %s", &seq, &o, &e)
		if o == op && e == epoch {
			return seq
		}
	}
	return 0
}

func (p *fakeProxy) view() map[string]any {
	var epochs []map[string]any
	for e, v := range p.held {
		epochs = append(epochs, map[string]any{"epoch": e, "state": v[0], "hash": v[1]})
	}
	return map[string]any{"epochs": epochs, "activeEpoch": p.active, "inflight": p.inflight, "mismatched": p.mismatched}
}

func (p *fakeProxy) serve(w http.ResponseWriter, r *http.Request) {
	p.mu.Lock()
	defer p.mu.Unlock()
	switch {
	case r.Method == http.MethodPut:
		var in stagedPolicy
		_ = json.NewDecoder(r.Body).Decode(&in)
		hash := specHash(in.Policy)
		if p.corrupt {
			hash = specHash(json.RawMessage(`{"tampered":true}`))
		}
		p.held[in.Epoch] = [2]string{in.State, hash}
		if in.State == "active" {
			p.active = in.Epoch
		}
		p.record("put-"+in.State, in.Epoch)
	case r.Method == http.MethodPost:
		var in struct{ Epoch string }
		_ = json.NewDecoder(r.Body).Decode(&in)
		v, ok := p.held[in.Epoch]
		if !ok {
			w.WriteHeader(404)
			return
		}
		p.held[in.Epoch] = [2]string{"active", v[1]}
		p.active = in.Epoch
		p.record("activate", in.Epoch)
	case r.Method == http.MethodDelete:
		e := strings.TrimPrefix(r.URL.Path, "/rpe/v1/policy/")
		if _, ok := p.held[e]; !ok || e == p.active {
			w.WriteHeader(409)
			return
		}
		delete(p.held, e)
		p.record("retire", e)
	}
	_ = json.NewEncoder(w).Encode(p.view())
}

// memStore is an in-memory rolloutStore.
type memStore struct {
	mu       sync.Mutex
	policies map[string]Policy // epoch -> policy
	status   map[string]string // epoch -> status
	acks     map[string]map[string]confirmation
	measured map[string]RolloutStatus
}

func newMemStore(ps ...Policy) *memStore {
	m := &memStore{policies: map[string]Policy{}, status: map[string]string{}, acks: map[string]map[string]confirmation{}, measured: map[string]RolloutStatus{}}
	for _, p := range ps {
		m.policies[p.Epoch] = p
		m.status[p.Epoch] = StatusPending
	}
	return m
}

func (m *memStore) get(_ context.Context, id string, v int) (Policy, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, p := range m.policies {
		if p.PolicyID == id && p.Version == v {
			return p, nil
		}
	}
	return Policy{}, errNotFound
}
func (m *memStore) recordRollout(context.Context, string, RolloutSpec) error          { return nil }
func (m *memStore) updateRollout(context.Context, string, string, string, bool) error { return nil }
func (m *memStore) setStatus(_ context.Context, id string, v int, s string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.status[fmt.Sprintf("%s-v%d", id, v)] = s
	return nil
}
func (m *memStore) activate(_ context.Context, id string, v int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for e, p := range m.policies {
		if p.PolicyID == id && m.status[e] == StatusActive {
			m.status[e] = StatusSuperseded
		}
	}
	m.status[fmt.Sprintf("%s-v%d", id, v)] = StatusActive
	return nil
}
func (m *memStore) recordDelivery(context.Context, string, string, string, int, string, string, string) error {
	return nil
}
func (m *memStore) confirmations(_ context.Context, epoch string) (map[string]confirmation, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.acks[epoch], nil
}
func (m *memStore) recordMeasurements(_ context.Context, name string, st RolloutStatus, _ int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.measured[name] = st
	return nil
}

type nopBus struct{}

func (nopBus) emit(context.Context, string, map[string]string) {}
func (nopBus) ack(context.Context, string, string, string)     {}
func (nopBus) setEffective(context.Context, Policy)            {}

type harness struct {
	t       *testing.T
	k       client.Client
	rec     *RolloutReconciler
	store   *memStore
	gateway *fakeProxy
	payment *fakeProxy
}

var (
	v1 = Policy{PolicyID: "pay", Version: 1, Epoch: "pay-v1", Scope: Scope{Service: "payment-svc"}, Spec: json.RawMessage(`{"deadlineMs":800}`)}
	v2 = Policy{PolicyID: "pay", Version: 2, Epoch: "pay-v2", Scope: Scope{Service: "payment-svc"}, Spec: json.RawMessage(`{"deadlineMs":1200,"retry":{"budget":1}}`)}
)

// newHarness: a gateway and a payment proxy that both enforce pay-v1.
func newHarness(t *testing.T) *harness {
	t.Setenv("POD_NAMESPACE", "rpe-system")
	seq := &atomic.Int64{}
	h := &harness{t: t, gateway: newFakeProxy(t, seq), payment: newFakeProxy(t, seq), store: newMemStore(v1, v2)}
	for _, p := range []*fakeProxy{h.gateway, h.payment} {
		p.held["pay-v1"] = [2]string{"active", specHash(v1.Spec)}
		p.active = "pay-v1"
	}
	h.store.status["pay-v1"] = StatusActive
	s := runtime.NewScheme()
	_ = clientgoscheme.AddToScheme(s)
	_ = addKnownTypes(s)
	h.k = fake.NewClientBuilder().WithScheme(s).WithStatusSubresource(&RPERollout{}, &RPEPolicy{}).WithObjects(
		pod("gw-1", "gateway", "gateway", h.gateway.addr(), true),
		pod("pay-1", "payment-svc", "", h.payment.addr(), true),
	).Build()
	h.rec = &RolloutReconciler{Client: h.k, reader: h.k, store: h.store, bus: nopBus{}, side: newSidecars(), demoNS: "rpe-demo",
		drain: 5 * time.Second, settle: 0, attempts: 3}
	return h
}

func (h *harness) create(name, mode string, p Policy, created time.Time) {
	ro := &RPERollout{ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: "rpe-system", CreationTimestamp: metav1.NewTime(created)},
		Spec: RolloutSpec{PolicyID: p.PolicyID, Version: p.Version, Epoch: p.Epoch, Service: p.Scope.Service, Mode: mode, PreviousEpoch: "pay-v1"}}
	if err := h.k.Create(context.Background(), ro); err != nil {
		h.t.Fatal(err)
	}
}

// step runs one reconcile and returns the rollout afterwards.
func (h *harness) step(name string) RPERollout {
	if _, err := h.rec.Reconcile(context.Background(), ctrl.Request{NamespacedName: types.NamespacedName{Namespace: "rpe-system", Name: name}}); err != nil {
		h.t.Fatal(err)
	}
	var ro RPERollout
	if err := h.k.Get(context.Background(), types.NamespacedName{Namespace: "rpe-system", Name: name}, &ro); err != nil {
		h.t.Fatal(err)
	}
	return ro
}

func (h *harness) until(name, phase string) RPERollout {
	for i := 0; i < 20; i++ {
		if ro := h.step(name); ro.Status.Phase == phase {
			return ro
		}
	}
	h.t.Fatalf("rollout %s never reached %s", name, phase)
	return RPERollout{}
}

func TestTwoPhaseStagesFlipsTheGatewayFirstAndDrains(t *testing.T) {
	h := newHarness(t)
	h.payment.inflight["pay-v1"] = 3 // requests pinned to v1 still in flight
	h.create("r2", "two-phase", v2, time.Now())

	ro := h.until("r2", PhaseDraining)
	if h.gateway.did("put-staged", "pay-v2") == 0 || h.payment.did("put-staged", "pay-v2") == 0 {
		t.Fatal("every proxy must hold the new version staged first")
	}
	gw, svc := h.gateway.did("activate", "pay-v2"), h.payment.did("activate", "pay-v2")
	if gw == 0 || svc == 0 || gw > svc {
		t.Fatalf("the gateway must flip before the services (gateway #%d, payment #%d)", gw, svc)
	}
	if ps := ro.Status.Pods; !ps[0].Verified || ps[0].Hash != specHash(v2.Spec) {
		t.Fatalf("deliveries must be verified by content hash: %+v", ps)
	}
	ro = h.step("r2")
	if ro.Status.Phase != PhaseDraining || h.payment.did("retire", "pay-v1") != 0 {
		t.Fatal("the previous epoch must not be retired while requests pinned to it are in flight")
	}
	if ro.Status.InflightPrevious != 3 {
		t.Fatalf("inflightPrevious = %d", ro.Status.InflightPrevious)
	}
	h.payment.mu.Lock()
	h.payment.inflight["pay-v1"] = 0
	h.payment.mu.Unlock()
	ro = h.until("r2", PhaseComplete)
	if h.payment.did("retire", "pay-v1") == 0 || h.gateway.did("retire", "pay-v1") == 0 {
		t.Fatal("after the drain the previous epoch is retired everywhere")
	}
	if h.store.status["pay-v2"] != StatusActive || h.store.status["pay-v1"] != StatusSuperseded {
		t.Fatalf("store statuses: %v", h.store.status)
	}
	ro = h.step("r2")
	if !ro.Status.Settled || ro.Status.MixedPolicyRequests != 0 || h.store.measured["r2"].ConvergenceMs < 0 {
		t.Fatalf("settled rollout: %+v", ro.Status)
	}
}

func TestNaiveSwitchesAtOnceAndDropsThePreviousVersion(t *testing.T) {
	h := newHarness(t)
	h.create("r2", "naive", v2, time.Now())
	ro := h.until("r2", PhaseComplete)
	if h.payment.did("put-staged", "pay-v2") != 0 {
		t.Fatal("naive does not stage")
	}
	if h.payment.did("put-active", "pay-v2") == 0 || h.payment.did("retire", "pay-v1") < h.payment.did("put-active", "pay-v2") {
		t.Fatal("naive switches each proxy and drops the previous version right away")
	}
	if ro.Status.DrainMs != 0 {
		t.Fatal("naive has no drain")
	}
	// a request stamped v1 that reaches the switched proxy is served under v2: the proxy counts it
	h.payment.mu.Lock()
	h.payment.mismatched += 4
	h.payment.mu.Unlock()
	if ro = h.step("r2"); !ro.Status.Settled || ro.Status.MixedPolicyRequests != 4 {
		t.Fatalf("the settled count includes the proxies' mixed-policy requests: %+v", ro.Status)
	}
}

func TestMixedCountIsRelativeToTheRolloutStart(t *testing.T) {
	h := newHarness(t)
	h.payment.mismatched = 10 // from earlier rollouts
	h.create("r2", "naive", v2, time.Now())
	if ro := h.until("r2", PhaseComplete); ro.Status.MixedBaseline["pay-1"] != 10 {
		t.Fatalf("baseline %v", ro.Status.MixedBaseline)
	}
	if ro := h.step("r2"); ro.Status.MixedPolicyRequests != 0 {
		t.Fatalf("earlier mismatches must not count: %d", ro.Status.MixedPolicyRequests)
	}
}

func TestAProxyHoldingOtherContentFailsVerification(t *testing.T) {
	h := newHarness(t)
	h.payment.corrupt = true
	h.create("r2", "two-phase", v2, time.Now())
	ro := h.until("r2", PhaseFailed)
	if !strings.Contains(ro.Status.Message, "staging did not converge") {
		t.Fatalf("message %q", ro.Status.Message)
	}
	for _, ps := range ro.Status.Pods {
		if ps.Name == "pay-1" && !strings.Contains(ps.Error, "hash mismatch") {
			t.Fatalf("the pod error names the hash mismatch: %+v", ps)
		}
	}
	if h.gateway.did("activate", "pay-v2") != 0 {
		t.Fatal("nothing is activated before every proxy verified the staged version")
	}
}

func TestRolloutsRunOneAtATime(t *testing.T) {
	h := newHarness(t)
	h.create("first", "two-phase", v2, time.Now().Add(-time.Second))
	h.create("second", "naive", v1, time.Now())
	if ro := h.step("second"); ro.Status.Phase != PhasePending || !strings.Contains(ro.Status.Message, "first") {
		t.Fatalf("the later rollout waits: %+v", ro.Status)
	}
	h.payment.inflight["pay-v1"] = 0
	h.until("first", PhaseComplete)
	h.step("first") // settle
	if ro := h.step("second"); ro.Status.Phase == PhasePending {
		t.Fatalf("the next rollout starts once the first settled: %+v", ro.Status)
	}
}

func TestProxyAcknowledgementsAreReported(t *testing.T) {
	h := newHarness(t)
	h.store.acks["pay-v2"] = map[string]confirmation{"pay-1": {Hash: specHash(v2.Spec), State: "active", At: time.Now()}}
	h.create("r2", "naive", v2, time.Now())
	ro := h.until("r2", PhaseComplete)
	for _, ps := range ro.Status.Pods {
		if ps.Name == "pay-1" && (ps.ConfirmedAt == "" || !ps.Verified) {
			t.Fatalf("the proxy's own ack is shown: %+v", ps)
		}
	}
}
