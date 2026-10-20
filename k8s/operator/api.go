package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

// api is the C3 policy API (contract A3.4): the only way policies enter the system.
type api struct {
	k       client.Client
	store   *store
	bus     *bus
	ns      string
	waiters *waiters
	fleet   *fleet
}

func (a *api) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok\n") })
	mux.HandleFunc("GET /api/k8s/policies", a.listPolicies)
	mux.HandleFunc("POST /api/k8s/policies", a.createPolicy)
	mux.HandleFunc("GET /api/k8s/policies/{id}/status", a.policyStatus)
	mux.HandleFunc("GET /api/k8s/rollouts", a.listRollouts)
	mux.HandleFunc("POST /api/k8s/rollouts", a.createRollout)
	mux.HandleFunc("GET /api/k8s/rollouts/{name}", a.getRollout)
	mux.HandleFunc("GET /api/k8s/effective", a.effective)
	mux.HandleFunc("GET /api/k8s/events", a.events)
	mux.HandleFunc("POST /api/k8s/acks", a.ack)
	mux.HandleFunc("GET /api/k8s/fleet", a.fleetReport)
	mux.HandleFunc("POST /api/k8s/fleet/reinject", a.reinject)
	return mux
}

type policyRequest struct {
	PolicyID         string          `json:"policyId"`
	Source           string          `json:"source"`
	RecommendationID string          `json:"recommendationId"`
	Scope            Scope           `json:"scope"`
	Spec             json.RawMessage `json:"spec"`
	// Parts makes a joint policy: one decision with settings for several services, delivered as one version.
	Parts    []Part `json:"parts"`
	Approval struct {
		ApprovedBy string `json:"approvedBy"`
		Decision   string `json:"decision"`
	} `json:"approval"`
}

var idPattern = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)

// createPolicy accepts only human-approved policies (C3 FR10) and waits for the store's change event.
func (a *api) createPolicy(w http.ResponseWriter, r *http.Request) {
	var in policyRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&in); err != nil {
		writeErr(w, 400, "invalid JSON body")
		return
	}
	if in.RecommendationID == "" {
		in.RecommendationID = r.Header.Get("Idempotency-Key")
	}
	if len(in.Parts) > 0 && in.Scope.Service == "" {
		in.Scope = in.Parts[0].Scope
	}
	switch {
	case !idPattern.MatchString(in.PolicyID):
		writeErr(w, 400, "policyId must be lowercase letters, digits and dashes")
	case in.Scope.Service == "":
		writeErr(w, 400, "scope.service is required")
	case in.Approval.Decision != "approved" || strings.TrimSpace(in.Approval.ApprovedBy) == "":
		writeErr(w, 422, "a human approval record (approval.decision=approved, approval.approvedBy) is required")
	case validateParts(in.Parts) != nil:
		writeErr(w, 400, validateParts(in.Parts).Error())
	default:
		if in.Source != "c4-recommendation" {
			in.Source = "admin"
		}
		if len(in.Spec) == 0 {
			in.Spec = json.RawMessage(`{}`)
		}
		in.Scope.Namespace = "rpe-demo"
		switch {
		case len(in.Parts) > 0:
			in.Spec = jointSpec(in.Parts)
		default:
			// A change to one service of a joint policy keeps the policy's other parts, so the new version is still
			// one decision delivered under one epoch.
			if latest, err := a.store.get(r.Context(), in.PolicyID, 0); err == nil && latest.joint() {
				in.Spec = jointSpec(withPart(latest.parts(), Part{Scope: Scope{Service: in.Scope.Service, Route: in.Scope.Route}, Spec: in.Spec}))
				in.Scope = latest.Scope
				in.Scope.Namespace = "rpe-demo"
			}
		}
		wait := a.waiters.expect()
		p, err := a.store.create(r.Context(), Policy{PolicyID: in.PolicyID, Scope: in.Scope, Spec: in.Spec, Source: in.Source,
			RecommendationID: in.RecommendationID, ApprovedBy: strings.TrimSpace(in.Approval.ApprovedBy)})
		if err != nil {
			a.waiters.cancel(wait)
			writeErr(w, 400, err.Error())
			return
		}
		notification := a.waiters.await(wait, p.Epoch, 3*time.Second)
		writeJSON(w, 201, map[string]any{"policy": p, "notification": notification})
	}
}

func (a *api) listPolicies(w http.ResponseWriter, r *http.Request) {
	ps, err := a.store.list(r.Context())
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	writeJSON(w, 200, ps)
}

