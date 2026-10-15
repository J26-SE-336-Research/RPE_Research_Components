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

type PolicyStatus struct {
	ActiveEpoch string `json:"activeEpoch,omitempty"`
	Rollout     string `json:"rollout,omitempty"`
	Phase       string `json:"phase,omitempty"`
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
	Phase       string      `json:"phase,omitempty"`
	Message     string      `json:"message,omitempty"`
	TargetPods  int         `json:"targetPods,omitempty"`
	Staged      int         `json:"staged,omitempty"`
	Active      int         `json:"active,omitempty"`
	StartedAt   string      `json:"startedAt,omitempty"`
	CompletedAt string      `json:"completedAt,omitempty"`
	DrainUntil  string      `json:"drainUntil,omitempty"`
	Attempts    int         `json:"attempts,omitempty"`
	Pods        []PodStatus `json:"pods,omitempty"`
}

type PodStatus struct {
	Name    string `json:"name"`
	Service string `json:"service"`
	Role    string `json:"role"` // gateway | service
	State   string `json:"state"`
	Error   string `json:"error,omitempty"`
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
