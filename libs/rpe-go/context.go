package rpe

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"strings"
	"time"
)

// Header names of the RPE v1 header set (spec §2). Field semantics are stable;
// the wire encoding defaults to an RFC 8941 dictionary until the wire-format study decides.
const (
	HeaderContext = "X-RPE-Context" // end-to-end request context
	HeaderHop     = "X-RPE-Hop"     // hop-scoped, rewritten at every hop, never reaches the application
	HeaderTrail   = "X-RPE-Trail"   // optional in-band per-hop trail
	HeaderResult  = "X-RPE-Result"  // response context
)

// CancelState is the cancellation state carried in the context (R-C01: terminal once set).
type CancelState string

const (
	CancelNone    CancelState = "none"
	CancelRequest CancelState = "cancel"
	CancelExpired CancelState = "expired"
)

// Context is the end-to-end X-RPE-Context.
type Context struct {
	Version      int64
	RID          string // 32 lowercase hex, equals the W3C trace-id
	IID          string // 16 lowercase hex, this invocation / attempt
	PID          string // 16 lowercase hex, parent invocation (RootParentID at the gateway)
	TTLMillis    int64  // remaining deadline when the message was sent (relative, skew-tolerant)
	BudgetMillis int64  // original end-to-end budget (immutable)
	RetryBudget  int64  // retry budget remaining for this subtree
	Cancel       CancelState
	PolicyEpoch  string // policy epoch the request is pinned to (immutable)
	Route        string // route key "METHOD /template" (immutable)
	// Unknown holds members this implementation does not understand; they are forwarded unchanged (R-V01).
	Unknown map[string]string
}

// ErrNoContext reports that the request carries no X-RPE-Context.
var ErrNoContext = problem(CodeMissingHeader, HeaderContext, "header absent")

// ParseContext reads and validates X-RPE-Context from headers.
func ParseContext(h http.Header) (Context, error) {
	raw, err := single(h, HeaderContext, false)
	if err != nil {
		return Context{}, err
	}
	if raw == "" {
		return Context{}, ErrNoContext
	}
	return DecodeContext(raw)
}

// DecodeContext parses the serialized X-RPE-Context value.
func DecodeContext(raw string) (Context, error) {
	if len(raw) > MaxHeaderValueBytes {
		return Context{}, problem(CodeContextTooLarge, HeaderContext, "value exceeds 256 bytes")
	}
	members, err := parseDictionary(HeaderContext, raw)
	if err != nil {
		return Context{}, err
	}
	c := Context{Cancel: CancelNone}
	for _, m := range members {
		it := m.item
		switch m.key {
		case "v":
			c.Version, err = wantInt(m, 1, 1<<31)
		case "rid":
			c.RID, err = wantID(m, RequestIDLength, false)
		case "iid":
			c.IID, err = wantID(m, InvocationIDLength, false)
		case "pid":
			c.PID, err = wantID(m, InvocationIDLength, true)
		case "ttl":
			c.TTLMillis, err = wantInt(m, -MaxRequestDeadlineMillis, MaxRequestDeadlineMillis)
		case "bud":
			c.BudgetMillis, err = wantInt(m, MinRequestDeadlineMillis, MaxRequestDeadlineMillis)
		case "rb":
			c.RetryBudget, err = wantInt(m, MinRetryBudget, MaxRetryBudget)
		case "cs":
			if it.kind != 't' {
				err = problem(CodeInvalidSyntax, HeaderContext, "cs must be a token")
			} else {
				c.Cancel = CancelState(it.s)
				if c.Cancel != CancelNone && c.Cancel != CancelRequest && c.Cancel != CancelExpired {
					err = problem(CodeInvalidSyntax, HeaderContext, "cs must be none, cancel or expired")
				}
			}
		case "pe":
			c.PolicyEpoch, err = wantString(m, 1, MaxPolicyVersionBytes)
		case "rt":
			c.Route, err = wantString(m, MinRequestRouteBytes, MaxRequestRouteBytes)
		default:
			if c.Unknown == nil {
				c.Unknown = map[string]string{}
			}
			c.Unknown[m.key] = it.raw
		}
		if err != nil {
			return Context{}, err
		}
	}
	return c, c.Validate()
}

// Validate checks required members and version support (R-V02).
func (c Context) Validate() error {
	if c.Version == 0 || c.RID == "" || c.IID == "" || c.PID == "" || c.BudgetMillis == 0 || c.Route == "" {
		return problem(CodeMissingHeader, HeaderContext, "v, rid, iid, pid, bud and rt are required")
	}
	if c.Version != SupportedProtocolVersion {
		return ruleProblem("R-V02", CodeUnsupportedVersion, HeaderContext, "unsupported protocol version")
	}
	if c.TTLMillis > c.BudgetMillis {
		return ruleProblem("R-T02", CodeInvalidTransition, HeaderContext, "ttl exceeds the original budget")
	}
	return nil
}

