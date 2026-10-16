package main

import (
	"errors"
	"fmt"
	"strings"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// demoResponse mirrors the demo-app response body (each child nests its own response).
type demoResponse struct {
	Service     string      `json:"service"`
	Context     string      `json:"context"`
	RemainingMs int64       `json:"remainingMs"`
	Outcome     string      `json:"outcome"`
	Children    []demoChild `json:"children"`
}

type demoChild struct {
	Name       string        `json:"name"`
	Status     int           `json:"status"`
	Outcome    string        `json:"outcome"`
	Terminal   bool          `json:"terminal"`
	Millis     int64         `json:"ms"`
	Error      string        `json:"error"`
	Downstream *demoResponse `json:"downstream"`
}

// Hop is one service visit in a request chain, with its decoded context and the protocol checks.
type Hop struct {
	ID       int               `json:"id"`
	Parent   int               `json:"parent"` // -1 for the root
	Depth    int               `json:"depth"`
	Service  string            `json:"service"`
	Name     string            `json:"name"` // name used by the caller
	Status   int               `json:"status"`
	Outcome  string            `json:"outcome"`
	Terminal bool              `json:"terminal"`
	Millis   int64             `json:"ms"`
	Context  string            `json:"context"`
	Fields   map[string]string `json:"fields,omitempty"`
	Checks   []Check           `json:"checks"`
}

type Check struct {
	Rule   string `json:"rule"`
	Pass   bool   `json:"pass"`
	Detail string `json:"detail"`
}

func contextFields(c rpe.Context) map[string]string {
	return map[string]string{
		"v": fmt.Sprint(c.Version), "rid": c.RID, "iid": c.IID, "pid": c.PID,
		"ttl": fmt.Sprint(c.TTLMillis), "bud": fmt.Sprint(c.BudgetMillis), "rb": fmt.Sprint(c.RetryBudget),
		"cs": string(c.Cancel), "pe": c.PolicyEpoch, "rt": c.Route,
	}
}

// flattenChain walks the nested demo response and evaluates the C1 rules for every parent-to-child step
// using the real rpe-go context guard, so the panel shows exactly what the library enforces.
func flattenChain(root *demoResponse) []Hop {
	var hops []Hop
	var walk func(r *demoResponse, name string, status int, outcome string, terminal bool, ms int64, parent, depth int, parentCtx *rpe.Context)
	walk = func(r *demoResponse, name string, status int, outcome string, terminal bool, ms int64, parent, depth int, parentCtx *rpe.Context) {
		h := Hop{ID: len(hops), Parent: parent, Depth: depth, Name: name, Status: status, Outcome: outcome, Terminal: terminal, Millis: ms, Checks: []Check{}}
		var ctx *rpe.Context
		if r != nil {
			h.Service, h.Context = r.Service, r.Context
			if r.Outcome != "" {
				h.Outcome = r.Outcome // the service's own outcome (e.g. partial), not the caller's view of the HTTP status
			}
			if r.Context != "" {
				if c, err := rpe.DecodeContext(r.Context); err == nil {
					ctx = &c
					h.Fields = contextFields(c)
				} else {
					h.Checks = append(h.Checks, Check{"decode", false, err.Error()})
				}
			}
		}
		switch {
		case ctx == nil && r != nil:
			h.Checks = append(h.Checks, Check{"unknown-edge", true, "No resilience context: an unmanaged hop upstream did not forward it"})
		case ctx != nil && parentCtx == nil:
			h.Checks = append(h.Checks, Check{"R-S01", ctx.PID == rpe.RootParentID, "Root context created at the gateway"})
		case ctx != nil:
			violations := rpe.ValidateContextTransition(*parentCtx, *ctx, 0)
			failed := map[string]string{}
			for _, v := range violations {
				var pe *rpe.ProtocolError
				if errors.As(v, &pe) {
					failed[pe.Rule] = pe.Detail
				}
			}
			for _, rule := range []struct{ id, ok string }{
				{"R-T01", "rid, bud, rt and pe unchanged"},
				{"R-T02", fmt.Sprintf("ttl %d ≤ parent %d", ctx.TTLMillis, parentCtx.TTLMillis)},
				{"R-T03", fmt.Sprintf("retry budget %d ≤ parent %d", ctx.RetryBudget, parentCtx.RetryBudget)},
				{"R-T06", "pid = parent iid, fresh iid"},
			} {
				if d, bad := failed[rule.id]; bad {
					h.Checks = append(h.Checks, Check{rule.id, false, d})
				} else {
					h.Checks = append(h.Checks, Check{rule.id, true, rule.ok})
				}
			}
		}
		hops = append(hops, h)
		id := h.ID
		if r == nil {
			return
		}
		var sum int64
		counted := 0
		for i := range r.Children {
			c := r.Children[i]
			if c.Downstream != nil && c.Downstream.Context != "" {
				if cc, err := rpe.DecodeContext(c.Downstream.Context); err == nil {
					sum += cc.RetryBudget
					counted++
				}
			}
			walk(c.Downstream, c.Name, c.Status, c.Outcome, c.Terminal, c.Millis, id, depth+1, ctx)
		}
		if ctx != nil && counted > 1 {
			hops[id].Checks = append(hops[id].Checks, Check{"R-F05", sum <= ctx.RetryBudget,
				fmt.Sprintf("children's retry budgets sum to %d ≤ %d (conserved across fan-out)", sum, ctx.RetryBudget)})
		}
	}
	walk(root, "client", 0, "", false, 0, -1, 0, nil)
	return hops
}

// resultFields decodes an X-RPE-Result header for display.
func resultFields(raw string) map[string]string {
	out := map[string]string{}
	for _, part := range strings.Split(raw, ",") {
		k, v, ok := strings.Cut(strings.TrimSpace(part), "=")
		if ok {
			out[k] = strings.Trim(v, `"`)
		}
	}
	return out
}
