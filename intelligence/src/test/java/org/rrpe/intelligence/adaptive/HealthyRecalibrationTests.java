package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** C3's joint healthy recalibration: trigger and calculation (C3 request sections 3 and 4, examples 1–3). */
class HealthyRecalibrationTests {
    static final AdaptiveConfig.DeadlineSettings DEADLINE = AdaptiveConfig.DeadlineSettings.defaults();
    static final AdaptiveConfig.Settings SETTINGS = AdaptiveConfig.Settings.defaults();
    static final AdaptiveConfig.JointSettings JOINT = AdaptiveConfig.JointSettings.on();

    static HealthyRecalibration.ChainView ready(double th, double m, long d) {
        return new HealthyRecalibration.ChainView("GET /api/orders", ChainDeadlineState.READY, DeadlineCase.HOLD, "IN_BAND", false, 0, 0,
                Lever.NONE, false, null, th, m, d);
    }
    static HealthyRecalibration.Plan plan(double q, double from, double to, double th, long d) {
        return HealthyRecalibration.plan(ready(th, .25, d), new HealthyRecalibration.RateMove("payment-svc", q, from, from, to), DEADLINE, SETTINGS, JOINT);
    }

    @Test void example1RecoveryShortensTheDeadline() {
        var p = plan(8, 50, 100, 700, 875);
        assertEquals(-80, p.measurements().get("jointWaitNewMs") - p.measurements().get("jointWaitOldMs"), 1e-9, "8/100 − 8/50 s");
        assertEquals(620, p.measurements().get("thNewMs"), 1e-9);
        assertEquals(780, p.proposedMs(), "ceil10(620 × 1.25)");
        assertTrue(p.passes(), "|780 − 875| = 95 ≥ 5% of 875");
        assertEquals(43.75, p.measurements().get("thresholdMs"), 1e-9);
        assertTrue(p.text().contains("875 → 780 ms"), p.text());
    }
    @Test void example2CapacityDropLengthensTheDeadline() {
        var p = plan(8, 100, 60, 620, 780);
        assertEquals(673.3, p.measurements().get("thNewMs"), .1);
        assertEquals(850, p.proposedMs(), "ceil10(673.3 × 1.25)");
        assertTrue(p.passes(), "|850 − 780| = 70 ≥ 39");
    }
    @Test void example3AnEmptyHealthyQueueLetsTheRateGoAlone() {
        var p = plan(0, 50, 100, 700, 875);
        assertEquals(880, p.proposedMs(), "ceil10(700 × 1.25); 5 ms from D, below the threshold"); assertFalse(p.passes());
        // a deadline elsewhere in its healthy band is deadline v2's job, not caused by the rate: still no joint
        var inBand = HealthyRecalibration.plan(ready(700, .25, 940), new HealthyRecalibration.RateMove("payment-svc", 0, 50, 50, 100), DEADLINE, SETTINGS, JOINT);
        assertFalse(inBand.passes(), inBand.text());
        // a small queue: the change stays below the threshold
        assertFalse(plan(1, 90, 100, 700, 875).passes());
    }
    @Test void theStepLimitsClampTheNewDeadline() {
        var p = plan(80, 20, 100, 700, 875); // T_h' = 700 + 800 − 4000 → far below
        assertEquals(DeadlineMath.ceil10(.80 * 875), p.proposedMs(), "not below ceil10(stepDownHealthy × D)");
        var up = plan(80, 100, 20, 700, 875);
        assertEquals(DeadlineMath.ceil10(1.5 * 875), up.proposedMs(), "not above ceil10(stepUp × D)");
    }
    @Test void onlyAHealthySettledChainInItsBandTakesPart() {
        assertNull(HealthyRecalibration.notReady(ready(700, .25, 875), DEADLINE));
        var base = ready(700, .25, 875);
        Map<String, HealthyRecalibration.ChainView> blocked = new LinkedHashMap<>();
        blocked.put("LEARNING", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.LEARNING, DeadlineCase.HOLD, "IN_BAND", false, 0, 0, Lever.NONE, false, null, 700.0, .25, 875L));
        blocked.put("pending", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.READY, DeadlineCase.HOLD, "IN_BAND", false, 0, 0, Lever.NONE, true, null, 700.0, .25, 875L));
        blocked.put("deadline not settled", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.READY, DeadlineCase.HOLD, "IN_BAND", false, 0, 0, Lever.NONE, false, "x changed", 700.0, .25, 875L));
        blocked.put("A_RAISE", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.READY, DeadlineCase.A_RAISE, null, false, 0, 0, Lever.NONE, false, null, 700.0, .25, 875L));
        blocked.put("COOLDOWN", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.READY, DeadlineCase.HOLD, "COOLDOWN", false, 0, 0, Lever.NONE, false, null, 700.0, .25, 875L));
        blocked.put("building", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.READY, DeadlineCase.HOLD, "IN_BAND", true, 0, 0, Lever.NONE, false, null, 700.0, .25, 875L));
        blocked.put("share", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.READY, DeadlineCase.HOLD, "IN_BAND", false, .06, 0, Lever.NONE, false, null, 700.0, .25, 875L));
        blocked.put("lever RATE", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.READY, DeadlineCase.HOLD, "IN_BAND", false, 0, 0, Lever.RATE, false, null, 700.0, .25, 875L));
        blocked.put("healthy time", new HealthyRecalibration.ChainView(base.chain(), ChainDeadlineState.READY, DeadlineCase.HOLD, "IN_BAND", false, 0, 0, Lever.NONE, false, null, null, .25, 875L));
        blocked.forEach((why, view) -> assertNotNull(HealthyRecalibration.notReady(view, DEADLINE), why));
        assertTrue(HealthyRecalibration.notReady(blocked.get("deadline not settled"), DEADLINE).startsWith("deadline not settled"));
    }
    @Test void theOldRateIsTheMedianRateOfTheCalibrationWindowsAndQIsTheMedianHealthyDepth() {
        var windows = new ArrayDeque<Map<String, Double>>(List.of(Map.of("payment-svc", 50.0), Map.of("payment-svc", 55.0), Map.of("payment-svc", 50.0), Map.of("order-svc", 9.0)));
        assertEquals(50, AdaptiveEngine.calibrationRate(windows, "payment-svc"));
        assertNull(AdaptiveEngine.calibrationRate(windows, "gateway"));
        var rs = new RateState();
        for (double d : new double[]{6, 8, 9, 8, 20}) rs.healthyDepth.addLast(d);
        assertEquals(8, rs.healthyQueue(5)); assertNull(rs.healthyQueue(6));
    }
    @Test void jointIsOffByDefault() {
        assertFalse(AdaptiveConfig.JointSettings.defaults().enabled());
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveConfig.JointSettings(true, 1.5, 10));
    }
}
