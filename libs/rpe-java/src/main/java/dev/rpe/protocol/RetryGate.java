package dev.rpe.protocol;

/**
 * The retry gate. A late original request is never rejected, the sidecar only marks it with {@code cs=expired}. What
 * we stop is a retry of a chain that can't finish in time anymore, because it would repeat side effects and add load
 * right when a service is slow. It only decides, it doesn't send anything. The checks always run in the same order
 * so the Java and Go versions give the same reason.
 */
public final class RetryGate {
    public static final String CANCELLED = "cancelled", DEADLINE = "deadline", TERMINAL = "terminal",
            NOT_REPLAY_SAFE = "not-replay-safe", INSUFFICIENT_TIME = "insufficient-time", BUDGET = "budget", ATTEMPTS = "attempts";

    /**
     * What the caller knows after a failed attempt.
     *
     * @param attempt       attempts sent so far, 1 after the first one
     * @param maxAttempts   the caller's own limit, 0 for none
     * @param cancel        cancel state from the context or from a response
     * @param terminal      the last response had {@code term=?1}
     * @param replaySafe    true if the operation is safe to repeat
     * @param remainingMs   time left on the deadline right now
     * @param backoffMs     wait before the next attempt
     * @param attemptCostMs how long one attempt usually takes
     * @param retryBudget   retry budget left
     */
    public record Check(long attempt, long maxAttempts, CancelState cancel, boolean terminal, boolean replaySafe,
            long remainingMs, long backoffMs, long attemptCostMs, long retryBudget) {
        public Check {
            cancel = cancel == null ? CancelState.NONE : cancel;
        }
    }

    /** What the gate decided. {@code reason} and {@code rule} are empty if the retry is allowed. */
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
