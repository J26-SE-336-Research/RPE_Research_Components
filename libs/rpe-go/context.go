package rpe

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
type Outcome string

const (
	OutcomeOK       Outcome = "ok"
	OutcomePartial  Outcome = "partial"
	OutcomeRejected Outcome = "rejected"
	OutcomeStopped  Outcome = "stopped"
	OutcomeFailed   Outcome = "failed"
	OutcomeUnknown  Outcome = "unknown"
)

type Reason string

const (
	ReasonNone               Reason = "none"
	ReasonDeadline           Reason = "deadline"
	ReasonCancelled          Reason = "cancelled"
	ReasonOverload           Reason = "overload"
	ReasonCircuitOpen        Reason = "circuit-open"
	ReasonInvalidContext     Reason = "invalid-context"
	ReasonUnsupportedVersion Reason = "unsupported-version"
	ReasonBusiness           Reason = "business"
	ReasonTransport          Reason = "transport"
)