// Encode serializes the context as an RFC 8941 dictionary.
func (c Context) Encode() string {
	var w dictWriter
	w.int("v", c.Version)
	w.str("rid", c.RID)
	w.str("iid", c.IID)
	w.str("pid", c.PID)
	w.int("ttl", c.TTLMillis)
	w.int("bud", c.BudgetMillis)
	w.int("rb", c.RetryBudget)
	cs := c.Cancel
	if cs == "" {
		cs = CancelNone
	}
	w.token("cs", string(cs))
	if c.PolicyEpoch != "" {
		w.str("pe", c.PolicyEpoch)
	}
	w.str("rt", c.Route)
	w.unknown(c.Unknown)
	return w.String()
}

// NewRoot creates the context at the gateway trust boundary (R-S01).
// traceID may be an existing W3C trace-id to bind rid to (trace binding); empty creates one.
func NewRoot(route string, budget time.Duration, retryBudget int64, epoch, traceID string) (Context, error) {
	budgetMs := budget.Milliseconds()
	rid := traceID
	if rid == "" {
		var err error
		if rid, err = randomID(16); err != nil {
			return Context{}, err
		}
	}
	iid, err := randomID(8)
	if err != nil {
		return Context{}, err
	}
	c := Context{Version: SupportedProtocolVersion, RID: rid, IID: iid, PID: RootParentID,
		TTLMillis: budgetMs, BudgetMillis: budgetMs, RetryBudget: retryBudget, Cancel: CancelNone,
		PolicyEpoch: epoch, Route: route}
	return c, c.Validate()
}

// Child derives the context for an outbound call: a fresh iid, pid set to the parent's iid,
// ttl reduced by the locally measured elapsed time (R-T02, R-D07) and the given retry-budget grant (R-T03).
func (c Context) Child(elapsed, reserve time.Duration, grant int64) (Context, error) {
	if grant < 0 || grant > c.RetryBudget {
		return Context{}, ruleProblem("R-T03", CodeInvalidTransition, HeaderContext, "child grant exceeds parent retry budget")
	}
	iid, err := randomID(8)
	if err != nil {
		return Context{}, err
	}
	child := c
	child.Unknown = cloneMap(c.Unknown)
	child.IID = iid
	child.PID = c.IID
	child.TTLMillis = c.TTLMillis - elapsed.Milliseconds() - reserve.Milliseconds()
	child.RetryBudget = grant
	return child, nil
}

// ValidateContextTransition is the context guard (R-T01..R-T06) for a parent → child step.
// elapsed is the time the parent spent locally before sending the child request.
func ValidateContextTransition(in, out Context, elapsed time.Duration) []error {
	var v []error
	if in.RID != out.RID || in.BudgetMillis != out.BudgetMillis || in.Route != out.Route || in.PolicyEpoch != out.PolicyEpoch {
		v = append(v, ruleProblem("R-T01", CodeInvalidTransition, HeaderContext, "rid, bud, rt and pe are immutable"))
	}
	if out.TTLMillis > in.TTLMillis-elapsed.Milliseconds() {
		v = append(v, ruleProblem("R-T02", CodeInvalidTransition, HeaderContext, "ttl must not increase"))
	}
	if out.RetryBudget > in.RetryBudget {
		v = append(v, ruleProblem("R-T03", CodeInvalidTransition, HeaderContext, "retry budget must not increase"))
	}
	if in.Cancel != CancelNone && out.Cancel != in.Cancel {
		v = append(v, ruleProblem("R-T04", CodeInvalidTransition, HeaderContext, "cancellation state must not be cleared"))
	}
	if out.Version < in.Version {
		v = append(v, ruleProblem("R-T05", CodeInvalidTransition, HeaderContext, "version must not downgrade"))
	}
	if out.PID != in.IID || out.IID == in.IID {
		v = append(v, ruleProblem("R-T06", CodeInvalidTransition, HeaderContext, "child pid must equal parent iid and iid must be fresh"))
	}
	return v
}

// Hop is the hop-scoped X-RPE-Hop header.
type Hop struct {
	CallerToken string // cb: backpressure caller token
	Attempt     int64  // att: attempt number, 1 = first attempt
}

func ParseHop(h http.Header) (Hop, error) {
	raw, err := single(h, HeaderHop, false)
	if err != nil || raw == "" {
		return Hop{}, err
	}
	members, err := parseDictionary(HeaderHop, raw)
	if err != nil {
		return Hop{}, err
	}
	var hop Hop
	for _, m := range members {
		switch m.key {
		case "cb":
			hop.CallerToken, err = wantString(m, 1, 64)
		case "att":
			hop.Attempt, err = wantInt(m, 1, 255)
		}
		if err != nil {
			return Hop{}, err
		}
	}
	return hop, nil
}

