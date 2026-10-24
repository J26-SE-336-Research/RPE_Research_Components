package conformance

import (
	"encoding/json"
	"fmt"
	"reflect"
)

// CaseResult is the verdict of one vector for one implementation.
type CaseResult struct {
	Name   string `json:"name"`
	Pass   bool   `json:"pass"`
	Detail string `json:"detail,omitempty"` // what the implementation produced when it failed
}

// FileResult is the verdict of one vector file.
type FileResult struct {
	File        string       `json:"file"`
	Description string       `json:"description"`
	Cases       []CaseResult `json:"cases"`
	Error       string       `json:"error,omitempty"`
}

// Files lists the vector files in a stable order.
var Files = []string{"context-decode.json", "context-encode.json", "context-transition.json", "context-child.json",
	"budget-split.json", "result.json", "hop.json", "traceparent.json", "retry-gate.json", "trail.json", "trail-merge.json"}

func eqFailure(a, b *Failure) bool {
	if a == nil || b == nil {
		return a == b
	}
	return a.Code == b.Code && a.Rule == b.Rule
}

func show(v ...any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func run[T any](dir, file string, check func(T) (string, bool, string)) FileResult {
	s, err := Load[T](dir, file)
	if err != nil {
		return FileResult{File: file, Error: err.Error()}
	}
	r := FileResult{File: file, Description: s.Description, Cases: []CaseResult{}}
	for _, c := range s.Cases {
		name, ok, detail := check(c)
		cr := CaseResult{Name: name, Pass: ok}
		if !ok {
			cr.Detail = detail
		}
		r.Cases = append(r.Cases, cr)
	}
	return r
}

// RunAll evaluates every vector file in dir with rpe-go (the same checks as the Go test runner).
func RunAll(dir string) []FileResult {
	return []FileResult{
		run(dir, "context-decode.json", func(c DecodeCase) (string, bool, string) {
			f, canonical, fail := Decode(c.Input)
			return c.Name, eqFailure(fail, c.Error) && reflect.DeepEqual(f, c.Expect) && canonical == c.Canonical, show(f, canonical, fail)
		}),
		run(dir, "context-encode.json", func(c EncodeCase) (string, bool, string) {
			got := ToContext(c.Fields).Encode()
			return c.Name, got == c.Expect, got
		}),
		run(dir, "context-transition.json", func(c TransitionCase) (string, bool, string) {
			got := Transition(c.Parent, c.Child, c.ElapsedMs)
			return c.Name, reflect.DeepEqual(got, c.Violations), show(got)
		}),
		run(dir, "context-child.json", func(c ChildCase) (string, bool, string) {
			k, fail := Child(c.Parent, c.ElapsedMs, c.ReserveMs, c.Grant)
			if !eqFailure(fail, c.Error) {
				return c.Name, false, show(fail)
			}
			if fail != nil {
				return c.Name, true, ""
			}
			ok := k.TTLMillis == *c.ExpectTTL && k.RetryBudget == *c.ExpectRB && k.PID == c.Parent.IID && k.IID != c.Parent.IID
			return c.Name, ok, fmt.Sprintf("ttl=%d rb=%d", k.TTLMillis, k.RetryBudget)
		}),
		run(dir, "budget-split.json", func(c SplitCase) (string, bool, string) {
			got := Split(c.Mode, c.RB, c.Reserve, c.N)
			return c.Name, reflect.DeepEqual(got, c.Expect), show(got)
		}),
		run(dir, "result.json", func(c ResultCase) (string, bool, string) {
			f, canonical, fail := Result(c.Input)
			return c.Name, eqFailure(fail, c.Error) && reflect.DeepEqual(f, c.Expect) && canonical == c.Canonical, show(f, canonical, fail)
		}),
		run(dir, "hop.json", func(c HopCase) (string, bool, string) {
			cb, att, canonical, fail := Hop(c.Input)
			return c.Name, eqFailure(fail, c.Error) && cb == c.ExpectCB && att == c.ExpectAtt && canonical == c.Canonical, show(cb, att, canonical, fail)
		}),
		run(dir, "traceparent.json", func(c TraceparentCase) (string, bool, string) {
			got := Traceparent(c.Input)
			return c.Name, got == c.Expect, got
		}),
		run(dir, "retry-gate.json", func(c RetryCase) (string, bool, string) {
			got := Retry(c.Input)
			return c.Name, got == c.Expect, show(got)
		}),
		run(dir, "trail.json", func(c TrailCase) (string, bool, string) {
			f, canonical, fail := DecodeTrail(c.Input)
			return c.Name, eqFailure(fail, c.Error) && reflect.DeepEqual(f, c.Expect) && canonical == c.Canonical, show(f, canonical, fail)
		}),
		run(dir, "trail-merge.json", func(c TrailMergeCase) (string, bool, string) {
			got := MergeTrail(c.Base, c.Sub, c.Parent)
			return c.Name, got == c.Expect, got
		}),
	}
}
