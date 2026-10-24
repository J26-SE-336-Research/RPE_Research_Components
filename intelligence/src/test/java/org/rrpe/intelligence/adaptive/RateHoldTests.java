package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * In automatic mode rate and retry changes never wait for a person. A change the guard refuses is
 * held and tried again every cycle (problems 2, 4, 5, 6, 7, 9).
 */
class RateHoldTests {
    static final List<String> DEMO = RateStartingReferenceTests.DEMO;
    static final String CHAIN = DeadlineSettledLockTests.CHAIN;

    @Test void aLockedRateChangeIsHeldVisiblyAndNeverBecomesACard() {
        var live = new DeadlineSettledLockTests.Live();
        live.learn();
        // a deadline card waits for a person on the chain through the gateway
        var now = live.clock.now;
        live.workflow.offer(new Proposal(UUID.randomUUID(), CHAIN, Action.CHAIN_DEADLINE, Source.TEMPO, live.workflow.current(CHAIN, Source.TEMPO).version(),
                500, 600, "milliseconds", now, now.plusSeconds(86400), Map.of("windowEndEpochSeconds", (double) now.plusSeconds(86400).getEpochSecond()), List.of("waiting")));
        for (int i = 0; i < 10; i++) live.cycle(500, 150); // RATE_TOO_LOW: a raise is needed
        assertTrue(live.workflow.list().stream().noneMatch(c -> c.proposal().action() == Action.LEAKY_BUCKET), "no rate card for a person");
        String first = live.last.assessment().evidence().get(0);
        assertTrue(first.startsWith("held: rate 100 req/s → ") && first.contains("deadline not settled") && first.endsWith("trying again"),
                "the first evidence line (shown in the Health view): " + first);
        @SuppressWarnings("unchecked") var held = (List<String>) live.engine.rateEvidence().get("gateway").get("held");
        assertFalse(held.isEmpty(), "and listed in rateEvidence.held");
        assertTrue(live.workflow.pending(CHAIN, Action.CHAIN_DEADLINE, Source.TEMPO).isPresent(), "the deadline card still waits");
    }

    @Test void aHeldRateNeverRemovesAWaitingDeadlineCard() {
        // problem 6: the operator's 100 ms deadline (e2e 95) makes a deadline card; the gateway's rate is too low at the same time
        var live = new DeadlineSettledLockTests.Live();
        live.learn();
        live.policies("op-v2", 100, .1, 100);
        UUID card = null; int stillWaiting = 0;
        for (int i = 0; i < 60; i++) {
            live.cycle(100, 150);
            var pending = live.workflow.pending(CHAIN, Action.CHAIN_DEADLINE, Source.TEMPO);
            if (card == null && pending.isPresent()) card = pending.get().proposal().id();
            else if (card != null && pending.isPresent() && pending.get().proposal().id().equals(card)) stillWaiting++;
        }
        assertNotNull(card, "the deadline card appears (no pending rate card holds deadline v2)");
        assertNotEquals(Delivery.SUPERSEDED, live.workflow.find(card).delivery(), "it is never removed by the held rate");
        assertTrue(stillWaiting >= 20, "it keeps waiting for a person: " + stillWaiting + " cycles");
        assertTrue(live.reasons().stream().anyMatch(r -> r.startsWith("held: rate")), live.reasons().toString());
    }

