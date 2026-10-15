package rpe

import (
	"net/http"
	"strconv"
	"strings"
)

var requestHeaders = []string{HeaderProtocolVersion, HeaderRequestID, HeaderInvocationID, HeaderParentInvocationID, HeaderDeadline, HeaderRequestDeadlineMillis, HeaderRequestRoute, HeaderRetryBudgetRemaining, HeaderDeadlineExceeded, HeaderCancellationTriggered}
var responseHeaders = []string{HeaderProtocolVersion, HeaderRequestID, HeaderInvocationID, HeaderDeadlineExceeded, HeaderCancellationTriggered, HeaderTerminalState, HeaderResponseOutcome, HeaderResponseReason, HeaderBackpressureLevel, HeaderPolicyVersion}
var projectHeaders = append(append(append([]string{}, requestHeaders...), responseHeaders...), HeaderRequestMethod, HeaderServiceHealth, HeaderTopologyTrail, HeaderTopologyRegistryVersion, HeaderTopologyIncomplete)

// single iterates raw map keys so differently cased duplicates cannot hide.
func single(h http.Header, name string, required bool) (string, error) {
	count := 0
	value := ""
	found := false
	for k, vs := range h {
		if strings.EqualFold(k, name) {
			found = true
			count += len(vs)
			if len(vs) > 0 {
				value = vs[0]
			}
		}
	}
	if count > 1 {
		return "", problem(CodeDuplicateHeader, name, "multiple values")
	}
	if count == 0 {
		if required || found {
			return "", problem(CodeMissingHeader, name, "header absent or has no value")
		}
		return "", nil
	}
	return value, nil
}
func checkEnvelope(h http.Header) error {
	total := 0
	for k, vs := range h {
		known := false
		for _, name := range projectHeaders {
			if strings.EqualFold(k, name) {
				known = true
				break
			}
		}
		if !known {
			continue
		}
		for _, v := range vs {
			n := len(k) + len(v) + 4
			if n > MaxProjectHeaderBytes-total {
				return problem(CodeContextTooLarge, "", "project headers exceed byte limit")
			}
			total += n
		}
	}
	return nil
}
func requiredValues(h http.Header, names []string) (map[string]string, error) {
	if e := checkEnvelope(h); e != nil {
		return nil, e
	}
	m := make(map[string]string, len(names))
	for _, n := range names {
		v, e := single(h, n, true)
		if e != nil {
			return nil, e
		}
		m[n] = v
	}
	return m, nil
}

// ParseRequest validates the required core only. It does not authenticate peers,
// look up route policy, or activate optional tracing/topology profiles.
func ParseRequest(h http.Header) (RequestContext, error) {
	var c RequestContext
	m, e := requiredValues(h, requestHeaders)
	if e != nil {
		return c, e
	}
	if c.ProtocolVersion, e = parseInteger(HeaderProtocolVersion, m[HeaderProtocolVersion], 0, 9223372036854775807); e != nil {
		return RequestContext{}, e
	}
	if c.ProtocolVersion != 1 {
		return RequestContext{}, problem(CodeUnsupportedVersion, HeaderProtocolVersion, "supported version is 1")
	}
	if c.RequestID, e = parseID(HeaderRequestID, m[HeaderRequestID], 32, false); e != nil {
		return RequestContext{}, e
	}
	if c.InvocationID, e = parseID(HeaderInvocationID, m[HeaderInvocationID], 16, false); e != nil {
		return RequestContext{}, e
	}
	if c.ParentInvocationID, e = parseID(HeaderParentInvocationID, m[HeaderParentInvocationID], 16, true); e != nil {
		return RequestContext{}, e
	}
	if c.DeadlineUnixMillis, e = parseInteger(HeaderDeadline, m[HeaderDeadline], 1, MaxDeadlineUnixMillis); e != nil {
		return RequestContext{}, e
	}
	if c.RequestDeadlineMillis, e = parseInteger(HeaderRequestDeadlineMillis, m[HeaderRequestDeadlineMillis], 1, MaxRequestDeadlineMillis); e != nil {
		return RequestContext{}, e
	}
	if c.RequestRoute, e = normalizeScalar(HeaderRequestRoute, m[HeaderRequestRoute]); e != nil {
		return RequestContext{}, e
	}
	if len(c.RequestRoute) > MaxRequestRouteBytes {
		return RequestContext{}, problem(CodeOutOfRange, HeaderRequestRoute, "route exceeds 128 bytes")
	}
	if c.RetryBudgetRemaining, e = parseInteger(HeaderRetryBudgetRemaining, m[HeaderRetryBudgetRemaining], 0, 255); e != nil {
		return RequestContext{}, e
	}
	if c.DeadlineExceeded, e = parseBoolean(HeaderDeadlineExceeded, m[HeaderDeadlineExceeded]); e != nil {
		return RequestContext{}, e
	}
	if c.CancellationTriggered, e = parseBoolean(HeaderCancellationTriggered, m[HeaderCancellationTriggered]); e != nil {
		return RequestContext{}, e
	}
	if c.DeadlineExceeded && !c.CancellationTriggered {
		return RequestContext{}, problem(CodeInvalidFlags, HeaderCancellationTriggered, "expiry requires cancellation")
	}
	return c, nil
}

