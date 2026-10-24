package org.rrpe.intelligence.adaptive;

/**
 * Before we change a rate we ask: is the app slow, or are requests just waiting in our own queue? The sidecar starts its
 * timer before the queue, so a rate that is too low looks like a slow app. We take the queue wait out with Little's law:
 * queue wait = depth / rate, and app time = p95 minus the queue wait.
 * This class only has the diagnosis of one window and the rate formulas.
 */
public final class RateDiagnosis {
    private RateDiagnosis() {}

    public enum Diagnosis { APP_OVERLOADED, RATE_TOO_LOW, HEALTHY, WATCH, UNKNOWN }

    /**
     * One service window: the current rate in req/s, how full the queue got in percent, the average queue depth, the
     * successful requests per second, the rejections per second, the normal app time P0, and whether errors are high.
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
        // An app that gets slower with more load is full, even when we have no good P0 yet.
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

    // ---------- formulas ----------

    /** The lowest rate we cut to: a share of the good rate, but at least the minimum. */
    public static double floor(double goodRate, AdaptiveConfig.RateSettings cfg) { return Math.max(cfg.minAbsolute(), cfg.floorFraction() * goodRate); }
    /** The highest rate we raise to. While we remember where the app slowed down, we stay at 90 percent of that. */
    public static double ceiling(Double brokeAt, AdaptiveConfig.RateSettings cfg) {
        return brokeAt == null ? cfg.maxAbsolute() : Math.max(cfg.minAbsolute(), Math.min(cfg.maxAbsolute(), .9 * brokeAt));
    }
    /** Raise for RATE_TOO_LOW: toward the demand plus headroom, below the ceiling, and never more than one step at once. */
    public static double raise(double rate, double demand, double goodRate, double ceiling, AdaptiveConfig.RateSettings cfg) {
        double a = cfg.stepFraction() * goodRate, limit = Math.min(ceiling, rate * (1 + cfg.maxRaise()));
        double target = Math.min(ceiling, Math.max(rate + a, demand * (1 + cfg.headroom())));
        return Math.max(rate, roundAtMost(Math.min(target, rate * (1 + cfg.maxRaise())), limit));
    }
    /** Cut for APP_OVERLOADED: a share of what the app really handled, but not below the floor. */
    public static double cut(double rate, double accepted, double floor, AdaptiveConfig.RateSettings cfg) {
        return round(Math.min(rate, Math.max(floor, cfg.cutFactor() * Math.min(rate, accepted))));
    }
    /** The healthy target: the highest recent demand plus headroom, kept between the floor and the ceiling. */
    public static double healthyTarget(double maxDemand, double floor, double ceiling, AdaptiveConfig.RateSettings cfg) {
        double v = Math.max(floor, Math.min(ceiling, maxDemand * (1 + cfg.headroom())));
        return v <= ceiling ? roundAtMost(v, ceiling) : round(v);
    }
    /** One healthy step: up a little toward the target, or down a little when the rate is too generous. */
    public static double healthyStep(double rate, double target, double goodRate, boolean relax, AdaptiveConfig.RateSettings cfg) {
        if (rate < target) return round(Math.min(target, rate + cfg.stepFraction() * goodRate));
        if (relax && rate > 2 * target) return round(Math.max(target, rate * (1 - cfg.relaxStep())));
        return rate;
    }
    static double round(double v) { return Math.round(v * 10) / 10.0; }
    /**
     * Rounds to 0.1 req/s but never above the limit. Normal rounding could land just above it, for example 56.16 to
     * 56.2, and the guard would then hold the raise forever.
     */
    static double roundAtMost(double v, double limit) {
        double r = round(v);
        return r > limit + 1e-9 ? Math.floor(limit * 10 + 1e-6) / 10 : r;
    }
}
