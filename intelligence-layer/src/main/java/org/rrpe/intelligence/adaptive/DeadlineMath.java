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
    public static double restore(double baselineP99Ms, double currentDeadlineMs) {
        positive(baselineP99Ms); positive(currentDeadlineMs);
        double candidate = Math.ceil(Math.max(baselineP99Ms * 1.10, currentDeadlineMs * .90));
        if (!Double.isFinite(candidate)) throw new IllegalArgumentException("Deadline exceeds numeric range");
        return candidate;
    }
    private static void positive(double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Positive finite chain p99/current deadline required");
    }
}