type rolloutRequest struct {
	PolicyID    string `json:"policyId"`
	Version     int    `json:"version"`
	Mode        string `json:"mode"`
	RequestedBy string `json:"requestedBy"`
	SkewMs      int    `json:"skewMs"`
	Order       string `json:"order"`
}

func (a *api) createRollout(w http.ResponseWriter, r *http.Request) {
	var in rolloutRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&in); err != nil {
		writeErr(w, 400, "invalid JSON body")
		return
	}
	if in.Mode == "" {
		in.Mode = "two-phase"
	}
	if in.Mode != "two-phase" && in.Mode != "naive" {
		writeErr(w, 400, "mode must be two-phase or naive")
		return
	}
	if in.SkewMs < 0 || in.SkewMs > 10000 {
		writeErr(w, 400, "skewMs must be from 0 to 10000")
		return
	}
	switch {
	case in.Order != "" && in.Order != "services-first" && in.Order != "gateway-first":
		writeErr(w, 400, "order must be services-first or gateway-first")
		return
	case in.Order != "" && in.Mode != "naive":
		writeErr(w, 400, "an arrival order applies to a naive rollout only (two-phase decides the order itself)")
		return
	}
	p, err := a.store.get(r.Context(), in.PolicyID, in.Version)
	if err != nil {
		writeErr(w, 404, "policy version not found")
		return
	}
	ro := &RPERollout{
		TypeMeta:   metav1.TypeMeta{APIVersion: GroupVersion.String(), Kind: "RPERollout"},
		ObjectMeta: metav1.ObjectMeta{Name: rolloutName(p), Namespace: a.ns, Labels: map[string]string{"resilience.rpe.dev/policy": crName(p.PolicyID)}},
		Spec:       rolloutSpec(p, in.Mode, a.store.activeEpoch(r.Context(), p.PolicyID), in.RequestedBy),
	}
	ro.Spec.SkewMs, ro.Spec.Order = in.SkewMs, in.Order
	if err := a.k.Create(r.Context(), ro); err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	writeJSON(w, 201, ro)
}

func (a *api) listRollouts(w http.ResponseWriter, r *http.Request) {
	var list RPERolloutList
	if err := a.k.List(r.Context(), &list, client.InNamespace(a.ns)); err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	sort.Slice(list.Items, func(i, j int) bool { return list.Items[j].CreationTimestamp.Before(&list.Items[i].CreationTimestamp) })
	writeJSON(w, 200, list.Items)
}

func (a *api) getRollout(w http.ResponseWriter, r *http.Request) {
	var ro RPERollout
	if err := a.k.Get(r.Context(), client.ObjectKey{Namespace: a.ns, Name: r.PathValue("name")}, &ro); err != nil {
		writeErr(w, 404, "rollout not found")
		return
	}
	writeJSON(w, 200, ro)
}

func (a *api) effective(w http.ResponseWriter, r *http.Request) {
	eff, err := a.bus.effective(r.Context())
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	writeJSON(w, 200, eff)
}

func (a *api) events(w http.ResponseWriter, r *http.Request) {
	ev, err := a.bus.recent(r.Context(), 100)
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	writeJSON(w, 200, ev)
}

// ack lets a proxy confirm a delivery itself (sidecar-initiated acknowledgement, contract A3.5): the state and
// the content hash the proxy computed from the policy it holds.
func (a *api) ack(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Pod, Service, Epoch, Hash, State string
		Version                          int
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&in); err != nil || in.Pod == "" || in.Epoch == "" {
		writeErr(w, 400, "pod and epoch are required")
		return
	}
	in.State = nonEmpty(in.State, "active")
	if in.State != "staged" && in.State != "active" && in.State != "retired" {
		writeErr(w, 400, "state must be staged, active or retired")
		return
	}
	if err := a.store.confirm(r.Context(), in.Pod, in.Service, in.Epoch, in.Version, in.Hash, in.State); err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	a.bus.ack(r.Context(), in.Epoch, in.Pod, in.State)
	w.WriteHeader(http.StatusNoContent)
}

