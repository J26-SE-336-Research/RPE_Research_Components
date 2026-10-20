package rpe

// The retry gate (spec §8, rule R-R02). RPE never rejects an original request because its deadline passed: the
// sidecar only signals it (cs=expired). What RPE refuses is a *retry* of a chain that can no longer succeed in time,
// because a retry of an expired chain repeats side effects (a second card charge) and multiplies load exactly when a
// dependency is already slow. The gate is a pure decision: it sends nothing and changes nothing.

// Reasons a retry is refused (the rsn of a refused attempt).
const (
	RetryRefusedCancelled        = "cancelled"         // the chain was cancelled (cs=cancel)
	RetryRefusedDeadline         = "deadline"          // the chain deadline has passed (cs=expired or ttl <= 0)
	RetryRefusedTerminal         = "terminal"          // the last response carried term=?1
	RetryRefusedNotReplaySafe    = "not-replay-safe"   // the operation may not be repeated
	RetryRefusedInsufficientTime = "insufficient-time" // less time left than the backoff plus one attempt
	RetryRefusedBudget           = "budget"            // the retry budget of this subtree is used up
	RetryRefusedAttempts         = "attempts"          // the caller's own attempt limit is reached
)

// RetryCheck is what the caller knows after an attempt failed.
type RetryCheck struct {
	Attempt       int64       // attempts already sent for this logical call (1 after the first attempt)
	MaxAttempts   int64       // the caller's own limit, 0 for none
	Cancel        CancelState // chain cancellation state: from the context, or cs reported on a response
	Terminal      bool        // the last response carried term=?1
	ReplaySafe    bool        // the operation may be repeated (idempotent, or protected by an idempotency key)
	RemainingMs   int64       // chain deadline left now (ttl minus the locally measured elapsed time)
	BackoffMs     int64       // wait before the next attempt
	AttemptCostMs int64       // time one attempt is expected to need (route p50/p95 from policy or C4)
	RetryBudget   int64       // retry budget left for this subtree (rb minus retries already spent)
}

// RetryVerdict is the gate's decision.
type RetryVerdict struct {
	Retry       bool   `json:"retry"`
	Reason      string `json:"reason,omitempty"` // empty when the retry is allowed
	Rule        string `json:"rule,omitempty"`   // R-R02 when refused
	NextAttempt int64  `json:"nextAttempt"`      // att of the next attempt (when allowed)
	BudgetLeft  int64  `json:"budgetLeft"`       // retry budget after this retry (when allowed)
}

// DecideRetry applies R-R02. The checks run in a fixed order so that every implementation reports the same reason.
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
