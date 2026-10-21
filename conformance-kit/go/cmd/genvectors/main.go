// genvectors computes the expected outputs of the conformance cases with the reference implementation (rpe-go)
// and writes the vector files. Vectors are reviewed and committed; regenerate only when the spec changes:
//
//	go run ./cmd/genvectors ../vectors
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	conformance "github.com/J26-SE-336-Research/rpe/conformance-kit/go"
)

const (
	rid  = "4bf92f3577b34da6a3ce929d0e0e4736"
	iid  = "00f067aa0ba902b7"
	iid2 = "b7ad6b7169203331"
	root = "0000000000000000"
	base = `v=1, rid="` + rid + `", iid="` + iid + `", pid="` + root + `", ttl=287, bud=300, rb=3, cs=none, pe="e42", rt="POST /api/orders"`
)

func with(old, new string) string { return strings.Replace(base, old, new, 1) }

func parent() conformance.Fields {
	return conformance.Fields{V: 1, RID: rid, IID: iid, PID: root, TTL: 287, Bud: 300, RB: 3, CS: "none", PE: "e42", RT: "POST /api/orders"}
}

func child() conformance.Fields {
	c := parent()
	c.IID, c.PID, c.TTL, c.RB = iid2, iid, 262, 1
	return c
}

func main() {
	dir := "../vectors"
	if len(os.Args) > 1 {
		dir = os.Args[1]
	}
	write(dir, "context-decode.json", "X-RPE-Context decoding: field values, canonical re-encoding, and the error code (and rule) of invalid input.", decodeCases())
	write(dir, "context-encode.json", "X-RPE-Context canonical encoding from field values.", encodeCases())
	write(dir, "context-transition.json", "Context guard R-T01..R-T06: the rules a parent -> child step violates (empty when valid).", transitionCases())
	write(dir, "context-child.json", "Child derivation: ttl = parent ttl - elapsed - reserve, rb = grant, pid = parent iid; a grant above the parent budget violates R-T03.", childCases())
	write(dir, "budget-split.json", "Retry-budget split across fan-out children (R-F05): grants never exceed rb - reserve in parallel mode.", splitCases())
	write(dir, "result.json", "X-RPE-Result decoding (defaults v=1, out=unknown, rsn=none) and canonical encoding.", resultCases())
	write(dir, "hop.json", "X-RPE-Hop decoding and canonical encoding.", hopCases())
	write(dir, "traceparent.json", "Trace binding: the trace-id of a W3C traceparent, or empty when invalid.", traceparentCases())
	write(dir, "retry-gate.json", "Retry gate R-R02: whether one more attempt may be sent, and the reason when it may not. Checks run in a fixed order: cs, terminal, replay safety, deadline, time for backoff plus one attempt, retry budget, attempt limit.", retryCases())
	write(dir, "trail.json", "X-RPE-Trail decoding (verbose v1: svcId:parent:elapsedMicros:decision:consumedMs) and canonical encoding.", trailCases())
	write(dir, "trail-merge.json", "Response-side trail merge: a callee's subtree appended under its caller's entry, re-indexed and bounded (R-P02, R-P03).", trailMergeCases())
}