// policyStatus is desired vs observed for one policy (contract A3.4): its latest rollout and the drift count.
func (a *api) policyStatus(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	p, err := a.store.get(r.Context(), id, 0)
	if err != nil {
		writeErr(w, 404, "policy not found")
		return
	}
	out := map[string]any{"policyId": p.PolicyID, "version": p.Version, "epoch": p.Epoch, "status": p.Status,
		"activeEpoch": a.store.activeEpoch(r.Context(), id)}
	var list RPERolloutList
	if err := a.k.List(r.Context(), &list, client.InNamespace(a.ns)); err == nil {
		var latest *RPERollout
		for i := range list.Items {
			ro := &list.Items[i]
			if ro.Spec.PolicyID == id && (latest == nil || latest.CreationTimestamp.Before(&ro.CreationTimestamp)) {
				latest = ro
			}
		}
		if latest != nil {
			staged, active := 0, 0
			for _, ps := range latest.Status.Pods {
				switch ps.State {
				case "staged":
					staged++
				case "active":
					active++
				}
			}
			pct := 0
			if latest.Status.TargetPods > 0 {
				pct = active * 100 / latest.Status.TargetPods
			}
			out["rollout"], out["rolloutState"] = latest.Name, strings.ToLower(latest.Status.Phase)
			out["stagedPods"], out["activePods"], out["totalPods"], out["convergencePct"] = staged, active, latest.Status.TargetPods, pct
			out["mixedPolicyRequests"] = latest.Status.MixedPolicyRequests
		}
	}
	var cr RPEPolicy
	if err := a.k.Get(r.Context(), client.ObjectKey{Namespace: a.ns, Name: crName(id)}, &cr); err == nil {
		out["driftPods"] = cr.Status.DriftPods
	}
	writeJSON(w, 200, out)
}

func (a *api) fleetReport(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	rep, err := a.fleet.audit(ctx)
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	writeJSON(w, 200, rep)
}

// reinject recreates a coverage-gap pod so that admission injects the sidecar (an admin action).
func (a *api) reinject(w http.ResponseWriter, r *http.Request) {
	var in struct{ Namespace, Pod string }
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&in); err != nil || in.Namespace == "" || in.Pod == "" {
		writeErr(w, 400, "namespace and pod are required")
		return
	}
	switch err := a.fleet.reinject(r.Context(), in.Namespace, in.Pod); {
	case errors.Is(err, errNotGap):
		writeErr(w, 409, err.Error())
	case err != nil:
		writeErr(w, 502, err.Error())
	default:
		a.bus.emit(r.Context(), "coverage-reinjected", map[string]string{"namespace": in.Namespace, "pod": in.Pod})
		writeJSON(w, 202, map[string]string{"recreated": in.Namespace + "/" + in.Pod})
	}
}

// waiters correlates API inserts with the NOTIFY the listener receives, so the caller sees the
// event-driven change detection happen end to end.
type waiters struct {
	mu   sync.Mutex
	list map[chan string]struct{}
}

func newWaiters() *waiters { return &waiters{list: map[chan string]struct{}{}} }

func (w *waiters) expect() chan string {
	ch := make(chan string, 16)
	w.mu.Lock()
	w.list[ch] = struct{}{}
	w.mu.Unlock()
	return ch
}

func (w *waiters) cancel(ch chan string) {
	w.mu.Lock()
	delete(w.list, ch)
	w.mu.Unlock()
}

func (w *waiters) notify(payload string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	for ch := range w.list {
		select {
		case ch <- payload:
		default:
		}
	}
}

func (w *waiters) await(ch chan string, epoch string, timeout time.Duration) map[string]string {
	defer w.cancel(ch)
	deadline := time.After(timeout)
	for {
		select {
		case payload := <-ch:
			if strings.Contains(payload, `"`+epoch+`"`) {
				return map[string]string{"channel": "rpe_policy_approved", "payload": payload}
			}
		case <-deadline:
			return nil
		}
	}
}

// rolloutSpec is the rollout of policy version p replacing epoch previous (the active version of the policy).
func rolloutSpec(p Policy, mode, previous, requestedBy string) RolloutSpec {
	spec := RolloutSpec{PolicyID: p.PolicyID, Version: p.Version, Epoch: p.Epoch, Service: p.Scope.Service, Mode: mode,
		PreviousEpoch: previous, RequestedBy: requestedBy}
	if spec.PreviousEpoch == p.Epoch {
		spec.PreviousEpoch = ""
	}
	if p.joint() {
		spec.Services = p.services()
		spec.Service = spec.Services[0]
	}
	return spec
}

func crName(policyID string) string { return policyID }

func rolloutName(p Policy) string {
	b := make([]byte, 3)
	_, _ = rand.Read(b)
	name := p.Epoch + "-" + hex.EncodeToString(b)
	if len(name) > 63 {
		name = name[len(name)-63:]
	}
	return strings.Trim(name, "-")
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
