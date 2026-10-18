package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** DeadlineVerifier (guide §6.7), the pause rules (§6.6) and the end-to-end revert path. */
class DeadlineVerifierTests {
    private final AdaptiveConfig.DeadlineSettings cfg = AdaptiveConfig.DeadlineSettings.demo();
    private static Proportion p(int x, int n) { return RobustStatistics.wilsonOneSided(x, n, 1.645, 1); }
    private DeadlineVerifier.Result verify(DeadlineCase c, Proportion before, Proportion h, Proportion o, boolean cleared) {
        return DeadlineVerifier.verify(c, before, h, o, cleared, cfg, 100).result();
    }

    @Test void afterARaise() {
        assertEquals(DeadlineVerifier.Result.PASS, verify(DeadlineCase.A_RAISE, p(75, 300), p(0, 600), p(0, 0), true));
        assertEquals(DeadlineVerifier.Result.CONTINUE, verify(DeadlineCase.A_RAISE, p(75, 300), p(18, 600), p(0, 0), true), "helped, not done (example 3: 450 ms)");
        assertEquals(DeadlineVerifier.Result.FAIL, verify(DeadlineCase.A_RAISE, p(18, 600), p(30, 600), p(0, 0), true));
        assertEquals(DeadlineVerifier.Result.INCONCLUSIVE, verify(DeadlineCase.A_RAISE, p(75, 300), p(0, 50), p(0, 0), true));
    }
    @Test void afterTightening() {
        assertEquals(DeadlineVerifier.Result.PASS, verify(DeadlineCase.D_TIGHTEN, p(0, 600), p(0, 600), p(0, 0), true));
        assertEquals(DeadlineVerifier.Result.FAIL, verify(DeadlineCase.D_TIGHTEN, p(0, 600), p(60, 600), p(0, 0), true), "healthy requests over-signalled");
        assertEquals(DeadlineVerifier.Result.INCONCLUSIVE, verify(DeadlineCase.D_TIGHTEN, p(0, 600), p(0, 10), p(0, 0), true));
    }
    @Test void afterLoweringUnderOverload() {
        assertEquals(DeadlineVerifier.Result.PASS, verify(DeadlineCase.B_LOWER_OVERLOAD, null, p(0, 0), p(300, 600), false), "the signal fires");
        assertEquals(DeadlineVerifier.Result.PASS, verify(DeadlineCase.B_LOWER_OVERLOAD, null, p(0, 600), p(0, 0), true), "the overload cleared");
        assertEquals(DeadlineVerifier.Result.CONTINUE, verify(DeadlineCase.B_LOWER_OVERLOAD, null, p(0, 0), p(3, 600), false), "example 1: 1500 → 1130 next");
        assertEquals(DeadlineVerifier.Result.FAIL, verify(DeadlineCase.B_LOWER_OVERLOAD, null, p(60, 600), p(300, 600), false), "healthy requests harmed");
        assertEquals(DeadlineVerifier.Result.INCONCLUSIVE, verify(DeadlineCase.B_LOWER_OVERLOAD, null, p(0, 10), p(0, 10), false));
    }

    @Test void twoFailedVerificationsWithinAnHourPause() {
        var safety = new DeadlineSafety(); Instant t = Instant.parse("2026-10-13T00:00:00Z");
        assertNull(safety.verificationFailed(t));
        assertNotNull(safety.verificationFailed(t.plusSeconds(3000)));
        var later = new DeadlineSafety();
        assertNull(later.verificationFailed(t)); assertNull(later.verificationFailed(t.plusSeconds(3600)), "an hour apart: no pause");
    }
    @Test void twoReversalsWithinAnHourPause() {
        var safety = new DeadlineSafety(); Instant t = Instant.parse("2026-10-13T00:00:00Z");
        assertNull(safety.applied(1200, 960, t));
        assertNull(safety.applied(960, 1200, t.plusSeconds(600)), "first reversal");
        assertTrue(safety.applied(1200, 960, t.plusSeconds(1200)).startsWith("TWO_REVERSALS"));
        var sameDirection = new DeadlineSafety();
        assertNull(sameDirection.applied(2000, 1500, t)); assertNull(sameDirection.applied(1500, 1130, t.plusSeconds(600)));
        assertNull(sameDirection.applied(1130, 850, t.plusSeconds(1200)), "steps in one direction are not reversals");
    }
    @Test void aRejectingMiddlewareInTwoCountedWindowsPauses() {
        var safety = new DeadlineSafety(); Instant t = Instant.parse("2026-10-13T00:00:00Z");
        assertNull(safety.rejectingMiddleware(t));
        assertTrue(safety.rejectingMiddleware(t.plusSeconds(300)).startsWith("REJECTING_MIDDLEWARE"));
    }

