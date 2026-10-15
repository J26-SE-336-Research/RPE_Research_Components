package rpe

import (
	"crypto/rand"
	"encoding/hex"
	"time"
)

func randomID(bytes int) (string, error) {
	raw := make([]byte, bytes)
	for {
		if _, e := rand.Read(raw); e != nil {
			return "", e
		}
		nonzero := false
		for _, b := range raw {
			nonzero = nonzero || b != 0
		}
		if nonzero {
			return hex.EncodeToString(raw), nil
		}
	}
}

// NewRootContext is called at trusted ingress after selecting approved policy.
// Caller must strip client-supplied protocol headers and authenticate the request.
func NewRootContext(route string, allowanceMillis, grant int64, now time.Time) (RequestContext, error) {
	if allowanceMillis < 1 || allowanceMillis > MaxRequestDeadlineMillis {
		return RequestContext{}, problem(CodeOutOfRange, HeaderRequestDeadlineMillis, "invalid root allowance")
	}
	wall := now.UnixMilli()
	if wall < 0 || wall > MaxDeadlineUnixMillis-allowanceMillis {
		return RequestContext{}, problem(CodeOutOfRange, HeaderDeadline, "root deadline overflow")
	}
	rid, e := randomID(16)
	if e != nil {
		return RequestContext{}, e
	}
	iid, e := randomID(8)
	if e != nil {
		return RequestContext{}, e
	}
	c := RequestContext{ProtocolVersion: 1, RequestID: rid, InvocationID: iid, ParentInvocationID: RootParentID, DeadlineUnixMillis: wall + allowanceMillis, RequestDeadlineMillis: allowanceMillis, RequestRoute: route, RetryBudgetRemaining: grant}
	if e = ValidateRequest(c); e != nil {
		return RequestContext{}, e
	}
	return c, nil
}

// NewChildContext allocates a new attempt's subtree grant. It does not spend
// the parent's retry token; AuthorizeRetry does that for extra attempts.
// Runtime checks the live parent deadline/token immediately before dispatch.
func NewChildContext(parent RequestContext, ledger *RetryLedger, grant, deadlineMillis int64) (RequestContext, AllocationReceipt, error) {
	if e := ValidateRequest(parent); e != nil {
		return RequestContext{}, AllocationReceipt{}, e
	}
	if ledger == nil || !ledger.bound || ledger.owner.RequestID != parent.RequestID || ledger.owner.InvocationID != parent.InvocationID || ledger.owner.RetryBudgetRemaining != parent.RetryBudgetRemaining {
		return RequestContext{}, AllocationReceipt{}, problem(CodeInvalidTransition, "", "ledger does not belong to parent")
	}
	if parent.CancellationTriggered || parent.DeadlineExceeded {
		return RequestContext{}, AllocationReceipt{}, problem(CodeInvalidTransition, "", "cancelled parent cannot start work")
	}
	if deadlineMillis < 1 || deadlineMillis > parent.DeadlineUnixMillis {
		return RequestContext{}, AllocationReceipt{}, problem(CodeInvalidTransition, HeaderDeadline, "child cannot extend deadline")
	}
	iid, e := randomID(8)
	if e != nil {
		return RequestContext{}, AllocationReceipt{}, e
	}
	child := parent
	child.InvocationID = iid
	child.ParentInvocationID = parent.InvocationID
	child.DeadlineUnixMillis = deadlineMillis
	child.RetryBudgetRemaining = grant
	receipt, e := ledger.AllocateChild(iid, grant)
	if e != nil {
		return RequestContext{}, AllocationReceipt{}, e
	}
	if e = ValidateChildTransition(parent, child, receipt); e != nil {
		return RequestContext{}, AllocationReceipt{}, e
	}
	return child, receipt, nil
}
