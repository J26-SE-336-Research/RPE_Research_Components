package rpe

import (
	"strings"
	"testing"
	"time"
)

func TestScalarConformance(t *testing.T) {
	for _, raw := range []string{"-1", "+1", "01", "00", "1.0", "1e1", "1,2", "", "1 2"} {
		t.Run("integer/"+raw, func(t *testing.T) {
			_, e := parseInteger(HeaderRetryBudgetRemaining, raw, 0, 255)
			codeIs(t, e, CodeInvalidSyntax)
		})
	}
	for _, raw := range []string{"256", "9223372036854775808"} {
		_, e := parseInteger(HeaderRetryBudgetRemaining, raw, 0, 255)
		codeIs(t, e, CodeOutOfRange)
	}
	for _, raw := range []string{"0", "255", " \t2\t "} {
		if _, e := parseInteger(HeaderRetryBudgetRemaining, raw, 0, 255); e != nil {
			t.Fatal(e)
		}
	}
	for _, raw := range []string{"TRUE", "False", "0", "1", "?1", "\"true\"", ""} {
		_, e := parseBoolean(HeaderCancellationTriggered, raw)
		codeIs(t, e, CodeInvalidSyntax)
	}
	for _, raw := range []string{"true", "false", " true "} {
		if _, e := parseBoolean(HeaderCancellationTriggered, raw); e != nil {
			t.Fatal(e)
		}
	}
	for _, raw := range []string{"a\rb", "a\nb", "a\x00b", "a\x7fb", "a\tb", "café"} {
		_, e := normalizeScalar(HeaderRequestRoute, raw)
		codeIs(t, e, CodeInvalidSyntax)
	}
	if _, e := normalizeScalar(HeaderRequestRoute, strings.Repeat("a", 256)); e != nil {
		t.Fatal(e)
	}
	_, e := normalizeScalar(HeaderRequestRoute, strings.Repeat("a", 257))
	codeIs(t, e, CodeContextTooLarge)
	for _, raw := range []string{"00F067aa0ba902b7", "g0f067aa0ba902b7", "abc", RootParentID} {
		_, e := parseID(HeaderInvocationID, raw, 16, false)
		codeIs(t, e, CodeInvalidSyntax)
	}
	if _, e := parseID(HeaderParentInvocationID, RootParentID, 16, true); e != nil {
		t.Fatal(e)
	}
}
func TestTypedContextRejectsUnnormalizedID(t *testing.T) {
	c := sample()
	c.RequestID = " " + c.RequestID
	_, e := SerializeRequest(c)
	codeIs(t, e, CodeInvalidSyntax)
}
func TestRootAndChildGeneration(t *testing.T) {
	root, e := NewRootContext("orders.create", 1000, 3, time.Now())
	if e != nil {
		t.Fatal(e)
	}
	ledger, e := NewInvocationLedger(root)
	if e != nil {
		t.Fatal(e)
	}
	child, receipt, e := NewChildContext(root, ledger, 1, root.DeadlineUnixMillis)
	if e != nil {
		t.Fatal(e)
	}
	if child.RequestID != root.RequestID || child.ParentInvocationID != root.InvocationID || child.InvocationID == root.InvocationID {
		t.Fatal("invalid identity propagation")
	}
	if e = ValidateChildTransition(root, child, receipt); e != nil {
		t.Fatal(e)
	}
}
