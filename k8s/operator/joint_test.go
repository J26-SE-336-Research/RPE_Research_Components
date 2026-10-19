package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"k8s.io/apimachinery/pkg/types"
)

// A joint change (one decision): a longer chain deadline at the gateway and a lower rate at payment-svc.
var (
	gwPart = func(ms int) Part {
		return Part{Scope: Scope{Service: "gateway", Route: "GET /api/orders"},
			Spec: json.RawMessage(`{"routes":{"GET /api/orders":{"deadlineMs":` + itoa(ms) + `}}}`)}
	}
	payPart = func(rate int) Part {
		return Part{Scope: Scope{Service: "payment-svc"}, Spec: json.RawMessage(`{"leakyBucket":{"ratePerSec":` + itoa(rate) + `}}`)}
	}
	j1 = Policy{PolicyID: "chain", Version: 1, Epoch: "chain-v1", Scope: Scope{Service: "gateway"}, Spec: jointSpec([]Part{gwPart(1000), payPart(100)})}
	j2 = Policy{PolicyID: "chain", Version: 2, Epoch: "chain-v2", Scope: Scope{Service: "gateway"}, Spec: jointSpec([]Part{gwPart(4000), payPart(8)})}
)

func itoa(n int) string { b, _ := json.Marshal(n); return string(b) }

// jointHarness: both proxies enforce the joint policy chain-v1, each with its own part.
func jointHarness(t *testing.T) *harness {
	h := newHarness(t)
	h.store.policies[j1.Epoch], h.store.policies[j2.Epoch] = j1, j2
	for px, svc := range map[*fakeProxy]string{h.gateway: "gateway", h.payment: "payment-svc"} {
		px.held[j1.Epoch] = [2]string{"active", specHash(partOf(j1, svc).Spec)}
		px.active = j1.Epoch
	}
	h.store.status[j1.Epoch] = StatusActive
	return h
}

func TestJointPartsAndProjections(t *testing.T) {
	if !j2.joint() || strings.Join(j2.services(), ",") != "gateway,payment-svc" {
		t.Fatalf("services %v", j2.services())
	}
	pay, ok := j2.forService("payment-svc")
	if !ok || pay.Epoch != "chain-v2" || specHash(pay.Spec) != specHash(payPart(8).Spec) {
		t.Fatalf("the payment part keeps the epoch and carries only the payment settings: %+v", pay)
	}
	if _, ok := j2.forService("order-svc"); ok {
		t.Fatal("no part for a service the policy does not name")
	}
	if single := v1.parts(); len(single) != 1 || single[0].Scope.Service != "payment-svc" || v1.joint() {
		t.Fatalf("a policy with one scope is its own single part: %+v", single)
	}
	// a one-service change to a joint policy keeps the other part
	parts := withPart(j2.parts(), payPart(50))
	if len(parts) != 2 || specHash(parts[0].Spec) != specHash(gwPart(4000).Spec) || specHash(parts[1].Spec) != specHash(payPart(50).Spec) {
		t.Fatalf("withPart: %+v", parts)
	}
	if err := validateParts([]Part{payPart(1), payPart(2)}); err == nil {
		t.Fatal("one part per service")
	}
	if versionOf("chain-orders-v12") != 12 || versionOf("e0") != 0 {
		t.Fatal("versionOf")
	}
}

