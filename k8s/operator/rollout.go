package main

import (
	"context"
	"fmt"
	"log"
	"math/rand/v2"
	"sort"
	"strings"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

// target is one proxy that must receive a policy; Part is the service whose part of the policy it receives.
type target struct {
	Pod, Service, Role, IP, Part string
}

// serviceOf is the RPE service name of a pod, in the webhook's order: annotation, label app, app.kubernetes.io/name.
func serviceOf(p corev1.Pod) string {
	for _, v := range []string{p.Annotations["resilience.rpe.dev/service"], p.Labels["app"], p.Labels["app.kubernetes.io/name"]} {
		if v != "" {
			return v
		}
	}
	return ""
}

// gatewayService is the RPE service name of the API gateway. A policy scoped to it (the chain deadline, which only
// the gateway applies) reaches the gateway proxies; a policy scoped to any other service reaches only that
// service's proxies. The gateway is never a target of a service-scoped policy: activating one on the gateway
// would replace the chain deadlines it enforces.
var gatewayService = env("GATEWAY_SERVICE", "gateway")

// resolveTargets returns the proxies a policy for `service` must reach: the ready proxies of that service (the
// gateway proxies when the policy is scoped to the gateway), and nothing else.
func resolveTargets(ctx context.Context, r client.Reader, ns, service string) ([]target, error) {
	var pods corev1.PodList
	if err := r.List(ctx, &pods, client.InNamespace(ns)); err != nil {
		return nil, err
	}
	var out []target
	for _, p := range pods.Items {
		if p.Status.PodIP == "" || p.DeletionTimestamp != nil || p.Status.Phase != corev1.PodRunning || !hasSidecar(p) {
			continue
		}
		role := "service"
		if p.Annotations["resilience.rpe.dev/role"] == "gateway" {
			role = "gateway"
		}
		svc := serviceOf(p)
		if svc == service || (role == "gateway" && service == gatewayService) {
			out = append(out, target{Pod: p.Name, Service: svc, Role: role, IP: p.Status.PodIP, Part: service})
		}
	}
	// gateways first (only present for a gateway-scoped policy)
	sort.SliceStable(out, func(i, j int) bool { return out[i].Role == "gateway" && out[j].Role != "gateway" })
	return out, nil
}

// resolveAll returns the proxies of every scope of a rollout (the parts of a joint policy), each with the part it
// receives. A proxy is listed once, for the first part that concerns it.
func resolveAll(ctx context.Context, r client.Reader, ns string, scopes []string) ([]target, error) {
	var out []target
	seen := map[string]bool{}
	for _, s := range scopes {
		ts, err := resolveTargets(ctx, r, ns, s)
		if err != nil {
			return nil, err
		}
		for _, t := range ts {
			if !seen[t.Pod] {
				seen[t.Pod] = true
				out = append(out, t)
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Role == "gateway" && out[j].Role != "gateway" })
	return out, nil
}

func (r *RolloutReconciler) targets(ctx context.Context, ro *RPERollout) ([]target, error) {
	return resolveAll(ctx, r.reader, r.demoNS, ro.Spec.scopes())
}

// partOf is the policy as the proxies of one service see it: the part for that service of a joint policy, or the
// policy itself.
func partOf(p Policy, service string) Policy {
	if q, ok := p.forService(service); ok {
		return q
	}
	return p
}

func hasSidecar(p corev1.Pod) bool {
	for _, c := range p.Spec.Containers {
		if c.Name == "rpe-sidecar" {
			return true
		}
	}
	return false
}

// rolloutStore is the part of the PostgreSQL policy store the rollout state machine uses.
type rolloutStore interface {
	get(ctx context.Context, policyID string, version int) (Policy, error)
	recordRollout(ctx context.Context, name string, spec RolloutSpec) error
	updateRollout(ctx context.Context, name, state, message string, done bool) error
	setStatus(ctx context.Context, policyID string, version int, status string) error
	activate(ctx context.Context, policyID string, version int) error
	recordDelivery(ctx context.Context, pod, service, epoch string, version int, hash, state, errMsg string) error
	confirmations(ctx context.Context, epoch string) (map[string]confirmation, error)
	recordMeasurements(ctx context.Context, name string, st RolloutStatus, skewMs int) error
}

// eventBus is the Redis fast path the state machine reports to.
type eventBus interface {
	emit(ctx context.Context, typ string, fields map[string]string)
	ack(ctx context.Context, epoch, pod, state string)
	setEffective(ctx context.Context, p Policy)
}

// RolloutReconciler drives an RPERollout through its phases.
//
// two-phase (per-request consistent): Staging (every target holds the new epoch inactive, verified by its content
// hash) → Activating (gateways flip first and stamp the new epoch into new requests; then services) → Draining
// (until no request pinned to the previous epoch is in flight on any target) → Complete (previous epoch retired).
// Every proxy serves a request under the epoch the request carries, so one request never sees two versions.
// A joint policy (parts for several services under one epoch) is activated in two steps: the loosening parts
// first, then, once no request stamped with the previous version is in flight any more, the tightening parts
// (planJoint). A limit that is a property of the proxy (the leaky-bucket rate) cannot be chosen per request, so this
// order is what keeps a request of the old version from meeting a tightened limit.
// naive: every target switches to the new version at once and drops the previous one, as a proxy holding a
// single configuration does (the baseline). Requests in flight during the switch can see both versions.
//
// Rollouts run one at a time (the gateway stamps one epoch). The mixed-policy count is read from the proxies'
// counters (relative to the start) during the rollout and once more after it settled.
type RolloutReconciler struct {
	client.Client
	reader   client.Reader
	store    rolloutStore
	bus      eventBus
	side     *sidecars
	demoNS   string
	drain    time.Duration // longest wait for in-flight requests on the previous epoch
	settle   time.Duration // wait after completion before the final mixed-policy count
	attempts int
}

func (r *RolloutReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var ro RPERollout
	if err := r.Get(ctx, req.NamespacedName, &ro); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}
	switch {
	case ro.Status.Phase == PhaseFailed || (ro.Status.Phase == PhaseComplete && ro.Status.Settled):
		return ctrl.Result{}, nil
	case ro.Status.Phase == PhaseComplete:
		return r.settleRollout(ctx, &ro)
	}
	policy, err := r.store.get(ctx, ro.Spec.PolicyID, ro.Spec.Version)
	if err != nil {
		return r.fail(ctx, &ro, fmt.Sprintf("policy %s v%d not found in the policy store", ro.Spec.PolicyID, ro.Spec.Version))
	}
	var next time.Duration
	switch ro.Status.Phase {
	case "", PhasePending:
		next, err = r.start(ctx, &ro, policy)
	case PhaseStaging:
		next, err = r.stage(ctx, &ro, policy)
	case PhaseActivating:
		next, err = r.activate(ctx, &ro, policy)
	case PhaseDraining:
		next, err = r.finishDrain(ctx, &ro, policy)
	}
	if err != nil {
		return ctrl.Result{}, err
	}
	if ro.Status.MixedBaseline != nil {
		r.refreshMixed(ctx, &ro)
		r.refreshConfirmations(ctx, &ro, policy)
	}
	if uerr := r.Status().Update(ctx, &ro); uerr != nil {
		if apierrors.IsConflict(uerr) {
			return ctrl.Result{Requeue: true}, nil
		}
		return ctrl.Result{}, uerr
	}
	switch ro.Status.Phase {
	case PhaseFailed:
		return ctrl.Result{}, nil
	case PhaseComplete:
		return ctrl.Result{RequeueAfter: r.settle}, nil
	}
	return ctrl.Result{RequeueAfter: next}, nil
}

// waitingFor returns the earlier rollout that has not finished (and settled) yet, if any.
func (r *RolloutReconciler) waitingFor(ctx context.Context, ro *RPERollout) string {
	var list RPERolloutList
	if err := r.List(ctx, &list, client.InNamespace(ro.Namespace)); err != nil {
		return ""
	}
	for _, o := range list.Items {
		if o.Name == ro.Name || o.Status.Phase == PhaseFailed || (o.Status.Phase == PhaseComplete && o.Status.Settled) {
			continue
		}
		if o.CreationTimestamp.Before(&ro.CreationTimestamp) ||
			(o.CreationTimestamp.Equal(&ro.CreationTimestamp) && o.Name < ro.Name) {
			return o.Name
		}
	}
	return ""
}

func (r *RolloutReconciler) start(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	if other := r.waitingFor(ctx, ro); other != "" {
		ro.Status.Phase, ro.Status.Message = PhasePending, "waiting for rollout "+other+" to finish"
		return time.Second, nil
	}
	targets, err := r.targets(ctx, ro)
	if err != nil {
		return 0, err
	}
	ro.Status.StartedAt = nowNano()
	ro.Status.TargetPods = len(targets)
	ro.Status.Pods = nil
	ro.Status.MixedBaseline = map[string]int64{}
	ro.Status.Plan, ro.Status.Step = nil, 1
	if p.joint() && ro.Spec.Mode != "naive" {
		var prev *Policy
		if v := versionOf(ro.Spec.PreviousEpoch); v > 0 {
			if q, err := r.store.get(ctx, ro.Spec.PolicyID, v); err == nil {
				prev = &q
			}
		}
		ro.Status.Plan = planJoint(p, prev)
	}
	for _, t := range targets {
		ro.Status.Pods = append(ro.Status.Pods, PodStatus{Name: t.Pod, Service: t.Service, Role: t.Role, State: "pending", Part: t.Part})
		if held, err := r.side.epochs(ctx, t.IP); err == nil {
			ro.Status.MixedBaseline[t.Pod] = held.Mismatched
		} else {
			ro.Status.MixedBaseline[t.Pod] = 0
		}
	}
	if len(targets) == 0 {
		ro.Status.Phase, ro.Status.Message = PhaseFailed, "no running proxies found for "+strings.Join(ro.Spec.scopes(), ", ")
		r.finish(ctx, ro, false)
		return 0, nil
	}
	_ = r.store.recordRollout(ctx, ro.Name, ro.Spec)
	_ = r.store.setStatus(ctx, ro.Spec.PolicyID, ro.Spec.Version, StatusRollingOut)
	switch {
	case ro.Spec.Mode == "naive" && ro.Spec.Order != "":
		ro.Status.Phase, ro.Status.Message = PhaseActivating, "switching the proxies without coordination ("+ro.Spec.Order+")"
	case ro.Spec.Mode == "naive":
		ro.Status.Phase, ro.Status.Message = PhaseActivating, "switching every proxy to the new version at once"
	default:
		ro.Status.Phase, ro.Status.Message = PhaseStaging, "staging the new version on every proxy"
	}
	r.bus.emit(ctx, "rollout-started", map[string]string{"rollout": ro.Name, "policyId": ro.Spec.PolicyID, "epoch": ro.Spec.Epoch,
		"mode": ro.Spec.Mode, "targets": fmt.Sprint(len(targets)), "skewMs": fmt.Sprint(ro.Spec.SkewMs)})
	return 0, nil
}

// forEachPod applies fn to every pod not yet in `done` that `only` selects (nil: every pod), concurrently, and
// records the outcome. Each delivery starts after a random 0..skew delay (spec.skewMs, the same for every mode).
func (r *RolloutReconciler) forEachPod(ctx context.Context, ro *RPERollout, done string, skew time.Duration, only func(t target) bool,
	fn func(t target, ps *PodStatus) error) (int, error) {
	targets, err := r.targets(ctx, ro)
	if err != nil {
		return 0, err
	}
	ips := map[string]target{}
	for _, t := range targets {
		ips[t.Pod] = t
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	count := 0
	for i := range ro.Status.Pods {
		ps := &ro.Status.Pods[i]
		if ps.State == done {
			// deliveries started earlier in this loop also update count
			mu.Lock()
			count++
			mu.Unlock()
			continue
		}
		t, ok := ips[ps.Name]
		if !ok {
			ps.State, ps.Error = "gone", "pod no longer running"
			continue
		}
		if only != nil && !only(t) {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			if skew > 0 {
				select {
				case <-time.After(rand.N(skew)):
				case <-ctx.Done():
					return
				}
			}
			err := fn(t, ps)
			mu.Lock()
			defer mu.Unlock()
			if err != nil {
				ps.Error = err.Error()
				return
			}
			ps.State, ps.Error = done, ""
			if done == "active" {
				ps.ActiveAt = nowNano()
			}
			count++
		}()
	}
	wg.Wait()
	return count, nil
}

// payload is what one proxy receives: the part of the policy for its own service.
func (r *RolloutReconciler) payload(p Policy, state string) stagedPolicy {
	return stagedPolicy{Epoch: p.Epoch, Version: p.Version, Hash: specHash(p.Spec), State: state, Policy: p.Spec}
}

// verified checks the proxy's answer: it must hold the epoch with the approved content hash (of its part).
func verified(ps *PodStatus, held sidecarEpochs, err error, p Policy) error {
	if err != nil {
		return err
	}
	ps.Hash = held.hashOf(p.Epoch)
	if err := held.verify(p.Epoch, specHash(p.Spec)); err != nil {
		ps.Verified = false
		return err
	}
	ps.Verified = true
	return nil
}

func (r *RolloutReconciler) skew(ro *RPERollout) time.Duration {
	return time.Duration(ro.Spec.SkewMs) * time.Millisecond
}

func (r *RolloutReconciler) stage(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	staged, err := r.forEachPod(ctx, ro, "staged", r.skew(ro), nil, func(t target, ps *PodStatus) error {
		part := partOf(p, t.Part)
		held, err := r.side.put(ctx, t.IP, r.payload(part, "staged"))
		err = verified(ps, held, err, part)
		r.delivered(ctx, t, part, "staged", err)
		return err
	})
	if err != nil {
		return 0, err
	}
	ro.Status.Staged = staged
	if staged == countLive(ro) {
		ro.Status.StagedAt = nowNano()
		ro.Status.Phase, ro.Status.Message, ro.Status.Attempts = PhaseActivating, "every proxy holds the new version; activating at the gateway first", 0
		r.bus.emit(ctx, "rollout-staged", map[string]string{"rollout": ro.Name, "epoch": p.Epoch, "pods": fmt.Sprint(staged)})
		return 0, nil
	}
	return r.retry(ctx, ro, "staging")
}

func (r *RolloutReconciler) activate(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	naive := ro.Spec.Mode == "naive"
	prev := ro.Spec.PreviousEpoch
	step := max(ro.Status.Step, 1)
	skew := r.skew(ro)
	if at, err := time.Parse(time.RFC3339Nano, ro.Status.NextStepAt); err == nil && time.Until(at) > 0 {
		// a naive rollout with an arrival order: the second group is not reached yet (a status update may have
		// started this reconcile early)
		return time.Until(at), nil
	}
	// inStep selects the proxies the current step activates: every proxy, except for a joint policy (the
	// loosening parts in step 1, the tightening parts in step 2) and a naive rollout with a fixed arrival order (one
	// group in step 1, the other SkewMs later in step 2).
	inStep := func(role, part string) bool { return true }
	switch {
	case naive && ro.Spec.Order != "":
		gatewayFirst := ro.Spec.Order == "gateway-first"
		inStep = func(role, _ string) bool { return (role == "gateway") == (gatewayFirst == (step == 1)) }
		skew = 0
	case len(ro.Status.Plan) > 0:
		inStep = func(_, part string) bool { return stepOf(ro.Status.Plan, part) == step }
	}
	activateOne := func(t target, ps *PodStatus) error {
		part := partOf(p, t.Part)
		var held sidecarEpochs
		var err error
		if naive {
			held, err = r.side.put(ctx, t.IP, r.payload(part, "active"))
		} else {
			held, err = r.side.activate(ctx, t.IP, p.Epoch)
		}
		err = verified(ps, held, err, part)
		r.delivered(ctx, t, part, "active", err)
		if err == nil && naive && prev != "" && prev != p.Epoch {
			// a proxy with a single configuration drops the previous one when it switches
			if r.side.retire(ctx, t.IP, prev) == nil {
				r.bus.ack(ctx, prev, t.Pod, "retired")
				_ = r.store.recordDelivery(ctx, t.Pod, t.Service, prev, p.Version, "", "retired", "")
			}
		}
		return err
	}
	if naive {
		// every selected target switches in one pass
		if _, err := r.forEachPod(ctx, ro, "active", skew, func(t target) bool { return inStep(t.Role, t.Part) }, activateOne); err != nil {
			return 0, err
		}
	} else {
		// Two-phase: the gateways of this step first (they stamp the new epoch into new requests), then the
		// services. A policy scoped to a service has no gateway among its targets, so that pass selects nothing.
		for _, role := range []string{"gateway", "service"} {
			only := func(t target) bool { return t.Role == role && inStep(t.Role, t.Part) }
			if _, err := r.forEachPod(ctx, ro, "active", skew, only, activateOne); err != nil {
				return 0, err
			}
			if role == "gateway" && !allActive(ro, func(ps PodStatus) bool { return ps.Role == "gateway" && inStep(ps.Role, ps.Part) }) {
				return r.retry(ctx, ro, "activating gateways")
			}
		}
	}
	active := 0
	for _, ps := range ro.Status.Pods {
		if ps.State == "active" {
			active++
		}
	}
	ro.Status.Active = active
	if !allActive(ro, func(ps PodStatus) bool { return inStep(ps.Role, ps.Part) }) {
		return r.retry(ctx, ro, "activating")
	}
	if active < countLive(ro) {
		if step >= 2 {
			return r.retry(ctx, ro, "activating")
		}
		// step 1 is done; step 2 remains
		ro.Status.Attempts = 0
		switch {
		case naive:
			ro.Status.Step = 2
			ro.Status.NextStepAt = time.Now().Add(r.skew(ro)).UTC().Format(time.RFC3339Nano)
			ro.Status.Message = fmt.Sprintf("%s: the first proxies switched; the others follow in %d ms", ro.Spec.Order, ro.Spec.SkewMs)
			return r.skew(ro), nil
		case prev != "" && prev != p.Epoch && stepHasGateway(ro, 1):
			// The gateway stamps the new version since step 1. Tighten only once no request stamped with the
			// previous version is in flight: such a request still carries the previous version's settings.
			ro.Status.Phase, ro.Status.GateStartedAt = PhaseDraining, nowNano()
			ro.Status.Message = fmt.Sprintf("loosening applied; waiting until no request stamped with %s is in flight before tightening", prev)
			return 0, nil
		default:
			ro.Status.Step = 2
			ro.Status.Message = "loosening applied; tightening"
			return 0, nil
		}
	}
	ro.Status.ActivatedAt = nowNano()
	ro.Status.ConvergenceMs = since(ro.Status.StartedAt, ro.Status.ActivatedAt)
	r.bus.emit(ctx, "rollout-activated", map[string]string{"rollout": ro.Name, "epoch": p.Epoch, "pods": fmt.Sprint(active)})
	if naive || prev == "" || prev == p.Epoch {
		return 0, r.complete(ctx, ro, p)
	}
	ro.Status.Phase, ro.Status.Attempts = PhaseDraining, 0
	ro.Status.Message = fmt.Sprintf("draining requests pinned to %s", prev)
	return 0, nil
}

// gating reports whether a joint rollout waits between its loosening and its tightening step.
func gating(ro *RPERollout) bool { return ro.Status.GateStartedAt != "" && ro.Status.Step <= 1 }

// finishDrain waits until no target proxy has a request pinned to the previous epoch in flight (at most r.drain).
// Between the two steps of a joint rollout it is the gate before the tightening step; after the last step it is
// the drain before the previous epoch is retired.
func (r *RolloutReconciler) finishDrain(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	prev := ro.Spec.PreviousEpoch
	targets, err := r.targets(ctx, ro)
	if err != nil {
		return 0, err
	}
	var inflight int64
	for _, t := range targets {
		cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		held, err := r.side.epochs(cctx, t.IP)
		cancel()
		if err == nil {
			inflight += held.Inflight[prev]
		}
	}
	ro.Status.InflightPrevious = inflight
	gate := gating(ro)
	from := ro.Status.ActivatedAt
	if gate {
		from = ro.Status.GateStartedAt
	}
	waited := time.Duration(since(from, nowNano())) * time.Millisecond
	if inflight > 0 && waited < r.drain {
		if gate {
			ro.Status.Message = fmt.Sprintf("waiting before tightening: %d request(s) stamped with %s still in flight", inflight, prev)
		} else {
			ro.Status.Message = fmt.Sprintf("draining: %d request(s) still pinned to %s", inflight, prev)
		}
		return 200 * time.Millisecond, nil
	}
	if inflight > 0 {
		log.Printf("rollout %s: drain limit %s reached with %d request(s) on %s", ro.Name, r.drain, inflight, prev)
	}
	if gate {
		ro.Status.GateMs = waited.Milliseconds()
		ro.Status.Phase, ro.Status.Step, ro.Status.Attempts = PhaseActivating, 2, 0
		ro.Status.Message = fmt.Sprintf("no request stamped with %s in flight after %d ms; tightening", prev, ro.Status.GateMs)
		return 0, nil
	}
	ro.Status.DrainMs = waited.Milliseconds()
	return 0, r.complete(ctx, ro, p)
}

func (r *RolloutReconciler) complete(ctx context.Context, ro *RPERollout, p Policy) error {
	// Mark the new version active in the system of record first, so drift repair never re-delivers the
	// version that is about to be retired.
	if err := r.store.activate(ctx, p.PolicyID, p.Version); err != nil {
		return err
	}
	if prev := ro.Spec.PreviousEpoch; prev != "" && prev != p.Epoch && ro.Spec.Mode != "naive" {
		targets, _ := r.targets(ctx, ro)
		for _, t := range targets {
			if err := r.side.retire(ctx, t.IP, prev); err == nil {
				r.bus.ack(ctx, prev, t.Pod, "retired")
				_ = r.store.recordDelivery(ctx, t.Pod, t.Service, prev, p.Version, "", "retired", "")
			}
		}
	}
	for _, part := range p.projections() {
		r.bus.setEffective(ctx, part)
	}
	ro.Status.Phase, ro.Status.CompletedAt = PhaseComplete, nowNano()
	ro.Status.Message = fmt.Sprintf("%s is active on %d proxies", p.Epoch, ro.Status.Active)
	r.finish(ctx, ro, true)
	r.markPolicy(ctx, p, ro)
	return nil
}

// settleRollout reads the proxies' mixed-policy counters and acknowledgements once more after the rollout, so
// requests that were still in flight at completion are counted, then records the measurements.
func (r *RolloutReconciler) settleRollout(ctx context.Context, ro *RPERollout) (ctrl.Result, error) {
	if wait := r.settle - time.Duration(since(ro.Status.CompletedAt, nowNano()))*time.Millisecond; wait > 0 {
		return ctrl.Result{RequeueAfter: wait}, nil
	}
	p, err := r.store.get(ctx, ro.Spec.PolicyID, ro.Spec.Version)
	if err == nil {
		r.refreshConfirmations(ctx, ro, p)
	}
	r.refreshMixed(ctx, ro)
	ro.Status.Settled = true
	if err := r.Status().Update(ctx, ro); err != nil {
		if apierrors.IsConflict(err) {
			return ctrl.Result{Requeue: true}, nil
		}
		return ctrl.Result{}, err
	}
	mixedRequests.WithLabelValues(ro.Spec.Mode).Add(float64(ro.Status.MixedPolicyRequests))
	rolloutConvergence.WithLabelValues(ro.Spec.Mode).Observe(float64(ro.Status.ConvergenceMs) / 1000)
	_ = r.store.recordMeasurements(ctx, ro.Name, ro.Status, ro.Spec.SkewMs)
	r.bus.emit(ctx, "rollout-settled", map[string]string{"rollout": ro.Name, "epoch": ro.Spec.Epoch, "mode": ro.Spec.Mode,
		"mixedPolicyRequests": fmt.Sprint(ro.Status.MixedPolicyRequests), "convergenceMs": fmt.Sprint(ro.Status.ConvergenceMs),
		"drainMs": fmt.Sprint(ro.Status.DrainMs)})
	if err == nil {
		r.markPolicy(ctx, p, ro)
	}
	log.Printf("rollout %s (%s, skew %d ms): %d mixed-policy request(s), converged in %d ms, drained in %d ms",
		ro.Name, ro.Spec.Mode, ro.Spec.SkewMs, ro.Status.MixedPolicyRequests, ro.Status.ConvergenceMs, ro.Status.DrainMs)
	return ctrl.Result{}, nil
}

// refreshMixed sums the target proxies' mixed-policy counters relative to the rollout start. A proxy that
// restarted (counter below its baseline) contributes what it counted since.
func (r *RolloutReconciler) refreshMixed(ctx context.Context, ro *RPERollout) {
	targets, err := r.targets(ctx, ro)
	if err != nil {
		return
	}
	var total int64
	for _, t := range targets {
		base, ok := ro.Status.MixedBaseline[t.Pod]
		if !ok {
			continue
		}
		cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		held, err := r.side.epochs(cctx, t.IP)
		cancel()
		if err != nil {
			continue
		}
		if held.Mismatched >= base {
			total += held.Mismatched - base
		} else {
			total += held.Mismatched
		}
	}
	if total > ro.Status.MixedPolicyRequests {
		ro.Status.MixedPolicyRequests = total
	}
}

// refreshConfirmations adds the proxies' own acknowledgements (POST /api/k8s/acks) to the pod list.
func (r *RolloutReconciler) refreshConfirmations(ctx context.Context, ro *RPERollout, p Policy) {
	acks, err := r.store.confirmations(ctx, p.Epoch)
	if err != nil {
		return
	}
	for i := range ro.Status.Pods {
		ps := &ro.Status.Pods[i]
		c, ok := acks[ps.Name]
		if !ok {
			continue
		}
		want := specHash(partOf(p, ps.Part).Spec)
		ps.ConfirmedAt = c.At.UTC().Format(time.RFC3339Nano)
		if ps.Hash == "" {
			ps.Hash = c.Hash
		}
		if c.Hash != want && ps.State != "failed" {
			ps.Verified, ps.Error = false, fmt.Sprintf("proxy acknowledged hash %s, approved %s", c.Hash, want)
		}
	}
}

func (r *RolloutReconciler) retry(ctx context.Context, ro *RPERollout, what string) (time.Duration, error) {
	ro.Status.Attempts++
	if ro.Status.Attempts > r.attempts {
		_, err := r.fail(ctx, ro, fmt.Sprintf("%s did not converge after %d attempts", what, r.attempts))
		return 0, err
	}
	// exponential backoff 250 ms .. 4 s
	return min(250*time.Millisecond<<min(ro.Status.Attempts-1, 4), 4*time.Second), nil
}

func (r *RolloutReconciler) fail(ctx context.Context, ro *RPERollout, msg string) (ctrl.Result, error) {
	ro.Status.Phase, ro.Status.Message, ro.Status.CompletedAt = PhaseFailed, msg, nowNano()
	_ = r.store.setStatus(ctx, ro.Spec.PolicyID, ro.Spec.Version, StatusFailed)
	r.finish(ctx, ro, false)
	return ctrl.Result{}, r.Status().Update(ctx, ro)
}

func (r *RolloutReconciler) finish(ctx context.Context, ro *RPERollout, ok bool) {
	_ = r.store.updateRollout(ctx, ro.Name, ro.Status.Phase, ro.Status.Message, true)
	rolloutsFinished.WithLabelValues(ro.Spec.Mode, ro.Status.Phase).Inc()
	typ := "rollout-complete"
	if !ok {
		typ = "rollout-failed"
	}
	r.bus.emit(ctx, typ, map[string]string{"rollout": ro.Name, "policyId": ro.Spec.PolicyID, "epoch": ro.Spec.Epoch, "message": ro.Status.Message})
	log.Printf("rollout %s: %s (%s)", ro.Name, ro.Status.Phase, ro.Status.Message)
}

func (r *RolloutReconciler) delivered(ctx context.Context, t target, p Policy, state string, err error) {
	msg := ""
	if err != nil {
		msg, state = err.Error(), "failed"
	}
	_ = r.store.recordDelivery(ctx, t.Pod, t.Service, p.Epoch, p.Version, specHash(p.Spec), state, msg)
	r.bus.ack(ctx, p.Epoch, t.Pod, state)
}

// markPolicy reports desired vs observed on the RPEPolicy mirror.
func (r *RolloutReconciler) markPolicy(ctx context.Context, p Policy, ro *RPERollout) {
	var cr RPEPolicy
	if err := r.Get(ctx, types.NamespacedName{Namespace: currentNamespace(), Name: crName(p.PolicyID)}, &cr); err != nil {
		return
	}
	st := PolicyStatus{ActiveEpoch: p.Epoch, DesiredEpoch: p.Epoch, Rollout: ro.Name, Phase: ro.Status.Phase, DriftPods: cr.Status.DriftPods}
	verifiedPods := 0
	for _, ps := range ro.Status.Pods {
		st.Observed = append(st.Observed, ObservedPod{Pod: ps.Name, Epoch: p.Epoch, Hash: ps.Hash, State: ps.State})
		if ps.State == "active" && ps.Verified {
			verifiedPods++
		}
	}
	if live := countLive(ro); live > 0 {
		st.ConvergencePct = verifiedPods * 100 / live
	}
	cr.Status = st
	_ = r.Status().Update(ctx, &cr)
}

func countLive(ro *RPERollout) int {
	n := 0
	for _, p := range ro.Status.Pods {
		if p.State != "gone" {
			n++
		}
	}
	return n
}

// allActive reports whether every live pod that `which` selects is active.
func allActive(ro *RPERollout, which func(PodStatus) bool) bool {
	for _, p := range ro.Status.Pods {
		if which(p) && p.State != "active" && p.State != "gone" {
			return false
		}
	}
	return true
}

// stepHasGateway reports whether a gateway proxy is activated in the given step of a joint rollout.
func stepHasGateway(ro *RPERollout, step int) bool {
	for _, p := range ro.Status.Pods {
		if p.Role == "gateway" && stepOf(ro.Status.Plan, p.Part) == step {
			return true
		}
	}
	return false
}

func now() string     { return time.Now().UTC().Format(time.RFC3339) }
func nowNano() string { return time.Now().UTC().Format(time.RFC3339Nano) }

// since is the number of milliseconds from a to b (RFC 3339 timestamps); 0 when either is missing.
func since(a, b string) int64 {
	ta, err1 := time.Parse(time.RFC3339Nano, a)
	tb, err2 := time.Parse(time.RFC3339Nano, b)
	if err1 != nil || err2 != nil || tb.Before(ta) {
		return 0
	}
	return tb.Sub(ta).Milliseconds()
}

func nonEmpty(s, def string) string {
	if s == "" {
		return def
	}
	return s
}
