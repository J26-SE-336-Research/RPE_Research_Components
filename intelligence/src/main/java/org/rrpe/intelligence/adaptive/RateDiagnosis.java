package org.rrpe.intelligence.adaptive;

/**
 * Leaky-bucket rate v2 (rate/retry guide §4): before changing a rate, ask "is the app slow, or are requests just waiting in
 * our own queue?". The sidecar's latency timer starts before the leaky-bucket queue, so a too-low rate looks like a slow app
 * unless the queue wait is taken out: {@code Wq = depth / rate} (Little's law) and {@code Papp = max(0, p95 − Wq)}.
 * Pure functions: the diagnosis of one window and the rate formulas.
 */
public final class RateDiagnosis {
    private RateDiagnosis() {}

    public enum Diagnosis { APP_OVERLOADED, RATE_TOO_LOW, HEALTHY, WATCH, UNKNOWN }

    /**
     * One service window. {@code rate}: current rate (req/s, from the C3 policy); {@code queuePercent}: max queue utilisation
     * (0–100); {@code queueDepth}: average queued requests; {@code accepted}: successful throughput (req/s); {@code shed}: overload
     * rejections per second; {@code p0Ms}: learned app-time baseline; {@code errorsHigh}: the service error rate breached its baseline.
     */
    public record Inputs(Double rate, Double queuePercent, Double queueDepth, Double p95Ms, Double accepted, Double shed, Double p0Ms, boolean errorsHigh, String loadGrowth) {
        public Inputs(Double rate, Double queuePercent, Double queueDepth, Double p95Ms, Double accepted, Double shed, Double p0Ms, boolean errorsHigh) {
            this(rate, queuePercent, queueDepth, p95Ms, accepted, shed, p0Ms, errorsHigh, null);
        }
    }
    public record Result(Diagnosis diagnosis, String reason, Double queueWaitMs, Double appMs) {}

    /** Queue wait in ms, or null when the rate or the depth is unknown. */
    public static Double queueWaitMs(Double depth, Double rate) {
        return depth == null || rate == null || !(rate > 0) || !Double.isFinite(depth) || depth < 0 ? null : depth / rate * 1000;
    }
    /** Estimated app time: p95 minus the queue wait, never below 0. */
    public static Double appMs(Double p95Ms, Double queueWaitMs) {
        return p95Ms == null || queueWaitMs == null || !Double.isFinite(p95Ms) ? null : Math.max(0, p95Ms - queueWaitMs);
    }

    public static Result diagnose(Inputs in, AdaptiveConfig.RateSettings cfg) {
        Double wq = queueWaitMs(in.queueDepth(), in.rate()), papp = appMs(in.p95Ms(), wq);
        String missing = in.rate() == null || !(in.rate() > 0) ? "current rate" : in.queuePercent() == null ? "queue utilisation"
                : in.queueDepth() == null ? "queue depth" : in.p95Ms() == null ? "latency" : in.accepted() == null ? "throughput"
                : in.shed() == null ? "rejections" : in.p0Ms() == null && in.loadGrowth() == null ? "app-time baseline P0 (learning)" : null;
        if (missing != null) return new Result(Diagnosis.UNKNOWN, "missing " + missing, wq, papp);
        // "App time grows with load" (rate learning fix B2) shows a saturating app even without a trustworthy P0.
        boolean appSlow = in.p0Ms() != null && papp > cfg.appTolerance() * in.p0Ms(), grows = in.loadGrowth() != null;
        if (appSlow || grows || in.errorsHigh()) {
            var reasons = new java.util.ArrayList<String>();
            if (appSlow) reasons.add(String.format(java.util.Locale.ROOT, "app time %.0f ms > %.1f × P0 %.0f ms", papp, cfg.appTolerance(), in.p0Ms()));
            if (grows) reasons.add(in.loadGrowth());
            if (in.errorsHigh()) reasons.add("service errors above baseline");
            return new Result(Diagnosis.APP_OVERLOADED, String.join(" and ", reasons), wq, papp);
        }
        if (in.queuePercent() >= cfg.queueHighPercent() || in.shed() > 0) {
            return new Result(Diagnosis.RATE_TOO_LOW, String.format(java.util.Locale.ROOT, "queue %.0f%% full, %.1f req/s shed, app time normal (%.0f ms, P0 %.0f ms)",
                    in.queuePercent(), in.shed(), papp, in.p0Ms()), wq, papp);
        }
        if (in.queuePercent() < cfg.queueLowPercent()) return new Result(Diagnosis.HEALTHY, "queue below " + (long) cfg.queueLowPercent() + "%, nothing shed, app time normal", wq, papp);
        return new Result(Diagnosis.WATCH, String.format(java.util.Locale.ROOT, "queue %.0f%% full (between %.0f%% and %.0f%%), app time normal",
                in.queuePercent(), cfg.queueLowPercent(), cfg.queueHighPercent()), wq, papp);
    }

