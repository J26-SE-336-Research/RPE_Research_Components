package rpe

import (
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

func sample() RequestContext {
	return RequestContext{ProtocolVersion: 1, RequestID: "4bf92f3577b34da6a3ce929d0e0e4736", InvocationID: "00f067aa0ba902b7", ParentInvocationID: RootParentID, DeadlineUnixMillis: 10000, RequestDeadlineMillis: 1000, RequestRoute: "orders.create", RetryBudgetRemaining: 3}
}
func codeIs(t *testing.T, e error, c ErrorCode) {
	t.Helper()
	var pe *ProtocolError
	if !errors.As(e, &pe) || pe.Code != c {
		t.Fatalf("expected %s, got %v", c, e)
	}
}
func TestRequestRoundTrip(t *testing.T) {
	c := sample()
	h, e := SerializeRequest(c)
	if e != nil {
		t.Fatal(e)
	}
	got, e := ParseRequest(h)
	if e != nil || got != c {
		t.Fatalf("round trip: %+v %v", got, e)
	}
}
func TestRejectCore(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(http.Header)
		code   ErrorCode
	}{
		{"missing", func(h http.Header) { h.Del(HeaderRequestID) }, CodeMissingHeader},
		{"case duplicate", func(h http.Header) { h["request-id"] = []string{sample().RequestID} }, CodeDuplicateHeader},
		{"leading zero", func(h http.Header) { h.Set(HeaderRetryBudgetRemaining, "02") }, CodeInvalidSyntax},
		{"negative", func(h http.Header) { h.Set(HeaderRetryBudgetRemaining, "-1") }, CodeInvalidSyntax},
		{"overflow", func(h http.Header) { h.Set(HeaderDeadline, "9223372036854775808") }, CodeOutOfRange},
		{"range", func(h http.Header) { h.Set(HeaderRetryBudgetRemaining, "256") }, CodeOutOfRange},
		{"boolean", func(h http.Header) { h.Set(HeaderDeadlineExceeded, "TRUE") }, CodeInvalidSyntax},
		{"flags", func(h http.Header) { h.Set(HeaderDeadlineExceeded, "true") }, CodeInvalidFlags},
		{"version", func(h http.Header) { h.Set(HeaderProtocolVersion, "2") }, CodeUnsupportedVersion},
		{"route length", func(h http.Header) { h.Set(HeaderRequestRoute, strings.Repeat("a", 129)) }, CodeOutOfRange},
		{"control", func(h http.Header) { h.Set(HeaderRequestRoute, "a\nb") }, CodeInvalidSyntax},
		{"size", func(h http.Header) { h.Set(HeaderTopologyTrail, strings.Repeat("a", 4096)) }, CodeContextTooLarge},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			h, _ := SerializeRequest(sample())
			tt.mutate(h)
			_, e := ParseRequest(h)
			codeIs(t, e, tt.code)
		})
	}
}
func TestLateSuccess(t *testing.T) {
	q := sample()
	r := ResponseContext{ProtocolVersion: 1, RequestID: q.RequestID, InvocationID: q.InvocationID, DeadlineExceeded: true, CancellationTriggered: true, TerminalState: true, Outcome: OutcomeOK, Reason: ReasonDeadline, PolicyVersion: "static-1"}
	h, e := SerializeResponse(r)
	if e != nil {
		t.Fatal(e)
	}
	got, e := ParseResponse(h, q)
	if e != nil || got != r {
		t.Fatalf("late result: %v", e)
	}
	q.InvocationID = "1111111111111111"
	_, e = ParseResponse(h, q)
	codeIs(t, e, CodeInvalidResponseContext)
	state := &LogicalCallState{}
	state.RecordResult(r)
	if !state.RetryBlocked() {
		t.Fatal("late success allowed retry")
	}
}
func TestResponseFlags(t *testing.T) {
	q := sample()
	r := ResponseContext{ProtocolVersion: 1, RequestID: q.RequestID, InvocationID: q.InvocationID, Outcome: OutcomeStopped, Reason: ReasonCancelled, PolicyVersion: "static-1"}
	_, e := SerializeResponse(r)
	codeIs(t, e, CodeInvalidFlags)
}
func TestDeadline(t *testing.T) {
	now := time.UnixMilli(9000)
	d, e := NewDeadline(10000, now, 10)
	if e != nil {
		t.Fatal(e)
	}
	if d.RemainingMillis(now) != 990 || d.RemainingMillis(now.Add(200*time.Millisecond)) != 790 {
		t.Fatal("wrong allowance")
	}
	if !d.IsExpired(now.Add(time.Second)) {
		t.Fatal("not expired")
	}
	expired, e := NewDeadline(8000, now, 0)
	if e != nil || !expired.IsExpired(now) {
		t.Fatal("expired input")
	}
}
func TestLedgerConcurrent(t *testing.T) {
	l, _ := NewRetryLedger(255)
	var wg sync.WaitGroup
	for i := 0; i < 1000; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); l.SpendRetry() }()
	}
	wg.Wait()
	s := l.Snapshot()
	if s.Spent != 255 || s.Unallocated != 0 || s.Spent+s.Delegated+s.Unallocated != s.Grant {
		t.Fatalf("bad ledger: %+v", s)
	}
}
func TestAllocationAndTransition(t *testing.T) {
	parent := sample()
	l, _ := NewInvocationLedger(parent)
	child := parent
	child.InvocationID = "1111111111111111"
	child.ParentInvocationID = parent.InvocationID
	child.RetryBudgetRemaining = 2
	receipt, e := l.AllocateChild(child.InvocationID, 2)
	if e != nil {
		t.Fatal(e)
	}
	if e = ValidateChildTransition(parent, child, receipt); e != nil {
		t.Fatal(e)
	}
	if _, e = l.AllocateChild("2222222222222222", 2); e == nil {
		t.Fatal("overdelegation")
	}
	child.DeadlineUnixMillis++
	codeIs(t, ValidateChildTransition(parent, child, receipt), CodeInvalidTransition)
}
func TestSplitter(t *testing.T) {
	l, _ := NewRetryLedger(5)
	r, e := l.SplitChildren([]string{"1111111111111111", "2222222222222222"})
	if e != nil || len(r) != 2 {
		t.Fatal(e)
	}
	s := l.Snapshot()
	if s.Unallocated != 3 || s.Delegated != 2 {
		t.Fatalf("wrong split %+v", s)
	}
}
func TestCancellation(t *testing.T) {
	token := &CancellationToken{}
	token.RequestCancel(ReasonDeadline)
	token.RequestCancel(ReasonCancelled)
	s := token.Snapshot()
	if !s.Requested || !s.DeadlineExceeded {
		t.Fatal("flags cleared")
	}
}
func TestTopologyBoundsAndConflict(t *testing.T) {
	top := Topology{Enabled: true, RegistryVersion: 1}
	for i := 1; i <= 17; i++ {
		top.Records = append(top.Records, TopologyRecord{InvocationID: fmt.Sprintf("%016x", i), ParentInvocationID: RootParentID, ServiceID: 1, ElapsedMicros: 1, Outcome: "ok"})
	}
	h, e := SerializeTopology(top, "")
	if e != nil {
		t.Fatal(e)
	}
	got, e := ParseTopology(h)
	if e != nil || len(got.Records) != 16 || !got.Incomplete {
		t.Fatalf("bounding %+v %v", got, e)
	}
	a := TopologyRecord{InvocationID: "1111111111111111", ParentInvocationID: RootParentID, ServiceID: 1, ElapsedMicros: 1, Outcome: "ok"}
	b := a
	b.ParentInvocationID = "2222222222222222"
	got = MergeTopology(Topology{Enabled: true, RegistryVersion: 1, Records: []TopologyRecord{a}}, Topology{Enabled: true, RegistryVersion: 1, Records: []TopologyRecord{b}}, "")
	if !got.Incomplete || len(got.Records) != 0 {
		t.Fatal("conflict retained")
	}
}
func TestMalformedTopologyDoesNotRejectCore(t *testing.T) {
	h, _ := SerializeRequest(sample())
	h.Set(HeaderTopologyRegistryVersion, "1")
	h.Set(HeaderTopologyIncomplete, "false")
	h.Set(HeaderTopologyTrail, "bad")
	if _, e := ParseRequest(h); e != nil {
		t.Fatal(e)
	}
	top, e := ParseTopology(h)
	if e == nil || !top.Incomplete {
		t.Fatal("missing topology diagnostic")
	}
}

