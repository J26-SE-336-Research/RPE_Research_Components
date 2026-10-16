package rpe

import "time"

// AggregateRetryGate is implemented by the runtime's service-scoped authority.
// A successful authorization must be single use and cannot be refunded here.
type AggregateRetryGate interface {
	AuthorizeRetry(destination, logicalCallID, attemptID string) bool
}
type RetryConditions struct {
	TransientFailure, ReplaySafe, TargetAllows bool
	Backoff, MinimumAttempt                    time.Duration
	Destination, LogicalCallID, AttemptID      string
}

// AuthorizeRetry reserves authority only. It never sends a request.
// One runtime owner must serialize retry decisions for each logical call.
func AuthorizeRetry(c RetryConditions, d Deadline, now time.Time, token *CancellationToken, state *LogicalCallState, ledger *RetryLedger, gate AggregateRetryGate) bool {
	if token == nil || state == nil || ledger == nil || gate == nil || !c.TransientFailure || !c.ReplaySafe || !c.TargetAllows || c.Backoff < 0 || c.MinimumAttempt <= 0 || c.Destination == "" || c.LogicalCallID == "" {
		return false
	}
	if _, e := parseID(HeaderInvocationID, c.AttemptID, 16, false); e != nil {
		return false
	}
	remaining := d.Remaining(now)
	if d.IsExpired(now) || c.Backoff > remaining || c.MinimumAttempt > remaining-c.Backoff || token.Snapshot().Requested || state.RetryBlocked() || ledger.Snapshot().Unallocated == 0 {
		return false
	}
	if !gate.AuthorizeRetry(c.Destination, c.LogicalCallID, c.AttemptID) {
		return false
	}
	return ledger.SpendRetry()
}

// ReadyToDispatch rechecks live state after backoff/reservation. Lost reservations
// are not refunded. Caller supplies a fresh time immediately before sending.
func ReadyToDispatch(d Deadline, now time.Time, token *CancellationToken, state *LogicalCallState) bool {
	return token != nil && state != nil && !d.IsExpired(now) && !token.Snapshot().Requested && !state.RetryBlocked()
}
