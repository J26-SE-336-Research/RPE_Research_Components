package rpe

func ValidateSameInvocationTransition(before, after RequestContext) error {
	if e := ValidateRequest(before); e != nil {
		return e
	}
	if e := ValidateRequest(after); e != nil {
		return e
	}
	if before.ProtocolVersion != after.ProtocolVersion || before.RequestID != after.RequestID || before.InvocationID != after.InvocationID || before.ParentInvocationID != after.ParentInvocationID || before.RequestRoute != after.RequestRoute || before.RequestDeadlineMillis != after.RequestDeadlineMillis || before.RetryBudgetRemaining != after.RetryBudgetRemaining {
		return problem(CodeInvalidTransition, "", "immutable invocation state changed")
	}
	if after.DeadlineUnixMillis > before.DeadlineUnixMillis || before.DeadlineExceeded && !after.DeadlineExceeded || before.CancellationTriggered && !after.CancellationTriggered {
		return problem(CodeInvalidTransition, "", "state authority increased or flags cleared")
	}
	return nil
}
func ValidateChildTransition(parent, child RequestContext, receipt AllocationReceipt) error {
	if e := ValidateRequest(parent); e != nil {
		return e
	}
	if e := ValidateRequest(child); e != nil {
		return e
	}
	if parent.ProtocolVersion != child.ProtocolVersion || parent.RequestID != child.RequestID || parent.RequestRoute != child.RequestRoute || parent.RequestDeadlineMillis != child.RequestDeadlineMillis || child.ParentInvocationID != parent.InvocationID || child.InvocationID == parent.InvocationID || child.DeadlineUnixMillis > parent.DeadlineUnixMillis || parent.DeadlineExceeded && !child.DeadlineExceeded || parent.CancellationTriggered && !child.CancellationTriggered {
		return problem(CodeInvalidTransition, "", "invalid parent to child transition")
	}
	if receipt.ledger == nil || receipt.childID != child.InvocationID || receipt.grant != child.RetryBudgetRemaining || !receipt.ledger.bound || receipt.ledger.owner.RequestID != parent.RequestID || receipt.ledger.owner.InvocationID != parent.InvocationID || receipt.ledger.Snapshot().Grant != parent.RetryBudgetRemaining {
		return problem(CodeInvalidTransition, HeaderRetryBudgetRemaining, "missing or mismatched allocation receipt")
	}
	return nil
}