func TestClassifyOrdersLooseningBeforeTightening(t *testing.T) {
	plan := planJoint(j2, &j1)
	if len(plan) != 2 || plan[0].Kind != changeLoosen || plan[0].Step != 1 || plan[1].Kind != changeTighten || plan[1].Step != 2 {
		t.Fatalf("a longer deadline loosens (step 1), a lower rate tightens (step 2): %+v", plan)
	}
	if !strings.Contains(strings.Join(plan[0].Changes, ";"), "1000 → 4000 ms") || !strings.Contains(strings.Join(plan[1].Changes, ";"), "100 → 8 requests/s") {
		t.Fatalf("changes in words: %+v", plan)
	}
	// the way back: the rate rises first, the deadline shortens second
	back := planJoint(j1, &j2)
	if back[0].Step != 2 || back[1].Step != 1 {
		t.Fatalf("a shorter deadline tightens, a higher rate loosens: %+v", back)
	}
	// the first version has nothing to compare with: one step
	if first := planJoint(j1, nil); first[0].Step != 1 || first[1].Step != 1 {
		t.Fatalf("first version: %+v", first)
	}
	if kind, _ := classify(json.RawMessage(`{"retry":{"budgetRatio":0.2}}`), json.RawMessage(`{"retry":{"budgetRatio":0.1}}`)); kind != changeTighten {
		t.Fatal("a lower retry ratio tightens")
	}
	if !strings.Contains(describePlan(plan), "then, once the previous version has drained, tighten: payment-svc") {
		t.Fatalf("describePlan: %s", describePlan(plan))
	}
}

func TestJointRolloutLoosensFirstAndTightensOnlyAfterTheOldVersionDrained(t *testing.T) {
	h := jointHarness(t)
	h.gateway.inflight["chain-v1"] = 2 // chains stamped with the previous version are still running
	h.create("j", "two-phase", j2, "chain-v1", time.Now())
	h.setJoint("j")

	ro := h.until("j", PhaseDraining)
	if len(ro.Status.Plan) != 2 || ro.Status.GateStartedAt == "" {
		t.Fatalf("the joint rollout waits between loosening and tightening: %+v", ro.Status)
	}
	// every proxy received only its own part, verified by that part's hash
	for _, ps := range ro.Status.Pods {
		if !ps.Verified || ps.Hash != specHash(partOf(j2, ps.Part).Spec) {
			t.Fatalf("pod %s must hold its part of the policy: %+v", ps.Name, ps)
		}
	}
	if h.gateway.did("activate", "chain-v2") == 0 {
		t.Fatal("the loosening part (the gateway's longer deadline) is activated first")
	}
	if h.payment.did("activate", "chain-v2") != 0 {
		t.Fatal("the tightening part (payment's lower rate) waits while requests of the previous version are in flight")
	}
	ro = h.step("j")
	if ro.Status.Phase != PhaseDraining || h.payment.did("activate", "chain-v2") != 0 || ro.Status.InflightPrevious != 2 {
		t.Fatalf("still waiting: %+v", ro.Status)
	}
	h.gateway.mu.Lock()
	h.gateway.inflight["chain-v1"] = 0
	h.gateway.mu.Unlock()
	h.until("j", PhaseComplete)
	if h.payment.did("activate", "chain-v2") < h.gateway.did("activate", "chain-v2") {
		t.Fatal("payment tightens after the gateway loosened")
	}
	if h.gateway.did("retire", "chain-v1") == 0 || h.payment.did("retire", "chain-v1") == 0 {
		t.Fatal("the previous version is retired on both services")
	}
	if h.store.status["chain-v2"] != StatusActive || h.store.status["chain-v1"] != StatusSuperseded {
		t.Fatalf("store statuses: %v", h.store.status)
	}
}

func TestJointRolloutWithoutGatewayLooseningNeedsNoGate(t *testing.T) {
	h := jointHarness(t)
	// the way back from chain-v2: the rate rises first (payment), the deadline shortens second (gateway)
	for px, svc := range map[*fakeProxy]string{h.gateway: "gateway", h.payment: "payment-svc"} {
		px.held = map[string][2]string{j2.Epoch: {"active", specHash(partOf(j2, svc).Spec)}}
		px.active = j2.Epoch
	}
	j3 := Policy{PolicyID: "chain", Version: 3, Epoch: "chain-v3", Scope: Scope{Service: "gateway"}, Spec: jointSpec([]Part{gwPart(1000), payPart(100)})}
	h.store.policies[j3.Epoch] = j3
	h.create("back", "two-phase", j3, "chain-v2", time.Now())
	h.setJoint("back")
	ro := h.until("back", PhaseComplete)
	if ro.Status.GateStartedAt != "" {
		t.Fatal("no gate: the gateway is not part of the loosening step")
	}
	if h.payment.did("activate", "chain-v3") == 0 || h.gateway.did("activate", "chain-v3") < h.payment.did("activate", "chain-v3") {
		t.Fatalf("payment's higher rate first, then the gateway's shorter deadline: gw=%v pay=%v", h.gateway.log, h.payment.log)
	}
}