func decodeCases() []conformance.DecodeCase {
	in := [][2]string{
		{"canonical sample", base},
		{"without policy epoch", with(`, pe="e42"`, "")},
		{"unknown members are kept and sorted (R-V01)", base + `, zz="future", zy=7, zx`},
		{"whitespace around members", "  " + strings.ReplaceAll(base, ", ", " ,\t ") + " \t"},
		{"members in any order", `rt="POST /api/orders", cs=none, rb=3, bud=300, ttl=287, pid="` + root + `", iid="` + iid + `", rid="` + rid + `", v=1, pe="e42"`},
		{"cancel requested", with("cs=none", "cs=cancel")},
		{"expired state", with("cs=none", "cs=expired")},
		{"negative ttl decodes (rejected later by admission)", with("ttl=287", "ttl=-5")},
		{"ttl equal to budget", with("ttl=287", "ttl=300")},
		{"escaped quote in route", with(`rt="POST /api/orders"`, `rt="GET /a\"b\\c"`)},
		{"leading zeros in integers", with("ttl=287", "ttl=0287")},
		{"maximum retry budget", with("rb=3", "rb=255")},
		{"empty value", ""},
		{"only whitespace", " \t "},
		{"duplicate member", base + ", ttl=5"},
		{"unknown cancel state", with("cs=none", "cs=maybe")},
		{"cancel state as string", with("cs=none", `cs="none"`)},
		{"ttl above budget (R-T02)", with("ttl=287", "ttl=301")},
		{"uppercase hex id", with(rid, strings.ToUpper(rid))},
		{"non-hex id", with("4bf92f35", "XXXXXXXX")},
		{"all-zero request id", with(rid, strings.Repeat("0", 32))},
		{"all-zero invocation id", with(`iid="`+iid+`"`, `iid="`+root+`"`)},
		{"short request id", with(rid, rid[:31])},
		{"unsupported version (R-V02)", with("v=1", "v=2")},
		{"version zero", with("v=1", "v=0")},
		{"retry budget out of range", with("rb=3", "rb=256")},
		{"negative retry budget", with("rb=3", "rb=-1")},
		{"budget zero", with("bud=300", "bud=0")},
		{"budget above maximum", with("bud=300", "bud=600001")},
		{"empty policy epoch", with(`pe="e42"`, `pe=""`)},
		{"parameters are not supported", base + ";x=1"},
		{"trailing comma", base + ","},
		{"missing route", with(`, rt="POST /api/orders"`, "")},
		{"missing version", strings.TrimPrefix(base, "v=1, ")},
		{"unterminated string", with(`rt="POST /api/orders"`, `rt="POST`)},
		{"decimal value", with("ttl=287", "ttl=287.5")},
		{"integer with 16 digits", with("ttl=287", "ttl=1234567890123456")},
		{"integer as string", with("ttl=287", `ttl="287"`)},
		{"version as bare key", strings.Replace(base, "v=1", "v", 1)},
		{"uppercase key", strings.Replace(base, "v=1", "V=1", 1)},
		{"space before =", strings.Replace(base, "v=1", "v =1", 1)},
		{"missing value after =", with("ttl=287", "ttl=")},
		{"invalid escape", with(`rt="POST /api/orders"`, `rt="a\nb"`)},
		{"non-ASCII in string", with(`rt="POST /api/orders"`, "rt=\"POST /é\"")},
		{"missing comma", with(", bud=300", " bud=300")},
		{"value exceeds 256 bytes", base + `, zz="` + strings.Repeat("a", 120) + `"`},
	}
	out := make([]conformance.DecodeCase, 0, len(in))
	for _, c := range in {
		f, canonical, fail := conformance.Decode(c[1])
		out = append(out, conformance.DecodeCase{Name: c[0], Input: c[1], Expect: f, Canonical: canonical, Error: fail})
	}
	return out
}

func encodeCases() []conformance.EncodeCase {
	p := parent()
	noEpoch := parent()
	noEpoch.PE = ""
	unknown := parent()
	unknown.Unknown = map[string]string{"zz": `"future"`, "aa": "7"}
	escaped := parent()
	escaped.RT = `GET /a"b\c`
	expired := child()
	expired.CS, expired.TTL = "expired", -12
	in := []struct {
		name string
		f    conformance.Fields
	}{{"root context", p}, {"policy epoch omitted when empty", noEpoch}, {"unknown members sorted after known members", unknown},
		{"string escaping", escaped}, {"expired child", expired}}
	out := []conformance.EncodeCase{}
	for _, c := range in {
		out = append(out, conformance.EncodeCase{Name: c.name, Fields: c.f, Expect: conformance.ToContext(c.f).Encode()})
	}
	return out
}

func transitionCases() []conformance.TransitionCase {
	mk := func(name string, mut func(p, c *conformance.Fields), elapsed int64) conformance.TransitionCase {
		p, c := parent(), child()
		if mut != nil {
			mut(&p, &c)
		}
		return conformance.TransitionCase{Name: name, Parent: p, Child: c, ElapsedMs: elapsed, Violations: conformance.Transition(p, c, elapsed)}
	}
	return []conformance.TransitionCase{
		mk("valid child", nil, 20),
		mk("ttl exactly parent ttl minus elapsed", func(p, c *conformance.Fields) { c.TTL = 267 }, 20),
		mk("ttl increases (R-T02)", func(p, c *conformance.Fields) { c.TTL = 268 }, 20),
		mk("retry budget increases (R-T03)", func(p, c *conformance.Fields) { c.RB = 4 }, 20),
		mk("route changes (R-T01)", func(p, c *conformance.Fields) { c.RT = "GET /other" }, 20),
		mk("request id changes (R-T01)", func(p, c *conformance.Fields) { c.RID = "0af7651916cd43dd8448eb211c80319c" }, 20),
		mk("budget changes (R-T01)", func(p, c *conformance.Fields) { c.Bud = 400 }, 20),
		mk("policy epoch changes (R-T01)", func(p, c *conformance.Fields) { c.PE = "e43" }, 20),
		mk("cancellation cleared (R-T04)", func(p, c *conformance.Fields) { p.CS = "cancel"; c.CS = "none" }, 20),
		mk("cancellation kept", func(p, c *conformance.Fields) { p.CS = "cancel"; c.CS = "cancel" }, 20),
		mk("cancellation set by the child", func(p, c *conformance.Fields) { c.CS = "expired" }, 20),
		mk("version downgrade (R-T05)", func(p, c *conformance.Fields) { p.V = 2 }, 20),
		mk("pid is not the parent iid (R-T06)", func(p, c *conformance.Fields) { c.PID = iid2 }, 20),
		mk("iid reused (R-T06)", func(p, c *conformance.Fields) { c.IID = iid }, 20),
		mk("several violations", func(p, c *conformance.Fields) { c.TTL = 500; c.RB = 9; c.RT = "GET /other" }, 20),
	}
}

