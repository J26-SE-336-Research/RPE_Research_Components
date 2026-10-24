package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The rate the sidecar is set to (rpe_backpressure_inbound_rate_per_second) is the starting rate
 * when Component 3 has none, like the configured deadline; a Component 3 rate always wins.
 */
class RateStartingReferenceTests {
    static final List<String> DEMO = List.of("BUCKET_RAISE", "BUCKET_CUT", "BUCKET_RELAX", "RETRY_DECREASE", "RETRY_INCREASE");

    @Test void aFreshSystemGetsAnAutomaticRateRecommendationFromTheSidecarRate() {
        var settings = DeadlineExamplesTests.settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-22T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureAutoApproval(new AdaptiveConfig.AutoApproval(DEMO));
        String svc = "payment-svc";
        assertNull(workflow.current(svc, Source.PROMETHEUS), "no Component 3 policy (fresh system)");
        var changes = new ArrayList<PolicyChange>();
        for (int cycle = 0; cycle < 60; cycle++) {
            double demand = cycle < 20 ? 80 : 150; // healthy, then the rate 100 is too low
            assertTrue(workflow.observeConfiguredRate(svc, 100, clock.now), "the sidecar's rate becomes the reference");
            var m = ServiceSimulation.model(new ServiceSimulation.Phase("p", 1, demand, 1000, 40), 100, cycle, settings.window().toSeconds());
            var window = new ServiceWindow(svc, clock.now.minus(settings.window()), clock.now, Source.PROMETHEUS, true, m.original(), m.p95(), m.errors(),
                    m.retries(), m.retrySuccess(), m.rejections(), 10.0, m.accepted(), null, null, null, null, List.of());
            var load = new ServiceLoadSnapshot(svc, m.queuePercent(), m.rejections(), 0.0, 0.0, null, null, null, null, List.of(), m.queueDepth(), 100.0);
            changes.addAll(engine.service(window, load).changes());
            clock.now = clock.now.plus(settings.cycle());
        }
        var raise = changes.stream().filter(c -> c.proposal().action() == Action.LEAKY_BUCKET && c.proposal().proposedValue() > 100).findFirst().orElseThrow(
                () -> new AssertionError("no rate recommendation on a fresh system"));
        assertEquals("c4-auto", raise.reviewer(), "approved automatically");
        assertEquals("prometheus-config:rate:100", raise.proposal().baseVersion(), "based on the sidecar's configured rate");
        assertEquals(100, workflow.current(svc, Source.PROMETHEUS).bucketRate());
    }

    @Test void aComponent3PolicyWithoutARateGetsTheSidecarRateAndAComponent3RateWins() {
        var clock = new AdaptiveEngineTests.TestClock();
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), AdaptiveEngineTests.settings(), clock);
        // the gateway's c4-gateway policy holds only route deadlines (no leakyBucket)
        workflow.putPolicy(new Policy("gateway", "c4-gateway-v3", clock.now.plusSeconds(3600), null, null, .1, null), Source.PROMETHEUS);
        assertTrue(workflow.observeConfiguredRate("gateway", 80, clock.now));
        var gw = workflow.current("gateway", Source.PROMETHEUS);
        assertEquals(80, gw.bucketRate(), "the sidecar's rate is filled in");
        assertEquals("c4-gateway-v3", gw.version(), "same Component 3 version");
        assertEquals(.1, gw.retryRatio(), "the rest of the policy is kept");
        // a Component 3 rate wins
        workflow.putPolicy(new Policy("payment-svc", "pay-v7", clock.now.plusSeconds(3600), 120.0, 120.0, .05, null), Source.PROMETHEUS);
        assertFalse(workflow.observeConfiguredRate("payment-svc", 80, clock.now));
        assertEquals(120, workflow.current("payment-svc", Source.PROMETHEUS).bucketRate());
        // an old observation is ignored
        assertFalse(workflow.observeConfiguredRate("order-svc", 50, clock.now.minusSeconds(3600)));
    }

    @Test void aRolloutInProgressNeverSupersedesItsOwnChange() {
        var clock = new AdaptiveEngineTests.TestClock();
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), AdaptiveEngineTests.settings(), clock);
        workflow.observeConfiguredRate("payment-svc", 100, clock.now);
        var p = workflow.current("payment-svc", Source.PROMETHEUS);
        var change = workflow.offer(new Proposal(UUID.randomUUID(), "payment-svc", Action.LEAKY_BUCKET, Source.PROMETHEUS, p.version(), 100, 150, "requests/second",
                clock.now, clock.now.plusSeconds(300), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("e")), new Review("c4-auto", true, "auto"));
        // during the rollout the sidecar already enforces 150
        clock.next(15);
        assertTrue(workflow.observeConfiguredRate("payment-svc", 150, clock.now));
        assertEquals(p.version(), workflow.current("payment-svc", Source.PROMETHEUS).version(), "the reference is only kept alive");
        assertEquals(Decision.APPROVED, workflow.find(change.proposal().id()).decision());
        assertNotEquals(Delivery.SUPERSEDED, workflow.find(change.proposal().id()).delivery(), "its own rollout does not supersede it");
    }
}
