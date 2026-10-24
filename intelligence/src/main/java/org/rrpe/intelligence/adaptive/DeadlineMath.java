package org.rrpe.intelligence.adaptive;

/**
 * The deadline formulas. They replace the old p99 bands; the old logic is still on the branch
 * component-4-intelligence-on-main. Every step is a fixed share of the distance and never goes past the target D*.
 */
public final class DeadlineMath {
    private DeadlineMath() {}

    /** Rounds up to the next 10 ms, so a candidate never undercuts its margin. */
    public static long ceil10(double ms) {
        positive(ms);
        double value = Math.ceil(ms / 10 - 1e-9) * 10;
        if (!(value < Long.MAX_VALUE)) throw new IllegalArgumentException("Deadline exceeds numeric range");
        return (long) value;
    }
    /** The margin m: more spread in the healthy time needs more margin, kept between min and max. */
    public static double margin(double sigma, double th, double k, double min, double max) {
        positive(th);
        if (!Double.isFinite(sigma) || sigma < 0) throw new IllegalArgumentException("Spread must be finite and non-negative");
        return Math.max(min, Math.min(max, k * sigma / th));
    }
    /** The target deadline D*: the healthy time plus a margin, rounded up to 10 ms and kept between min and max. */
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
