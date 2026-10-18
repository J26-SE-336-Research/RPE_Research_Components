package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Guide §7 examples 1–6 end to end on a virtual clock (window 5 min, cycle 1 min, demo deadline settings). A simulated
 * reviewer approves every proposal and it is applied on the next cycle: proposal sequence, no reversals, final deadline.
 */
class DeadlineExamplesTests {
    static AdaptiveConfig.Settings settings() {
        var s = AdaptiveConfig.Settings.defaults();
        return new AdaptiveConfig.Settings(s.window(), s.cycle(), 6, s.minimumServiceRequests(), s.minimumChainRequests(), s.persistence(),
                s.recoveryWindows(), s.cooldown(), s.ttl(), s.stabilityTolerance(), s.stressedMad(), s.overloadedMad(), s.zeroMadRelativeFloor(),
                s.ratioFloor(), s.maximumRateChange(), s.maximumRetryRatio(), s.minimumDeadlineMs(), s.maximumDeadlineMs(), s.maximumDeadlineChange());
    }
    record Run(ChainSimulation.ChainResult result, AuditLog audit) {}
    static Run simulate(DeadlineExamples.Example example) {
        var settings = settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-13T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var audit = new AuditLog("", "", "");
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null); engine.setAudit(audit);
        var result = ChainSimulation.run(engine, workflow, clock, settings, List.of(example.script()), true, 300);
        return new Run(result.chains().get(0), audit);
    }
    static Run run(String scenario) { return simulate(DeadlineExamples.byScenario(scenario)); }
    static List<long[]> steps(ChainSimulation.ChainResult r) { return r.steps().stream().map(s -> new long[]{s.fromMs(), s.toMs()}).toList(); }
    static double exceed(ChainSimulation.Profile p, long d) {
        double[] x = p.durations(300, 1); return Arrays.stream(x).filter(v -> v >= d).count() / (double) x.length;
    }

    @Test void example1TooLongUnderOverloadLowersUntilTheSignalFiresThenTightensIntoTheBand() {
        var run = run("deadline-too-long-under-overload"); var r = run.result();
        var overloadSteps = r.steps().stream().filter(s -> s.phase().startsWith("payment")).toList();
        assertFalse(overloadSteps.isEmpty());
        assertTrue(overloadSteps.stream().allMatch(s -> s.decisionCase() == DeadlineCase.B_LOWER_OVERLOAD && s.toMs() < s.fromMs()), "decreases only (Case B)");
        assertTrue(r.steps().stream().noneMatch(s -> s.toMs() > s.fromMs()), "never an increase");
        long atEndOfOverload = r.phaseEndMs().get(1);
        assertTrue(exceed(DeadlineExamples.O900, atEndOfOverload) >= .05, "the overload signal fires at " + atEndOfOverload);
        assertTrue(r.finalMs() >= 490 && r.finalMs() <= 612, "after recovery D is in [D*, 1.25·D*]: " + r.finalMs());
        assertEquals(550, r.finalMs());
        assertTrue(r.steps().stream().noneMatch(s -> s.headline().contains("VERIFICATION_FAILED")), "no reversals");
        var kinds = run.audit().recent(500).stream().map(AuditLog.Entry::kind).toList();
        assertTrue(kinds.contains(AuditLog.DEADLINE_DECISION)); assertTrue(kinds.contains(AuditLog.DEADLINE_VERIFICATION));
        assertTrue(kinds.contains(AuditLog.DEADLINE_STATE_CHANGED));
        assertEquals(DeadlineCase.HOLD, r.lastEvidence().decisionCase()); assertEquals("IN_BAND", r.lastEvidence().holdReason());
    }
    @Test void example2ServicesSlowingAreHeldByTheStressGate() {
        var r = run("services-slowing").result();
        assertTrue(r.steps().isEmpty(), "the old logic raised 1125 → 1428 here");
        assertEquals(1250, r.finalMs());
        assertEquals("STRESS_GATE", r.lastEvidence().holdReason());
    }
    @Test void example3TooShortFromDayOneIsFixedInTwoSteps() {
        var r = run("deadline-too-short-day-one").result();
        assertEquals(List.of("300→450", "450→680"), r.steps().stream().map(s -> s.fromMs() + "→" + s.toMs()).toList());
        assertTrue(r.steps().stream().allMatch(s -> s.decisionCase() == DeadlineCase.A_RAISE));
        assertTrue(r.steps().get(0).headline().contains("censored"));
        assertEquals(680, r.finalMs());
        assertTrue(r.lastEvidence().verification().startsWith("PASS"), r.lastEvidence().verification());
        assertEquals("IDENTIFIED", r.lastEvidence().model().status(), "T_h identified once the short-deadline windows age out");
        assertEquals(500, r.lastEvidence().model().thMs());
    }
    @Test void example4AFullQueueIsARateProblemNotADeadlineProblem() {
        var r = run("queue-rate-not-deadline").result();
        assertTrue(r.steps().isEmpty(), "the old logic raised 720 → 1620 here");
        assertEquals(800, r.finalMs());
        assertEquals(Lever.RATE, r.lastEvidence().lever());
        assertNotNull(r.lastEvidence().advisory()); assertTrue(r.lastEvidence().advisory().contains("inventory"));
    }
    @Test void example5MixedPathsKeepTheMixtureHealthyTime() {
        var r = run("mixed-paths").result();
        assertTrue(r.steps().isEmpty(), "the old logic raised 638 → 957 here");
        assertEquals(700, r.finalMs());
        var model = r.lastEvidence().model();
        assertTrue(model.thMs() >= 560, "T_h stays near the mixed p99, not the cache p99 (~150): " + model.thMs());
        assertTrue(model.coverageGap(), "the stressed database path is a coverage gap: tightening is blocked");
    }
    @Test void example6RequestsCutByARejectingMiddlewareHoldAndPause() {
        var run = run("rejecting-middleware-504"); var r = run.result();
        assertEquals(r.phaseStartMs().get(1), r.phaseEndMs().get(1), "no change while requests are cut");
        assertEquals(ChainDeadlineState.PAUSED, r.lastEvidence().state());
        assertEquals("PAUSED", r.lastEvidence().holdReason());
        assertEquals("REJECTING_MIDDLEWARE", r.lastEvidence().cutHint());
        var kinds = run.audit().recent(500).stream().map(AuditLog.Entry::kind).toList();
        assertTrue(kinds.contains(AuditLog.DEADLINE_CUT_DETECTED));
        assertTrue(run.audit().recent(500).stream().anyMatch(e -> e.kind().equals(AuditLog.DEADLINE_STATE_CHANGED) && e.detail().contains("PAUSED")));
    }
}
