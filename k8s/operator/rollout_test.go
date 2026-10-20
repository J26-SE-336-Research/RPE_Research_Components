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
	// payment-svc policies: a rate limit / retry ratio, applied by the payment sidecars only
	v1 = Policy{PolicyID: "pay", Version: 1, Epoch: "pay-v1", Scope: Scope{Service: "payment-svc"}, Spec: json.RawMessage(`{"leakyBucket":{"ratePerSec":100}}`)}
	v2 = Policy{PolicyID: "pay", Version: 2, Epoch: "pay-v2", Scope: Scope{Service: "payment-svc"}, Spec: json.RawMessage(`{"leakyBucket":{"ratePerSec":150},"retry":{"budgetRatio":0.1}}`)}
	// gateway policies: the chain deadline, applied by the gateway sidecars only
	g1 = Policy{PolicyID: "gw", Version: 1, Epoch: "gw-v1", Scope: Scope{Service: "gateway"}, Spec: json.RawMessage(`{"routes":{"GET /api/orders":{"deadlineMs":900}}}`)}
	g2 = Policy{PolicyID: "gw", Version: 2, Epoch: "gw-v2", Scope: Scope{Service: "gateway"}, Spec: json.RawMessage(`{"routes":{"GET /api/orders":{"deadlineMs":1400}}}`)}
)

