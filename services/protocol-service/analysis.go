package main

import (
	"math"
	"sort"
	"strings"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

var decisionLabel = map[rpe.TrailDecision]string{
	rpe.DecisionForward: "served", rpe.DecisionEarly: "refused before the application", rpe.DecisionExpired: "served late (deadline signalled)",
	rpe.DecisionCancelled: "skipped (chain cancelled)", rpe.DecisionRetry: "retry served", rpe.DecisionActive: "active",
	rpe.DecisionPartial: "partial", rpe.DecisionOverload: "shed (overload)", rpe.DecisionUnknown: "no RPE (unknown hop)",
}

// AttributedHop is one hop of a request and how much of the deadline it used.
type AttributedHop struct {
	Index     int     `json:"index"`
	Parent    int     `json:"parent"`
	Depth     int     `json:"depth"`
	Service   string  `json:"service"`
	ServiceID int64   `json:"serviceId"`
	Decision  string  `json:"decision"`
	Label     string  `json:"label"`
	StartMs   float64 `json:"startMs"`   // budget already used when the hop got the request
	ElapsedMs float64 `json:"elapsedMs"` // time spent at the hop, callees included
	SelfMs    float64 `json:"selfMs"`    // time spent at the hop itself, without its callees
	Unknown   bool    `json:"unknown"`
}

// Attribution shows where one request spent its deadline, hop by hop.
type Attribution struct {
	RID        string          `json:"rid"`
	Route      string          `json:"route"`
	BudgetMs   int64           `json:"budgetMs"`
	Epoch      string          `json:"epoch,omitempty"`
	Status     int             `json:"status"`
	At         time.Time       `json:"at"`
	Trail      string          `json:"trail"`
	Bytes      int             `json:"bytes"`
	Incomplete bool            `json:"incomplete"`
	TotalMs    float64         `json:"totalMs"`
	Late       bool            `json:"late"`
	Bottleneck int             `json:"bottleneck"` // the hop that used the most time itself
	Hops       []AttributedHop `json:"hops"`
}

func round1(v float64) float64 { return math.Round(v*10) / 10 }

func attribute(s *stored, reg *registry) Attribution {
	a := Attribution{RID: s.rec.RID, Route: s.rec.Route, BudgetMs: s.rec.BudgetMs, Epoch: s.rec.Epoch, Status: s.rec.Status,
		At: s.rec.At, Trail: s.rec.Trail, Bytes: s.bytes, Incomplete: s.trail.Incomplete, Hops: []AttributedHop{}}
	entries := s.trail.Entries
	depth := make([]int, len(entries)+1)
	for i, e := range entries {
		idx := i + 1
		if e.Parent > 0 {
			depth[idx] = depth[e.Parent] + 1
		}
		h := AttributedHop{Index: idx, Parent: e.Parent, Depth: depth[idx], ServiceID: e.Service, Service: reg.name(e.Service),
			Decision: string(rune(e.Decision)), Label: decisionLabel[e.Decision], StartMs: float64(e.ConsumedMs),
			ElapsedMs: float64(max(e.ElapsedMicros, 0)) / 1000, Unknown: e.Decision == rpe.DecisionUnknown}
		a.Hops = append(a.Hops, h)
	}
	// own time is the elapsed time minus the time covered by its callees
	for i := range a.Hops {
		h := &a.Hops[i]
		start, end := h.StartMs, h.StartMs+h.ElapsedMs
		var spans [][2]float64
		for _, c := range a.Hops {
			if c.Parent == h.Index {
				spans = append(spans, [2]float64{max(c.StartMs, start), min(c.StartMs+c.ElapsedMs, end)})
			}
		}
		sort.Slice(spans, func(x, y int) bool { return spans[x][0] < spans[y][0] })
		covered, cur := 0.0, [2]float64{-1, -1}
		for _, sp := range spans {
			if sp[1] <= sp[0] {
				continue
			}
			if sp[0] > cur[1] {
				if cur[1] > cur[0] {
					covered += cur[1] - cur[0]
				}
				cur = sp
			} else if sp[1] > cur[1] {
				cur[1] = sp[1]
			}
		}
		if cur[1] > cur[0] {
			covered += cur[1] - cur[0]
		}
		h.SelfMs = round1(max(h.ElapsedMs-covered, 0))
		h.ElapsedMs = round1(h.ElapsedMs)
	}
	for i, h := range a.Hops {
		if a.Bottleneck == 0 || h.SelfMs > a.Hops[a.Bottleneck-1].SelfMs {
			a.Bottleneck = i + 1
		}
		if end := h.StartMs + h.ElapsedMs; end > a.TotalMs {
			a.TotalMs = round1(end)
		}
		if h.Decision == string(rune(rpe.DecisionExpired)) {
			a.Late = true
		}
	}
	if a.TotalMs > float64(a.BudgetMs) {
		a.Late = true
	}
	return a
}

// TrailSummary is one row in the list of recent trails.
type TrailSummary struct {
	RID        string    `json:"rid"`
	Route      string    `json:"route"`
	At         time.Time `json:"at"`
	Status     int       `json:"status"`
	Hops       int       `json:"hops"`
	Bytes      int       `json:"bytes"`
	Incomplete bool      `json:"incomplete"`
	Late       bool      `json:"late"`
	TotalMs    float64   `json:"totalMs"`
	BudgetMs   int64     `json:"budgetMs"`
	Bottleneck string    `json:"bottleneck"`
}

func summarize(s *stored, reg *registry) TrailSummary {
	a := attribute(s, reg)
	b := ""
	if a.Bottleneck > 0 {
		b = a.Hops[a.Bottleneck-1].Service
	}
	return TrailSummary{RID: a.RID, Route: a.Route, At: a.At, Status: a.Status, Hops: len(a.Hops), Bytes: a.Bytes,
		Incomplete: a.Incomplete, Late: a.Late, TotalMs: a.TotalMs, BudgetMs: a.BudgetMs, Bottleneck: b}
}

type NodeStats struct {
	Service   string         `json:"service"`
	ServiceID int64          `json:"serviceId"`
	Hops      int            `json:"hops"`
	P50Ms     float64        `json:"p50Ms"`
	P95Ms     float64        `json:"p95Ms"`
	SelfP50Ms float64        `json:"selfP50Ms"`
	Unknown   bool           `json:"unknown"`
	Decisions map[string]int `json:"decisions"`
}

type EdgeStats struct {
	From      string         `json:"from"`
	To        string         `json:"to"`
	Calls     int            `json:"calls"`
	P50Ms     float64        `json:"p50Ms"`
	P95Ms     float64        `json:"p95Ms"`
	Unknown   bool           `json:"unknown"`
	Decisions map[string]int `json:"decisions"`
}

// Topology is the service graph rebuilt only from the trails of a time window.
type Topology struct {
	WindowSec       int         `json:"windowSec"`
	Trails          int         `json:"trails"`
	Incomplete      int         `json:"incomplete"`
	AvgBytes        float64     `json:"avgBytes"`
	MaxBytes        int         `json:"maxBytes"`
	RegistryVersion int64       `json:"registryVersion"`
	Routes          []string    `json:"routes"`
	Nodes           []NodeStats `json:"nodes"`
	Edges           []EdgeStats `json:"edges"`
}

func percentile(v []float64, p float64) float64 {
	if len(v) == 0 {
		return 0
	}
	sort.Float64s(v)
	i := int(math.Ceil(p*float64(len(v)))) - 1
	return round1(v[max(i, 0)])
}

func rebuild(items []*stored, reg *registry, window time.Duration) Topology {
	version, _ := reg.snapshot()
	t := Topology{WindowSec: int(window.Seconds()), Trails: len(items), RegistryVersion: version, Nodes: []NodeStats{}, Edges: []EdgeStats{}, Routes: []string{}}
	type acc struct {
		lat, self []float64
		decisions map[string]int
		id        int64
		unknown   bool
	}
	nodes, edges := map[string]*acc{}, map[[2]string]*acc{}
	routes := map[string]bool{}
	bytes := 0
	for _, it := range items {
		bytes += it.bytes
		t.MaxBytes = max(t.MaxBytes, it.bytes)
		if it.trail.Incomplete {
			t.Incomplete++
		}
		routes[it.rec.Route] = true
		a := attribute(it, reg)
		for _, h := range a.Hops {
			n := nodes[h.Service]
			if n == nil {
				n = &acc{decisions: map[string]int{}, id: h.ServiceID}
				nodes[h.Service] = n
			}
			n.lat, n.self = append(n.lat, h.ElapsedMs), append(n.self, h.SelfMs)
			n.decisions[h.Decision]++
			n.unknown = n.unknown || h.Unknown
			if h.Parent == 0 {
				continue
			}
			key := [2]string{a.Hops[h.Parent-1].Service, h.Service}
			e := edges[key]
			if e == nil {
				e = &acc{decisions: map[string]int{}}
				edges[key] = e
			}
			e.lat = append(e.lat, h.ElapsedMs)
			e.decisions[h.Decision]++
			e.unknown = e.unknown || h.Unknown
		}
	}
	if len(items) > 0 {
		t.AvgBytes = round1(float64(bytes) / float64(len(items)))
	}
	for r := range routes {
		t.Routes = append(t.Routes, r)
	}
	sort.Strings(t.Routes)
	for name, n := range nodes {
		t.Nodes = append(t.Nodes, NodeStats{Service: name, ServiceID: n.id, Hops: len(n.lat), P50Ms: percentile(n.lat, 0.5),
			P95Ms: percentile(n.lat, 0.95), SelfP50Ms: percentile(n.self, 0.5), Unknown: n.unknown, Decisions: n.decisions})
	}
	sort.Slice(t.Nodes, func(i, j int) bool { return t.Nodes[i].ServiceID < t.Nodes[j].ServiceID })
	for k, e := range edges {
		t.Edges = append(t.Edges, EdgeStats{From: k[0], To: k[1], Calls: len(e.lat), P50Ms: percentile(e.lat, 0.5),
			P95Ms: percentile(e.lat, 0.95), Unknown: e.unknown, Decisions: e.decisions})
	}
	sort.Slice(t.Edges, func(i, j int) bool {
		if t.Edges[i].From != t.Edges[j].From {
			return t.Edges[i].From < t.Edges[j].From
		}
		return t.Edges[i].To < t.Edges[j].To
	})
	return t
}

// Comparison compares the rebuilt edges with the real call graph.
type Comparison struct {
	Truth     []string `json:"truth"`
	Rebuilt   []string `json:"rebuilt"`
	Matched   []string `json:"matched"`
	Missing   []string `json:"missing"`
	Extra     []string `json:"extra"`
	Precision float64  `json:"precision"`
	Recall    float64  `json:"recall"`
	Note      string   `json:"note"`
	// Source says where the real call graph came from, the deployments or GROUND_TRUTH.
	Source string `json:"source"`
}

func compare(t Topology, truth [][2]string, source string) Comparison {
	key := func(a, b string) string { return a + " → " + b }
	want := map[string]bool{}
	c := Comparison{Truth: []string{}, Rebuilt: []string{}, Matched: []string{}, Missing: []string{}, Extra: []string{}, Source: source}
	for _, e := range truth {
		want[key(e[0], e[1])] = true
		c.Truth = append(c.Truth, key(e[0], e[1]))
	}
	got := map[string]bool{}
	for _, e := range t.Edges {
		k := key(e.From, e.To)
		got[k] = true
		c.Rebuilt = append(c.Rebuilt, k)
		if want[k] {
			c.Matched = append(c.Matched, k)
		} else {
			c.Extra = append(c.Extra, k)
		}
	}
	for _, k := range c.Truth {
		if !got[k] {
			c.Missing = append(c.Missing, k)
		}
	}
	if len(c.Rebuilt) > 0 {
		c.Precision = round1(100 * float64(len(c.Matched)) / float64(len(c.Rebuilt)))
	}
	if len(c.Truth) > 0 {
		c.Recall = round1(100 * float64(len(c.Matched)) / float64(len(c.Truth)))
	}
	var unknown []string
	for _, e := range t.Edges {
		if e.Unknown {
			unknown = append(unknown, e.To)
		}
	}
	if len(unknown) > 0 {
		c.Note = "Calls below " + strings.Join(unknown, ", ") + " are invisible to the trail: that hop does not speak RPE, so the trail is marked incomplete (R-P05)."
	}
	return c
}

func parseTruth(spec string) [][2]string {
	var out [][2]string
	for _, e := range strings.Split(spec, ",") {
		if a, b, ok := strings.Cut(strings.TrimSpace(e), ">"); ok {
			out = append(out, [2]string{strings.TrimSpace(a), strings.TrimSpace(b)})
		}
	}
	return out
}
