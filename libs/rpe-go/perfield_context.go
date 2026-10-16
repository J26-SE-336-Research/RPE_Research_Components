package rpe

// Original per-field encoding: one HTTP header per field (Protocol-Version, Request-Id, Deadline, ...).
// Kept as a candidate in the wire-format study and as the basis of the trail and retry ledger.

// RequestContext contains the ten required business-request fields.
type RequestContext struct {
	ProtocolVersion                             int64
	RequestID, InvocationID, ParentInvocationID string
	DeadlineUnixMillis, RequestDeadlineMillis   int64
	RequestRoute                                string
	RetryBudgetRemaining                        int64
	DeadlineExceeded, CancellationTriggered     bool
}
type ResponseContext struct {
	ProtocolVersion                                        int64
	RequestID, InvocationID                                string
	DeadlineExceeded, CancellationTriggered, TerminalState bool
	Outcome                                                Outcome
	Reason                                                 Reason
	BackpressureLevel                                      int64
	PolicyVersion                                          string
}