// newHarness: a gateway proxy enforcing the chain deadline gw-v1 and a payment proxy enforcing the rate limit pay-v1.
func newHarness(t *testing.T) *harness {
	t.Setenv("POD_NAMESPACE", "rpe-system")
	seq := &atomic.Int64{}
	h := &harness{t: t, gateway: newFakeProxy(t, seq), payment: newFakeProxy(t, seq), store: newMemStore(v1, v2, g1, g2)}
	for px, p := range map[*fakeProxy]Policy{h.gateway: g1, h.payment: v1} {
		px.held[p.Epoch] = [2]string{"active", specHash(p.Spec)}
		px.active = p.Epoch
		h.store.status[p.Epoch] = StatusActive
	}
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

func (h *harness) create(name, mode string, p Policy, previous string, created time.Time) {
	ro := &RPERollout{ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: "rpe-system", CreationTimestamp: metav1.NewTime(created)},
		Spec: RolloutSpec{PolicyID: p.PolicyID, Version: p.Version, Epoch: p.Epoch, Service: p.Scope.Service, Mode: mode, PreviousEpoch: previous}}
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

func TestServicePolicyReachesOnlyTheServiceAndNeverTheGateway(t *testing.T) {
	h := newHarness(t)
	h.payment.inflight["pay-v1"] = 3 // requests that arrived under the previous version are still being served
	h.create("r2", "two-phase", v2, "pay-v1", time.Now())

	ro := h.until("r2", PhaseDraining)
	if ro.Status.TargetPods != 1 || ro.Status.Pods[0].Name != "pay-1" || ro.Status.Pods[0].Role != "service" {
		t.Fatalf("only the payment proxy is a target: %+v", ro.Status.Pods)
	}
	if h.payment.did("put-staged", "pay-v2") == 0 || h.payment.did("put-staged", "pay-v2") > h.payment.did("activate", "pay-v2") {
		t.Fatal("the payment proxy must hold the new version staged before it is activated")
	}
	if ps := ro.Status.Pods[0]; !ps.Verified || ps.Hash != specHash(v2.Spec) {
		t.Fatalf("the delivery must be verified by the content hash: %+v", ps)
	}
	if ro.Status.Pods[0].Error != "" {
		t.Fatalf("no misleading transient error without a gateway target: %q", ro.Status.Pods[0].Error)
	}
	ro = h.step("r2")
	if ro.Status.Phase != PhaseDraining || h.payment.did("retire", "pay-v1") != 0 || ro.Status.InflightPrevious != 3 {
		t.Fatalf("the previous version is kept while requests served under it are in flight: %+v", ro.Status)
	}
	h.payment.mu.Lock()
	h.payment.inflight["pay-v1"] = 0
	h.payment.mu.Unlock()
	h.until("r2", PhaseComplete)
	if h.payment.did("retire", "pay-v1") == 0 {
		t.Fatal("after the drain the previous version is retired")
	}
	if h.store.status["pay-v2"] != StatusActive || h.store.status["pay-v1"] != StatusSuperseded {
		t.Fatalf("store statuses: %v", h.store.status)
	}
	// the gateway was never contacted: its chain deadline policy is untouched
	if len(h.gateway.log) != 0 || h.gateway.active != "gw-v1" {
		t.Fatalf("a payment-svc policy must not reach the gateway: log=%v active=%s", h.gateway.log, h.gateway.active)
	}
}

func TestChainDeadlineReachesOnlyTheGateway(t *testing.T) {
	h := newHarness(t)
	h.create("r2", "two-phase", g2, "gw-v1", time.Now())
	ro := h.until("r2", PhaseComplete)
	if ro.Status.TargetPods != 1 || ro.Status.Pods[0].Name != "gw-1" || ro.Status.Pods[0].Role != "gateway" {
		t.Fatalf("only the gateway proxy is a target: %+v", ro.Status.Pods)
	}
	if h.gateway.did("put-staged", "gw-v2") == 0 || h.gateway.did("activate", "gw-v2") == 0 || h.gateway.did("retire", "gw-v1") == 0 {
		t.Fatalf("the gateway stages, activates and retires: %v", h.gateway.log)
	}
	if len(h.payment.log) != 0 || h.payment.active != "pay-v1" {
		t.Fatalf("a chain deadline must not reach a service: log=%v active=%s", h.payment.log, h.payment.active)
	}
}

func TestNaiveSwitchesAtOnceAndDropsThePreviousVersion(t *testing.T) {
	h := newHarness(t)
	h.create("r2", "naive", v2, "pay-v1", time.Now())
	ro := h.until("r2", PhaseComplete)
	if h.payment.did("put-staged", "pay-v2") != 0 {
		t.Fatal("naive does not stage")
	}
	if h.payment.did("put-active", "pay-v2") == 0 || h.payment.did("retire", "pay-v1") < h.payment.did("put-active", "pay-v2") {
		t.Fatal("naive switches the proxy and drops the previous version right away")
	}
	if ro.Status.DrainMs != 0 {
		t.Fatal("naive has no drain")
	}
	// a request stamped with the previous version that reaches the switched proxy is served under the new one:
	// the proxy counts it (informational version skew)
	h.payment.mu.Lock()
	h.payment.mismatched += 4
	h.payment.mu.Unlock()
	if ro = h.step("r2"); !ro.Status.Settled || ro.Status.MixedPolicyRequests != 4 {
		t.Fatalf("the settled count includes the proxy's version-skew count: %+v", ro.Status)
	}
}

func TestVersionSkewIsCountedRelativeToTheRolloutStart(t *testing.T) {
	h := newHarness(t)
	h.payment.mismatched = 10 // from earlier rollouts
	h.create("r2", "naive", v2, "pay-v1", time.Now())
	if ro := h.until("r2", PhaseComplete); ro.Status.MixedBaseline["pay-1"] != 10 {
		t.Fatalf("baseline %v", ro.Status.MixedBaseline)
	}
	if ro := h.step("r2"); ro.Status.MixedPolicyRequests != 0 {
		t.Fatalf("earlier skew must not count: %d", ro.Status.MixedPolicyRequests)
	}
}

func TestAProxyHoldingOtherContentFailsVerification(t *testing.T) {
	h := newHarness(t)
	h.payment.corrupt = true
	h.create("r2", "two-phase", v2, "pay-v1", time.Now())
	ro := h.until("r2", PhaseFailed)
	if !strings.Contains(ro.Status.Message, "staging did not converge") {
		t.Fatalf("message %q", ro.Status.Message)
	}
	if !strings.Contains(ro.Status.Pods[0].Error, "hash mismatch") {
		t.Fatalf("the pod error names the hash mismatch: %+v", ro.Status.Pods[0])
	}
	if h.payment.did("activate", "pay-v2") != 0 {
		t.Fatal("nothing is activated before the staged version was verified")
	}
	if h.store.status["pay-v2"] != StatusFailed {
		t.Fatalf("the policy is marked rollout-failed: %v", h.store.status)
	}
}

func TestRolloutsRunOneAtATime(t *testing.T) {
	h := newHarness(t)
	h.create("first", "two-phase", v2, "pay-v1", time.Now().Add(-time.Second))
	h.create("second", "two-phase", g2, "gw-v1", time.Now())
	if ro := h.step("second"); ro.Status.Phase != PhasePending || !strings.Contains(ro.Status.Message, "first") {
		t.Fatalf("the later rollout waits: %+v", ro.Status)
	}
	h.until("first", PhaseComplete)
	h.step("first") // settle
	if ro := h.step("second"); ro.Status.Phase == PhasePending {
		t.Fatalf("the next rollout starts once the first settled: %+v", ro.Status)
	}
}

func TestProxyAcknowledgementsAreReported(t *testing.T) {
	h := newHarness(t)
	h.store.acks["pay-v2"] = map[string]confirmation{"pay-1": {Hash: specHash(v2.Spec), State: "active", At: time.Now()}}
	h.create("r2", "naive", v2, "pay-v1", time.Now())
	ro := h.until("r2", PhaseComplete)
	if ps := ro.Status.Pods[0]; ps.ConfirmedAt == "" || !ps.Verified {
		t.Fatalf("the proxy's own ack is shown: %+v", ps)
	}
}

func TestAWrongAckHashIsFlagged(t *testing.T) {
	h := newHarness(t)
	h.store.acks["pay-v2"] = map[string]confirmation{"pay-1": {Hash: "deadbeefdeadbeef", State: "active", At: time.Now()}}
	h.create("r2", "naive", v2, "pay-v1", time.Now())
	ro := h.until("r2", PhaseComplete)
	if ps := ro.Status.Pods[0]; ps.Verified || !strings.Contains(ps.Error, "acknowledged hash") {
		t.Fatalf("an acknowledgement with another hash must not count as verified: %+v", ps)
	}
}