func i64(v int64) *int64 { return &v }

func childCases() []conformance.ChildCase {
	in := []struct {
		name                    string
		elapsed, reserve, grant int64
	}{{"elapsed and reserve are subtracted", 20, 5, 1}, {"whole budget granted", 0, 0, 3}, {"zero grant", 10, 0, 0},
		{"ttl may become negative", 300, 0, 1}, {"grant above parent budget (R-T03)", 0, 0, 4}, {"negative grant (R-T03)", 0, 0, -1}}
	out := []conformance.ChildCase{}
	for _, c := range in {
		k, fail := conformance.Child(parent(), c.elapsed, c.reserve, c.grant)
		cc := conformance.ChildCase{Name: c.name, Parent: parent(), ElapsedMs: c.elapsed, ReserveMs: c.reserve, Grant: c.grant, Error: fail}
		if fail == nil {
			cc.ExpectTTL, cc.ExpectRB = i64(k.TTLMillis), i64(k.RetryBudget)
		}
		out = append(out, cc)
	}
	return out
}

func splitCases() []conformance.SplitCase {
	in := []conformance.SplitCase{
		{Name: "remainder goes to the first children", Mode: "parallel", RB: 7, Reserve: 1, N: 4},
		{Name: "even split", Mode: "parallel", RB: 3, Reserve: 0, N: 3},
		{Name: "fewer tokens than children", Mode: "parallel", RB: 2, Reserve: 0, N: 3},
		{Name: "reserve above budget", Mode: "parallel", RB: 1, Reserve: 2, N: 2},
		{Name: "sequential children share the pool", Mode: "sequential", RB: 3, Reserve: 1, N: 2},
		{Name: "no children", Mode: "parallel", RB: 3, Reserve: 0, N: 0},
		{Name: "single child", Mode: "parallel", RB: 255, Reserve: 5, N: 1},
	}
	for i := range in {
		in[i].Expect = conformance.Split(in[i].Mode, in[i].RB, in[i].Reserve, in[i].N)
	}
	return in
}

func resultCases() []conformance.ResultCase {
	in := [][2]string{
		{"full result", `v=1, out=partial, rsn=deadline, term=?1, bp=2, bpu=78, rbu=1, pv="e42", late=?0, kids="inv:ok:80;loy:expired:90"`},
		{"defaults", "out=ok"},
		{"bare boolean is true", "out=rejected, rsn=overload, term"},
		{"unknown members are ignored", "out=ok, zz=1"},
		{"late completion", `out=ok, rsn=deadline, late=?1, pv="e7"`},
		{"escaped child annotation", `out=partial, kids="a\"b"`},
		{"backpressure level out of range", "out=ok, bp=4"},
		{"utilisation out of range", "out=ok, bpu=101"},
		{"duplicate member", "out=ok, out=failed"},
		{"syntax error", "out=ok,,"},
	}
	out := []conformance.ResultCase{}
	for _, c := range in {
		f, canonical, fail := conformance.Result(c[1])
		out = append(out, conformance.ResultCase{Name: c[0], Input: c[1], Expect: f, Canonical: canonical, Error: fail})
	}
	return out
}

