package dev.rpe.protocol;

/**
 * The retry gate (spec §8, rule R-R02). RPE never rejects an original request because its deadline passed (the
 * sidecar only signals {@code cs=expired}); it refuses a <em>retry</em> of a chain that can no longer succeed in time,
 * because such a retry repeats side effects and adds load exactly when a dependency is slow. A pure decision: it sends
 * nothing. The checks run in a fixed order so that every implementation reports the same reason.
 */
public final class RetryGate {
    public static final String CANCELLED = "cancelled", DEADLINE = "deadline", TERMINAL = "terminal",
            NOT_REPLAY_SAFE = "not-replay-safe", INSUFFICIENT_TIME = "insufficient-time", BUDGET = "budget", ATTEMPTS = "attempts";

    /**
     * What the caller knows after an attempt failed.
     *
     * @param attempt       attempts already sent (1 after the first attempt)
     * @param maxAttempts   the caller's own limit, 0 for none
     * @param cancel        chain cancellation state (context, or cs reported on a response)
     * @param terminal      the last response carried {@code term=?1}
     * @param replaySafe    the operation may be repeated
     * @param remainingMs   chain deadline left now
     * @param backoffMs     wait before the next attempt
     * @param attemptCostMs time one attempt is expected to need
     * @param retryBudget   retry budget left for this subtree
     */
    public record Check(long attempt, long maxAttempts, CancelState cancel, boolean terminal, boolean replaySafe,
            long remainingMs, long backoffMs, long attemptCostMs, long retryBudget) {
        public Check {
            cancel = cancel == null ? CancelState.NONE : cancel;
        }
    }

    /** The decision: {@code reason} and {@code rule} are empty when the retry is allowed. */
    public record Verdict(boolean retry, String reason, String rule, long nextAttempt, long budgetLeft) {}

    private RetryGate() {}

    public static Verdict decide(Check c) {
        long attempt = Math.max(c.attempt(), 1);
        String reason;
        if (c.cancel() == CancelState.EXPIRED) reason = DEADLINE;
        else if (c.cancel() == CancelState.CANCEL) reason = CANCELLED;
        else if (c.terminal()) reason = TERMINAL;
        else if (!c.replaySafe()) reason = NOT_REPLAY_SAFE;
        else if (c.remainingMs() <= 0) reason = DEADLINE;
        else if (c.remainingMs() < Math.max(c.backoffMs(), 0) + Math.max(c.attemptCostMs(), 0)) reason = INSUFFICIENT_TIME;
        else if (c.retryBudget() <= 0) reason = BUDGET;
        else if (c.maxAttempts() > 0 && attempt >= c.maxAttempts()) reason = ATTEMPTS;
        else return new Verdict(true, "", "", attempt + 1, c.retryBudget() - 1);
        return new Verdict(false, reason, "R-R02", 0, Math.max(c.retryBudget(), 0));
    }
}
