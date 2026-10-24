package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.util.*;

/**
 * The load of a service in one window. The first match wins:
 * UNKNOWN (the deadline guard has no usable result), OVERLOADED, STRESSED, HEALTHY.
 * Optional signals that are unavailable (null) are skipped; they never block.
 */
public final class ServiceLoadClassifier {
    private ServiceLoadClassifier() {}
    public record LoadResult(ServiceLoad load, String reason) {}

    public static Map<String, LoadResult> classify(Collection<String> services, Map<String, ServiceLoadSnapshot> snapshots,
            Map<String, Health> guards, AdaptiveConfig.LoadSettings t) {
        var result = new TreeMap<String, LoadResult>();
        for (String service : services) result.put(service, classify(snapshots.get(service), guards.get(service), t));
        return result;
    }

    public static LoadResult classify(ServiceLoadSnapshot snapshot, Health guard, AdaptiveConfig.LoadSettings t) {
        var s = snapshot == null ? ServiceLoadSnapshot.unavailable("") : snapshot;
        if (guard == null || !List.of(Health.HEALTHY, Health.STRESSED, Health.OVERLOADED).contains(guard)) {
            return new LoadResult(ServiceLoad.UNKNOWN, "deadline guard is " + (guard == null ? "missing" : guard));
        }
        if (at(s.queuePressurePercent(), t.pressureOverloaded())) return over("queue pressure " + pct(s.queuePressurePercent()) + " ≥ " + pct(t.pressureOverloaded()));
        if (positive(s.overloadRejections())) return over("overload rejections " + num(s.overloadRejections()));
        if (positive(s.backpressureRejections())) return over("backpressure rejections " + num(s.backpressureRejections()));
        if (at(s.cpuUtilisation(), t.cpuOverloaded())) return over("CPU " + ratio(s.cpuUtilisation()) + " ≥ " + ratio(t.cpuOverloaded()));
        if (at(s.cpuThrottling(), t.throttleOverloaded())) return over("CPU throttling " + ratio(s.cpuThrottling()));
        if (at(s.memoryUtilisation(), t.memoryOverloaded())) return over("memory " + ratio(s.memoryUtilisation()));
        if (positive(s.breakerOpenPeers())) return over("circuit breaker open to " + num(s.breakerOpenPeers()) + " peer(s)");
        if (guard == Health.OVERLOADED) return over("deadline guard OVERLOADED");
        if (at(s.queuePressurePercent(), t.pressureStressed())) return new LoadResult(ServiceLoad.STRESSED, "queue pressure " + pct(s.queuePressurePercent()) + " ≥ " + pct(t.pressureStressed()));
        if (at(s.cpuUtilisation(), t.cpuStressed())) return new LoadResult(ServiceLoad.STRESSED, "CPU " + ratio(s.cpuUtilisation()) + " ≥ " + ratio(t.cpuStressed()));
        if (guard == Health.STRESSED) return new LoadResult(ServiceLoad.STRESSED, "deadline guard STRESSED");
        return new LoadResult(ServiceLoad.HEALTHY, "no load signal above its threshold");
    }
    private static LoadResult over(String reason) { return new LoadResult(ServiceLoad.OVERLOADED, reason); }
    private static boolean at(Double value, double threshold) { return value != null && Double.isFinite(value) && value >= threshold; }
    private static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    private static String pct(double v) { return Math.round(v) + "%"; }
    private static String ratio(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private static String num(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }
}
