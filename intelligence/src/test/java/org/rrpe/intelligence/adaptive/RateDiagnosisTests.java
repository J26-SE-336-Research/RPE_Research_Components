package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.rrpe.intelligence.adaptive.RateDiagnosis.Diagnosis.*;
import java.time.*;
import org.junit.jupiter.api.Test;

/** Rate v2 diagnosis (every row of guide §4.2), the formulas of §4.3 and the per-service RateState. */
class RateDiagnosisTests {
    private final AdaptiveConfig.RateSettings cfg = AdaptiveConfig.RateSettings.defaults();
    private RateDiagnosis.Result d(Double rate, Double pct, Double depth, Double p95, Double accepted, Double shed, Double p0, boolean errors) {
        return RateDiagnosis.diagnose(new RateDiagnosis.Inputs(rate, pct, depth, p95, accepted, shed, p0, errors), cfg);
    }

    @Test void aFullQueueWithANormalAppIsARateProblemNotOverload() {
        // guide example 1: rate 100, queue full (depth 200), p95 ≈ 2040 ms of which 2000 ms is queue wait
        var r = d(100.0, 100.0, 200.0, 2040.0, 100.0, 50.0, 40.0, false);
        assertEquals(RATE_TOO_LOW, r.diagnosis());
        assertEquals(2000, r.queueWaitMs(), 1e-9); assertEquals(40, r.appMs(), 1e-9);
        assertEquals(RATE_TOO_LOW, d(100.0, 85.0, 20.0, 240.0, 100.0, 0.0, 40.0, false).diagnosis(), "queue ≥ 80% with nothing shed");
        assertEquals(RATE_TOO_LOW, d(100.0, 10.0, 0.0, 40.0, 100.0, 2.0, 40.0, false).diagnosis(), "shedding alone");
    }
    @Test void aSlowAppOrRisingErrorsIsAppOverload() {
        assertEquals(APP_OVERLOADED, d(100.0, 0.0, 0.0, 600.0, 60.0, 0.0, 40.0, false).diagnosis(), "guide example 2");
        assertEquals(APP_OVERLOADED, d(100.0, 100.0, 200.0, 2600.0, 60.0, 30.0, 40.0, false).diagnosis(), "slow app wins over a full queue");
        assertEquals(APP_OVERLOADED, d(100.0, 0.0, 0.0, 40.0, 60.0, 0.0, 40.0, true).diagnosis(), "errors above baseline");
        assertNotEquals(APP_OVERLOADED, d(100.0, 0.0, 0.0, 60.0, 60.0, 0.0, 40.0, false).diagnosis(), "1.5 × P0 is still normal");
    }
    @Test void healthyWatchAndUnknown() {
        assertEquals(HEALTHY, d(100.0, 30.0, 0.0, 41.0, 80.0, 0.0, 40.0, false).diagnosis());
        assertEquals(WATCH, d(100.0, 60.0, 5.0, 90.0, 80.0, 0.0, 40.0, false).diagnosis(), "50 ≤ queue < 80, app normal");
        for (var missing : new RateDiagnosis.Result[]{d(null, 30.0, 0.0, 41.0, 80.0, 0.0, 40.0, false), d(100.0, null, 0.0, 41.0, 80.0, 0.0, 40.0, false),
                d(100.0, 30.0, null, 41.0, 80.0, 0.0, 40.0, false), d(100.0, 30.0, 0.0, 41.0, 80.0, 0.0, null, false)}) {
            assertEquals(UNKNOWN, missing.diagnosis(), missing.reason()); assertTrue(missing.reason().startsWith("missing"));
        }
    }
    @Test void raiseFollowsDemandWithHeadroomInBoundedSteps() {
        // example 1: target max(100 + 10, 150 × 1.25 = 187.5), one step at most +50% → 150, then 187.5
        assertEquals(150, RateDiagnosis.raise(100, 150, 100, 1000, cfg));
        assertEquals(187.5, RateDiagnosis.raise(150, 150, 100, 1000, cfg));
        assertEquals(90, RateDiagnosis.raise(80, 150, 100, 90, cfg), "never above the ceiling 0.9 × brokeAt");
        assertEquals(110, RateDiagnosis.raise(100, 50, 100, 1000, cfg), "at least one step a = 0.10 × goodRate");
    }
    @Test void cutHasAFloorAndUsesAcceptedThroughput() {
        double floor = RateDiagnosis.floor(100, cfg);
        assertEquals(50, floor);
        assertEquals(50, RateDiagnosis.cut(100, 60, floor, cfg), "example 2: max(50, 0.8 × 60 = 48)");
        assertEquals(64, RateDiagnosis.cut(100, 80, floor, cfg));
        assertEquals(5, RateDiagnosis.floor(4, cfg), "never below min-absolute");
        assertEquals(90, RateDiagnosis.ceiling(100.0, cfg)); assertEquals(1000, RateDiagnosis.ceiling(null, cfg));
    }
    @Test void healthyMovesSlowlyTowardDemandPlusHeadroom() {
        assertEquals(200, RateDiagnosis.healthyTarget(160, 60, 1000, cfg), "max demand 160 × 1.25 (example 4)");
        assertEquals(60, RateDiagnosis.healthyTarget(10, 60, 1000, cfg), "not below the floor");
        assertEquals(132, RateDiagnosis.healthyStep(120, 200, 120, false, cfg), "up by a = 12");
        assertEquals(500, RateDiagnosis.healthyStep(500, 200, 120, false, cfg), "down only when relaxing is due");
        assertEquals(450, RateDiagnosis.healthyStep(500, 200, 120, true, cfg), "then by at most 10%");
        assertEquals(300, RateDiagnosis.healthyStep(300, 200, 120, true, cfg), "only when R > 2 × target");
    }

