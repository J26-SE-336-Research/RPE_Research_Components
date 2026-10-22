package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdaptiveEngineTests {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        void next(long seconds) { now = now.plusSeconds(seconds); }
    }
    static AdaptiveConfig.Connections connections(String delivery) {
        return new AdaptiveConfig.Connections(false, "http://localhost:9090", "http://localhost:3200", "default",
                List.of("order-service"), "api-gateway", Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofSeconds(30), 5000,
                delivery, "", Map.of());
    }
    static AdaptiveConfig.Settings settings() {
        var s = AdaptiveConfig.Settings.defaults();
        return new AdaptiveConfig.Settings(s.window(), s.cycle(), 5, s.minimumServiceRequests(), s.minimumChainRequests(),
                s.persistence(), s.recoveryWindows(), s.cooldown(), s.ttl(), s.stabilityTolerance(), s.stressedMad(), s.overloadedMad(),
                s.zeroMadRelativeFloor(), s.ratioFloor(), s.maximumRateChange(), s.maximumRetryRatio(), s.minimumDeadlineMs(), s.maximumDeadlineMs(), s.maximumDeadlineChange());
    }
    static class Context {
        final TestClock clock = new TestClock();
        final PolicyWorkflow workflow = new PolicyWorkflow(connections(""), clock);
        final AdaptiveEngine engine = new AdaptiveEngine(settings(), workflow, clock);
        ServiceWindow service(double latency, double throughput, double retries, Double successes) {
            return new ServiceWindow("order-service", clock.now.minusSeconds(300), clock.now, Source.MOCK, true,
                    1000.0, latency, 0.0, retries, successes, 0.0, 10.0, throughput, true, true, 500.0, 100.0, List.of());
        }
        ChainWindow chain(double p99, double failures, Health health) {
            return new ChainWindow("POST /api/orders | api-gateway > order-service", List.of("api-gateway", "order-service"),
                    clock.now.minusSeconds(300), clock.now, Source.MOCK, true, 1000.0, failures, p99, health, List.of("trace-example"));
        }
        void policy(double rate, double ratio, double deadline) {
            workflow.putPolicy(new Policy("order-service", "v1", clock.now.plusSeconds(7200), rate, 120.0, ratio, null), Source.MOCK);
            workflow.putPolicy(new Policy(chain(1000, 0, Health.HEALTHY).chain(), "v1", clock.now.plusSeconds(7200), null, null, null, deadline), Source.MOCK);
        }
        void learnService(boolean normalRetries) {
            double[] latencies = {80,90,100,110,120}; double[] throughputs = {90,105,120,135,150};
            for (int i = 0; i < 5; i++) {
                var outcome = engine.service(service(latencies[i], throughputs[i], normalRetries ? 100 : 0, normalRetries ? 40.0 : 0.0));
                if (i == 4) assertEquals(Health.BASELINE_READY, outcome.assessment().health());
                clock.next(60);
            }
            clock.next(300);
        }
        void learnChain() {
            for (int i = 0; i < 5; i++) { engine.chain(chain(1000, 0, Health.HEALTHY)); clock.next(60); }
            clock.next(300);
        }
        PolicyChange action(Outcome outcome, Action action) { return outcome.changes().stream().filter(c -> c.proposal().action() == action).findFirst().orElseThrow(); }
    }
    /** Rate v2 load signals for order-service: a quiet queue (20% full, empty on average, nothing shed). */
    static ServiceLoadSnapshot quietQueue() { return new ServiceLoadSnapshot("order-service", 20.0, 0.0, 0.0, 0.0, null, null, null, null, List.of(), 0.0); }
    /** Like {@code Context.learnService}, with load signals, so rate v2 also learns its app-time baseline P0. */
    static void learnServiceWithLoad(Context c, boolean normalRetries) {
        double[] latencies = {80,90,100,110,120}; double[] throughputs = {90,105,120,135,150};
        for (int i = 0; i < 5; i++) {
            var outcome = c.engine.service(c.service(latencies[i], throughputs[i], normalRetries ? 100 : 0, normalRetries ? 40.0 : 0.0), quietQueue());
            if (i == 4) assertEquals(Health.BASELINE_READY, outcome.assessment().health());
            c.clock.next(60);
        }
        c.clock.next(300);
    }
    static boolean noRateCut(Outcome o) {
        return o.changes().stream().noneMatch(ch -> ch.proposal().action() == Action.LEAKY_BUCKET && ch.proposal().proposedValue() < ch.proposal().currentValue());
    }
    // rate-retry v2: intended behaviour change (see RATE_RETRY_V2_MIGRATION.md) - a slower app is cut only when the app time is above 1.5 × P0 for 2 cycles; the cut uses the accepted (successful) throughput: max(floor, 0.8 × min(rate, accepted))
    @Test void correctedMadExamplePersistsAndRateUsesSuccessfulCapacity() {
        var c = new Context(); c.policy(120, .05, 1000); learnServiceWithLoad(c, false);
        var pending = c.engine.service(c.service(125, 100, 0, 0.0), quietQueue());
        assertEquals(100, pending.assessment().baseline().get("latency").median());
        assertEquals(10, pending.assessment().baseline().get("latency").mad());
        assertEquals(Health.PENDING_BREACH, pending.assessment().health()); assertTrue(noRateCut(pending));
        c.clock.next(60); var stressed = c.engine.service(c.service(125, 100, 0, 0.0), quietQueue());
        assertEquals(Health.STRESSED, stressed.assessment().health());
        assertTrue(noRateCut(stressed), "app time 125 ms is within 1.5 × P0 (100 ms): no cut");
        assertEquals(120, c.workflow.current("order-service", Source.MOCK).bucketRate(), "Desired updates do not pretend to be applied");
        c.clock.next(60); var overloaded = c.engine.service(c.service(150, 80, 0, 0.0), quietQueue());
        assertEquals(Health.OVERLOADED, overloaded.assessment().health());
        assertTrue(noRateCut(overloaded), "exactly 1.5 × P0 is still within tolerance");
        assertEquals(100, overloaded.assessment().baseline().get("latency").median(), "Freeze baseline during overload");
        c.clock.next(60); assertTrue(noRateCut(c.engine.service(c.service(200, 80, 0, 0.0), quietQueue())), "the app is slow, but not yet for 2 cycles");
        c.clock.next(60); var slow = c.engine.service(c.service(200, 80, 0, 0.0), quietQueue());
        var rate = c.action(slow, Action.LEAKY_BUCKET);
        assertEquals(64, rate.proposal().proposedValue(), "max(floor 60, 0.8 × min(rate 120, accepted 80))");
        assertEquals(Decision.PENDING_APPROVAL, rate.decision());
        assertTrue(rate.proposal().evidence().stream().anyMatch(e -> e.contains("APP_OVERLOADED")));
        assertEquals(120, c.workflow.current("order-service", Source.MOCK).bucketRate(), "Desired updates do not pretend to be applied");
    }
    @Test void nearZeroMadFromVerySteadyTrafficDoesNotTurnNoiseIntoABreach() {
        var c = new Context(); c.policy(120, .05, 1000);
        double[] throughputs = {3.5089, 3.5090, 3.5091, 3.5090, 3.5089}; // a steady load generator: MAD ~ 0.0001 req/s
        for (int i = 0; i < 5; i++) { c.engine.service(c.service(10, throughputs[i], 0, 0.0)); c.clock.next(60); }
        c.clock.next(300);
        for (int i = 0; i < 3; i++) {
            var outcome = c.engine.service(c.service(10, 3.5085, 0, 0.0)); // 0.0005 req/s lower
            assertEquals(Health.HEALTHY, outcome.assessment().health(), "a fraction of a request per second is noise, not a breach");
            c.clock.next(60);
        }
        var drop = c.engine.service(c.service(10, 1.0, 0, 0.0)); c.clock.next(60);
        drop = c.engine.service(c.service(10, 1.0, 0, 0.0));
        assertNotEquals(Health.HEALTHY, drop.assessment().health(), "a real throughput collapse is still detected");
    }
    // rate-retry v2: intended behaviour change (see RATE_RETRY_V2_MIGRATION.md) - the budget no longer follows 0.10 × effectiveness halved under stress; retries that rarely succeed (10 of 100: Wilson upper bound 0.16 < 0.20) for 2 cycles step the ratio down to 0.02 = 20 extra attempts per 1000 requests
    @Test void retryEffectivenessExampleProducesTwentyExtraAttempts() {
        var c = new Context(); c.policy(120, .05, 1000); c.learnService(false);
        c.engine.service(c.service(100, 120, 100, 10.0)); c.clock.next(60);
        var outcome = c.engine.service(c.service(100, 120, 100, 10.0));
        assertEquals(Health.STRESSED, outcome.assessment().health());
        var retry = c.action(outcome, Action.RETRY_BUDGET);
        assertEquals(.02, retry.proposal().proposedValue(), 1e-10);
        assertEquals(20, retry.proposal().measurements().get("allowedRetries"));
        assertTrue(retry.proposal().measurements().get("retrySuccessUpperBound") < .20);
        assertEquals(Decision.PENDING_APPROVAL, retry.decision());
    }
    @Test void criticalOverloadClassificationStillWaitsTwoCyclesForAutomaticPolicies() {
        var c = new Context(); c.policy(120, .05, 1000); c.learnService(false);
        var first = c.engine.service(c.service(150, 80, 0, null));
        assertEquals(Health.OVERLOADED, first.assessment().health()); assertTrue(first.changes().isEmpty());
        c.clock.next(60); var second = c.engine.service(c.service(150, 80, 0, null));
        assertEquals(0, c.action(second, Action.RETRY_BUDGET).proposal().proposedValue());
    }
    // rate-retry v2: intended behaviour change (see RATE_RETRY_V2_MIGRATION.md) - the rate rises toward demand + 25% headroom by a = 0.10 × goodRate after 3 healthy cycles (66 = 60 + 6); load signals are now part of the input; the retry ratio rises by +0.02 toward 0.10 (not +0.01), once per 5 min stabilisation period after the last applied change
    @Test void recoveryRequiresHealthyWindowsAndAppliedPolicyThenHonoursCooldown() {
        var c = new Context(); c.policy(60, .02, 1000); learnServiceWithLoad(c, true);
        for (int i = 0; i < 2; i++) { assertTrue(c.engine.service(c.service(100, 120, 100, 40.0), quietQueue()).changes().isEmpty()); c.clock.next(60); }
        var outcome = c.engine.service(c.service(100, 120, 100, 40.0), quietQueue());
        assertEquals(66, c.action(outcome, Action.LEAKY_BUCKET).proposal().proposedValue());
        var retry = c.action(outcome, Action.RETRY_BUDGET);
        assertEquals(.04, retry.proposal().proposedValue(), 1e-10);
        c.workflow.decide(retry.proposal().id(), new Review("sre", true, null));
        c.workflow.deliveryResult(retry.proposal().id(), new Acknowledgement("APPLIED", "v2", .04), null);
        c.clock.next(60); assertTrue(c.engine.service(c.service(100, 120, 100, 40.0), quietQueue()).changes().stream().noneMatch(p -> p.proposal().action() == Action.RETRY_BUDGET));
        c.clock.next(120); assertTrue(c.engine.service(c.service(100, 120, 100, 40.0), quietQueue()).changes().stream().noneMatch(p -> p.proposal().action() == Action.RETRY_BUDGET), "cooldown over, stabilisation not yet");
        c.clock.next(120); var next = c.engine.service(c.service(100, 120, 100, 40.0), quietQueue());
        assertEquals(.06, c.action(next, Action.RETRY_BUDGET).proposal().proposedValue(), 1e-10);
    }
    /** One window of per-request traffic for the Context chain (deadline v2). */
    static Outcome chainV2(Context c, ChainSimulation.Profile profile, int window, long deadline, Map<String, Health> guards, boolean complete) {
        var phase = new ChainSimulation.Phase("p", 1, List.of(new ChainSimulation.PathTraffic(1, profile, "api-gateway", "order-service")), guards, Map.of());
        var set = ChainSimulation.samples(phase, window, deadline, 300, c.clock.now, "POST", "/api/orders", "t");
        var all = new HashMap<String, Health>(Map.of("api-gateway", Health.HEALTHY, "order-service", Health.HEALTHY)); all.putAll(guards);
        double failures = set.samples().stream().filter(DeadlineSample::expired).count();
        var w = new ChainWindow(c.chain(1, 0, Health.HEALTHY).chain(), List.of("api-gateway", "order-service"), c.clock.now.minusSeconds(300), c.clock.now,
                Source.MOCK, complete, 300.0, failures, TempoWindows.percentile99(set.samples().stream().mapToDouble(DeadlineSample::durationMs).toArray()),
                all.values().stream().anyMatch(h -> h == Health.OVERLOADED) ? Health.OVERLOADED : Health.HEALTHY, List.of("t"));
        return c.engine.chain(w, new ChainEvidence(set, Map.of(), all, Set.of()), Map.of(), List.of());
    }
    static final ChainSimulation.Profile OVER_SIGNALLED = ChainSimulation.profile(500, 1150, 1180, 1200, 1220); // ~25% over 1000 ms
    static final ChainSimulation.Profile HEALTHY_1000 = ChainSimulation.profile(500, 950, 980, 1000, 1020);
    static final ChainSimulation.Profile HEALTHY_400 = ChainSimulation.profile(150, 350, 380, 400, 420);

    // v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md) - Case A (raise) and Case D (tighten) replace the bands and the restoration formula
    @Test void deadlineBandsAndRestorationRequireApproval() {
        var c = new Context(); c.policy(120, .05, 1000); c.engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
        PolicyChange increase = null;
        for (int i = 0; i < 10 && increase == null; i++) {
            increase = chainV2(c, OVER_SIGNALLED, i, 1000, Map.of(), true).changes().stream().findFirst().orElse(null); c.clock.next(60);
        }
        assertNotNull(increase, "Case A after k = 2 disjoint windows");
        assertEquals(1500, increase.proposal().proposedValue(), "censored healthy time: raise ×1.5");
        assertEquals(300, increase.proposal().measurements().get("requestCount"));
        assertEquals(.253, increase.proposal().measurements().get("healthyExceedRate"), .001);
        assertTrue(increase.proposal().evidence().get(0).contains("censored"));
        assertEquals(Decision.PENDING_APPROVAL, increase.decision());
        assertTrue(c.workflow.deliverable().isEmpty(), "Unapproved deadlines are never delivered");
        c.workflow.decide(increase.proposal().id(), new Review("researcher", true, "test"));
        c.workflow.deliveryResult(increase.proposal().id(), new Acknowledgement("APPLIED", "v2", 1500.0), null);
        PolicyChange restore = null;
        for (int i = 10; i < 60 && restore == null; i++) {
            c.clock.next(60);
            restore = chainV2(c, HEALTHY_1000, i, 1500, Map.of(), true).changes().stream().findFirst().orElse(null);
        }
        assertNotNull(restore, "Case D once the healthy time (1000 ms) is learned at the new deadline");
        double target = restore.proposal().measurements().get("targetMs");
        assertTrue(target >= 1150 && target <= 1180, "D* = T_h (1000–1020 ms) × (1 + m), m = 3σ̂/T_h with σ̂ ≥ 5% of the median: " + target);
        assertEquals(Math.max(target, 1200), restore.proposal().proposedValue(), "max(D*, ceil10(0.80 × 1500))");
        assertEquals(0, restore.proposal().measurements().get("predictedHealthyExceed"), "impact preview");
        assertEquals(Decision.PENDING_APPROVAL, restore.decision());
    }
    // v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md) - overload no longer blocks every change: Case B lowers; a firing signal is kept (Case C)
    @Test void overloadBlocksDeadlineChangesAndLargeIncreasesAreBoundedSteps() {
        var c = new Context(); c.policy(120, .05, 600); c.engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
        for (int i = 0; i < 15; i++) { assertTrue(chainV2(c, HEALTHY_400, i, 600, Map.of(), true).changes().isEmpty(), "600 ms is in the healthy band"); c.clock.next(60); }
        PolicyChange lower = null; var notFiring = ChainSimulation.profile(520, 580, 585, 590, 595);
        for (int i = 15; i < 40 && lower == null; i++) {
            lower = chainV2(c, notFiring, i, 600, Map.of("order-service", Health.OVERLOADED), true).changes().stream().findFirst().orElse(null); c.clock.next(60);
        }
        assertNotNull(lower);
        assertEquals(490, lower.proposal().proposedValue(), "Case B: max(D* = 490, ceil10(0.75 × 600))");
        assertEquals(1, lower.proposal().measurements().get("predictedOverloadExceed"), "at 490 ms the overloaded requests would be signalled");
        assertEquals(Decision.PENDING_APPROVAL, lower.decision());
        // a second chain context where the overloaded requests already pass the deadline: the signal fires, nothing changes
        var firing = new Context(); firing.policy(120, .05, 600); firing.engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
        for (int i = 0; i < 15; i++) { chainV2(firing, HEALTHY_400, i, 600, Map.of(), true); firing.clock.next(60); }
        for (int i = 15; i < 40; i++) {
            assertTrue(chainV2(firing, ChainSimulation.profile(800, 880, 900), i, 600, Map.of("order-service", Health.OVERLOADED), true).changes().isEmpty(),
                    "never an increase while overloaded requests are signalled");
            firing.clock.next(60);
        }
        assertEquals(DeadlineCase.C_KEEP_FIRING, firing.engine.deadlineEvidence(firing.chain(1, 0, Health.HEALTHY).chain(), Source.MOCK).decisionCase());
    }
    @Test void missingCountsAndTooFewRequestsNeverBecomeHealthy() {
        var c = new Context(); c.policy(120, .05, 1000); c.learnService(false);
        var w = c.service(100, 120, 0, 0.0);
        var missing = new ServiceWindow(w.service(), w.start(), w.end(), Source.MOCK, true, w.originalRequests(), w.p95Ms(), w.errorCount(),
                null, null, w.rejections(), w.inFlight(), w.successfulThroughput(), null, null, null, null, List.of());
        assertEquals(Health.INSUFFICIENT_DATA, c.engine.service(missing).assessment().health());
        c.clock.next(60); w = c.service(100, 120, 0, 0.0);
        var low = new ServiceWindow(w.service(), w.start(), w.end(), Source.MOCK, true, 1.0, w.p95Ms(), w.errorCount(), w.retryAttempts(),
                w.successfulRetries(), w.rejections(), w.inFlight(), w.successfulThroughput(), true, true, 500.0, 100.0, List.of());
        assertEquals(Health.INSUFFICIENT_DATA, c.engine.service(low).assessment().health());
    }
    // rate-retry v2: intended behaviour change (see RATE_RETRY_V2_MIGRATION.md) - per-request safety (retryable, safe operation, remaining deadline) is the sidecar's job; the budget holds while STRESSED, whatever those flags say, and a repeated window still changes nothing
    @Test void retrySafetyAndRepeatedWindowsPreventUnsafeChanges() {
        var c = new Context(); c.policy(120, .05, 1000); c.learnService(false);
        c.engine.service(c.service(100, 120, 100, 40.0));
        assertTrue(c.engine.service(c.service(100, 120, 100, 40.0)).changes().isEmpty());
        c.clock.next(60); var w = c.service(100, 120, 100, 40.0);
        var unsafe = new ServiceWindow(w.service(), w.start(), w.end(), w.source(), true, w.originalRequests(), w.p95Ms(), w.errorCount(),
                w.retryAttempts(), w.successfulRetries(), w.rejections(), w.inFlight(), w.successfulThroughput(), true, false, 500.0, 100.0, List.of());
        var outcome = c.engine.service(unsafe);
        assertEquals(Health.STRESSED, outcome.assessment().health());
        assertTrue(outcome.changes().stream().noneMatch(p -> p.proposal().action() == Action.RETRY_BUDGET));
        assertTrue(outcome.assessment().evidence().stream().anyMatch(e -> e.contains("hold while STRESSED")));
    }
    @Test void mediansAndMadMatchTheDocumentAndZeroMadUsesOnlyTheFloor() {
        assertEquals(new Statistic(100, 10), RobustStatistics.summarize(new double[]{80,90,100,110,120}));
        var c = new Context(); c.policy(120, .05, 1000);
        for (int i = 0; i < 5; i++) { c.engine.service(c.service(100,120,0,0.0)); c.clock.next(60); }
        c.clock.next(300);
        assertEquals(Health.HEALTHY, c.engine.service(c.service(101,120,0,0.0)).assessment().health());
    }
    // v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md) - persistence counts disjoint windows, not consecutive cycles
    @Test void deadlineFailuresMustPersistAcrossDistinctFreshCyclesAndGapsResetThem() {
        var c = new Context(); c.policy(120, .05, 1000); c.engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
        assertTrue(chainV2(c, OVER_SIGNALLED, 0, 1000, Map.of(), true).changes().isEmpty(), "first counted window");
        assertTrue(chainV2(c, OVER_SIGNALLED, 0, 1000, Map.of(), true).changes().isEmpty(), "Refreshing the same window is not a second cycle");
        c.clock.next(60);
        assertTrue(chainV2(c, OVER_SIGNALLED, 1, 1000, Map.of(), true).changes().isEmpty(), "An overlapping window is not a second counted window");
        c.clock.next(900);
        assertTrue(chainV2(c, OVER_SIGNALLED, 2, 1000, Map.of(), true).changes().isEmpty(), "A collection gap resets persistence");
        c.clock.next(300); var next = chainV2(c, OVER_SIGNALLED, 3, 1000, Map.of(), true);
        assertEquals(1500, c.action(next, Action.CHAIN_DEADLINE).proposal().proposedValue());
        c.clock.next(60);
        assertEquals(Health.INSUFFICIENT_DATA, chainV2(c, OVER_SIGNALLED, 4, 1000, Map.of(), false).assessment().health());
        assertTrue(c.workflow.list().stream().noneMatch(item -> item.delivery() == Delivery.READY || item.delivery() == Delivery.WAITING_FOR_COMPONENT_3));
    }
}