// ValidateRoute must be called against trusted policy on strict routes.
func ValidateRoute(c RequestContext, known func(string) bool) error {
	if known == nil || !known(c.RequestRoute) {
		return problem(CodeUnknownRoute, HeaderRequestRoute, "route not in trusted registry")
	}
	return nil
}
func requestWire(c RequestContext) http.Header {
	h := make(http.Header)
	h.Set(HeaderProtocolVersion, strconv.FormatInt(c.ProtocolVersion, 10))
	h.Set(HeaderRequestID, c.RequestID)
	h.Set(HeaderInvocationID, c.InvocationID)
	h.Set(HeaderParentInvocationID, c.ParentInvocationID)
	h.Set(HeaderDeadline, strconv.FormatInt(c.DeadlineUnixMillis, 10))
	h.Set(HeaderRequestDeadlineMillis, strconv.FormatInt(c.RequestDeadlineMillis, 10))
	h.Set(HeaderRequestRoute, c.RequestRoute)
	h.Set(HeaderRetryBudgetRemaining, strconv.FormatInt(c.RetryBudgetRemaining, 10))
	h.Set(HeaderDeadlineExceeded, strconv.FormatBool(c.DeadlineExceeded))
	h.Set(HeaderCancellationTriggered, strconv.FormatBool(c.CancellationTriggered))
	return h
}
func SerializeRequest(c RequestContext) (http.Header, error) {
	h := requestWire(c)
	parsed, e := ParseRequest(h)
	if e != nil {
		return nil, e
	}
	if parsed != c {
		return nil, problem(CodeInvalidSyntax, "", "typed context must already be normalized")
	}
	if strings.Trim(c.RequestRoute, " \t") != c.RequestRoute {
		return nil, problem(CodeInvalidSyntax, HeaderRequestRoute, "typed route must already be normalized")
	}
	return h, nil
}
func ValidateRequest(c RequestContext) error { _, e := SerializeRequest(c); return e }
func validOutcome(o Outcome) bool {
	switch o {
	case OutcomeOK, OutcomePartial, OutcomeRejected, OutcomeStopped, OutcomeFailed, OutcomeUnknown:
		return true
	}
	return false
}
func validReason(r Reason) bool {
	switch r {
	case ReasonNone, ReasonDeadline, ReasonCancelled, ReasonOverload, ReasonCircuitOpen, ReasonInvalidContext, ReasonUnsupportedVersion, ReasonBusiness, ReasonTransport:
		return true
	}
	return false
}
func ParseResponse(h http.Header, expected RequestContext) (ResponseContext, error) {
	var c ResponseContext
	m, e := requiredValues(h, responseHeaders)
	if e != nil {
		return c, e
	}
	if c.ProtocolVersion, e = parseInteger(HeaderProtocolVersion, m[HeaderProtocolVersion], 0, 9223372036854775807); e != nil {
		return ResponseContext{}, e
	}
	if c.ProtocolVersion != 1 {
		return ResponseContext{}, problem(CodeUnsupportedVersion, HeaderProtocolVersion, "supported version is 1")
	}
	if c.RequestID, e = parseID(HeaderRequestID, m[HeaderRequestID], 32, false); e != nil {
		return ResponseContext{}, e
	}
	if c.InvocationID, e = parseID(HeaderInvocationID, m[HeaderInvocationID], 16, false); e != nil {
		return ResponseContext{}, e
	}
	if c.RequestID != expected.RequestID || c.InvocationID != expected.InvocationID {
		return ResponseContext{}, problem(CodeInvalidResponseContext, "", "response does not match attempt")
	}
	if c.DeadlineExceeded, e = parseBoolean(HeaderDeadlineExceeded, m[HeaderDeadlineExceeded]); e != nil {
		return ResponseContext{}, e
	}
	if c.CancellationTriggered, e = parseBoolean(HeaderCancellationTriggered, m[HeaderCancellationTriggered]); e != nil {
		return ResponseContext{}, e
	}
	if c.TerminalState, e = parseBoolean(HeaderTerminalState, m[HeaderTerminalState]); e != nil {
		return ResponseContext{}, e
	}
	o, e := normalizeScalar(HeaderResponseOutcome, m[HeaderResponseOutcome])
	if e != nil {
		return ResponseContext{}, e
	}
	c.Outcome = Outcome(o)
	if !validOutcome(c.Outcome) {
		return ResponseContext{}, problem(CodeInvalidSyntax, HeaderResponseOutcome, "unknown outcome")
	}
	r, e := normalizeScalar(HeaderResponseReason, m[HeaderResponseReason])
	if e != nil {
		return ResponseContext{}, e
	}
	c.Reason = Reason(r)
	if !validReason(c.Reason) {
		return ResponseContext{}, problem(CodeInvalidSyntax, HeaderResponseReason, "unknown reason")
	}
	if c.BackpressureLevel, e = parseInteger(HeaderBackpressureLevel, m[HeaderBackpressureLevel], 0, 3); e != nil {
		return ResponseContext{}, e
	}
	if c.PolicyVersion, e = normalizeScalar(HeaderPolicyVersion, m[HeaderPolicyVersion]); e != nil {
		return ResponseContext{}, e
	}
	if len(c.PolicyVersion) > 64 {
		return ResponseContext{}, problem(CodeOutOfRange, HeaderPolicyVersion, "version exceeds 64 bytes")
	}
	for _, v := range []byte(c.PolicyVersion) {
		if !((v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9') || v == '.' || v == '_' || v == '-') {
			return ResponseContext{}, problem(CodeInvalidSyntax, HeaderPolicyVersion, "invalid version identifier")
		}
	}
	if (c.DeadlineExceeded && !c.CancellationTriggered) || ((c.DeadlineExceeded || c.CancellationTriggered) && !c.TerminalState) || (c.Outcome == OutcomeStopped && (!c.CancellationTriggered || !c.TerminalState)) {
		return ResponseContext{}, problem(CodeInvalidFlags, "", "inconsistent result flags")
	}
	if (expected.DeadlineExceeded && !c.DeadlineExceeded) || (expected.CancellationTriggered && !c.CancellationTriggered) {
		return ResponseContext{}, problem(CodeInvalidFlags, "", "response cleared inherited state")
	}
	if c.DeadlineExceeded && c.Reason != ReasonDeadline {
		return ResponseContext{}, problem(CodeInvalidFlags, HeaderResponseReason, "deadline takes precedence")
	}
	if !c.DeadlineExceeded && c.CancellationTriggered && c.Reason != ReasonCancelled {
		return ResponseContext{}, problem(CodeInvalidFlags, HeaderResponseReason, "cancellation takes precedence")
	}
	if c.Reason == ReasonDeadline && !c.DeadlineExceeded || c.Reason == ReasonCancelled && !c.CancellationTriggered {
		return ResponseContext{}, problem(CodeInvalidFlags, HeaderResponseReason, "reason requires matching flag")
	}
	if c.Outcome == OutcomeOK && c.Reason != ReasonNone && c.Reason != ReasonDeadline && c.Reason != ReasonCancelled {
		return ResponseContext{}, problem(CodeInvalidFlags, HeaderResponseReason, "invalid success reason")
	}
	if c.Outcome == OutcomeRejected && c.Reason == ReasonNone {
		return ResponseContext{}, problem(CodeInvalidFlags, HeaderResponseReason, "rejection requires reason")
	}
	return c, nil
}
func responseWire(c ResponseContext) http.Header {
	h := make(http.Header)
	h.Set(HeaderProtocolVersion, strconv.FormatInt(c.ProtocolVersion, 10))
	h.Set(HeaderRequestID, c.RequestID)
	h.Set(HeaderInvocationID, c.InvocationID)
	h.Set(HeaderDeadlineExceeded, strconv.FormatBool(c.DeadlineExceeded))
	h.Set(HeaderCancellationTriggered, strconv.FormatBool(c.CancellationTriggered))
	h.Set(HeaderTerminalState, strconv.FormatBool(c.TerminalState))
	h.Set(HeaderResponseOutcome, string(c.Outcome))
	h.Set(HeaderResponseReason, string(c.Reason))
	h.Set(HeaderBackpressureLevel, strconv.FormatInt(c.BackpressureLevel, 10))
	h.Set(HeaderPolicyVersion, c.PolicyVersion)
	return h
}
func SerializeResponse(c ResponseContext) (http.Header, error) {
	h := responseWire(c)
	parsed, e := ParseResponse(h, RequestContext{RequestID: c.RequestID, InvocationID: c.InvocationID})
	if e != nil {
		return nil, e
	}
	if parsed != c {
		return nil, problem(CodeInvalidSyntax, "", "typed result must already be normalized")
	}
	if strings.Trim(c.PolicyVersion, " \t") != c.PolicyVersion {
		return nil, problem(CodeInvalidSyntax, HeaderPolicyVersion, "typed version must already be normalized")
	}
	return h, nil
}
func ValidateResponseStatus(c ResponseContext, status int) error {
	if status < 100 || status > 599 {
		return problem(CodeOutOfRange, "", "invalid HTTP status")
	}
	if c.Outcome == OutcomeRejected && status >= 200 && status < 300 {
		return problem(CodeInvalidResponseContext, "", "rejection cannot use success status")
	}
	return nil
}