    @Test void heldCutsStayStepsOfAtMostTwentyPercent() {
        // problem 5: a real overload while a deadline card waits on a chain through the service
        var r = AutoApprovalTests.run(ServiceSimulation.byId("rate-real-overload"), Source.PROMETHEUS, DEMO, workflow -> {
            var now = Instant.parse("2026-10-20T00:00:00Z");
            workflow.putPolicy(new Policy(AutoApprovalTests.CHAIN, "e1", now.plusSeconds(86400), null, null, null, 1000.0), Source.TEMPO);
            workflow.offer(new Proposal(UUID.randomUUID(), AutoApprovalTests.CHAIN, Action.CHAIN_DEADLINE, Source.TEMPO, "e1", 1000, 1200, "milliseconds", now,
                    now.plusSeconds(86400), Map.of("windowEndEpochSeconds", (double) now.plusSeconds(86400).getEpochSecond()), List.of("waiting")));
        });
        var heldCuts = new ArrayList<double[]>();
        var p = Pattern.compile("^held: rate ([0-9.]+) req/s → ([0-9.]+) req/s");
        r.result().outcomes().forEach(o -> o.assessment().evidence().forEach(e -> {
            var m = p.matcher(e);
            if (m.find() && Double.parseDouble(m.group(2)) < Double.parseDouble(m.group(1))) heldCuts.add(new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))});
        }));
        assertFalse(heldCuts.isEmpty(), "the cut is held while the deadline card waits");
        assertTrue(heldCuts.stream().allMatch(c -> c[1] >= .8 * c[0] - .05), "a held cut is a step of at most −20%, not the full cut");
        assertTrue(r.rate().isEmpty() || r.rate().stream().noneMatch(s -> "pending".equals(s.reviewer())), "never a card for a person");
    }

    @Test void anUndoAlwaysGoesThrough() {
        // problem 7: an undo is approved even behind a deadline card, while paused, with incomplete data and not in the list
        var check = AutoApprovalGuard.check(new AutoApprovalGuard.Request(AdaptiveConfig.AutoKind.BUCKET_RAISE, Source.PROMETHEUS, 50, 100, 50.0, 60.0,
                false, true, "GET /api/orders is PROPOSED", false, true), EnumSet.of(AdaptiveConfig.AutoKind.BUCKET_CUT),
                AdaptiveConfig.RateSettings.defaults(), AdaptiveConfig.RetrySettings.defaults());
        assertTrue(check.approve(), check.reason());
        assertFalse(AutoApprovalGuard.check(new AutoApprovalGuard.Request(AdaptiveConfig.AutoKind.BUCKET_RAISE, Source.MOCK, 50, 100, 50.0, 60.0,
                true, true, null, true, false), EnumSet.allOf(AdaptiveConfig.AutoKind.class), AdaptiveConfig.RateSettings.defaults(),
                AdaptiveConfig.RetrySettings.defaults()).approve(), "but never for a synthetic change");
    }

    @Test void aTooGenerousRateIsLoweredAutomaticallyInSmallStepsDownToDemandTimesOnePointTwoFive() {
        // problem 4: rate 400 for a demand of 80, so the target is 80 * 1.05 * 1.25 = 105; generous-wait is 2 minutes
        var settings = DeadlineExamplesTests.settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-22T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        var d = AdaptiveConfig.RateSettings.defaults();
        engine.configureRate(new AdaptiveConfig.RateSettings(d.headroom(), d.queueHighPercent(), d.queueLowPercent(), d.appTolerance(), d.cutFactor(),
                d.stepFraction(), d.minAbsolute(), d.maxAbsolute(), d.floorFraction(), d.brokeAtMemory(), Duration.ofMinutes(1), Duration.ofMinutes(2),
                d.relaxStep(), d.maxRaise(), d.minImprovement()), null, null, null);
        engine.configureAutoApproval(new AdaptiveConfig.AutoApproval(DEMO));
        var s = new ServiceSimulation.Scenario("generous", "a too generous rate", 400, .05, List.of(new ServiceSimulation.Phase("healthy, demand 80", 600, 80, 1000, 40)), "lower");
        var result = ServiceSimulation.run(engine, workflow, clock, settings, "inventory-svc", Source.PROMETHEUS, s, false);
        var steps = result.steps().stream().filter(st -> st.action() == Action.LEAKY_BUCKET).toList();
        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().allMatch(st -> "c4-auto".equals(st.reviewer())), "automatic, never a card for a person: " + steps);
        var down = steps.stream().filter(st -> st.to() < st.from()).toList();
        assertTrue(down.size() >= 10 && down.stream().allMatch(st -> st.to() >= .9 * st.from() - .05), "down in steps of at most −10%: " + steps);
        assertEquals(105, down.get(down.size() - 1).to(), 1e-9, "down to the demand target 80 × 1.05 × 1.25 = 105, not below");
        assertTrue(steps.get(0).cycle() >= 8, "only after the healthy learning and the generous-wait: " + steps.get(0));
        assertTrue(result.finalRate() >= 80 * 1.04 * 1.05 * 1.25 - 15 && result.finalRate() < 140, "down to about demand × 1.25, never below: " + result.finalRate() + " " + steps);
        assertTrue(result.lowestRate() >= 100, "never below demand × 1.25 (with the ±4% jitter): " + result.lowestRate());
        assertEquals(0, result.shedAtEnd(), "nothing shed");
    }

    @Test void aFailedDeliveryDoesNotFreezeTheService() {
        // problem 9
        var live = new DeadlineSettledLockTests.Live();
        live.learn();
        PolicyChange auto = null;
        for (int i = 0; i < 15 && auto == null; i++) {
            live.cycle(500, 150);
            auto = live.auto().stream().findFirst().orElse(null);
        }
        assertNotNull(auto, "an automatic raise");
        UUID id = auto.proposal().id();
        for (int attempt = 1; attempt <= 5; attempt++) {
            live.workflow.deliveryResult(id, null, "Component 3 unreachable");
            live.cycle(500, 150);
            var evidence = live.last.assessment().evidence();
            assertTrue(evidence.stream().noneMatch(e -> e.startsWith("Rate v2: waiting for Component 3")), "not frozen: " + evidence);
            assertTrue(evidence.stream().anyMatch(e -> e.contains("failed") && e.contains("still deciding")), evidence.toString());
        }
        assertEquals(Delivery.SUPERSEDED, live.workflow.find(id).delivery(), "after 5 failed attempts the change is replaced");
        for (int i = 0; i < 5; i++) live.cycle(500, 150);
        final UUID failed = id;
        assertTrue(live.auto().stream().anyMatch(c -> !c.proposal().id().equals(failed) && c.delivery() != Delivery.SUPERSEDED), "a fresh automatic change is made");
    }
}