func hopCases() []conformance.HopCase {
	in := [][2]string{
		{"caller token and attempt", `cb="c-7f3a", att=2`},
		{"attempt only", "att=1"},
		{"unknown members are ignored", `cb="t", zz=1`},
		{"attempt zero", "att=0"},
		{"attempt above 255", "att=256"},
		{"empty caller token", `cb=""`},
		{"caller token as token", "cb=abc"},
	}
	out := []conformance.HopCase{}
	for _, c := range in {
		cb, att, canonical, fail := conformance.Hop(c[1])
		out = append(out, conformance.HopCase{Name: c[0], Input: c[1], ExpectCB: cb, ExpectAtt: att, Canonical: canonical, Error: fail})
	}
	return out
}

func traceparentCases() []conformance.TraceparentCase {
	in := [][2]string{
		{"valid", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"},
		{"uppercase is normalised", "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"},
		{"all-zero trace id", "00-00000000000000000000000000000000-00f067aa0ba902b7-01"},
		{"non-hex trace id", "00-4bf92f3577b34da6a3ce929d0e0e47zz-00f067aa0ba902b7-01"},
		{"too few parts", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7"},
		{"short trace id", "00-4bf92f3577b34da6a3ce929d0e0e47-00f067aa0ba902b7-01"},
		{"empty", ""},
	}
	out := []conformance.TraceparentCase{}
	for _, c := range in {
		out = append(out, conformance.TraceparentCase{Name: c[0], Input: c[1], Expect: conformance.Traceparent(c[1])})
	}
	return out
}

func write[T any](dir, file, description string, cases []T) {
	b, err := json.MarshalIndent(conformance.Suite[T]{Description: description, Cases: cases}, "", "  ")
	if err != nil {
		panic(err)
	}
	if err := os.WriteFile(filepath.Join(dir, file), append(b, '\n'), 0o644); err != nil {
		panic(err)
	}
	fmt.Printf("%s: %d cases\n", file, len(cases))
}

func retryCases() []conformance.RetryCase {
	ok := conformance.RetryInput{Attempt: 1, MaxAttempts: 3, CS: "none", ReplaySafe: true, RemainingMs: 600, BackoffMs: 50, AttemptCostMs: 300, RB: 2}
	mk := func(name string, mut func(i *conformance.RetryInput)) conformance.RetryCase {
		in := ok
		if mut != nil {
			mut(&in)
		}
		return conformance.RetryCase{Name: name, Input: in, Expect: conformance.Retry(in)}
	}
	return []conformance.RetryCase{
		mk("retry allowed: time, budget and attempts left", nil),
		mk("exactly enough time for backoff plus one attempt", func(i *conformance.RetryInput) { i.RemainingMs = 350 }),
		mk("one millisecond short (insufficient-time)", func(i *conformance.RetryInput) { i.RemainingMs = 349 }),
		mk("expired chain (cs=expired)", func(i *conformance.RetryInput) { i.CS = "expired" }),
		mk("cancelled chain (cs=cancel)", func(i *conformance.RetryInput) { i.CS = "cancel" }),
		mk("expired chain wins over every other reason", func(i *conformance.RetryInput) {
			i.CS, i.Terminal, i.ReplaySafe, i.RemainingMs, i.RB = "expired", true, false, -5, 0
		}),
		mk("terminal response (term=?1)", func(i *conformance.RetryInput) { i.Terminal = true }),
		mk("not replay-safe", func(i *conformance.RetryInput) { i.ReplaySafe = false }),
		mk("no time left (ttl reached 0)", func(i *conformance.RetryInput) { i.RemainingMs = 0 }),
		mk("negative time left", func(i *conformance.RetryInput) { i.RemainingMs = -120 }),
		mk("retry budget used up", func(i *conformance.RetryInput) { i.RB = 0 }),
		mk("attempt limit reached", func(i *conformance.RetryInput) { i.Attempt = 3 }),
		mk("no attempt limit", func(i *conformance.RetryInput) { i.MaxAttempts, i.Attempt = 0, 7 }),
		mk("last unit of budget", func(i *conformance.RetryInput) { i.RB = 1 }),
		mk("zero backoff and cost", func(i *conformance.RetryInput) { i.BackoffMs, i.AttemptCostMs, i.RemainingMs = 0, 0, 1 }),
		mk("attempt 0 counts as the first", func(i *conformance.RetryInput) { i.Attempt = 0 }),
	}
}

func trailCases() []conformance.TrailCase {
	in := [][2]string{
		{"request side: path of active entries", `rv=1, inc=?0, h="1:0:-1:A:0;2:1:-1:A:3"`},
		{"response side: a finished subtree", `rv=7, inc=?0, h="2:0:41250:F:3;3:1:8100:F:5;4:1:38110:X:6;5:1:900:C:6"`},
		{"unknown hop marks the trail incomplete", `rv=1, inc=?1, h="2:0:5000:F:3;6:1:1200:U:4"`},
		{"every decision code", `rv=1, inc=?0, h="1:0:9:F:0;2:1:8:E:1;3:1:7:X:2;4:1:6:C:3;5:1:5:R:4;6:1:4:P:5;7:1:3:O:6;8:1:2:U:7;9:1:-1:A:8"`},
		{"no entries", "rv=3, inc=?1"},
		{"members in any order, unknown ignored", `h="1:0:5:F:0", zz=1, inc=?0, rv=2`},
		{"service id 0 (not registered)", `rv=1, inc=?1, h="0:0:5:U:0"`},
		{"late hop consumed more than the budget", `rv=1, inc=?0, h="1:0:5:X:1300"`},
		{"rv missing", `inc=?0, h="1:0:5:F:0"`},
		{"inc missing", `rv=1, h="1:0:5:F:0"`},
		{"rv zero", "rv=0, inc=?0"},
		{"inc not a boolean", "rv=1, inc=1"},
		{"h not a string", "rv=1, inc=?0, h=abc"},
		{"parent refers to itself", `rv=1, inc=?0, h="1:1:5:F:0"`},
		{"parent refers to a later entry", `rv=1, inc=?0, h="1:0:5:F:0;2:3:5:F:0;3:1:5:F:0"`},
		{"service id above 65535", `rv=1, inc=?0, h="65536:0:5:F:0"`},
		{"unknown decision code", `rv=1, inc=?0, h="1:0:5:Z:0"`},
		{"finished entry with elapsed -1", `rv=1, inc=?0, h="1:0:-1:F:0"`},
		{"active entry with an elapsed time", `rv=1, inc=?0, h="1:0:5:A:0"`},
		{"negative consumed time", `rv=1, inc=?0, h="1:0:5:F:-1"`},
		{"four fields", `rv=1, inc=?0, h="1:0:5:F"`},
		{"leading zero (noncanonical)", `rv=1, inc=?0, h="1:0:05:F:0"`},
		{"empty entry", `rv=1, inc=?0, h="1:0:5:F:0;"`},
		{"seventeen entries", `rv=1, inc=?0, h="` + strings.TrimSuffix(strings.Repeat("1:0:5:F:0;", 17), ";") + `"`},
		{"duplicate member", "rv=1, inc=?0, rv=2"},
	}
	out := []conformance.TrailCase{}
	for _, c := range in {
		f, canonical, fail := conformance.DecodeTrail(c[1])
		out = append(out, conformance.TrailCase{Name: c[0], Input: c[1], Expect: f, Canonical: canonical, Error: fail})
	}
	return out
}

func trailMergeCases() []conformance.TrailMergeCase {
	sixteen := `rv=1, inc=?0, h="` + strings.TrimSuffix(strings.Repeat("1:0:5:F:0;", 16), ";") + `"`
	in := []conformance.TrailMergeCase{
		{Name: "callee subtree under the caller", Base: `rv=1, inc=?0, h="2:0:900:F:3"`, Sub: `rv=1, inc=?0, h="3:0:400:F:5;4:1:100:F:6"`, Parent: 1},
		{Name: "second callee after the first", Base: `rv=1, inc=?0, h="2:0:900:F:3;3:1:400:F:5"`, Sub: `rv=1, inc=?0, h="4:0:300:F:5"`, Parent: 1},
		{Name: "incomplete subtree makes the result incomplete", Base: `rv=1, inc=?0, h="2:0:900:F:3"`, Sub: `rv=1, inc=?1, h="6:0:1200:U:4"`, Parent: 1},
		{Name: "another registry version is not merged", Base: `rv=1, inc=?0, h="2:0:900:F:3"`, Sub: `rv=2, inc=?0, h="3:0:400:F:5"`, Parent: 1},
		{Name: "entries beyond 16 are dropped with their descendants", Base: sixteen, Sub: `rv=1, inc=?0, h="3:0:400:F:5;4:1:100:F:6"`, Parent: 1},
		{Name: "appended as a new root", Base: `rv=1, inc=?0, h="2:0:900:F:3"`, Sub: `rv=1, inc=?0, h="3:0:400:F:5"`, Parent: 0},
	}
	for i := range in {
		in[i].Expect = conformance.MergeTrail(in[i].Base, in[i].Sub, in[i].Parent)
	}
	return in
}