func (h Hop) Encode() string {
	var w dictWriter
	if h.CallerToken != "" {
		w.str("cb", h.CallerToken)
	}
	if h.Attempt > 0 {
		w.int("att", h.Attempt)
	}
	return w.String()
}

// StripHop removes hop-scoped headers before a request reaches the application (spec §2).
func StripHop(h http.Header) { h.Del(HeaderHop) }

// Result is the response context X-RPE-Result.
type Result struct {
	Version     int64
	Outcome     Outcome
	Reason      Reason
	Terminal    bool  // term: MUST NOT be retried (R-R02)
	BPLevel     int64 // 0..3
	BPUtil      int64 // 0..100
	BudgetUsed  int64 // rbu
	PolicyEpoch string
	Late        bool
	Children    string // kids annotation "name:outcome:ms;..."
}

func ParseResult(h http.Header) (Result, bool, error) {
	raw, err := single(h, HeaderResult, false)
	if err != nil || raw == "" {
		return Result{}, false, err
	}
	members, err := parseDictionary(HeaderResult, raw)
	if err != nil {
		return Result{}, true, err
	}
	r := Result{Version: 1, Outcome: OutcomeUnknown, Reason: ReasonNone}
	for _, m := range members {
		switch m.key {
		case "v":
			r.Version, err = wantInt(m, 1, 1<<31)
		case "out":
			r.Outcome = Outcome(m.item.s)
		case "rsn":
			r.Reason = Reason(m.item.s)
		case "term":
			r.Terminal = m.item.kind == 'b' && m.item.b
		case "bp":
			r.BPLevel, err = wantInt(m, MinBackpressureLevel, MaxBackpressureLevel)
		case "bpu":
			r.BPUtil, err = wantInt(m, 0, 100)
		case "rbu":
			r.BudgetUsed, err = wantInt(m, 0, MaxRetryBudget)
		case "pv":
			r.PolicyEpoch, err = wantString(m, 1, MaxPolicyVersionBytes)
		case "late":
			r.Late = m.item.kind == 'b' && m.item.b
		case "kids":
			r.Children, err = wantString(m, 0, 512)
		}
		if err != nil {
			return Result{}, true, err
		}
	}
	return r, true, nil
}

func (r Result) Encode() string {
	var w dictWriter
	w.int("v", max(r.Version, 1))
	w.token("out", string(r.Outcome))
	w.token("rsn", string(r.Reason))
	w.boolean("term", r.Terminal)
	w.int("bp", r.BPLevel)
	w.int("bpu", r.BPUtil)
	w.int("rbu", r.BudgetUsed)
	if r.PolicyEpoch != "" {
		w.str("pv", r.PolicyEpoch)
	}
	w.boolean("late", r.Late)
	if r.Children != "" {
		w.str("kids", r.Children)
	}
	return w.String()
}

// ReasonInsufficientTime is the R-D03 reason (not enough time left for this hop).
const ReasonInsufficientTime Reason = "insufficient-time"

func wantInt(m sfMember, lo, hi int64) (int64, error) {
	if m.item.kind != 'i' {
		return 0, problem(CodeInvalidSyntax, HeaderContext, m.key+" must be an integer")
	}
	if m.item.i < lo || m.item.i > hi {
		return 0, problem(CodeOutOfRange, HeaderContext, m.key+" out of range")
	}
	return m.item.i, nil
}

func wantString(m sfMember, minLen, maxLen int) (string, error) {
	if m.item.kind != 's' {
		return "", problem(CodeInvalidSyntax, HeaderContext, m.key+" must be a string")
	}
	if len(m.item.s) < minLen || len(m.item.s) > maxLen {
		return "", problem(CodeOutOfRange, HeaderContext, m.key+" length out of range")
	}
	return m.item.s, nil
}

func wantID(m sfMember, length int, allowZero bool) (string, error) {
	s, err := wantString(m, length, length)
	if err != nil {
		return "", err
	}
	return parseID(HeaderContext, s, length, allowZero)
}

func cloneMap(m map[string]string) map[string]string {
	if m == nil {
		return nil
	}
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

// TraceIDFromTraceparent extracts the trace-id of a W3C traceparent header, or "".
func TraceIDFromTraceparent(tp string) string {
	parts := strings.Split(tp, "-")
	if len(parts) != 4 || len(parts[1]) != 32 {
		return ""
	}
	if _, err := hex.DecodeString(parts[1]); err != nil || parts[1] == strings.Repeat("0", 32) {
		return ""
	}
	return strings.ToLower(parts[1])
}

// newTraceparent builds a W3C traceparent for rid with a fresh span id.
func newTraceparent(rid string) string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "00-" + rid + "-" + hex.EncodeToString(b) + "-01"
}
