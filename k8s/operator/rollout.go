package main

import (
	"context"
	"fmt"
	"log"
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

// resolveTargets returns the proxies a policy for `service` must reach: every ready proxy of the
// service, plus the gateway proxies (they stamp the policy epoch into new requests at the ingress).
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
		if p.Labels["app"] == service || role == "gateway" {
			out = append(out, target{Pod: p.Name, Service: p.Labels["app"], Role: role, IP: p.Status.PodIP})
		}
	}
	// gateways first: the two-phase flip happens at the ingress
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

// RolloutReconciler drives an RPERollout through its phases.
//
// two-phase: Staging (every target holds the new epoch inactive) → Activating (gateways flip first, then
// services) → Draining (in-flight requests on the previous epoch finish) → Complete (previous epoch retired).
// naive: every target is switched to active at once (the comparison baseline, no consistency guarantee).
type RolloutReconciler struct {
	client.Client
	reader   client.Reader
	store    *store
	bus      *bus
	side     *sidecars
	demoNS   string
	drain    time.Duration
	attempts int
}

func (r *RolloutReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	var ro RPERollout
	if err := r.Get(ctx, req.NamespacedName, &ro); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}
	if ro.Status.Phase == PhaseComplete || ro.Status.Phase == PhaseFailed {
		return ctrl.Result{}, nil
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
	if uerr := r.Status().Update(ctx, &ro); uerr != nil {
		if apierrors.IsConflict(uerr) {
			return ctrl.Result{Requeue: true}, nil
		}
		return ctrl.Result{}, uerr
	}
	if ro.Status.Phase == PhaseComplete || ro.Status.Phase == PhaseFailed {
		return ctrl.Result{}, nil
	}
	return ctrl.Result{RequeueAfter: next}, nil
}

