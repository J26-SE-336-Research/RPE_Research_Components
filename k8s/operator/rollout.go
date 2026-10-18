package main

import (
	"context"
	"fmt"
	"log"
	"math/rand/v2"
	"sort"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

// target is one proxy that must receive a policy.
type target struct {
	Pod, Service, Role, IP string
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
			out = append(out, target{Pod: p.Name, Service: svc, Role: role, IP: p.Status.PodIP})
		}
	}
	// gateways first (only present for a gateway-scoped policy)
	sort.SliceStable(out, func(i, j int) bool { return out[i].Role == "gateway" && out[j].Role != "gateway" })
	return out, nil
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
		next, err = r.start(ctx, &ro)
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

func (r *RolloutReconciler) start(ctx context.Context, ro *RPERollout) (time.Duration, error) {
	if other := r.waitingFor(ctx, ro); other != "" {
		ro.Status.Phase, ro.Status.Message = PhasePending, "waiting for rollout "+other+" to finish"
		return time.Second, nil
	}
	targets, err := resolveTargets(ctx, r.reader, r.demoNS, ro.Spec.Service)
	if err != nil {
		return 0, err
	}
	ro.Status.StartedAt = nowNano()
	ro.Status.TargetPods = len(targets)
	ro.Status.Pods = nil
	ro.Status.MixedBaseline = map[string]int64{}
	for _, t := range targets {
		ro.Status.Pods = append(ro.Status.Pods, PodStatus{Name: t.Pod, Service: t.Service, Role: t.Role, State: "pending"})
		if held, err := r.side.epochs(ctx, t.IP); err == nil {
			ro.Status.MixedBaseline[t.Pod] = held.Mismatched
		} else {
			ro.Status.MixedBaseline[t.Pod] = 0
		}
	}
	if len(targets) == 0 {
		ro.Status.Phase, ro.Status.Message = PhaseFailed, "no running proxies found for "+ro.Spec.Service
		r.finish(ctx, ro, false)
		return 0, nil
	}
	_ = r.store.recordRollout(ctx, ro.Name, ro.Spec)
	_ = r.store.setStatus(ctx, ro.Spec.PolicyID, ro.Spec.Version, StatusRollingOut)
	if ro.Spec.Mode == "naive" {
		ro.Status.Phase, ro.Status.Message = PhaseActivating, "switching every proxy to the new version at once"
	} else {
		ro.Status.Phase, ro.Status.Message = PhaseStaging, "staging the new version on every proxy"
	}
	r.bus.emit(ctx, "rollout-started", map[string]string{"rollout": ro.Name, "policyId": ro.Spec.PolicyID, "epoch": ro.Spec.Epoch,
		"mode": ro.Spec.Mode, "targets": fmt.Sprint(len(targets)), "skewMs": fmt.Sprint(ro.Spec.SkewMs)})
	return 0, nil
}

// forEachPod applies fn to every pod not yet in `done`, concurrently, and records the outcome. With
// spec.skewMs, each delivery starts after a random 0..skewMs delay (the same for every mode).
func (r *RolloutReconciler) forEachPod(ctx context.Context, ro *RPERollout, done string, fn func(t target, ps *PodStatus) error) (int, error) {
	targets, err := resolveTargets(ctx, r.reader, r.demoNS, ro.Spec.Service)
	if err != nil {
		return 0, err
	}
	ips := map[string]target{}
	for _, t := range targets {
		ips[t.Pod] = t
	}
	skew := time.Duration(ro.Spec.SkewMs) * time.Millisecond
	var mu sync.Mutex
	var wg sync.WaitGroup
	count := 0
	for i := range ro.Status.Pods {
		ps := &ro.Status.Pods[i]
		if ps.State == done {
			count++
			continue
		}
		t, ok := ips[ps.Name]
		if !ok {
			ps.State, ps.Error = "gone", "pod no longer running"
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
			count++
		}()
	}
	wg.Wait()
	return count, nil
}

func (r *RolloutReconciler) payload(p Policy, state string) stagedPolicy {
	return stagedPolicy{Epoch: p.Epoch, Version: p.Version, Hash: specHash(p.Spec), State: state, Policy: p.Spec}
}

// verified checks the proxy's answer: it must hold the epoch with the approved content hash.
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