    @Test void stateLearnsP0FromCleanWindowsAndOptionallyFromSheddingOnes() {
        var s = new RateState();
        for (double v : new double[]{38, 40, 42}) s.learnApp(v, true, false, true, false, false, 6);
        assertNull(s.p0(5)); s.learnApp(40.0, true, false, true, false, false, 6); s.learnApp(41.0, true, false, true, false, false, 6);
        assertEquals(40, s.p0(5));
        var shed = new RateState();
        for (int i = 0; i < 5; i++) shed.learnApp(40.0 + i, false, true, true, false, true, 6);
        assertEquals(40, shed.p0(5), "shedding from day one: the lowest app time after N full-queue windows");
        var allowed = new RateState();
        for (int i = 0; i < 5; i++) allowed.learnApp(40.0 + i, false, true, true, true, true, 6);
        assertEquals(42, allowed.p0(5), "allow-shed-windows feeds the median");
        var errors = new RateState();
        for (int i = 0; i < 5; i++) errors.learnApp(40.0, true, false, false, true, false, 6);
        assertNull(errors.p0(5), "windows with errors above baseline never teach P0");
    }
    @Test void stateRemembersDemandStreaksBrokeAtAndFlips() {
        var s = new RateState(); Instant t = Instant.parse("2026-10-20T00:00:00Z");
        assertEquals(80, s.maxDemand(80, 3)); assertEquals(160, s.maxDemand(160, 3)); assertEquals(160, s.maxDemand(80, 3));
        assertEquals(160, s.maxDemand(80, 3)); assertEquals(80, s.maxDemand(80, 3), "only the last 3 windows count");
        assertEquals(1, s.observe(RATE_TOO_LOW)); assertEquals(2, s.observe(RATE_TOO_LOW)); assertEquals(1, s.observe(HEALTHY));
        assertEquals(1, s.healthyStreak); assertEquals(2, s.observe(HEALTHY)); assertEquals(2, s.healthyStreak);
        s.rememberBrokeAt(100, t, Duration.ofMinutes(30));
        assertEquals(100, s.brokeAt(t.plusSeconds(60))); assertNull(s.brokeAt(t.plusSeconds(1800)), "forgotten after the memory");
        assertFalse(s.recordDirection(-1, t, Duration.ofMinutes(30)));
        assertTrue(s.recordDirection(1, t.plusSeconds(60), Duration.ofMinutes(30)));
        assertTrue(s.recordDirection(-1, t.plusSeconds(120), Duration.ofMinutes(30)));
        assertEquals(2, s.flips.size());
    }
    @Test void autoApprovalListNeverAcceptsChainDeadlines() {
        assertTrue(AdaptiveConfig.AutoApproval.none().kinds().isEmpty());
        assertEquals(3, new AdaptiveConfig.AutoApproval(java.util.List.of("BUCKET_RAISE", " BUCKET_CUT", "RETRY_DECREASE")).kinds().size());
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveConfig.AutoApproval(java.util.List.of("CHAIN_DEADLINE")));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveConfig.AutoApproval(java.util.List.of("LEAKY_BUCKET")));
    }
    // a raise that reaches a limit is rounded down, so the automatic-approval guard accepts it (it was held forever)
    @Test void aRaiseNeverRoundsAboveTheCeilingOrTheStepLimit() {
        var all = java.util.EnumSet.allOf(AdaptiveConfig.AutoKind.class);
        java.util.function.BiFunction<Double, Double, Boolean> guardOk = (from, to) -> AutoApprovalGuard.check(new AutoApprovalGuard.Request(
                AdaptiveConfig.AutoKind.BUCKET_RAISE, AdaptiveModels.Source.PROMETHEUS, from, to, 50.0, 56.16, true, false, null, true, false),
                all, cfg, AdaptiveConfig.RetrySettings.defaults()).approve();
        double up = RateDiagnosis.raise(51.2, 88, 100, 56.16, cfg); // the live case: ceiling 0.9 × 62.4 = 56.16
        assertEquals(56.1, up, 1e-9, "not 56.2");
        assertTrue(guardOk.apply(51.2, up));
        assertEquals(49.9, RateDiagnosis.raise(33.3, 100, 10, 1000, cfg), 1e-9, "+50% of 33.3 is 49.95: not 50.0");
        assertEquals(56.1, RateDiagnosis.healthyTarget(100, 5, 56.16, cfg), 1e-9);
        assertEquals(163.8, RateDiagnosis.raise(109.2, 150, 100, 1000, cfg), 1e-9, "values below a limit keep the normal rounding");
        assertEquals(51.2, RateDiagnosis.raise(51.2, 88, 100, 51.2, cfg), 1e-9, "no raise at the ceiling");
    }
}
