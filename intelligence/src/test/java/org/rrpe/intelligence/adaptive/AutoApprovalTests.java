package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Automatic approval of rate and retry changes with its safety rules (guide §6). */
class AutoApprovalTests {
    static final List<String> DEMO = List.of("BUCKET_RAISE", "BUCKET_CUT", "RETRY_DECREASE");

    record Run(ServiceSimulation.Result result, AuditLog audit, PolicyWorkflow workflow) {
        List<ServiceSimulation.Step> rate() { return result.steps().stream().filter(s -> s.action() == Action.LEAKY_BUCKET).toList(); }
        long audited(String kind) { return audit.recent(500).stream().filter(e -> e.kind().equals(kind)).count(); }
    }
    static Run run(ServiceSimulation.Scenario s, Source source, List<String> allowed, java.util.function.Consumer<PolicyWorkflow> before) {
        var settings = DeadlineExamplesTests.settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-20T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        var audit = new AuditLog("", "", "");
        workflow.setAudit(audit); engine.setAudit(audit);
        engine.configureAutoApproval(new AdaptiveConfig.AutoApproval(allowed));
        if (before != null) { before.accept(workflow); engine.chain(lockedChain(source)); }
        return new Run(ServiceSimulation.run(engine, workflow, clock, settings, "inventory-svc", source, s, false), audit, workflow);
    }
    static final String CHAIN = "GET /api/inventory | api-gateway > inventory-svc";
    static ChainWindow lockedChain(Source source) {
        Source chainSource = source == Source.MOCK ? Source.MOCK : Source.TEMPO;
        var now = Instant.parse("2026-10-20T00:00:00Z");
        return new ChainWindow(CHAIN, List.of("api-gateway", "inventory-svc"), now.minusSeconds(300), now, chainSource, true, 1000.0, 0.0, 500.0, Health.HEALTHY, List.of());
    }

    /** Demand 150 above the rate 100; the app handles only 120 req/s, so a raise to 150 slows it down. */
    static final ServiceSimulation.Scenario RAISE_HURTS = new ServiceSimulation.Scenario("raise-hurts", "raise beyond the app capacity", 100, .05, List.of(
            new ServiceSimulation.Phase("healthy (learn), demand 80", 16, 80, 120, 40),
            new ServiceSimulation.Phase("demand 150 > rate 100, app capacity 120", 90, 150, 120, 40)), "raise, undo, pause");

    @Test void allowedChangesAreApprovedByC4AutoAndAudited() {
        var r = run(ServiceSimulation.byId("rate-too-low"), Source.PROMETHEUS, DEMO, null);
        assertFalse(r.rate().isEmpty());
        assertTrue(r.rate().stream().allMatch(s -> "c4-auto".equals(s.reviewer()) && s.to() > s.from()), r.rate().toString());
        assertTrue(r.result().finalRate() >= 187.5, "reaches demand × 1.25 without a reviewer: " + r.result().finalRate());
        assertEquals(0, r.result().shedAtEnd());
        assertEquals(r.rate().size(), r.audited(AuditLog.RATE_AUTO_APPROVED));
        var entry = r.audit().recent(500).stream().filter(e -> e.kind().equals(AuditLog.RATE_AUTO_APPROVED)).findFirst().orElseThrow();
        assertEquals("c4-auto", entry.actor());
        assertTrue(entry.detail().contains("BUCKET_RAISE") && entry.detail().contains("queuePercent") && entry.detail().contains("appMs"), entry.detail());
    }

    @Test void anEmptyAllowListKeepsEveryRecommendationWaitingForAReviewer() {
        var r = run(ServiceSimulation.byId("rate-too-low"), Source.PROMETHEUS, List.of(), null);
        assertFalse(r.rate().isEmpty());
        assertTrue(r.result().steps().stream().allMatch(s -> "pending".equals(s.reviewer())), r.result().steps().toString());
        assertEquals(0, r.audited(AuditLog.RATE_AUTO_APPROVED));
    }

    @Test void syntheticRecommendationsAreNeverApprovedAutomatically() {
        var r = run(ServiceSimulation.byId("rate-too-low"), Source.MOCK, DEMO, null);
        assertTrue(r.result().steps().stream().allMatch(s -> "pending".equals(s.reviewer())), r.result().steps().toString());
    }

