package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Joint recalibration: when the healthy rate of a service changes for good, the healthy time of every chain through it
 * changes by the change in queue wait. So the matching deadline can be recommended in the same decision. The engine
 * gives us the deadline state of the chain and the rate change.
 * <ul>
 *   <li>The healthy queue wait at a rate is Q / rate, where Q is the typical largest queue depth of healthy windows;</li>
 *   <li>the new healthy time is the old one plus the new queue wait minus the old queue wait, where the old rate is
 *       the rate in the windows the healthy time was learned from;</li>
 *   <li>the new target deadline comes from DeadlineMath.target and stays within the step limits;</li>
 *   <li>we only make a joint card when both the change of the deadline and the change of the target are big enough.</li>
 * </ul>
 */
final class HealthyRecalibration {
    private HealthyRecalibration() {}

    /**
     * The deadline state of a chain from its latest evaluation. lastCase and lastHold are the last counted decision,
     * building is true while a deadline streak is running, and unsettled says why the deadline is not settled (null
     * when it is).
     */
    record ChainView(String chain, ChainDeadlineState state, DeadlineCase lastCase, String lastHold, boolean building, double oShare,
                     double stressedShare, Lever lever, boolean pending, String unsettled, Double thMs, Double margin, Long currentMs) {}
    /** The healthy rate step of one service: {@code q} healthy queue depth, {@code rateOld} the calibration rate. */
    record RateMove(String service, double q, double rateOld, double rateLastStep, double rateNew) {}
    record Plan(String chain, long currentMs, long proposedMs, boolean passes, Map<String, Double> measurements, String text) {}

    /** Why this chain cannot take part in a joint card, or null when it can. */
    static String notReady(ChainView c, AdaptiveConfig.DeadlineSettings deadline) {
        if (c.state() != ChainDeadlineState.READY) return c.chain() + " is " + c.state();
        if (c.pending()) return c.chain() + " has a pending deadline proposal";
        if (c.unsettled() != null) return "deadline not settled: " + c.unsettled();
        if (c.lastCase() != DeadlineCase.HOLD || !"IN_BAND".equals(c.lastHold()))
            return c.chain() + ": the deadline is not in its healthy band (" + c.lastCase() + (c.lastHold() == null ? "" : " " + c.lastHold()) + ")";
        if (c.building()) return c.chain() + ": a deadline case is building";
        if (c.oShare() >= deadline.shareThreshold() || c.stressedShare() >= deadline.shareThreshold())
            return c.chain() + ": overloaded or stressed requests above the share threshold";
        if (c.lever() == Lever.RATE || c.lever() == Lever.CAPACITY) return c.chain() + ": lever " + c.lever();
        if (c.thMs() == null || c.margin() == null || c.currentMs() == null || c.currentMs() <= 0) return c.chain() + ": healthy time not identified";
        return null;
    }

    /** The recalculated deadline for one chain (C3 section 4). */
    static Plan plan(ChainView c, RateMove move, AdaptiveConfig.DeadlineSettings deadline, AdaptiveConfig.Settings settings, AdaptiveConfig.JointSettings joint) {
        long d = c.currentMs(); double th = c.thMs(), m = c.margin();
        double waitOld = 1000 * move.q() / move.rateOld(), waitNew = 1000 * move.q() / move.rateNew(), dw = waitNew - waitOld;
        double thNew = Math.max(1, th + dw);
        long min = settings.minimumDeadlineMs(), max = settings.maximumDeadlineMs();
        long target = DeadlineMath.target(th, m, min, max), targetNew = DeadlineMath.target(thNew, m, min, max);
        long lo = Math.max(DeadlineMath.ceil10(deadline.stepDownHealthy() * d), DeadlineMath.ceil10(d * (1 - settings.maximumDeadlineChange())));
        long hi = Math.min(DeadlineMath.ceil10(deadline.stepUp() * d), DeadlineMath.ceil10(d * (1 + settings.maximumDeadlineChange())));
        long proposed = Math.max(lo, Math.min(hi, targetNew));
        double threshold = Math.max(joint.minChangeFraction() * d, joint.minChangeMs());
        boolean passes = Math.abs(proposed - d) >= threshold && Math.abs(targetNew - target) >= threshold;
        var mm = new LinkedHashMap<String, Double>();
        mm.put("jointQ", move.q()); mm.put("jointRateOld", move.rateOld()); mm.put("jointRateLastStep", move.rateLastStep());
        mm.put("jointRateNew", move.rateNew()); mm.put("jointWaitOldMs", waitOld); mm.put("jointWaitNewMs", waitNew);
        mm.put("thMs", th); mm.put("thNewMs", thNew); mm.put("margin", m); mm.put("currentMs", (double) d);
        mm.put("targetMs", (double) target); mm.put("targetNewMs", (double) targetNew); mm.put("proposedMs", (double) proposed);
        mm.put("thresholdMs", threshold); mm.put("jointPasses", passes ? 1.0 : 0.0);
        String text = String.format(java.util.Locale.ROOT,
                "Joint healthy recalibration for %s: %s rate %s → %s/s (calibrated at %s/s, healthy queue Q = %.1f): wait %.0f → %.0f ms, "
                        + "T_h %.0f → %.0f ms, D* = ceil10(T_h' × %.2f) = %d ms; deadline %d → %d ms (change %d ms, threshold %.0f ms: %s)",
                c.chain(), move.service(), fmt(move.rateLastStep()), fmt(move.rateNew()), fmt(move.rateOld()), move.q(), waitOld, waitNew,
                th, thNew, 1 + m, targetNew, d, proposed, Math.abs(proposed - d), threshold, passes ? "joint" : "too small, the rate goes alone");
        return new Plan(c.chain(), d, proposed, passes, Map.copyOf(mm), text);
    }
    private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v); }
}