    // ---------- formulas (guide §4.3) ----------

    /** Never cut below max(min-absolute, floor-fraction × goodRate). */
    public static double floor(double goodRate, AdaptiveConfig.RateSettings cfg) { return Math.max(cfg.minAbsolute(), cfg.floorFraction() * goodRate); }
    /** max-absolute, and below 0.9 × the rate at which the app last slowed down (while that is remembered). */
    public static double ceiling(Double brokeAt, AdaptiveConfig.RateSettings cfg) {
        return brokeAt == null ? cfg.maxAbsolute() : Math.max(cfg.minAbsolute(), Math.min(cfg.maxAbsolute(), .9 * brokeAt));
    }
    /** RATE_TOO_LOW: target = min(ceiling, max(R + a, D × (1 + h))), one step at most R × (1 + max-raise). */
    public static double raise(double rate, double demand, double goodRate, double ceiling, AdaptiveConfig.RateSettings cfg) {
        double a = cfg.stepFraction() * goodRate, limit = Math.min(ceiling, rate * (1 + cfg.maxRaise()));
        double target = Math.min(ceiling, Math.max(rate + a, demand * (1 + cfg.headroom())));
        return Math.max(rate, roundAtMost(Math.min(target, rate * (1 + cfg.maxRaise())), limit));
    }
    /** APP_OVERLOADED: max(floor, cut-factor × min(R, accepted)). */
    public static double cut(double rate, double accepted, double floor, AdaptiveConfig.RateSettings cfg) {
        return round(Math.min(rate, Math.max(floor, cfg.cutFactor() * Math.min(rate, accepted))));
    }
    /** HEALTHY target: clamp(max demand of the last windows × (1 + h), floor, ceiling). */
    public static double healthyTarget(double maxDemand, double floor, double ceiling, AdaptiveConfig.RateSettings cfg) {
        double v = Math.max(floor, Math.min(ceiling, maxDemand * (1 + cfg.headroom())));
        return v <= ceiling ? roundAtMost(v, ceiling) : round(v);
    }
    /** HEALTHY step: up by at most a toward the target; down only when relaxing is due, by at most relax-step and not below the target. */
    public static double healthyStep(double rate, double target, double goodRate, boolean relax, AdaptiveConfig.RateSettings cfg) {
        if (rate < target) return round(Math.min(target, rate + cfg.stepFraction() * goodRate));
        if (relax && rate > 2 * target) return round(Math.max(target, rate * (1 - cfg.relaxStep())));
        return rate;
    }
    static double round(double v) { return Math.round(v * 10) / 10.0; }
    /**
     * Rounded to 0.1 req/s but never above {@code limit} (the ceiling or the +max-raise step): rounding to the nearest value
     * could land just above it (ceiling 56.16 → 56.2), and the automatic-approval guard would then hold the raise forever.
     */
    static double roundAtMost(double v, double limit) {
        double r = round(v);
        return r > limit + 1e-9 ? Math.floor(limit * 10 + 1e-6) / 10 : r;
    }
}
