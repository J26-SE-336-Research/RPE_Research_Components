package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.util.*;

/**
 * Which lever fits an overloaded service. Queue wait counts against the deadline, so a full queue with idle CPU is a
 * rate problem, not a deadline problem:
 * <ul>
 *   <li>RATE if the queue wait is at least half of the request time and CPU is low (unknown CPU never blocks);</li>
 *   <li>CAPACITY if CPU is at least 0.85 or throttling at least 0.25 (only when we have that data);</li>
 *   <li>otherwise DEADLINE.</li>
 * </ul>
 * Without queue wait data, a queue at the overloaded level with low CPU is RATE too, marked as inferred.
 */
public final class LeverDiagnosis {
    private LeverDiagnosis() {}
    public record LeverResult(Lever lever, boolean inferred, String reason) {}

    public static LeverResult diagnose(String service, ServiceLoadSnapshot snapshot, Double queueWaitShare,
            AdaptiveConfig.LeverSettings lever, AdaptiveConfig.LoadSettings load) {
        var s = snapshot == null ? ServiceLoadSnapshot.unavailable(service) : snapshot;
        Double wait = queueWaitShare != null ? queueWaitShare : s.queueWaitShare();
        boolean cpuLow = s.cpuUtilisation() == null || s.cpuUtilisation() < lever.cpuLow();
        if (wait != null && wait >= lever.queueShare() && cpuLow) {
            return new LeverResult(Lever.RATE, false, service + ": queue wait is " + Math.round(wait * 100) + "% of the request time with low CPU");
        }
        if (s.cpuUtilisation() != null && s.cpuUtilisation() >= load.cpuOverloaded()
                || s.cpuThrottling() != null && s.cpuThrottling() >= load.throttleOverloaded()) {
            return new LeverResult(Lever.CAPACITY, false, service + ": CPU saturated or throttled");
        }
        if (wait == null && s.queuePressurePercent() != null && s.queuePressurePercent() >= load.pressureOverloaded() && cpuLow) {
            return new LeverResult(Lever.RATE, true, service + ": inbound queue " + Math.round(s.queuePressurePercent())
                    + "% full with low or unknown CPU (no queue-wait data; inferred)");
        }
        return new LeverResult(Lever.DEADLINE, false, service + ": no rate or capacity signal");
    }

    /** The chain's lever from its overloaded services on O paths: RATE before CAPACITY before DEADLINE; NONE if none. */
    public static LeverResult chain(Collection<String> overloadedServices, Map<String, ServiceLoadSnapshot> snapshots,
            AdaptiveConfig.LeverSettings lever, AdaptiveConfig.LoadSettings load) {
        if (overloadedServices.isEmpty()) return new LeverResult(Lever.NONE, false, "no overloaded service on the chain's paths");
        LeverResult capacity = null, deadline = null;
        for (String service : new TreeSet<>(overloadedServices)) {
            var r = diagnose(service, snapshots.get(service), null, lever, load);
            if (r.lever() == Lever.RATE) return r;
            if (r.lever() == Lever.CAPACITY && capacity == null) capacity = r;
            if (r.lever() == Lever.DEADLINE && deadline == null) deadline = r;
        }
        return capacity != null ? capacity : deadline;
    }
}