    /** A tightening that over-signals healthy requests fails verification and creates a human-approved revert. */
    @Test void aFailedVerificationProposesARevertAndAuditsIt() {
        var settings = DeadlineExamplesTests.settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-13T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var audit = new AuditLog("", "", "");
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null); engine.setAudit(audit);
        var slower = ChainSimulation.profile(700, 1000, 1050, 1080, 1100); // still healthy services, but slower requests
        var script = new ChainSimulation.Script("GET /api/orders", 1200, List.of(
                new ChainSimulation.Phase("healthy", 12, List.of(new ChainSimulation.PathTraffic(1, DeadlineExamples.H400, "gateway", "order"))),
                new ChainSimulation.Phase("slower after tightening", 30, List.of(new ChainSimulation.PathTraffic(1, slower, "gateway", "order")))));
        var r = ChainSimulation.run(engine, workflow, clock, settings, List.of(script), true, 300).chains().get(0);
        assertEquals("1200→960", r.steps().get(0).fromMs() + "→" + r.steps().get(0).toMs(), "Case D at the end of learning");
        var revert = r.steps().stream().filter(s -> s.fromMs() == 960 && s.toMs() == 1200).findFirst().orElseThrow();
        assertEquals("slower after tightening", revert.phase());
        var proposals = workflow.list().stream().filter(c -> c.proposal().proposedValue() == 1200).toList();
        assertTrue(proposals.get(0).proposal().evidence().stream().anyMatch(e -> e.contains("VERIFICATION_FAILED")), "the revert says why");
        assertTrue(audit.recent(500).stream().anyMatch(e -> e.kind().equals(AuditLog.DEADLINE_VERIFICATION) && "FAIL".equals(e.decision())));
        assertEquals(1200, r.finalMs());
    }

    @Test void aConfigurationConflictPausesTheChainUntilTheCauseHasClearedForThirtyMinutes() {
        var settings = DeadlineExamplesTests.settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-13T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var audit = new AuditLog("", "", "");
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null); engine.setAudit(audit);
        var phase = new ChainSimulation.Phase("healthy", 1, List.of(new ChainSimulation.PathTraffic(1, DeadlineExamples.H400, "gateway", "order")));
        var script = new ChainSimulation.Script("GET /api/orders", 600, List.of(new ChainSimulation.Phase("healthy", 16, phase.paths())));
        ChainSimulation.run(engine, workflow, clock, settings, List.of(script), true, 300);
        engine.pauseChain("GET /api/orders", Source.MOCK, "CONFIGURATION_CONFLICT: test");
        assertTrue(audit.recent(50).stream().anyMatch(e -> e.kind().equals(AuditLog.DEADLINE_STATE_CHANGED) && "PAUSED".equals(e.decision())));
        var resumed = ChainSimulation.run(engine, workflow, clock, settings, List.of(new ChainSimulation.Script("GET /api/orders", 600,
                List.of(new ChainSimulation.Phase("healthy", 40, phase.paths())))), true, 300).chains().get(0);
        var states = resumed.evidence().stream().map(DeadlineEvidence::state).toList();
        assertEquals(ChainDeadlineState.PAUSED, states.get(0));
        assertEquals(ChainDeadlineState.READY, states.get(states.size() - 1), "the cause has cleared for 30 minutes");
    }

    @Test void withdrawPendingIncreaseLeavesOtherProposalsAlone() {
        var clock = new AdaptiveEngineTests.TestClock();
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), clock);
        workflow.putPolicy(new Policy("GET /r", "v1", clock.now.plusSeconds(3600), null, null, null, 1000.0), Source.TEMPO);
        var down = workflow.offer(new Proposal(UUID.randomUUID(), "GET /r", Action.CHAIN_DEADLINE, Source.TEMPO, "v1", 1000, 800, "milliseconds",
                clock.now, clock.now.plusSeconds(300), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("e")));
        workflow.withdrawPendingIncrease("GET /r", Source.TEMPO, 1000, "Case C");
        assertEquals(Delivery.WAITING_FOR_COMPONENT_3, workflow.find(down.proposal().id()).delivery(), "a decrease is not withdrawn");
        var up = workflow.offer(new Proposal(UUID.randomUUID(), "GET /r", Action.CHAIN_DEADLINE, Source.TEMPO, "v1", 1000, 1500, "milliseconds",
                clock.now, clock.now.plusSeconds(300), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("e")));
        workflow.withdrawPendingIncrease("GET /r", Source.TEMPO, 1000, "Case C");
        assertEquals(Delivery.SUPERSEDED, workflow.find(up.proposal().id()).delivery());
        assertEquals("Case C", workflow.find(up.proposal().id()).detail());
        assertTrue(workflow.pending("GET /r", Action.CHAIN_DEADLINE, Source.TEMPO).isEmpty());
    }
}
