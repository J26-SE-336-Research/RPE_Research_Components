package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Cross-lever lock (owner decision 21 Oct 2026): no automatic rate or retry change for a service while any chain through it
 * has an unsettled deadline; no automatic change on missing data; a reviewer's rate/retry approval during deadline
 * settling makes the deadline verification INCONCLUSIVE.
 */
class DeadlineSettledLockTests {
    static final String CHAIN = "GET /api/orders";
    static final List<String> DEMO = List.of("BUCKET_RAISE", "BUCKET_CUT", "RETRY_DECREASE");
    static final ChainSimulation.Profile HEALTHY_400 = ChainSimulation.profile(150, 350, 380, 400, 420);

    /** A live engine (PROMETHEUS services, TEMPO chain) with the demo allow-list, like the demo cluster. */
    static final class Live {
        final AdaptiveConfig.Settings settings = DeadlineExamplesTests.settings();
        final ChainSimulation.VirtualClock clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-21T00:00:00Z"));
        final PolicyWorkflow workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        final AdaptiveEngine engine = new AdaptiveEngine(settings, workflow, clock);
        final List<PolicyChange> gateway = new ArrayList<>();
        int cycle;
        Live() {
            engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
            engine.configureAutoApproval(new AdaptiveConfig.AutoApproval(DEMO));
            policies("op-v1", 100, .05, 500);
        }
        /** The operator's policy for the gateway: rate, retry ratio and the GET /api/orders route budget (as C3 sync delivers it). */
        void policies(String version, double rate, double retry, double deadline) {
            workflow.putPolicy(new Policy("gateway", version, clock.now.plusSeconds(86400), rate, rate, retry, null), Source.PROMETHEUS);
            workflow.putPolicy(new Policy(CHAIN, version, clock.now.plusSeconds(86400), null, null, null, deadline), Source.TEMPO);
        }
        /** One cycle: a chain window stamped with {@code budget} and a gateway window at {@code demand} req/s. */
        void cycle(long budget, double demand) { cycle(budget, demand, null, null); }
        /** With {@code retries}/{@code retrySuccesses}: simulated retry counts for the gateway window (C2 does not count retries yet). */
        void cycle(long budget, double demand, Double retries, Double retrySuccesses) {
            var phase = new ChainSimulation.Phase("p", 1, List.of(new ChainSimulation.PathTraffic(1, HEALTHY_400, "gateway", "order-svc")));
            var set = ChainSimulation.samples(phase, cycle, budget, 300, clock.now, "GET", "/api/orders", "t");
            double failures = set.samples().stream().filter(DeadlineSample::expired).count();
            var w = new ChainWindow(CHAIN, List.of("gateway", "order-svc"), clock.now.minus(settings.window()), clock.now, Source.TEMPO, true, 300.0, failures,
                    TempoWindows.percentile99(set.samples().stream().mapToDouble(DeadlineSample::durationMs).toArray()), Health.HEALTHY, List.of("t"));
            engine.chain(w, new ChainEvidence(set, Map.of(), Map.of("gateway", Health.HEALTHY, "order-svc", Health.HEALTHY), Set.of()), Map.of(), List.of());
            var rate = workflow.current("gateway", Source.PROMETHEUS).bucketRate();
            var m = ServiceSimulation.model(new ServiceSimulation.Phase("p", 1, demand, 1000, 40), rate, cycle, settings.window().toSeconds());
            var window = new ServiceWindow("gateway", clock.now.minus(settings.window()), clock.now, Source.PROMETHEUS, true, m.original(), m.p95(), m.errors(),
                    retries != null ? retries : m.retries(), retrySuccesses != null ? retrySuccesses : m.retrySuccess(), m.rejections(), 10.0, m.accepted(), null, null, null, null, List.of());
            var load = new ServiceLoadSnapshot("gateway", m.queuePercent(), m.rejections(), 0.0, 0.0, null, null, null, null, List.of(), m.queueDepth());
            gateway.addAll(engine.service(window, load).changes());
            clock.now = clock.now.plus(settings.cycle()); cycle++;
        }
        ChainDeadlineState state() { var e = engine.deadlineEvidence(CHAIN, Source.TEMPO); return e == null ? null : e.state(); }
        List<PolicyChange> auto() { return workflow.list().stream().filter(c -> c.proposal().target().equals("gateway") && "c4-auto".equals(c.reviewer())).toList(); }
        List<String> reasons() {
            return gateway.stream().flatMap(c -> c.proposal().evidence().stream()).filter(e -> e.startsWith("Automatic approval not used")).distinct().toList();
        }
        void learn() { for (int i = 0; i < 25; i++) cycle(500, 60); }
    }

