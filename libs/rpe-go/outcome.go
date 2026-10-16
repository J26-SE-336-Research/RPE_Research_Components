package rpe

// Outcome and Reason are the response vocabulary shared by every encoding.

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
