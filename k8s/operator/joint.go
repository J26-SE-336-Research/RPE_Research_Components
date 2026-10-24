package main

import (
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// A joint policy is one approved decision that changes settings on several services, for example a longer chain
// deadline at the gateway and a lower rate at payment-svc. It has one version and one epoch, and the stored spec
// lists the parts:
//
//	{"parts": [{"scope": {"service": "gateway", "route": "GET /api/orders"}, "spec": {...}},
//	           {"scope": {"service": "payment-svc"}, "spec": {...}}]}
//
// A sidecar only receives the part for its own service, and the hash it reports is the hash of that part. Since all
// parts share the epoch, the gateway stamps the same version on every new request and the other sidecars can tell
// old requests from new ones.

// Part is the share of a joint policy one service enforces.
type Part struct {
	Scope Scope           `json:"scope"`
	Spec  json.RawMessage `json:"spec"`
}

// parts returns the parts of a joint policy, or the policy's own scope and spec as its single part.
func (p Policy) parts() []Part {
	var j struct {
		Parts []Part `json:"parts"`
	}
	if json.Unmarshal(p.Spec, &j) == nil && len(j.Parts) > 0 {
		return j.Parts
	}
	return []Part{{Scope: p.Scope, Spec: p.Spec}}
}

// joint reports whether the policy spans more than one service.
func (p Policy) joint() bool { return len(p.parts()) > 1 }

// services returns the services the policy's parts are scoped to, in the order of the parts.
func (p Policy) services() []string {
	var out []string
	for _, part := range p.parts() {
		out = append(out, part.Scope.Service)
	}
	return out
}

// forService is the policy as one service sees it: same id, version and epoch, but only that service's part.
// ok is false when the policy has no part for the service.
func (p Policy) forService(service string) (Policy, bool) {
	for _, part := range p.parts() {
		if part.Scope.Service == service {
			q := p
			q.Scope, q.Spec = part.Scope, part.Spec
			if q.Scope.Namespace == "" {
				q.Scope.Namespace = p.Scope.Namespace
			}
			return q, true
		}
	}
	return Policy{}, false
}

// projections is the policy split into one policy per part (each as its service sees it).
func (p Policy) projections() []Policy {
	var out []Policy
	for _, s := range p.services() {
		if q, ok := p.forService(s); ok {
			out = append(out, q)
		}
	}
	return out
}

// jointSpec builds the stored spec of a joint policy from its parts.
func jointSpec(parts []Part) json.RawMessage {
	b, _ := json.Marshal(map[string]any{"parts": parts})
	return b
}

// withPart replaces the part of part.Scope.Service, or adds it at the end. A change to one service keeps the other
// parts, so the new version is still one decision.
func withPart(parts []Part, part Part) []Part {
	out := make([]Part, 0, len(parts)+1)
	replaced := false
	for _, p := range parts {
		if p.Scope.Service == part.Scope.Service {
			out = append(out, part)
			replaced = true
			continue
		}
		out = append(out, p)
	}
	if !replaced {
		out = append(out, part)
	}
	return out
}

// mergeParts applies the changed parts on top of the previous version's parts. A joint decision only names the
// services it changes; the rest keep their part. Without this a service would drop out of the policy, stay on the old
// version, count every new request as mixed and no longer be checked by drift repair.
func mergeParts(previous, changed []Part) []Part {
	out := previous
	for _, part := range changed {
		out = withPart(out, part)
	}
	return out
}

// validateParts checks the parts of a joint policy: every part names a service, no service appears twice.
func validateParts(parts []Part) error {
	seen := map[string]bool{}
	for i, p := range parts {
		switch {
		case p.Scope.Service == "":
			return fmt.Errorf("parts[%d].scope.service is required", i)
		case seen[p.Scope.Service]:
			return fmt.Errorf("service %s appears in two parts; one part per service", p.Scope.Service)
		}
		seen[p.Scope.Service] = true
	}
	return nil
}

var epochVersion = regexp.MustCompile(`-v(\d+)$`)

// versionOf is the version in an epoch named <policyId>-v<version> (0 when it has none).
func versionOf(epoch string) int {
	m := epochVersion.FindStringSubmatch(epoch)
	if m == nil {
		return 0
	}
	n, _ := strconv.Atoi(m[1])
	return n
}

// Kinds of change for a part. Tightening means a stricter limit (shorter deadline, lower rate, lower retry ratio).
// Anything else loosens. A part with the same content as in the previous version is unchanged, for example the
// gateway's part when only a service's rate changes.
const (
	changeLoosen    = "loosen"
	changeTighten   = "tighten"
	changeUnchanged = "unchanged"
)

// PartPlan is how one part of a joint policy is activated: its kind, the step it belongs to (1 loosening, 2
// tightening) and the changes, in words.
type PartPlan struct {
	Service string   `json:"service"`
	Kind    string   `json:"kind"`
	Step    int      `json:"step"`
	Changes []string `json:"changes,omitempty"`
}

// limits are the settings a part's change is classified by: route deadlines, the rate and the retry ratio.
type limits struct {
	deadlines map[string]float64
	rate      *float64
	retry     *float64
}

func readLimits(spec json.RawMessage) limits {
	var v struct {
		DeadlineMs float64 `json:"deadlineMs"`
		Routes     map[string]struct {
			DeadlineMs float64 `json:"deadlineMs"`
		} `json:"routes"`
		LeakyBucket struct {
			RatePerSec *float64 `json:"ratePerSec"`
		} `json:"leakyBucket"`
		Retry struct {
			BudgetRatio *float64 `json:"budgetRatio"`
		} `json:"retry"`
	}
	_ = json.Unmarshal(spec, &v)
	l := limits{deadlines: map[string]float64{}, rate: v.LeakyBucket.RatePerSec, retry: v.Retry.BudgetRatio}
	for route, r := range v.Routes {
		if r.DeadlineMs > 0 {
			l.deadlines[route] = r.DeadlineMs
		}
	}
	if len(l.deadlines) == 0 && v.DeadlineMs > 0 {
		l.deadlines["deadline"] = v.DeadlineMs
	}
	return l
}

func num(f float64) string { return strconv.FormatFloat(f, 'f', -1, 64) }

// classify compares a part's new spec with the spec the same service had in the previous version (nil when it had
// none) and returns the kind of the change and the changes in words. A part that tightens anything is a tightening.
func classify(prev, next json.RawMessage) (string, []string) {
	before, after := readLimits(prev), readLimits(next)
	kind := changeLoosen
	var changes []string
	routes := make([]string, 0, len(after.deadlines))
	for r := range after.deadlines {
		routes = append(routes, r)
	}
	sort.Strings(routes)
	for _, r := range routes {
		was, had := before.deadlines[r]
		now := after.deadlines[r]
		switch {
		case !had:
			changes = append(changes, fmt.Sprintf("%s deadline %s ms", r, num(now)))
		case now < was:
			kind = changeTighten
			changes = append(changes, fmt.Sprintf("%s deadline %s → %s ms", r, num(was), num(now)))
		case now > was:
			changes = append(changes, fmt.Sprintf("%s deadline %s → %s ms", r, num(was), num(now)))
		}
	}
	compare := func(name, unit string, was, now *float64) {
		switch {
		case now == nil:
		case was == nil:
			changes = append(changes, fmt.Sprintf("%s %s%s", name, num(*now), unit))
		case *now < *was:
			kind = changeTighten
			changes = append(changes, fmt.Sprintf("%s %s → %s%s", name, num(*was), num(*now), unit))
		case *now > *was:
			changes = append(changes, fmt.Sprintf("%s %s → %s%s", name, num(*was), num(*now), unit))
		}
	}
	compare("rate", " requests/s", before.rate, after.rate)
	compare("retry ratio", "", before.retry, after.retry)
	return kind, changes
}

// planJoint decides the order for a joint policy compared with its previous version (prev is nil for the first
// one): loosening parts go first, tightening parts second. Between the two steps the Operator waits until no request
// stamped with the old version is still running. That way a request with the old, short deadline never ends up in a
// queue that has already been slowed down.
func planJoint(p Policy, prev *Policy) []PartPlan {
	before := map[string]json.RawMessage{}
	if prev != nil {
		for _, part := range prev.parts() {
			before[part.Scope.Service] = part.Spec
		}
	}
	return planParts(p, before)
}

// planParts orders the activation of a joint policy against the spec each of its services enforces before it
// (before[service], absent when the service enforces nothing yet).
func planParts(p Policy, specs map[string]json.RawMessage) []PartPlan {
	var plan []PartPlan
	for _, part := range p.parts() {
		before := specs[part.Scope.Service]
		kind, changes := classify(before, part.Spec)
		if before != nil && specHash(before) == specHash(part.Spec) {
			kind, changes = changeUnchanged, nil
		}
		step := 1
		if kind == changeTighten {
			step = 2
		}
		plan = append(plan, PartPlan{Service: part.Scope.Service, Kind: kind, Step: step, Changes: changes})
	}
	return plan
}

// governing returns what each service of policy p enforces before p: its part of the previous version, or else its
// part of another active policy (active is ordered by approval, so the last one wins). The second case is the first
// joint version over separate policies, for example a joint policy under the gateway's id while payment-svc still has
// its own rate policy. Payment's change has to be compared with that policy, not treated as a new setting.
// takenOver lists the other policies whose services are all parts of p, because p replaces them completely.
func governing(p Policy, prev *Policy, active []Policy) (before map[string]json.RawMessage, takenOver []Policy) {
	before = map[string]json.RawMessage{}
	if prev != nil {
		for _, part := range prev.parts() {
			before[part.Scope.Service] = part.Spec
		}
	}
	from := map[string]Policy{}
	for _, s := range p.services() {
		if _, ok := before[s]; ok {
			continue
		}
		for _, q := range active {
			if q.PolicyID == p.PolicyID {
				continue
			}
			if part, ok := q.forService(s); ok {
				before[s], from[s] = part.Spec, q
			}
		}
	}
	covered := map[string]bool{}
	for _, s := range p.services() {
		covered[s] = true
	}
	seen := map[string]bool{}
	for _, q := range from {
		if seen[q.Epoch] {
			continue
		}
		seen[q.Epoch] = true
		all := true
		for _, s := range q.services() {
			all = all && covered[s]
		}
		if all {
			takenOver = append(takenOver, q)
		}
	}
	sort.Slice(takenOver, func(i, j int) bool { return takenOver[i].Epoch < takenOver[j].Epoch })
	return before, takenOver
}

// stepOf is the activation step of a service in a plan (1 when the service has no part in it).
func stepOf(plan []PartPlan, service string) int {
	for _, pp := range plan {
		if pp.Service == service {
			return pp.Step
		}
	}
	return 1
}

// kindOf is the kind of a service's change in a plan ("" when the service has no part in it).
func kindOf(plan []PartPlan, service string) string {
	for _, pp := range plan {
		if pp.Service == service {
			return pp.Kind
		}
	}
	return ""
}

// describePlan is the plan in one line, for the rollout message.
func describePlan(plan []PartPlan) string {
	var first, second []string
	for _, pp := range plan {
		s := pp.Service
		if len(pp.Changes) > 0 {
			s += " (" + strings.Join(pp.Changes, ", ") + ")"
		}
		if pp.Step == 2 {
			second = append(second, s)
		} else {
			first = append(first, s)
		}
	}
	out := "loosen first: " + orNone(first)
	if len(second) > 0 {
		out += "; then, once the previous version has drained, tighten: " + strings.Join(second, ", ")
	}
	return out
}

func orNone(s []string) string {
	if len(s) == 0 {
		return "none"
	}
	return strings.Join(s, ", ")
}