    @Test void theE2e95TimeGapGetsNoAutomaticGatewayChange() {
        var live = new Live();
        live.learn();
        assertEquals(ChainDeadlineState.READY, live.state(), "the chain is learned and settled before the operator's rollout");
        assertTrue(live.auto().isEmpty());
        // e2e 95: the operator rolls out a too-tight deadline (100 ms) with rate 100 and retry 0.1; from now on the gateway
        // gets a retry change the guard would otherwise approve: retries rarely succeed (10 of 100) → 0.1 → 0.02. The demo
        // traffic stays low, so there is no rate proposal (a pending rate proposal would hold the deadline: deadline v2 rule).
        live.policies("op-v2", 100, .1, 100);
        PolicyChange deadline = null; int inGap = 0;
        for (int i = 0; i < 40; i++) {
            int before = live.gateway.size();
            live.cycle(100, 60, 100.0, 10.0);
            if (deadline == null && live.gateway.size() > before) inGap++;
            if (deadline == null) deadline = live.workflow.pending(CHAIN, Action.CHAIN_DEADLINE, Source.TEMPO).orElse(null);
        }
        var ev = live.engine.deadlineEvidence(CHAIN, Source.TEMPO);
        assertNotNull(deadline, "C4 still proposes the deadline increase: " + ev.state() + " " + ev.decisionCase() + " " + ev.holdReason() + " " + ev.advisory() + " model=" + ev.model() + " h=" + ev.hExceed());
        assertTrue(deadline.proposal().proposedValue() > 100);
        assertTrue(inGap > 0, "the retry change arrives before any deadline proposal is pending (the e2e 95 time gap)");
        assertEquals(.02, live.gateway.get(0).proposal().proposedValue(), 1e-9);
        assertEquals(Decision.PENDING_APPROVAL, deadline.decision(), "a chain deadline is never automatic");
        assertTrue(live.auto().isEmpty(), "no automatic gateway change during the whole test: " + live.auto());
        var rates = live.gateway.stream().filter(c -> c.proposal().action() == Action.RETRY_BUDGET).toList();
        assertFalse(rates.isEmpty(), "the gateway retry change is still recommended");
        assertTrue(rates.stream().allMatch(c -> c.decision() == Decision.PENDING_APPROVAL), "a human can still approve it");
        assertTrue(live.reasons().stream().allMatch(r -> r.contains("deadline not settled")), live.reasons().toString());
        // the gap itself: before any deadline proposal was pending, the lock came from the budget change / building raise
        assertTrue(live.reasons().stream().anyMatch(r -> r.contains("the deadline changed") || r.contains("a raise is building")), live.reasons().toString());
    }

    @Test void withSettledDeadlinesAutomaticChangesStillHappen() {
        var live = new Live();
        live.learn();
        for (int i = 0; i < 15; i++) live.cycle(500, 150);
        assertEquals(ChainDeadlineState.READY, live.state());
        assertFalse(live.auto().isEmpty(), "settled chains do not block the automatic raise: " + live.reasons());
    }

    @Test void aDeadlineChangeByAnyoneLocksForTheSettlePeriod() {
        var live = new Live();
        live.learn();
        live.policies("op-v2", 100, .05, 510);   // another tool changes the route budget (seen through the C3 sync)
        for (int i = 0; i < 12; i++) live.cycle(510, 150);
        assertTrue(live.auto().isEmpty(), "settle period 2 × 5 min + 3 min cooldown");
        assertTrue(live.reasons().stream().anyMatch(r -> r.contains("deadline not settled") && r.contains("the deadline changed")), live.reasons().toString());
        for (int i = 0; i < 12; i++) live.cycle(510, 150);
        assertFalse(live.auto().isEmpty(), "settled again after the settle period: " + live.reasons());
    }