    @Test void chainDeadlinesAreNeverApprovedAutomatically() {
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-20T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), DeadlineExamplesTests.settings(), clock);
        workflow.putPolicy(new Policy(CHAIN, "e1", clock.now.plusSeconds(3600), null, null, null, 1000.0), Source.TEMPO);
        var proposal = new Proposal(UUID.randomUUID(), CHAIN, Action.CHAIN_DEADLINE, Source.TEMPO, "e1", 1000, 1500, "milliseconds", clock.now,
                clock.now.plusSeconds(600), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("e"));
        assertThrows(IllegalArgumentException.class, () -> workflow.offer(proposal, new Review("c4-auto", true, "x")));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveConfig.AutoApproval(List.of("CHAIN_DEADLINE")));
        assertEquals(Decision.PENDING_APPROVAL, workflow.offer(proposal).decision());
    }

    // rates never wait for a person (owner rule, 22 Oct): the locked rate change is held and tried again, no card for a reviewer
    @Test void aPendingDeadlineChangeOnAChainThroughTheServiceLocksAutomaticRateChanges() {
        var r = run(ServiceSimulation.byId("rate-too-low"), Source.PROMETHEUS, DEMO, workflow -> {
            var now = Instant.parse("2026-10-20T00:00:00Z");
            workflow.putPolicy(new Policy(CHAIN, "e1", now.plusSeconds(86400), null, null, null, 1000.0), Source.TEMPO);
            // a deadline recommendation waiting for a reviewer for the whole run
            workflow.offer(new Proposal(UUID.randomUUID(), CHAIN, Action.CHAIN_DEADLINE, Source.TEMPO, "e1", 1000, 1200, "milliseconds", now,
                    now.plusSeconds(86400), Map.of("windowEndEpochSeconds", (double) now.plusSeconds(86400).getEpochSecond()), List.of("pending deadline")));
        });
        assertTrue(r.rate().isEmpty(), "no rate change while the deadline card waits, and no card for a person: " + r.rate());
        assertTrue(r.workflow().list().stream().noneMatch(c -> c.proposal().action() == Action.LEAKY_BUCKET), "no rate card at all");
        assertTrue(r.result().outcomes().stream().flatMap(o -> o.assessment().evidence().stream())
                .anyMatch(e -> e.startsWith("held: rate") && e.contains("deadline not settled") && e.contains("trying again")), "visible as a held change");
    }

    // rates never wait for a person (owner rule, 22 Oct): after the pause the next change is held, not a card for a reviewer
    @Test void aRaiseThatSlowsTheAppIsUndoneAndRepeatedUndosPauseAutomaticApproval() {
        var r = run(RAISE_HURTS, Source.PROMETHEUS, DEMO, null);
        var steps = r.rate();
        var reverts = steps.stream().filter(s -> "c4-auto-revert".equals(s.reviewer())).toList();
        assertTrue(reverts.size() >= 1, steps.toString());
        int first = steps.indexOf(reverts.get(0));
        var failedRaise = steps.get(first - 1); // the automatic raise that the first undo reverts
        assertEquals("c4-auto", failedRaise.reviewer()); assertTrue(failedRaise.to() > failedRaise.from());
        assertEquals(failedRaise.from(), reverts.get(0).to(), "undo to the value before the raise");
        assertTrue(reverts.get(0).evidence().contains("Automatic undo"));
        assertEquals(reverts.size(), r.audited(AuditLog.RATE_AUTO_REVERTED));
        // the next raise stays below the ceiling learned from the failed raise (0.9 × the rate that slowed the app)
        var next = steps.get(first + 1);
        assertTrue(next.to() > next.from() && next.to() <= .9 * failedRaise.to() + .1, steps.toString());
        // after two undos automatic approval pauses: the next change is held (tried again), never a card for a person
        assertEquals(2, reverts.size(), steps.toString());
        assertEquals(1, r.audited(AuditLog.RATE_AUTO_PAUSED), steps.toString());
        assertTrue(steps.stream().noneMatch(s -> "pending".equals(s.reviewer())), "no card for a person: " + steps);
        assertTrue(r.result().outcomes().stream().flatMap(o -> o.assessment().evidence().stream())
                .anyMatch(e -> e.startsWith("held: rate") && e.contains("paused")), "held while paused");
    }

    @Test void aRealOverloadInAutomaticModeIsCutInCheckedStepsAndSettlesNearTheAppCapacity() {
        var r = run(ServiceSimulation.byId("rate-real-overload"), Source.PROMETHEUS, DEMO, null);
        var overload = r.rate().stream().filter(s -> s.phase().startsWith("app capacity 60")).toList();
        var cuts = overload.stream().filter(s -> s.to() < s.from() && !"c4-auto-revert".equals(s.reviewer())).toList();
        assertTrue(cuts.size() >= 2, "the overload is cut: " + overload);
        assertTrue(cuts.stream().allMatch(s -> "c4-auto".equals(s.reviewer()) && s.to() >= .8 * s.from() - .05), "automatic steps of at most −20%: " + cuts);
        assertTrue(cuts.get(0).evidence().contains("automatic mode: a checked step"), cuts.get(0).evidence());
        assertTrue(r.result().lowestRate() >= 50, "never below the floor 0.5 × good rate 100: " + r.rate());
        double settled = overload.get(overload.size() - 1).to();
        assertTrue(settled >= 50 && settled <= 66, "settles near the app capacity 60: " + overload);
        assertEquals(0, r.audited(AuditLog.RATE_AUTO_PAUSED), "no back-and-forth, so no pause: " + r.rate());
        assertTrue(r.result().finalRate() > settled, "rises again once the app recovers: " + r.rate());
        var retry = r.result().steps().stream().filter(s -> s.action() == Action.RETRY_BUDGET && s.phase().startsWith("app capacity 60")).findFirst().orElseThrow();
        assertEquals(0, retry.to(), "retry 0 while the app is overloaded");
        assertEquals("c4-auto", retry.reviewer(), "RETRY_DECREASE is allowed");
    }

    @Test void guardStepLimits() {
        var rate = AdaptiveConfig.RateSettings.defaults(); var retry = AdaptiveConfig.RetrySettings.defaults();
        var all = EnumSet.allOf(AdaptiveConfig.AutoKind.class);
        java.util.function.Function<AutoApprovalGuard.Request, Boolean> ok = q -> AutoApprovalGuard.check(q, all, rate, retry).approve();
        var raise = AdaptiveConfig.AutoKind.BUCKET_RAISE; var cut = AdaptiveConfig.AutoKind.BUCKET_CUT;
        assertTrue(ok.apply(new AutoApprovalGuard.Request(raise, Source.PROMETHEUS, 100, 150, 50.0, 200.0, true, false, null, true, false)));
        assertFalse(ok.apply(new AutoApprovalGuard.Request(raise, Source.PROMETHEUS, 100, 151, 50.0, 200.0, true, false, null, true, false)), "+50% at most");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(raise, Source.PROMETHEUS, 100, 140, 50.0, 130.0, true, false, null, true, false)), "ceiling");
        assertTrue(ok.apply(new AutoApprovalGuard.Request(raise, Source.PROMETHEUS, 50, 100, 50.0, 90.0, true, true, null, true, false)), "an undo may return to the previous value");
        assertTrue(ok.apply(new AutoApprovalGuard.Request(cut, Source.PROMETHEUS, 100, 80, 50.0, null, true, false, null, true, false)));
        assertFalse(ok.apply(new AutoApprovalGuard.Request(cut, Source.PROMETHEUS, 100, 79, 50.0, null, true, false, null, true, false)), "−20% at most");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(cut, Source.PROMETHEUS, 60, 49, 50.0, null, true, false, null, true, false)), "floor");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(cut, Source.PROMETHEUS, 100, 90, 50.0, null, false, false, null, true, false)), "persistence");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(cut, Source.PROMETHEUS, 100, 90, 50.0, null, true, false, "GET /api/orders is PROPOSED", true, false)), "cross-lever lock");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(cut, Source.PROMETHEUS, 100, 90, 50.0, null, true, false, null, true, true)), "paused");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(cut, Source.MOCK, 100, 90, 50.0, null, true, false, null, true, false)), "synthetic");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(AdaptiveConfig.AutoKind.RETRY_DECREASE, Source.PROMETHEUS, .10, 0, null, null, true, false, null, false, false)), "rate UNKNOWN: not even the protective retry 0");
        var down = AdaptiveConfig.AutoKind.RETRY_DECREASE; var up = AdaptiveConfig.AutoKind.RETRY_INCREASE;
        assertTrue(ok.apply(new AutoApprovalGuard.Request(down, Source.PROMETHEUS, .10, 0, null, null, true, false, null, true, false)), "protective 0");
        assertTrue(ok.apply(new AutoApprovalGuard.Request(down, Source.PROMETHEUS, .05, .02, null, null, true, false, null, true, false)), "protective 0.02");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(down, Source.PROMETHEUS, .10, .05, null, null, true, false, null, true, false)));
        assertTrue(ok.apply(new AutoApprovalGuard.Request(up, Source.PROMETHEUS, .06, .08, null, null, true, false, null, true, false)));
        assertFalse(ok.apply(new AutoApprovalGuard.Request(up, Source.PROMETHEUS, .04, .10, null, null, true, false, null, true, false)));
        assertFalse(AutoApprovalGuard.check(new AutoApprovalGuard.Request(up, Source.PROMETHEUS, .06, .08, null, null, true, false, null, true, false),
                EnumSet.of(down), rate, retry).approve(), "RETRY_INCREASE not in the list");
        assertFalse(ok.apply(new AutoApprovalGuard.Request(null, Source.PROMETHEUS, 200, 180, 50.0, null, true, false, null, true, false)), "relaxing down: always a reviewer");
    }
}
