package main

import (
	"context"
	"fmt"
	"log"
	"sort"
	"strings"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

// The fleet view (GET /api/k8s/fleet) covers every namespace labelled rpe-injection=enabled:
//   - coverage audit: eligible pods (not opted out) that run without the RPE sidecar, for example because they
//     were admitted while the webhook was unavailable with failurePolicy Ignore, or existed before injection
//     was enabled (FR1, NFR reliability, rpe_injection_coverage_ratio);
//   - per proxy: the epochs it holds with the content hash it computed, in-flight requests per epoch and its
//     mixed-policy count (FR8 version consistency);
//   - drift: a proxy that does not hold an approved active policy with the approved content. The periodic loop
//     repairs drift by re-delivering the approved version (it never changes a policy, FR10).

const (
	injectionLabel   = "rpe-injection"
	annotationInject = "resilience.rpe.dev/inject"
	annotationDone   = "resilience.rpe.dev/injected"
)

type heldEpoch struct {
	Epoch    string `json:"epoch"`
	Version  int    `json:"version"`
	Hash     string `json:"hash"`
	State    string `json:"state"`
	Inflight int64  `json:"inflight"`
}

type fleetPod struct {
	Namespace   string      `json:"namespace"`
	Pod         string      `json:"pod"`
	Service     string      `json:"service"`
	Role        string      `json:"role"`
	Phase       string      `json:"phase"`
	CreatedAt   string      `json:"createdAt"`
	Owner       string      `json:"owner,omitempty"`
	Sidecar     bool        `json:"sidecar"`
	Injected    bool        `json:"injected"`
	OptedOut    bool        `json:"optedOut"`
	Gap         string      `json:"gap,omitempty"`
	ActiveEpoch string      `json:"activeEpoch,omitempty"`
	Epochs      []heldEpoch `json:"epochs,omitempty"`
	Mismatched  int64       `json:"mismatched"`
	Drift       []string    `json:"drift,omitempty"`
	Error       string      `json:"error,omitempty"`
	ip          string
	expected    []Policy
}

type fleetNamespace struct {
	Name     string `json:"name"`
	Eligible int    `json:"eligible"`
	Injected int    `json:"injected"`
	Gaps     int    `json:"gaps"`
}

type expectedPolicy struct {
	PolicyID  string `json:"policyId"`
	Epoch     string `json:"epoch"`
	Service   string `json:"service"`
	Hash      string `json:"hash"`
	DriftPods int    `json:"driftPods"`
}

type fleetReport struct {
	AuditedAt  string           `json:"auditedAt"`
	Namespaces []fleetNamespace `json:"namespaces"`
	Coverage   struct {
		Eligible int     `json:"eligible"`
		Injected int     `json:"injected"`
		Ratio    float64 `json:"ratio"`
	} `json:"coverage"`
	Gaps      []fleetPod       `json:"gaps"`
	Pods      []fleetPod       `json:"pods"`
	Policies  []expectedPolicy `json:"policies"`
	DriftPods int              `json:"driftPods"`
}

type fleet struct {
	reader client.Reader // uncached: pods and namespaces across the cluster
	writer client.Client
	store  *store
	side   *sidecars
	bus    *bus
	ns     string // the Operator's namespace (RPEPolicy mirrors)

	mu   sync.Mutex
	gaps map[string]bool // gap pods already reported as events
}

// audit builds the fleet report; it reads every proxy's control API but changes nothing.
func (f *fleet) audit(ctx context.Context) (fleetReport, error) {
	var rep fleetReport
	rep.AuditedAt = nowNano()
	var nss corev1.NamespaceList
	if err := f.reader.List(ctx, &nss, client.MatchingLabelsSelector{Selector: labels.SelectorFromSet(labels.Set{injectionLabel: "enabled"})}); err != nil {
		return rep, err
	}
	active, err := f.store.active(ctx)
	if err != nil {
		return rep, err
	}
	busy, _ := f.store.rollingOut(ctx)
	byService := map[string][]Policy{}
	var all []Policy
	for _, p := range active {
		if busy[p.PolicyID] { // a rollout of this policy is moving proxies right now
			continue
		}
		// a joint policy is expected on each of its services with the part for that service
		for _, part := range p.projections() {
			byService[part.Scope.Service] = append(byService[part.Scope.Service], part)
			all = append(all, part)
		}
	}
	// A proxy must hold the active policies scoped to its own service, and only those: the gateway holds the
	// gateway-scoped (chain deadline) policies, a service holds its rate limit and retry ratio.
	expectedFor := func(fp *fleetPod) []Policy {
		exp := byService[fp.Service]
		if fp.Role == "gateway" && fp.Service != gatewayService {
			exp = append(append([]Policy{}, exp...), byService[gatewayService]...)
		}
		return exp
	}
	var pods []*fleetPod
	for _, ns := range nss.Items {
		var list corev1.PodList
		if err := f.reader.List(ctx, &list, client.InNamespace(ns.Name)); err != nil {
			return rep, err
		}
		row := fleetNamespace{Name: ns.Name}
		for _, p := range list.Items {
			if p.DeletionTimestamp != nil || p.Status.Phase == corev1.PodSucceeded || p.Status.Phase == corev1.PodFailed {
				continue
			}
			fp := describe(p)
			if !fp.OptedOut {
				row.Eligible++
				if fp.Sidecar {
					row.Injected++
				} else {
					row.Gaps++
				}
			}
			if fp.Sidecar && p.Status.PodIP != "" && p.Status.Phase == corev1.PodRunning && ns.Name == f.demoNS() {
				fp.ip = p.Status.PodIP
				fp.expected = expectedFor(fp)
			}
			pods = append(pods, fp)
		}
		rep.Namespaces = append(rep.Namespaces, row)
		rep.Coverage.Eligible += row.Eligible
		rep.Coverage.Injected += row.Injected
	}
	if rep.Coverage.Eligible > 0 {
		rep.Coverage.Ratio = float64(rep.Coverage.Injected) / float64(rep.Coverage.Eligible)
	} else {
		rep.Coverage.Ratio = 1
	}
	// read the proxies in parallel
	var wg sync.WaitGroup
	for _, fp := range pods {
		if fp.ip == "" {
			continue
		}
		wg.Add(1)
		go func(fp *fleetPod) {
			defer wg.Done()
			cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
			defer cancel()
			held, err := f.side.epochs(cctx, fp.ip)
			if err != nil {
				fp.Error = err.Error()
				return
			}
			fp.ActiveEpoch, fp.Mismatched = held.ActiveEpoch, held.Mismatched
			for _, e := range held.Epochs {
				fp.Epochs = append(fp.Epochs, heldEpoch{Epoch: e.Epoch, Version: e.Version, Hash: e.Hash, State: e.State, Inflight: held.Inflight[e.Epoch]})
			}
			fp.Drift = driftOf(held, fp.expected)
		}(fp)
	}
	wg.Wait()
	drift := map[string]int{}
	for _, fp := range pods {
		rep.Pods = append(rep.Pods, *fp)
		if fp.Gap != "" {
			rep.Gaps = append(rep.Gaps, *fp)
		}
		if len(fp.Drift) > 0 {
			rep.DriftPods++
			for _, d := range fp.Drift {
				drift[strings.Fields(d)[0]]++ // each reason starts with the epoch
			}
		}
	}
	for _, p := range all {
		rep.Policies = append(rep.Policies, expectedPolicy{PolicyID: p.PolicyID, Epoch: p.Epoch, Service: p.Scope.Service, Hash: specHash(p.Spec), DriftPods: drift[p.Epoch]})
	}
	sort.Slice(rep.Pods, func(i, j int) bool {
		if rep.Pods[i].Namespace != rep.Pods[j].Namespace {
			return rep.Pods[i].Namespace < rep.Pods[j].Namespace
		}
		return rep.Pods[i].Pod < rep.Pods[j].Pod
	})
	return rep, nil
}

func (f *fleet) demoNS() string { return env("DEMO_NAMESPACE", "rpe-demo") }

func describe(p corev1.Pod) *fleetPod {
	fp := &fleetPod{Namespace: p.Namespace, Pod: p.Name, Service: serviceOf(p), Role: "service", Phase: string(p.Status.Phase),
		CreatedAt: p.CreationTimestamp.UTC().Format(time.RFC3339), Sidecar: hasSidecar(p),
		Injected: p.Annotations[annotationDone] == "true", OptedOut: p.Annotations[annotationInject] == "disabled"}
	if p.Annotations["resilience.rpe.dev/role"] == "gateway" {
		fp.Role = "gateway"
	}
	if ref := metav1.GetControllerOf(&p); ref != nil {
		fp.Owner = ref.Kind + "/" + ref.Name
	}
	if !fp.OptedOut && !fp.Sidecar {
		fp.Gap = "running without the RPE sidecar: admitted without injection (webhook unavailable with failurePolicy Ignore, or created before injection was enabled)"
	}
	return fp
}

// driftOf lists how a proxy differs from the approved active policies it should hold: not held at all, held with
// other content (hash mismatch), or held but not active. Every reason starts with the epoch.
func driftOf(held sidecarEpochs, expected []Policy) []string {
	var out []string
	for _, p := range expected {
		switch h := held.hashOf(p.Epoch); {
		case h == "":
			out = append(out, p.Epoch+" missing")
		case h != specHash(p.Spec):
			out = append(out, fmt.Sprintf("%s hash mismatch (holds %s, approved %s)", p.Epoch, h, specHash(p.Spec)))
		case !held.holds(p.Epoch, "active"):
			out = append(out, p.Epoch+" not active")
		}
	}
	return out
}

// repair re-delivers the approved version to a drifted proxy: stage (verified by hash), then activate.
func (f *fleet) repair(ctx context.Context, fp fleetPod) {
	for _, p := range fp.expected {
		reason := ""
		for _, d := range fp.Drift {
			if strings.HasPrefix(d, p.Epoch+" ") {
				reason = strings.TrimPrefix(d, p.Epoch+" ")
			}
		}
		if reason == "" {
			continue
		}
		payload := stagedPolicy{Epoch: p.Epoch, Version: p.Version, Hash: specHash(p.Spec), State: "staged", Policy: p.Spec}
		held, err := f.side.put(ctx, fp.ip, payload)
		if err == nil {
			err = held.verify(p.Epoch, payload.Hash)
		}
		if err == nil {
			_, err = f.side.activate(ctx, fp.ip, p.Epoch)
		}
		if err != nil {
			log.Printf("drift repair of %s on %s failed: %v", p.Epoch, fp.Pod, err)
			continue
		}
		_ = f.store.recordDelivery(ctx, fp.Pod, fp.Service, p.Epoch, p.Version, payload.Hash, "active", "")
		f.bus.ack(ctx, p.Epoch, fp.Pod, "active")
		f.bus.emit(ctx, "drift-repaired", map[string]string{"pod": fp.Pod, "policyId": p.PolicyID, "epoch": p.Epoch, "reason": reason})
		driftRepairs.Inc()
		log.Printf("drift repaired: %s now enforces %s (%s)", fp.Pod, p.Epoch, reason)
	}
}

// run audits the fleet periodically: it updates the coverage metrics and the RPEPolicy drift counts, reports
// new coverage gaps as events and repairs drift.
func (f *fleet) run(ctx context.Context, interval time.Duration, repairDrift bool) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		rep, err := f.audit(ctx)
		if err != nil {
			log.Printf("fleet audit: %v", err)
			continue
		}
		coverageRatio.Set(rep.Coverage.Ratio)
		coverageGaps.Set(float64(len(rep.Gaps)))
		f.reportGaps(ctx, rep.Gaps)
		f.markDrift(ctx, rep.Policies)
		if repairDrift {
			for _, fp := range rep.Pods {
				if len(fp.Drift) > 0 && fp.ip != "" {
					f.repair(ctx, fp)
				}
			}
		}
	}
}