    @Test void missingQueueDataIsNeverAutomaticNotEvenTheProtectiveRetryZero() {
        var settings = AdaptiveEngineTests.settings();
        var clock = new AdaptiveEngineTests.TestClock();
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureAutoApproval(new AdaptiveConfig.AutoApproval(DEMO));
        workflow.putPolicy(new Policy("order-service", "v1", clock.now.plusSeconds(7200), 120.0, 120.0, .05, null), Source.PROMETHEUS);
        java.util.function.BiFunction<Double, Double, ServiceWindow> window = (latency, throughput) -> new ServiceWindow("order-service",
                clock.now.minusSeconds(300), clock.now, Source.PROMETHEUS, true, 1000.0, latency, 0.0, 0.0, 0.0, 0.0, 10.0, throughput, true, true, 500.0, 100.0, List.of());
        double[] latencies = {80, 90, 100, 110, 120}; double[] throughputs = {90, 105, 120, 135, 150};
        for (int i = 0; i < 5; i++) { engine.service(window.apply(latencies[i], throughputs[i])); clock.next(60); }
        clock.next(300);
        engine.service(window.apply(150.0, 80.0)); clock.next(60);
        var outcome = engine.service(window.apply(150.0, 80.0)); // no queue signals: rate diagnosis UNKNOWN, health OVERLOADED
        var retry = outcome.changes().stream().filter(c -> c.proposal().action() == Action.RETRY_BUDGET).findFirst().orElseThrow();
        assertEquals(0, retry.proposal().proposedValue());
        assertEquals(Decision.PENDING_APPROVAL, retry.decision());
        assertTrue(retry.proposal().evidence().stream().anyMatch(e -> e.contains("insufficient data for automatic change")), retry.proposal().evidence().toString());
    }

    @Test void aReviewerRateChangeWhileTheDeadlineSettlesMakesItsVerificationInconclusive() {
        var c = new AdaptiveEngineTests.Context(); c.policy(120, .05, 1000); c.engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
        PolicyChange increase = null; int i = 0;
        for (; i < 10 && increase == null; i++) {
            increase = AdaptiveEngineTests.chainV2(c, AdaptiveEngineTests.OVER_SIGNALLED, i, 1000, Map.of(), true).changes().stream().findFirst().orElse(null);
            c.clock.next(60);
        }
        assertNotNull(increase);
        c.workflow.decide(increase.proposal().id(), new Review("researcher", true, "test"));
        c.workflow.deliveryResult(increase.proposal().id(), new Acknowledgement("APPLIED", "v2", increase.proposal().proposedValue()), null);
        long applied = Math.round(increase.proposal().proposedValue());
        String chain = c.chain(1, 0, Health.HEALTHY).chain();
        AdaptiveEngineTests.chainV2(c, AdaptiveEngineTests.HEALTHY_1000, i++, applied, Map.of(), true); c.clock.next(60);
        assertEquals(ChainDeadlineState.SETTLING, c.engine.deadlineEvidence(chain, Source.MOCK).state());
        // a reviewer approves a rate change on a service of the chain while the deadline settles
        var rate = c.workflow.offer(new Proposal(UUID.randomUUID(), "order-service", Action.LEAKY_BUCKET, Source.MOCK, "v1", 120, 100, "requests/second",
                c.clock.now, c.clock.now.plusSeconds(600), Map.of("windowEndEpochSeconds", (double) c.clock.now.getEpochSecond()), List.of("e")));
        c.workflow.decide(rate.proposal().id(), new Review("sre", true, "manual"));
        String verification = null;
        for (int k = 0; k < 40 && verification == null; k++, i++) {
            AdaptiveEngineTests.chainV2(c, AdaptiveEngineTests.HEALTHY_1000, i, applied, Map.of(), true);
            verification = c.engine.deadlineEvidence(chain, Source.MOCK).verification();
            c.clock.next(60);
        }
        assertNotNull(verification);
        assertTrue(verification.startsWith("INCONCLUSIVE") && verification.contains("a reviewer approved a rate or retry change for order-service"), verification);
    }
}
