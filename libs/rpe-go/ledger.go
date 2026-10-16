package rpe

import "sync"

type LedgerSnapshot struct{ Grant, Spent, Delegated, Unallocated int64 }

// AllocationReceipt is created only by a real ledger, not by a caller's integers.
type AllocationReceipt struct {
	ledger  *RetryLedger
	childID string
	grant   int64
}
type RetryLedger struct {
	mu       sync.Mutex
	state    LedgerSnapshot
	children map[string]int64
	owner    RequestContext
	bound    bool
}

func NewRetryLedger(grant int64) (*RetryLedger, error) {
	if grant < 0 || grant > 255 {
		return nil, problem(CodeOutOfRange, HeaderRetryBudgetRemaining, "invalid grant")
	}
	return &RetryLedger{state: LedgerSnapshot{Grant: grant, Unallocated: grant}, children: make(map[string]int64)}, nil
}
func (l *RetryLedger) AllocateChild(childID string, grant int64) (AllocationReceipt, error) {
	if _, e := parseID(HeaderInvocationID, childID, 16, false); e != nil {
		return AllocationReceipt{}, e
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if _, exists := l.children[childID]; exists {
		return AllocationReceipt{}, problem(CodeInvalidTransition, HeaderInvocationID, "child already allocated")
	}
	if grant < 0 || grant > l.state.Unallocated {
		return AllocationReceipt{}, problem(CodeInvalidTransition, HeaderRetryBudgetRemaining, "allocation exceeds available budget")
	}
	l.children[childID] = grant
	l.state.Delegated += grant
	l.state.Unallocated -= grant
	return AllocationReceipt{l, childID, grant}, nil
}
func (l *RetryLedger) SpendRetry() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.state.Unallocated == 0 {
		return false
	}
	l.state.Unallocated--
	l.state.Spent++
	return true
}
func (l *RetryLedger) Snapshot() LedgerSnapshot { l.mu.Lock(); defer l.mu.Unlock(); return l.state }

// SplitChildren allocates atomically in caller-supplied stable role order.
func (l *RetryLedger) SplitChildren(ids []string) ([]AllocationReceipt, error) {
	for _, id := range ids {
		if _, e := parseID(HeaderInvocationID, id, 16, false); e != nil {
			return nil, e
		}
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	seen := make(map[string]bool)
	for _, id := range ids {
		if seen[id] {
			return nil, problem(CodeInvalidTransition, HeaderInvocationID, "duplicate child")
		}
		seen[id] = true
		if _, ok := l.children[id]; ok {
			return nil, problem(CodeInvalidTransition, HeaderInvocationID, "child already allocated")
		}
	}
	if len(ids) == 0 {
		return []AllocationReceipt{}, nil
	}
	pool := l.state.Unallocated / 2
	receipts := make([]AllocationReceipt, len(ids))
	for i, id := range ids {
		g := pool / int64(len(ids))
		if int64(i) < pool%int64(len(ids)) {
			g++
		}
		l.children[id] = g
		receipts[i] = AllocationReceipt{l, id, g}
	}
	l.state.Delegated += pool
	l.state.Unallocated -= pool
	return receipts, nil
}

// NewInvocationLedger binds allocation authority to one validated invocation.
// The runtime must create exactly one ledger per invocation and retain it.
func NewInvocationLedger(c RequestContext) (*RetryLedger, error) {
	if e := ValidateRequest(c); e != nil {
		return nil, e
	}
	l, e := NewRetryLedger(c.RetryBudgetRemaining)
	if e != nil {
		return nil, e
	}
	l.owner = c
	l.bound = true
	return l, nil
}