type fakeGate struct{ allowed bool }

func (g fakeGate) AuthorizeRetry(string, string, string) bool { return g.allowed }
func TestRetryGates(t *testing.T) {
	now := time.Now()
	d, _ := NewDeadline(now.UnixMilli()+1000, now, 0)
	l, _ := NewRetryLedger(1)
	token := &CancellationToken{}
	s := &LogicalCallState{}
	c := RetryConditions{TransientFailure: true, ReplaySafe: true, TargetAllows: true, MinimumAttempt: time.Millisecond, Destination: "inventory", LogicalCallID: "reserve", AttemptID: "1111111111111111"}
	if AuthorizeRetry(c, d, now, token, s, l, fakeGate{false}) {
		t.Fatal("denied gate allowed")
	}
	if l.Snapshot().Spent != 0 {
		t.Fatal("spent on denial")
	}
	if !AuthorizeRetry(c, d, now, token, s, l, fakeGate{true}) {
		t.Fatal("valid retry denied")
	}
	token.RequestCancel(ReasonCancelled)
	if ReadyToDispatch(d, now, token, s) {
		t.Fatal("cancelled dispatch allowed")
	}
}
func TestInjectClonesHeaders(t *testing.T) {
	req, e := http.NewRequest("GET", "http://example.test", nil)
	if e != nil {
		t.Fatal(e)
	}
	req.Header = nil
	got, e := InjectRequest(req, sample())
	if e != nil {
		t.Fatal(e)
	}
	if req.Header != nil || got.Header.Get(HeaderRequestID) != sample().RequestID {
		t.Fatal("mutated caller or omitted ID")
	}
}
