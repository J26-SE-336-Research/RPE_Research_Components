// Package conformance loads the shared conformance cases and runs them with rpe-go. The Java tests run the same
// files, and both libraries have to pass all of them.
package conformance

import (
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// Fields is an X-RPE-Context written as plain JSON fields, so Go and Java can share it.
type Fields struct {
	V       int64             `json:"v"`
	RID     string            `json:"rid"`
	IID     string            `json:"iid"`
	PID     string            `json:"pid"`
	TTL     int64             `json:"ttl"`
	Bud     int64             `json:"bud"`
	RB      int64             `json:"rb"`
	CS      string            `json:"cs"`
	PE      string            `json:"pe"`
	RT      string            `json:"rt"`
	Unknown map[string]string `json:"unknown,omitempty"`
}

// Failure is an expected protocol error.
type Failure struct {
	Code string `json:"code"`
	Rule string `json:"rule,omitempty"`
}

type DecodeCase struct {
	Name      string   `json:"name"`
	Input     string   `json:"input"`
	Expect    *Fields  `json:"expect,omitempty"`
	Canonical string   `json:"canonical,omitempty"`
	Error     *Failure `json:"error,omitempty"`
}

type EncodeCase struct {
	Name   string `json:"name"`
	Fields Fields `json:"fields"`
	Expect string `json:"expect"`
}

type TransitionCase struct {
	Name       string   `json:"name"`
	Parent     Fields   `json:"parent"`
	Child      Fields   `json:"child"`
	ElapsedMs  int64    `json:"elapsedMs"`
	Violations []string `json:"violations"`
}

type ChildCase struct {
	Name      string   `json:"name"`
	Parent    Fields   `json:"parent"`
	ElapsedMs int64    `json:"elapsedMs"`
	ReserveMs int64    `json:"reserveMs"`
	Grant     int64    `json:"grant"`
	ExpectTTL *int64   `json:"expectTtl,omitempty"`
	ExpectRB  *int64   `json:"expectRb,omitempty"`
	Error     *Failure `json:"error,omitempty"`
}

type SplitCase struct {
	Name    string  `json:"name"`
	Mode    string  `json:"mode"`
	RB      int64   `json:"rb"`
	Reserve int64   `json:"reserve"`
	N       int     `json:"n"`
	Expect  []int64 `json:"expect"`
}

type ResultFields struct {
	V    int64  `json:"v"`
	Out  string `json:"out"`
	Rsn  string `json:"rsn"`
	Term bool   `json:"term"`
	BP   int64  `json:"bp"`
	BPU  int64  `json:"bpu"`
	RBU  int64  `json:"rbu"`
	PV   string `json:"pv"`
	Late bool   `json:"late"`
	Kids string `json:"kids"`
}

type ResultCase struct {
	Name      string        `json:"name"`
	Input     string        `json:"input"`
	Expect    *ResultFields `json:"expect,omitempty"`
	Canonical string        `json:"canonical,omitempty"`
	Error     *Failure      `json:"error,omitempty"`
}

type HopCase struct {
	Name      string   `json:"name"`
	Input     string   `json:"input"`
	ExpectCB  string   `json:"expectCb"`
	ExpectAtt int64    `json:"expectAtt"`
	Canonical string   `json:"canonical,omitempty"`
	Error     *Failure `json:"error,omitempty"`
}

// RetryInput is one retry gate check written as plain JSON fields.
type RetryInput struct {
	Attempt       int64  `json:"attempt"`
	MaxAttempts   int64  `json:"maxAttempts"`
	CS            string `json:"cs"`
	Terminal      bool   `json:"terminal"`
	ReplaySafe    bool   `json:"replaySafe"`
	RemainingMs   int64  `json:"remainingMs"`
	BackoffMs     int64  `json:"backoffMs"`
	AttemptCostMs int64  `json:"attemptCostMs"`
	RB            int64  `json:"rb"`
}

type RetryExpect struct {
	Retry       bool   `json:"retry"`
	Reason      string `json:"reason"`
	Rule        string `json:"rule"`
	NextAttempt int64  `json:"nextAttempt"`
	BudgetLeft  int64  `json:"budgetLeft"`
}

type RetryCase struct {
	Name   string      `json:"name"`
	Input  RetryInput  `json:"input"`
	Expect RetryExpect `json:"expect"`
}

// TrailEntryFields is one entry of an X-RPE-Trail.
type TrailEntryFields struct {
	Svc      int64  `json:"svc"`
	Parent   int    `json:"parent"`
	Elapsed  int64  `json:"elapsedMicros"`
	Decision string `json:"decision"`
	Consumed int64  `json:"consumedMs"`
}

type TrailFields struct {
	RV      int64              `json:"rv"`
	Inc     bool               `json:"inc"`
	Entries []TrailEntryFields `json:"entries"`
}

type TrailCase struct {
	Name      string       `json:"name"`
	Input     string       `json:"input"`
	Expect    *TrailFields `json:"expect,omitempty"`
	Canonical string       `json:"canonical,omitempty"`
	Error     *Failure     `json:"error,omitempty"`
}

// TrailMergeCase puts sub under entry parent of base, the way a response trail is merged.
type TrailMergeCase struct {
	Name   string `json:"name"`
	Base   string `json:"base"`
	Sub    string `json:"sub"`
	Parent int    `json:"parent"`
	Expect string `json:"expect"`
}

type TraceparentCase struct {
	Name   string `json:"name"`
	Input  string `json:"input"`
	Expect string `json:"expect"`
}

// Suite is one vector file.
type Suite[T any] struct {
	Description string `json:"description"`
	Cases       []T    `json:"cases"`
}

func Load[T any](dir, file string) (Suite[T], error) {
	var s Suite[T]
	b, err := os.ReadFile(filepath.Join(dir, file))
	if err != nil {
		return s, err
	}
	return s, json.Unmarshal(b, &s)
}

// running the cases with rpe-go

func ToContext(f Fields) rpe.Context {
	return rpe.Context{Version: f.V, RID: f.RID, IID: f.IID, PID: f.PID, TTLMillis: f.TTL, BudgetMillis: f.Bud,
		RetryBudget: f.RB, Cancel: rpe.CancelState(f.CS), PolicyEpoch: f.PE, Route: f.RT, Unknown: f.Unknown}
}

func FromContext(c rpe.Context) Fields {
	cs := string(c.Cancel)
	if cs == "" {
		cs = "none"
	}
	return Fields{V: c.Version, RID: c.RID, IID: c.IID, PID: c.PID, TTL: c.TTLMillis, Bud: c.BudgetMillis, RB: c.RetryBudget,
		CS: cs, PE: c.PolicyEpoch, RT: c.Route, Unknown: c.Unknown}
}

// Fail turns an rpe-go error into the form used in the case files.
func Fail(err error) *Failure {
	if err == nil {
		return nil
	}
	var pe *rpe.ProtocolError
	if errors.As(err, &pe) {
		return &Failure{Code: string(pe.Code), Rule: pe.Rule}
	}
	return &Failure{Code: "UNEXPECTED: " + err.Error()}
}

func Decode(in string) (*Fields, string, *Failure) {
	c, err := rpe.DecodeContext(in)
	if err != nil {
		return nil, "", Fail(err)
	}
	f := FromContext(c)
	return &f, c.Encode(), nil
}

func Transition(parent, child Fields, elapsedMs int64) []string {
	out := []string{}
	for _, e := range rpe.ValidateContextTransition(ToContext(parent), ToContext(child), time.Duration(elapsedMs)*time.Millisecond) {
		if f := Fail(e); f != nil {
			out = append(out, f.Rule)
		}
	}
	return out
}

func Child(p Fields, elapsedMs, reserveMs, grant int64) (rpe.Context, *Failure) {
	c, err := ToContext(p).Child(time.Duration(elapsedMs)*time.Millisecond, time.Duration(reserveMs)*time.Millisecond, grant)
	return c, Fail(err)
}

func Split(mode string, rb, reserve int64, n int) []int64 {
	out := rpe.SplitBudget(rpe.SplitMode(mode), rb, reserve, n)
	if out == nil {
		return []int64{}
	}
	return out
}

func Result(in string) (*ResultFields, string, *Failure) {
	h := http.Header{}
	h.Set(rpe.HeaderResult, in)
	r, _, err := rpe.ParseResult(h)
	if err != nil {
		return nil, "", Fail(err)
	}
	return &ResultFields{V: r.Version, Out: string(r.Outcome), Rsn: string(r.Reason), Term: r.Terminal, BP: r.BPLevel, BPU: r.BPUtil,
		RBU: r.BudgetUsed, PV: r.PolicyEpoch, Late: r.Late, Kids: r.Children}, r.Encode(), nil
}

func Hop(in string) (string, int64, string, *Failure) {
	h := http.Header{}
	h.Set(rpe.HeaderHop, in)
	hop, err := rpe.ParseHop(h)
	if err != nil {
		return "", 0, "", Fail(err)
	}
	return hop.CallerToken, hop.Attempt, hop.Encode(), nil
}

func Traceparent(in string) string { return rpe.TraceIDFromTraceparent(in) }

func Retry(in RetryInput) RetryExpect {
	v := rpe.DecideRetry(rpe.RetryCheck{Attempt: in.Attempt, MaxAttempts: in.MaxAttempts, Cancel: rpe.CancelState(in.CS),
		Terminal: in.Terminal, ReplaySafe: in.ReplaySafe, RemainingMs: in.RemainingMs, BackoffMs: in.BackoffMs,
		AttemptCostMs: in.AttemptCostMs, RetryBudget: in.RB})
	return RetryExpect{Retry: v.Retry, Reason: v.Reason, Rule: v.Rule, NextAttempt: v.NextAttempt, BudgetLeft: v.BudgetLeft}
}

func TrailToFields(t rpe.Trail) *TrailFields {
	f := &TrailFields{RV: t.RegistryVersion, Inc: t.Incomplete, Entries: []TrailEntryFields{}}
	for _, e := range t.Entries {
		f.Entries = append(f.Entries, TrailEntryFields{Svc: e.Service, Parent: e.Parent, Elapsed: e.ElapsedMicros,
			Decision: string(rune(e.Decision)), Consumed: e.ConsumedMs})
	}
	return f
}

func DecodeTrail(in string) (*TrailFields, string, *Failure) {
	t, err := rpe.DecodeTrail(in)
	if err != nil {
		return nil, "", Fail(err)
	}
	return TrailToFields(t), t.Encode(), nil
}

// MergeTrail returns base with sub added under parent, encoded. Returns "" if an input is broken.
func MergeTrail(base, sub string, parent int) string {
	b, err := rpe.DecodeTrail(base)
	if err != nil {
		return ""
	}
	s, err := rpe.DecodeTrail(sub)
	if err != nil {
		return ""
	}
	return b.Append(s, parent).Encode()
}