func (r *RolloutReconciler) start(ctx context.Context, ro *RPERollout) (time.Duration, error) {
	targets, err := resolveTargets(ctx, r.reader, r.demoNS, ro.Spec.Service)
	if err != nil {
		return 0, err
	}
	ro.Status.StartedAt = now()
	ro.Status.TargetPods = len(targets)
	ro.Status.Pods = nil
	for _, t := range targets {
		ro.Status.Pods = append(ro.Status.Pods, PodStatus{Name: t.Pod, Service: t.Service, Role: t.Role, State: "pending"})
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
	r.bus.emit(ctx, "rollout-started", map[string]string{"rollout": ro.Name, "policyId": ro.Spec.PolicyID, "epoch": ro.Spec.Epoch, "mode": ro.Spec.Mode, "targets": fmt.Sprint(len(targets))})
	return 0, nil
}

// forEachPod applies fn to every pod not yet in `done`, concurrently, and records the outcome.
func (r *RolloutReconciler) forEachPod(ctx context.Context, ro *RPERollout, done string, fn func(t target) error) (int, error) {
	targets, err := resolveTargets(ctx, r.reader, r.demoNS, ro.Spec.Service)
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
			err := fn(t)
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

func (r *RolloutReconciler) stage(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	staged, err := r.forEachPod(ctx, ro, "staged", func(t target) error {
		err := r.side.put(ctx, t.IP, r.payload(p, "staged"))
		r.delivered(ctx, t, p, "staged", err)
		return err
	})
	if err != nil {
		return 0, err
	}
	ro.Status.Staged = staged
	if staged == countLive(ro) {
		ro.Status.Phase, ro.Status.Message, ro.Status.Attempts = PhaseActivating, "every proxy holds the new version; activating at the gateway first", 0
		r.bus.emit(ctx, "rollout-staged", map[string]string{"rollout": ro.Name, "epoch": p.Epoch, "pods": fmt.Sprint(staged)})
		return 0, nil
	}
	return r.retry(ctx, ro, "staging")
}

func (r *RolloutReconciler) activate(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	naive := ro.Spec.Mode == "naive"
	// two-phase: gateways first; only after every gateway stamps the new epoch do services switch.
	for _, role := range []string{"gateway", "service"} {
		if naive && role == "service" {
			break
		}
		_, err := r.forEachPod(ctx, ro, "active", func(t target) error {
			if !naive && t.Role != role {
				return fmt.Errorf("waiting for gateways")
			}
			var err error
			if naive {
				err = r.side.put(ctx, t.IP, r.payload(p, "active"))
			} else {
				err = r.side.activate(ctx, t.IP, p.Epoch)
			}
			r.delivered(ctx, t, p, "active", err)
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
	if naive {
		return 0, r.complete(ctx, ro, p)
	}
	ro.Status.Phase = PhaseDraining
	ro.Status.DrainUntil = time.Now().Add(r.drain).UTC().Format(time.RFC3339Nano)
	ro.Status.Message = fmt.Sprintf("draining requests pinned to %s", nonEmpty(ro.Spec.PreviousEpoch, "the previous version"))
	r.bus.emit(ctx, "rollout-activated", map[string]string{"rollout": ro.Name, "epoch": p.Epoch, "pods": fmt.Sprint(active)})
	return r.drain, nil
}

func (r *RolloutReconciler) finishDrain(ctx context.Context, ro *RPERollout, p Policy) (time.Duration, error) {
	until, _ := time.Parse(time.RFC3339Nano, ro.Status.DrainUntil)
	if wait := time.Until(until); wait > 0 {
		return wait, nil
	}
	return 0, r.complete(ctx, ro, p)
}

func (r *RolloutReconciler) complete(ctx context.Context, ro *RPERollout, p Policy) error {
	// Mark the new version active in the system of record first, so drift repair never re-delivers the
	// version that is about to be retired.
	if err := r.store.activate(ctx, p.PolicyID, p.Version); err != nil {
		return err
	}
	if prev := ro.Spec.PreviousEpoch; prev != "" && prev != p.Epoch {
		targets, _ := resolveTargets(ctx, r.reader, r.demoNS, ro.Spec.Service)
		for _, t := range targets {
			if err := r.side.retire(ctx, t.IP, prev); err == nil {
				r.bus.ack(ctx, prev, t.Pod, "retired")
				_ = r.store.recordDelivery(ctx, t.Pod, t.Service, prev, p.Version, "", "retired", "")
			}
		}
	}
	r.bus.setEffective(ctx, p)
	ro.Status.Phase, ro.Status.CompletedAt = PhaseComplete, now()
	ro.Status.Message = fmt.Sprintf("%s is active on %d proxies", p.Epoch, ro.Status.Active)
	r.finish(ctx, ro, true)
	r.markPolicy(ctx, p, ro.Name, PhaseComplete)
	return nil
}

func (r *RolloutReconciler) retry(ctx context.Context, ro *RPERollout, what string) (time.Duration, error) {
	ro.Status.Attempts++
	if ro.Status.Attempts > r.attempts {
		_, err := r.fail(ctx, ro, fmt.Sprintf("%s did not converge after %d attempts", what, r.attempts))
		return 0, err
	}
	return time.Second, nil
}

func (r *RolloutReconciler) fail(ctx context.Context, ro *RPERollout, msg string) (ctrl.Result, error) {
	ro.Status.Phase, ro.Status.Message, ro.Status.CompletedAt = PhaseFailed, msg, now()
	_ = r.store.setStatus(ctx, ro.Spec.PolicyID, ro.Spec.Version, StatusFailed)
	r.finish(ctx, ro, false)
	return ctrl.Result{}, r.Status().Update(ctx, ro)
}

func (r *RolloutReconciler) finish(ctx context.Context, ro *RPERollout, ok bool) {
	_ = r.store.updateRollout(ctx, ro.Name, ro.Status.Phase, ro.Status.Message, true)
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

func (r *RolloutReconciler) markPolicy(ctx context.Context, p Policy, rollout, phase string) {
	var cr RPEPolicy
	if err := r.Get(ctx, types.NamespacedName{Namespace: currentNamespace(), Name: crName(p.PolicyID)}, &cr); err != nil {
		return
	}
	cr.Status = PolicyStatus{ActiveEpoch: p.Epoch, Rollout: rollout, Phase: phase}
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

func allRole(ro *RPERollout, role, state string) bool {
	for _, p := range ro.Status.Pods {
		if p.Role == role && p.State != state && p.State != "gone" {
			return false
		}
	}
	return true
}

func now() string { return time.Now().UTC().Format(time.RFC3339) }

func nonEmpty(s, def string) string {
	if s == "" {
		return def
	}
	return s
}

// repairDrift periodically checks that every proxy enforcing an active policy still holds it (for example a
// restarted pod comes up without any policy) and re-delivers it: stage, then activate.
func repairDrift(ctx context.Context, interval time.Duration, reader client.Reader, s *store, b *bus, side *sidecars, ns string) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		policies, err := s.active(ctx)
		if err != nil {
			continue
		}
		for _, p := range policies {
			targets, err := resolveTargets(ctx, reader, ns, p.Scope.Service)
			if err != nil {
				continue
			}
			for _, tg := range targets {
				held, err := side.epochs(ctx, tg.IP)
				if err != nil || held.holds(p.Epoch, "active") {
					continue
				}
				payload := stagedPolicy{Epoch: p.Epoch, Version: p.Version, Hash: specHash(p.Spec), State: "staged", Policy: p.Spec}
				if err := side.put(ctx, tg.IP, payload); err != nil {
					continue
				}
				if err := side.activate(ctx, tg.IP, p.Epoch); err != nil {
					continue
				}
				_ = s.recordDelivery(ctx, tg.Pod, tg.Service, p.Epoch, p.Version, payload.Hash, "active", "")
				b.ack(ctx, p.Epoch, tg.Pod, "active")
				b.emit(ctx, "drift-repaired", map[string]string{"pod": tg.Pod, "policyId": p.PolicyID, "epoch": p.Epoch})
				log.Printf("drift repaired: %s now enforces %s", tg.Pod, p.Epoch)
			}
		}
	}
}