func TestASingleRateChangeInAJointPolicyIsNotHeldBack(t *testing.T) {
	// Only payment's rate changes (100 -> 12); the gateway's part is unchanged. The lower rate must be applied
	// without waiting for requests of the previous version: the deadline they carry did not change.
	j3 := Policy{PolicyID: "chain", Version: 3, Epoch: "chain-v3", Scope: Scope{Service: "gateway"}, Spec: jointSpec([]Part{gwPart(1000), payPart(12)})}
	plan := planJoint(j3, &j1)
	if plan[0].Kind != changeUnchanged || plan[0].Step != 1 || len(plan[0].Changes) != 0 || plan[1].Kind != changeTighten {
		t.Fatalf("the gateway's part is unchanged, payment tightens: %+v", plan)
	}
	h := jointHarness(t)
	h.store.policies[j3.Epoch] = j3
	h.gateway.inflight["chain-v1"] = 2 // requests of the previous version are still running
	h.create("rate", "two-phase", j3, "chain-v1", time.Now())
	h.setJoint("rate")
	ro := h.until("rate", PhaseDraining)
	if ro.Status.GateStartedAt != "" {
		t.Fatal("no wait before the tightening: the gateway's part did not change")
	}
	if h.payment.did("activate", "chain-v3") == 0 {
		t.Fatal("payment's new rate is enforced while requests of the previous version are still in flight")
	}
	if h.gateway.did("retire", "chain-v1") != 0 {
		t.Fatal("the previous version is only retired after the drain")
	}
	h.gateway.mu.Lock()
	h.gateway.inflight["chain-v1"] = 0
	h.gateway.mu.Unlock()
	h.until("rate", PhaseComplete)
}

func TestNaiveArrivalOrderServicesFirst(t *testing.T) {
	h := jointHarness(t)
	h.create("n", "naive", j2, "chain-v1", time.Now())
	h.setJoint("n", func(s *RolloutSpec) { s.Order = "services-first" })
	ro := h.until("n", PhaseComplete)
	pay, gw := h.payment.did("put-active", "chain-v2"), h.gateway.did("put-active", "chain-v2")
	if pay == 0 || gw == 0 || pay > gw {
		t.Fatalf("services first: payment %d, gateway %d", pay, gw)
	}
	if h.payment.did("retire", "chain-v1") == 0 {
		t.Fatal("naive drops the previous version as it switches")
	}
	if len(ro.Status.Plan) != 0 {
		t.Fatal("naive has no plan")
	}
}

// setJoint sets the joint scopes (and options) on a created rollout, as the policy API does.
func (h *harness) setJoint(name string, opts ...func(*RolloutSpec)) {
	ctx := context.Background()
	var ro RPERollout
	if err := h.k.Get(ctx, types.NamespacedName{Namespace: "rpe-system", Name: name}, &ro); err != nil {
		h.t.Fatal(err)
	}
	p, _ := h.store.get(ctx, ro.Spec.PolicyID, ro.Spec.Version)
	ro.Spec = rolloutSpec(p, ro.Spec.Mode, ro.Spec.PreviousEpoch, "")
	for _, o := range opts {
		o(&ro.Spec)
	}
	if err := h.k.Update(ctx, &ro); err != nil {
		h.t.Fatal(err)
	}
}
