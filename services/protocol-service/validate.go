package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"reflect"
	"strconv"
	"time"

	conformance "github.com/J26-SE-336-Research/rpe/conformance-kit/go"
	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// Verdict is what one library made of a header value. rpe-java returns the same JSON, so the two can be compared
// field by field.
type Verdict struct {
	Impl      string            `json:"impl"`
	Valid     bool              `json:"valid"`
	Fields    map[string]string `json:"fields,omitempty"`
	Canonical string            `json:"canonical,omitempty"`
	Error     *ProtoErr         `json:"error,omitempty"`
	Failure   string            `json:"failure,omitempty"` // set when we couldn't reach that side
}

type ProtoErr struct {
	Code   string `json:"code"`
	Rule   string `json:"rule,omitempty"`
	Detail string `json:"detail,omitempty"`
}

func protoErr(err error) *ProtoErr {
	var pe *rpe.ProtocolError
	if errors.As(err, &pe) {
		return &ProtoErr{Code: string(pe.Code), Rule: pe.Rule, Detail: pe.Detail}
	}
	return &ProtoErr{Code: "ERROR", Detail: err.Error()}
}

var kinds = map[string]bool{"context": true, "result": true, "hop": true, "trail": true}

// validateGo decodes a value of the given kind with rpe-go.
func validateGo(kind, value string) Verdict {
	v := Verdict{Impl: "go"}
	fail := func(err error) Verdict { v.Error = protoErr(err); return v }
	if value == "" {
		// an empty result or hop header counts as missing, so an empty value can't be checked
		return fail(&rpe.ProtocolError{Code: rpe.CodeInvalidSyntax, Detail: "empty dictionary"})
	}
	switch kind {
	case "context":
		c, err := rpe.DecodeContext(value)
		if err != nil {
			return fail(err)
		}
		v.Fields = map[string]string{"v": fmt.Sprint(c.Version), "rid": c.RID, "iid": c.IID, "pid": c.PID, "ttl": fmt.Sprint(c.TTLMillis),
			"bud": fmt.Sprint(c.BudgetMillis), "rb": fmt.Sprint(c.RetryBudget), "cs": string(c.Cancel), "pe": c.PolicyEpoch, "rt": c.Route}
		for k, raw := range c.Unknown {
			v.Fields["+"+k] = raw
		}
		v.Canonical = c.Encode()
	case "result":
		h := http.Header{}
		h.Set(rpe.HeaderResult, value)
		r, _, err := rpe.ParseResult(h)
		if err != nil {
			return fail(err)
		}
		v.Fields = map[string]string{"v": fmt.Sprint(r.Version), "out": string(r.Outcome), "rsn": string(r.Reason), "term": strconv.FormatBool(r.Terminal),
			"bp": fmt.Sprint(r.BPLevel), "bpu": fmt.Sprint(r.BPUtil), "rbu": fmt.Sprint(r.BudgetUsed), "pv": r.PolicyEpoch,
			"late": strconv.FormatBool(r.Late), "kids": r.Children}
		v.Canonical = r.Encode()
	case "hop":
		h := http.Header{}
		h.Set(rpe.HeaderHop, value)
		hop, err := rpe.ParseHop(h)
		if err != nil {
			return fail(err)
		}
		v.Fields = map[string]string{"cb": hop.CallerToken, "att": fmt.Sprint(hop.Attempt)}
		v.Canonical = hop.Encode()
	case "trail":
		t, err := rpe.DecodeTrail(value)
		if err != nil {
			return fail(err)
		}
		v.Fields = map[string]string{"rv": fmt.Sprint(t.RegistryVersion), "inc": strconv.FormatBool(t.Incomplete), "entries": fmt.Sprint(len(t.Entries))}
		for i, e := range t.Entries {
			v.Fields[fmt.Sprintf("#%02d", i+1)] = fmt.Sprintf("svc %d, parent %d, %d µs, %c, consumed %d ms", e.Service, e.Parent, e.ElapsedMicros, e.Decision, e.ConsumedMs)
		}
		v.Canonical = t.Encode()
	}
	v.Valid = true
	return v
}

// javaClient calls the rpe-java validator that runs next to this service.
type javaClient struct {
	url    string
	client *http.Client
}

func (j javaClient) do(ctx context.Context, method, path string, body any, out any) error {
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, j.url+path, r)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := j.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("rpe-java: %s %s", resp.Status, bytes.TrimSpace(b))
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func (j javaClient) validate(ctx context.Context, kind, value string) Verdict {
	var v Verdict
	if err := j.do(ctx, http.MethodPost, "/validate", map[string]string{"kind": kind, "value": value}, &v); err != nil {
		return Verdict{Impl: "java", Failure: err.Error()}
	}
	v.Impl = "java"
	return v
}

// agree tells if two verdicts are the same: valid or not, error code and rule, fields and encoded value.
func agree(a, b Verdict) bool {
	if a.Failure != "" || b.Failure != "" || a.Valid != b.Valid || a.Canonical != b.Canonical {
		return false
	}
	if (a.Error == nil) != (b.Error == nil) || (a.Error != nil && (a.Error.Code != b.Error.Code || a.Error.Rule != b.Error.Rule)) {
		return false
	}
	return reflect.DeepEqual(a.Fields, b.Fields)
}

// Matrix holds every conformance case, run right now by both libraries.
type Matrix struct {
	At    time.Time    `json:"at"`
	Files []MatrixFile `json:"files"`
	Total int          `json:"total"`
	Go    int          `json:"goPassed"`
	Java  int          `json:"javaPassed"`
	// JavaError is set when rpe-java could not be reached.
	JavaError string `json:"javaError,omitempty"`
}

type MatrixFile struct {
	File        string       `json:"file"`
	Description string       `json:"description"`
	Cases       []MatrixCase `json:"cases"`
}

type MatrixCase struct {
	Name       string `json:"name"`
	Go         bool   `json:"go"`
	Java       *bool  `json:"java"`
	GoDetail   string `json:"goDetail,omitempty"`
	JavaDetail string `json:"javaDetail,omitempty"`
}

func conformanceMatrix(ctx context.Context, dir string, java javaClient) Matrix {
	m := Matrix{At: time.Now().UTC(), Files: []MatrixFile{}}
	var javaFiles []conformance.FileResult
	if err := java.do(ctx, http.MethodGet, "/conformance", nil, &javaFiles); err != nil {
		m.JavaError = err.Error()
	}
	javaCase := map[string]conformance.CaseResult{}
	for _, f := range javaFiles {
		for _, c := range f.Cases {
			javaCase[f.File+"\x00"+c.Name] = c
		}
	}
	for _, f := range conformance.RunAll(dir) {
		mf := MatrixFile{File: f.File, Description: f.Description, Cases: []MatrixCase{}}
		for _, c := range f.Cases {
			mc := MatrixCase{Name: c.Name, Go: c.Pass, GoDetail: c.Detail}
			if jc, ok := javaCase[f.File+"\x00"+c.Name]; ok {
				pass := jc.Pass
				mc.Java, mc.JavaDetail = &pass, jc.Detail
				if pass {
					m.Java++
				}
			}
			if c.Pass {
				m.Go++
			}
			m.Total++
			mf.Cases = append(mf.Cases, mc)
		}
		m.Files = append(m.Files, mf)
	}
	return m
}