func (f *fleet) reportGaps(ctx context.Context, gaps []fleetPod) {
	f.mu.Lock()
	defer f.mu.Unlock()
	seen := map[string]bool{}
	for _, g := range gaps {
		key := g.Namespace + "/" + g.Pod
		seen[key] = true
		if !f.gaps[key] {
			f.bus.emit(ctx, "coverage-gap", map[string]string{"namespace": g.Namespace, "pod": g.Pod, "service": g.Service})
			log.Printf("coverage gap: %s runs without the RPE sidecar", key)
		}
	}
	f.gaps = seen
}

func (f *fleet) markDrift(ctx context.Context, policies []expectedPolicy) {
	for _, p := range policies {
		var cr RPEPolicy
		if err := f.writer.Get(ctx, client.ObjectKey{Namespace: f.ns, Name: crName(p.PolicyID)}, &cr); err != nil {
			continue
		}
		if cr.Status.DriftPods != p.DriftPods {
			cr.Status.DriftPods = p.DriftPods
			_ = f.writer.Status().Update(ctx, &cr)
		}
	}
}

var errNotGap = fmt.Errorf("pod is not a coverage gap")

// reinject recreates a coverage-gap pod through its controller, so the webhook admits it again with the sidecar.
func (f *fleet) reinject(ctx context.Context, namespace, name string) error {
	var ns corev1.Namespace
	if err := f.reader.Get(ctx, client.ObjectKey{Name: namespace}, &ns); err != nil {
		return err
	}
	var p corev1.Pod
	if err := f.reader.Get(ctx, client.ObjectKey{Namespace: namespace, Name: name}, &p); err != nil {
		return err
	}
	fp := describe(p)
	if ns.Labels[injectionLabel] != "enabled" || fp.Gap == "" {
		return errNotGap
	}
	if fp.Owner == "" {
		return fmt.Errorf("pod has no controller to recreate it")
	}
	return f.writer.Delete(ctx, &p)
}
