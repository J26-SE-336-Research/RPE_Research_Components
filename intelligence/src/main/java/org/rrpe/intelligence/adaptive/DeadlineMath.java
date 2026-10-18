package org.rrpe.intelligence.adaptive;

/** Pure calculations from the corrected document, independent of evidence/approval gates. */
public final class DeadlineMath {
    private DeadlineMath() {}
    public record Candidate(double latencyChangeRatio, double safetyMargin, double milliseconds) {}
    public static Candidate increase(double recentP99Ms, double baselineP99Ms) {
        positive(recentP99Ms); positive(baselineP99Ms);
        double change = (recentP99Ms - baselineP99Ms) / baselineP99Ms;
        double margin = change <= .10 ? .10 : change <= .25 ? .20 : .30;
        double candidate = Math.ceil(recentP99Ms * (1 + margin));
        if (!Double.isFinite(candidate)) throw new IllegalArgumentException("Deadline exceeds numeric range");
        return new Candidate(change, margin, candidate);
    }
    /**
     * Censoring-aware increase. While requests fail on the deadline, the observed chain latency is right-censored at
     * the current deadline (requests are cut there), so recent p99 understates the true p99. In that case the
     * candidate is at least the uncensored baseline p99 plus 10%.
     */
    public static Candidate increaseCensored(double recentP99Ms, double baselineP99Ms, double currentDeadlineMs) {
        Candidate c = increase(recentP99Ms, baselineP99Ms);
        positive(currentDeadlineMs);
        if (recentP99Ms < currentDeadlineMs * .95) return c;
        double uncensored = Math.ceil(baselineP99Ms * 1.10);
        return uncensored > c.milliseconds() ? new Candidate(c.latencyChangeRatio(), .10, uncensored) : c;
    }
    /** Bounded step: a candidate never moves the deadline by more than {@code maximumChange} of the current value. */
    public static double clamp(double candidateMs, double currentMs, double maximumChange) {
        double high = Math.floor(currentMs * (1 + maximumChange)), low = Math.ceil(currentMs * (1 - maximumChange));
        return Math.max(low, Math.min(high, candidateMs));
    }
    public static double restore(double baselineP99Ms, double currentDeadlineMs) {
        positive(baselineP99Ms); positive(currentDeadlineMs);
        double candidate = Math.ceil(Math.max(baselineP99Ms * 1.10, currentDeadlineMs * .90));
        if (!Double.isFinite(candidate)) throw new IllegalArgumentException("Deadline exceeds numeric range");
        return candidate;
    }
    // ---------- v2 (guide §6.4, §6.5) ----------

    /** Rounds up to the next 10 ms, so a candidate never undercuts its margin. */
    public static long ceil10(double ms) {
        positive(ms);
        double value = Math.ceil(ms / 10 - 1e-9) * 10;
        if (!(value < Long.MAX_VALUE)) throw new IllegalArgumentException("Deadline exceeds numeric range");
        return (long) value;
    }
    /** m = clamp(k·σ / T_h, min, max): more spread in the healthy time needs more margin. */
    public static double margin(double sigma, double th, double k, double min, double max) {
        positive(th);
        if (!Double.isFinite(sigma) || sigma < 0) throw new IllegalArgumentException("Spread must be finite and non-negative");
        return Math.max(min, Math.min(max, k * sigma / th));
    }
    /** D* = clamp(ceil10(T_h × (1 + m)), minMs, maxMs). */
    public static long target(double th, double m, long minMs, long maxMs) {
        positive(th);
        return Math.max(minMs, Math.min(maxMs, ceil10(th * (1 + m))));
    }
    /** Case A: raise geometrically, never past the target when it is known; capped at {@code maxMs}. */
    public static long stepUp(long d, java.util.OptionalLong target, double factor, long maxMs) {
        positive(d);
        long step = Math.min(maxMs, ceil10(d * factor));
        return target.isPresent() ? Math.min(target.getAsLong(), step) : step;
    }
    /** Case B: lower under overload, max(D*, ceil10(factor·D)); never below the target. */
    public static long stepDownOverload(long d, long target, double factor) { positive(d); return Math.max(target, ceil10(d * factor)); }
    /** Case D: tighten when healthy, max(D*, ceil10(factor·D)); never below the target. */
    public static long stepDownHealthy(long d, long target, double factor) { positive(d); return Math.max(target, ceil10(d * factor)); }

    private static void positive(double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Positive finite chain p99/current deadline required");
    }
}