func (r *RolloutReconciler) stage(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	staged, err := r.forEachPod(ctx, ro, "staged", func(t target, ps *PodStatus) error {
		held, err := r.side.put(ctx, t.IP, r.payload(p, "staged"))
		err = verified(ps, held, err, p)
		r.delivered(ctx, t, p, "staged", err)
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
	// Two-phase: gateways first, then services. A policy scoped to a service has no gateway among its targets, so
	// that pass only runs when the targets include a gateway.
	roles := []string{"service"}
	switch {
	case naive:
		roles = []string{"all"} // every target switches in one pass
	case hasRole(ro, "gateway"):
		roles = []string{"gateway", "service"}
	}
	for _, role := range roles {
		_, err := r.forEachPod(ctx, ro, "active", func(t target, ps *PodStatus) error {
			if !naive && t.Role != role {
				return fmt.Errorf("waiting for gateways")
			}
			var held sidecarEpochs
			var err error
			if naive {
				held, err = r.side.put(ctx, t.IP, r.payload(p, "active"))
			} else {
				held, err = r.side.activate(ctx, t.IP, p.Epoch)
			}
			err = verified(ps, held, err, p)
			r.delivered(ctx, t, p, "active", err)
			if err == nil && naive && prev != "" && prev != p.Epoch {
				// a proxy with a single configuration drops the previous one when it switches
				if r.side.retire(ctx, t.IP, prev) == nil {
					r.bus.ack(ctx, prev, t.Pod, "retired")
					_ = r.store.recordDelivery(ctx, t.Pod, t.Service, prev, p.Version, "", "retired", "")
				}
			}
			return err
		})
		if err != nil {
			return 0, err
		}
		if !naive && role == "gateway" && !allRole(ro, "gateway", "active") {
			return r.retry(ctx, ro, "activating gateways")
		}
	}
	active := 0
	for _, ps := range ro.Status.Pods {
		if ps.State == "active" {
			active++
		}
	}
	ro.Status.Active = active
	if active < countLive(ro) {
		return r.retry(ctx, ro, "activating")
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

// finishDrain waits until no target proxy has a request pinned to the previous epoch in flight (at most r.drain).
func (r *RolloutReconciler) finishDrain(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	prev := ro.Spec.PreviousEpoch
	targets, err := resolveTargets(ctx, r.reader, r.demoNS, ro.Spec.Service)
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
	waited := time.Duration(since(ro.Status.ActivatedAt, nowNano())) * time.Millisecond
	if inflight > 0 && waited < r.drain {
		ro.Status.Message = fmt.Sprintf("draining: %d request(s) still pinned to %s", inflight, prev)
		return 200 * time.Millisecond, nil
	}
	ro.Status.DrainMs = waited.Milliseconds()
	if inflight > 0 {
		log.Printf("rollout %s: drain limit %s reached with %d request(s) on %s", ro.Name, r.drain, inflight, prev)
	}
	return 0, r.complete(ctx, ro, p)
}

func (r *RolloutReconciler) complete(ctx context.Context, ro *RPERollout, p Policy) error {
	// Mark the new version active in the system of record first, so drift repair never re-delivers the
	// version that is about to be retired.
	if err := r.store.activate(ctx, p.PolicyID, p.Version); err != nil {
		return err
	}
	if prev := ro.Spec.PreviousEpoch; prev != "" && prev != p.Epoch && ro.Spec.Mode != "naive" {
		targets, _ := resolveTargets(ctx, r.reader, r.demoNS, ro.Spec.Service)
		for _, t := range targets {
			if err := r.side.retire(ctx, t.IP, prev); err == nil {
				r.bus.ack(ctx, prev, t.Pod, "retired")
				_ = r.store.recordDelivery(ctx, t.Pod, t.Service, prev, p.Version, "", "retired", "")
			}
		}
	}
	r.bus.setEffective(ctx, p)
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
	targets, err := resolveTargets(ctx, r.reader, r.demoNS, ro.Spec.Service)
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
	want := specHash(p.Spec)
	for i := range ro.Status.Pods {
		ps := &ro.Status.Pods[i]
		c, ok := acks[ps.Name]
		if !ok {
			continue
		}
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

func hasRole(ro *RPERollout, role string) bool {
	for _, p := range ro.Status.Pods {
		if p.Role == role {
			return true
		}
	}
	return false
}

func allRole(ro *RPERollout, role, state string) bool {
	for _, p := range ro.Status.Pods {
		if p.Role == role && p.State != state && p.State != "gone" {
			return false
		}
	}
	return true
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
