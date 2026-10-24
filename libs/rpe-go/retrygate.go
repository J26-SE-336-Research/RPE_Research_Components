package rpe

// The retry gate. A late original request is never rejected, the sidecar only marks it with cs=expired. What we do
// stop is a retry of a chain that can't finish in time anymore. Retrying then repeats side effects, like a second
// card charge, and adds load right when a service is already slow. The gate only decides, it doesn't send or
// change anything.

// Reasons for refusing a retry, sent as rsn.
const (
	RetryRefusedCancelled        = "cancelled"         // the chain was cancelled
	RetryRefusedDeadline         = "deadline"          // the deadline has passed
	RetryRefusedTerminal         = "terminal"          // the last response had term=?1
	RetryRefusedNotReplaySafe    = "not-replay-safe"   // the operation can't be repeated safely
	RetryRefusedInsufficientTime = "insufficient-time" // not enough time for the backoff and one more attempt
	RetryRefusedBudget           = "budget"            // no retry budget left
	RetryRefusedAttempts         = "attempts"          // the caller's own attempt limit is reached
)

// RetryCheck is what the caller knows after a failed attempt.
type RetryCheck struct {
	Attempt       int64       // attempts sent so far, 1 after the first one
	MaxAttempts   int64       // the caller's own limit, 0 for none
	Cancel        CancelState // cancel state from the context or from a response
	Terminal      bool        // the last response had term=?1
	ReplaySafe    bool        // safe to repeat, for example idempotent or using an idempotency key
	RemainingMs   int64       // time left on the deadline right now
	BackoffMs     int64       // wait before the next attempt
	AttemptCostMs int64       // how long one attempt usually takes
	RetryBudget   int64       // retry budget left, rb minus the retries already used
}

// RetryVerdict is what the gate decided.
type RetryVerdict struct {
	Retry       bool   `json:"retry"`
	Reason      string `json:"reason,omitempty"` // empty if the retry is allowed
	Rule        string `json:"rule,omitempty"`   // the spec rule, set when refused
	NextAttempt int64  `json:"nextAttempt"`      // att for the next attempt, if allowed
	BudgetLeft  int64  `json:"budgetLeft"`       // budget left after this retry, if allowed
}

// DecideRetry decides if one more attempt is allowed. The checks always run in the same order so the Go and Java
// versions give the same reason.
func DecideRetry(c RetryCheck) RetryVerdict {
	refuse := func(reason string) RetryVerdict {
		return RetryVerdict{Reason: reason, Rule: "R-R02", BudgetLeft: max(c.RetryBudget, 0)}
	}
	attempt := max(c.Attempt, 1)
	switch {
	case c.Cancel == CancelExpired:
		return refuse(RetryRefusedDeadline)
	case c.Cancel == CancelRequest:
		return refuse(RetryRefusedCancelled)
	case c.Terminal:
		return refuse(RetryRefusedTerminal)
	case !c.ReplaySafe:
		return refuse(RetryRefusedNotReplaySafe)
	case c.RemainingMs <= 0:
		return refuse(RetryRefusedDeadline)
	case c.RemainingMs < max(c.BackoffMs, 0)+max(c.AttemptCostMs, 0):
		return refuse(RetryRefusedInsufficientTime)
	case c.RetryBudget <= 0:
		return refuse(RetryRefusedBudget)
	case c.MaxAttempts > 0 && attempt >= c.MaxAttempts:
		return refuse(RetryRefusedAttempts)
	}
	return RetryVerdict{Retry: true, NextAttempt: attempt + 1, BudgetLeft: c.RetryBudget - 1}
}
