package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
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

// api is the policy API: the only way policies enter the system.
type api struct {
	k       client.Client
	store   *store
	bus     *bus
	ns      string
	waiters *waiters
}

func (a *api) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok\n") })
	mux.HandleFunc("GET /api/k8s/policies", a.listPolicies)
	mux.HandleFunc("POST /api/k8s/policies", a.createPolicy)
	mux.HandleFunc("GET /api/k8s/rollouts", a.listRollouts)
	mux.HandleFunc("POST /api/k8s/rollouts", a.createRollout)
	mux.HandleFunc("GET /api/k8s/rollouts/{name}", a.getRollout)
	mux.HandleFunc("GET /api/k8s/effective", a.effective)
	mux.HandleFunc("GET /api/k8s/events", a.events)
	mux.HandleFunc("POST /api/k8s/acks", a.ack)
	return mux
}

type policyRequest struct {
	PolicyID         string          `json:"policyId"`
	Source           string          `json:"source"`
	RecommendationID string          `json:"recommendationId"`
	Scope            Scope           `json:"scope"`
	Spec             json.RawMessage `json:"spec"`
	Approval         struct {
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
	switch {
	case !idPattern.MatchString(in.PolicyID):
		writeErr(w, 400, "policyId must be lowercase letters, digits and dashes")
	case in.Scope.Service == "":
		writeErr(w, 400, "scope.service is required")
	case in.Approval.Decision != "approved" || strings.TrimSpace(in.Approval.ApprovedBy) == "":
		writeErr(w, 422, "a human approval record (approval.decision=approved, approval.approvedBy) is required")
	default:
		if in.Source != "c4-recommendation" {
			in.Source = "admin"
		}
		if len(in.Spec) == 0 {
			in.Spec = json.RawMessage(`{}`)
		}
		in.Scope.Namespace = "rpe-demo"
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
	p, err := a.store.get(r.Context(), in.PolicyID, in.Version)
	if err != nil {
		writeErr(w, 404, "policy version not found")
		return
	}
	ro := &RPERollout{
		TypeMeta:   metav1.TypeMeta{APIVersion: GroupVersion.String(), Kind: "RPERollout"},
		ObjectMeta: metav1.ObjectMeta{Name: rolloutName(p), Namespace: a.ns, Labels: map[string]string{"resilience.rpe.dev/policy": crName(p.PolicyID)}},
		Spec: RolloutSpec{PolicyID: p.PolicyID, Version: p.Version, Epoch: p.Epoch, Service: p.Scope.Service, Mode: in.Mode,
			PreviousEpoch: a.store.activeEpoch(r.Context(), p.PolicyID), RequestedBy: in.RequestedBy},
	}
	if ro.Spec.PreviousEpoch == p.Epoch {
		ro.Spec.PreviousEpoch = ""
	}
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

// ack lets a proxy confirm a delivery itself (sidecar-initiated acknowledgement).
func (a *api) ack(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Pod, Service, Epoch, Hash, State string
		Version                          int
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&in); err != nil || in.Pod == "" || in.Epoch == "" {
		writeErr(w, 400, "pod and epoch are required")
		return
	}
	if err := a.store.recordDelivery(r.Context(), in.Pod, in.Service, in.Epoch, in.Version, in.Hash, nonEmpty(in.State, "active"), ""); err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	a.bus.ack(r.Context(), in.Epoch, in.Pod, nonEmpty(in.State, "active"))
	w.WriteHeader(http.StatusNoContent)
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
