package main

import (
	"encoding/json"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
)

// GroupVersion of the RPE custom resources (config/crd).
var GroupVersion = schema.GroupVersion{Group: "resilience.rpe.dev", Version: "v1alpha1"}

// RPEPolicy mirrors the latest approved version of a policy from the PostgreSQL store (the system of
// record) so that the cluster has a Kubernetes-native view of what should be enforced.
type RPEPolicy struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`
	Spec              PolicySpec   `json:"spec"`
	Status            PolicyStatus `json:"status,omitempty"`
}

type PolicySpec struct {
	PolicyID   string          `json:"policyId"`
	Version    int             `json:"version"`
	Epoch      string          `json:"epoch"`
	Service    string          `json:"service"`
	Route      string          `json:"route,omitempty"`
	Settings   json.RawMessage `json:"settings,omitempty"`
	ApprovedBy string          `json:"approvedBy"`
	ApprovedAt string          `json:"approvedAt"`
}

// PolicyStatus is desired vs observed: the epoch that should be enforced, what each proxy reported holding,
// the share of target proxies that confirmed it and how many proxies drifted from it.
type PolicyStatus struct {
	ActiveEpoch    string        `json:"activeEpoch,omitempty"`
	DesiredEpoch   string        `json:"desiredEpoch,omitempty"`
	Rollout        string        `json:"rollout,omitempty"`
	Phase          string        `json:"phase,omitempty"`
	ConvergencePct int           `json:"convergencePct,omitempty"`
	Observed       []ObservedPod `json:"observed,omitempty"`
	DriftPods      int           `json:"driftPods,omitempty"`
}

// ObservedPod is what one proxy reported holding: the epoch, the content hash the proxy computed, the state.
type ObservedPod struct {
	Pod   string `json:"pod"`
	Epoch string `json:"epoch"`
	Hash  string `json:"hash,omitempty"`
	State string `json:"state"`
}

type RPEPolicyList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []RPEPolicy `json:"items"`
}

// RPERollout drives one rollout of one approved policy version to the running proxies.
type RPERollout struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`
	Spec              RolloutSpec   `json:"spec"`
	Status            RolloutStatus `json:"status,omitempty"`
}

type RolloutSpec struct {
	PolicyID      string `json:"policyId"`
	Version       int    `json:"version"`
	Epoch         string `json:"epoch"`
	Service       string `json:"service"`
	Mode          string `json:"mode"` // two-phase | naive
	PreviousEpoch string `json:"previousEpoch,omitempty"`
	RequestedBy   string `json:"requestedBy,omitempty"`
	// SkewMs delays each delivery to a proxy by a random 0..SkewMs ms, modelling the propagation spread of a
	// real fleet. It applies equally to every mode, so naive and two-phase are compared under the same timing.
	SkewMs int `json:"skewMs,omitempty"`
}

// Rollout phases.
const (
	PhasePending    = "Pending"
	PhaseStaging    = "Staging"
	PhaseActivating = "Activating"
	PhaseDraining   = "Draining"
	PhaseComplete   = "Complete"
	PhaseFailed     = "Failed"
)

type RolloutStatus struct {
	Phase       string `json:"phase,omitempty"`
	Message     string `json:"message,omitempty"`
	TargetPods  int    `json:"targetPods,omitempty"`
	Staged      int    `json:"staged,omitempty"`
	Active      int    `json:"active,omitempty"`
	StartedAt   string `json:"startedAt,omitempty"`
	StagedAt    string `json:"stagedAt,omitempty"`
	ActivatedAt string `json:"activatedAt,omitempty"`
	CompletedAt string `json:"completedAt,omitempty"`
	// ConvergenceMs: start until every target proxy enforces the new version. DrainMs: until no request pinned
	// to the previous version is in flight (two-phase only).
	ConvergenceMs int64 `json:"convergenceMs,omitempty"`
	DrainMs       int64 `json:"drainMs,omitempty"`
	// InflightPrevious: requests pinned to the previous epoch still in flight (last drain check).
	InflightPrevious int64 `json:"inflightPrevious,omitempty"`
	// MixedPolicyRequests: requests that a target proxy served under another version of the policy than the epoch
	// they carry, counted by the proxies (rpe_policy_mismatch_total) since the rollout started.
	MixedPolicyRequests int64            `json:"mixedPolicyRequests"`
	MixedBaseline       map[string]int64 `json:"mixedBaseline,omitempty"`
	// Settled: the mixed-policy count and the proxies' acknowledgements were read once more after completion.
	Settled  bool        `json:"settled,omitempty"`
	Attempts int         `json:"attempts,omitempty"`
	Pods     []PodStatus `json:"pods,omitempty"`
}

type PodStatus struct {
	Name    string `json:"name"`
	Service string `json:"service"`
	Role    string `json:"role"` // gateway | service
	State   string `json:"state"`
	Error   string `json:"error,omitempty"`
	// Hash is the content hash the proxy reported for the new epoch; Verified when it equals the approved spec's.
	Hash     string `json:"hash,omitempty"`
	Verified bool   `json:"verified,omitempty"`
	// ConfirmedAt is when the proxy itself acknowledged the delivery (POST /api/k8s/acks).
	ConfirmedAt string `json:"confirmedAt,omitempty"`
}

type RPERolloutList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []RPERollout `json:"items"`
}

// DeepCopy via JSON keeps these small hand-written types correct without code generation.
func deepCopy[T any](in *T) *T {
	out := new(T)
	b, _ := json.Marshal(in)
	_ = json.Unmarshal(b, out)
	return out
}

func (in *RPEPolicy) DeepCopyObject() runtime.Object      { return deepCopy(in) }
func (in *RPEPolicyList) DeepCopyObject() runtime.Object  { return deepCopy(in) }
func (in *RPERollout) DeepCopyObject() runtime.Object     { return deepCopy(in) }
func (in *RPERolloutList) DeepCopyObject() runtime.Object { return deepCopy(in) }

func addKnownTypes(s *runtime.Scheme) error {
	s.AddKnownTypes(GroupVersion, &RPEPolicy{}, &RPEPolicyList{}, &RPERollout{}, &RPERolloutList{})
	metav1.AddToGroupVersion(s, GroupVersion)
	return nil
}
