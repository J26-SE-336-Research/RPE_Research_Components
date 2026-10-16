package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class AdaptiveEngineTests {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");

        public Instant instant() {
            return now;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        void next(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    static AdaptiveConfig.Connections connections(String delivery) {
        return new AdaptiveConfig.Connections(
                false,
                "http://localhost:9090",
                "http://localhost:3200",
                "default",
                List.of("order-service"),
                "api-gateway",
                Duration.ofSeconds(1),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                5000,
                delivery,
                "",
                Map.of());
    }

    static AdaptiveConfig.Settings settings() {
        var s = AdaptiveConfig.Settings.defaults();
        return new AdaptiveConfig.Settings(
                s.window(),
                s.cycle(),
                5,
                s.minimumServiceRequests(),
                s.minimumChainRequests(),
                s.persistence(),
                s.recoveryWindows(),
                s.cooldown(),
                s.ttl(),
                s.stabilityTolerance(),
                s.stressedMad(),
                s.overloadedMad(),
                s.zeroMadRelativeFloor(),
                s.ratioFloor(),
                s.maximumRateChange(),
                s.maximumRetryRatio(),
                s.minimumDeadlineMs(),
                s.maximumDeadlineMs(),
                s.maximumDeadlineChange());
    }

    static class Context {
        final TestClock clock = new TestClock();
        final PolicyWorkflow workflow = new PolicyWorkflow(connections(""), clock);
        final AdaptiveEngine engine = new AdaptiveEngine(settings(), workflow, clock);

        ServiceWindow service(double latency, double throughput, double retries, Double successes) {
            return new ServiceWindow(
                    "order-service",
                    clock.now.minusSeconds(300),
                    clock.now,
                    Source.MOCK,
                    true,
                    1000.0,
                    latency,
                    0.0,
                    retries,
                    successes,
                    0.0,
                    10.0,
                    throughput,
                    true,
                    true,
                    500.0,
                    100.0,
                    List.of());
        }

        ChainWindow chain(double p99, double failures, Health health) {
            return new ChainWindow(
                    "POST /api/orders | api-gateway > order-service",
                    List.of("api-gateway", "order-service"),
                    clock.now.minusSeconds(300),
                    clock.now,
                    Source.MOCK,
                    true,
                    1000.0,
                    failures,
                    p99,
                    health,
                    List.of("trace-example"));
        }

        void policy(double rate, double ratio, double deadline) {
            workflow.putPolicy(
                    new Policy(
                            "order-service",
                            "v1",
                            clock.now.plusSeconds(7200),
                            rate,
                            120.0,
                            ratio,
                            null),
                    Source.MOCK);
            workflow.putPolicy(
                    new Policy(
                            chain(1000, 0, Health.HEALTHY).chain(),
                            "v1",
                            clock.now.plusSeconds(7200),
                            null,
                            null,
                            null,
                            deadline),
                    Source.MOCK);
        }

        void learnService(boolean normalRetries) {
            double[] latencies = {80, 90, 100, 110, 120};
            double[] throughputs = {90, 105, 120, 135, 150};
            for (int i = 0; i < 5; i++) {
                var outcome =
                        engine.service(
                                service(
                                        latencies[i],
                                        throughputs[i],
                                        normalRetries ? 100 : 0,
                                        normalRetries ? 40.0 : 0.0));
                if (i == 4) assertEquals(Health.BASELINE_READY, outcome.assessment().health());
                clock.next(60);
            }
            clock.next(300);
        }

        void learnChain() {
            for (int i = 0; i < 5; i++) {
                engine.chain(chain(1000, 0, Health.HEALTHY));
                clock.next(60);
            }
            clock.next(300);
        }

        PolicyChange action(Outcome outcome, Action action) {
            return outcome.changes().stream()
                    .filter(c -> c.proposal().action() == action)
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Test
    void correctedMadExamplePersistsAndRateUsesSuccessfulCapacity() {
        var c = new Context();
        c.policy(120, .05, 1000);
        c.learnService(false);
        var pending = c.engine.service(c.service(125, 100, 0, 0.0));
        assertEquals(100, pending.assessment().baseline().get("latency").median());
        assertEquals(10, pending.assessment().baseline().get("latency").mad());
        assertEquals(Health.PENDING_BREACH, pending.assessment().health());
        assertTrue(pending.changes().isEmpty());
        c.clock.next(60);
        var stressed = c.engine.service(c.service(125, 100, 0, 0.0));
        assertEquals(Health.STRESSED, stressed.assessment().health());
        var rate = c.action(stressed, Action.LEAKY_BUCKET);
        assertEquals(90, rate.proposal().proposedValue());
        assertEquals(Decision.PENDING_APPROVAL, rate.decision());
        assertEquals(
                120,
                c.workflow.current("order-service", Source.MOCK).bucketRate(),
                "Desired updates do not pretend to be applied");
        c.clock.next(60);
        var overloaded = c.engine.service(c.service(150, 80, 0, 0.0));
        assertEquals(Health.OVERLOADED, overloaded.assessment().health());
        assertEquals(60, c.action(overloaded, Action.LEAKY_BUCKET).proposal().proposedValue());
        assertEquals(
                100,
                overloaded.assessment().baseline().get("latency").median(),
                "Freeze baseline during overload");
    }

    @Test
    void nearZeroMadFromVerySteadyTrafficDoesNotTurnNoiseIntoABreach() {
        var c = new Context();
        c.policy(120, .05, 1000);
        double[] throughputs = {
            3.5089, 3.5090, 3.5091, 3.5090, 3.5089
        }; // a steady load generator: MAD ~ 0.0001 req/s
        for (int i = 0; i < 5; i++) {
            c.engine.service(c.service(10, throughputs[i], 0, 0.0));
            c.clock.next(60);
        }
        c.clock.next(300);
        for (int i = 0; i < 3; i++) {
            var outcome = c.engine.service(c.service(10, 3.5085, 0, 0.0)); // 0.0005 req/s lower
            assertEquals(
                    Health.HEALTHY,
                    outcome.assessment().health(),
                    "a fraction of a request per second is noise, not a breach");
            c.clock.next(60);
        }
        var drop = c.engine.service(c.service(10, 1.0, 0, 0.0));
        c.clock.next(60);
        drop = c.engine.service(c.service(10, 1.0, 0, 0.0));
        assertNotEquals(
                Health.HEALTHY,
                drop.assessment().health(),
                "a real throughput collapse is still detected");
    }

    @Test
    void retryEffectivenessExampleProducesTwentyExtraAttempts() {
        var c = new Context();
        c.policy(120, .05, 1000);
        c.learnService(false);
        c.engine.service(c.service(100, 120, 100, 40.0));
        c.clock.next(60);
        var outcome = c.engine.service(c.service(100, 120, 100, 40.0));
        assertEquals(Health.STRESSED, outcome.assessment().health());
        var retry = c.action(outcome, Action.RETRY_BUDGET);
        assertEquals(.02, retry.proposal().proposedValue(), 1e-10);
        assertEquals(20, retry.proposal().measurements().get("allowedRetries"));
        assertEquals(Decision.PENDING_APPROVAL, retry.decision());
    }

    @Test
    void criticalOverloadClassificationStillWaitsTwoCyclesForAutomaticPolicies() {
        var c = new Context();
        c.policy(120, .05, 1000);
        c.learnService(false);
        var first = c.engine.service(c.service(150, 80, 0, null));
        assertEquals(Health.OVERLOADED, first.assessment().health());
        assertTrue(first.changes().isEmpty());
        c.clock.next(60);
        var second = c.engine.service(c.service(150, 80, 0, null));
        assertEquals(0, c.action(second, Action.RETRY_BUDGET).proposal().proposedValue());
    }

    @Test
    void recoveryRequiresHealthyWindowsAndAppliedPolicyThenHonoursCooldown() {
        var c = new Context();
        c.policy(60, .02, 1000);
        c.learnService(true);
        for (int i = 0; i < 2; i++) {
            assertTrue(c.engine.service(c.service(100, 120, 100, 40.0)).changes().isEmpty());
            c.clock.next(60);
        }
        var outcome = c.engine.service(c.service(100, 120, 100, 40.0));
        assertEquals(66, c.action(outcome, Action.LEAKY_BUCKET).proposal().proposedValue());
        var retry = c.action(outcome, Action.RETRY_BUDGET);
        assertEquals(.03, retry.proposal().proposedValue(), 1e-10);
        c.workflow.decide(retry.proposal().id(), new Review("sre", true, null));
        c.workflow.deliveryResult(
                retry.proposal().id(), new Acknowledgement("APPLIED", "v2", .03), null);
        c.clock.next(60);
        assertTrue(
                c.engine.service(c.service(100, 120, 100, 40.0)).changes().stream()
                        .noneMatch(p -> p.proposal().action() == Action.RETRY_BUDGET));
        c.clock.next(120);
        var next = c.engine.service(c.service(100, 120, 100, 40.0));
        assertEquals(.04, c.action(next, Action.RETRY_BUDGET).proposal().proposedValue(), 1e-10);
    }

    @Test
    void deadlineBandsAndRestorationRequireApproval() {
        var c = new Context();
        c.policy(120, .05, 1000);
        c.learnChain();
        assertTrue(c.engine.chain(c.chain(1200, 20, Health.STRESSED)).changes().isEmpty());
        c.clock.next(60);
        var increase =
                c.action(c.engine.chain(c.chain(1200, 20, Health.STRESSED)), Action.CHAIN_DEADLINE);
        assertEquals(1440, increase.proposal().proposedValue());
        assertEquals(.20, increase.proposal().measurements().get("safetyMargin"));
        assertEquals(1000, increase.proposal().measurements().get("requestCount"));
        assertEquals(20, increase.proposal().measurements().get("deadlineFailureCount"));
        assertEquals(2, increase.proposal().measurements().get("failureCycles"));
        assertEquals(0, increase.proposal().measurements().get("healthyCycles"));
        assertEquals(Decision.PENDING_APPROVAL, increase.decision());
        assertTrue(c.workflow.deliverable().isEmpty(), "Unapproved deadlines are never delivered");
        c.workflow.decide(increase.proposal().id(), new Review("researcher", true, "test"));
        c.workflow.deliveryResult(
                increase.proposal().id(), new Acknowledgement("APPLIED", "v2", 1440.0), null);
        c.clock.next(60);
        c.engine.chain(c.chain(1000, 0, Health.HEALTHY));
        c.clock.next(60);
        c.engine.chain(c.chain(1000, 0, Health.HEALTHY));
        c.clock.next(60);
        var restore =
                c.action(c.engine.chain(c.chain(1000, 0, Health.HEALTHY)), Action.CHAIN_DEADLINE);
        assertEquals(1296, restore.proposal().proposedValue());
        assertEquals(Decision.PENDING_APPROVAL, restore.decision());
        assertEquals(0, restore.proposal().measurements().get("deadlineFailureCount"));
        assertEquals(3, restore.proposal().measurements().get("healthyCycles"));
    }

    @Test
    void overloadBlocksDeadlineChangesAndLargeIncreasesAreBoundedSteps() {
        var c = new Context();
        c.policy(120, .05, 1000);
        c.learnChain();
        c.engine.chain(c.chain(2000, 20, Health.STRESSED));
        c.clock.next(60);
        var bounded =
                c.action(c.engine.chain(c.chain(2000, 20, Health.STRESSED)), Action.CHAIN_DEADLINE);
        assertEquals(
                1500,
                bounded.proposal().proposedValue(),
                "2600 ms wanted; one step never exceeds +50%");
        assertTrue(
                bounded.proposal().evidence().stream().anyMatch(e -> e.contains("maximum step")));
        assertEquals(Decision.PENDING_APPROVAL, bounded.decision());
        c.clock.next(60);
        assertTrue(c.engine.chain(c.chain(1200, 20, Health.OVERLOADED)).changes().isEmpty());
    }

    @Test
    void missingCountsAndTooFewRequestsNeverBecomeHealthy() {
        var c = new Context();
        c.policy(120, .05, 1000);
        c.learnService(false);
        var w = c.service(100, 120, 0, 0.0);
        var missing =
                new ServiceWindow(
                        w.service(),
                        w.start(),
                        w.end(),
                        Source.MOCK,
                        true,
                        w.originalRequests(),
                        w.p95Ms(),
                        w.errorCount(),
                        null,
                        null,
                        w.rejections(),
                        w.inFlight(),
                        w.successfulThroughput(),
                        null,
                        null,
                        null,
                        null,
                        List.of());
        assertEquals(Health.INSUFFICIENT_DATA, c.engine.service(missing).assessment().health());
        c.clock.next(60);
        w = c.service(100, 120, 0, 0.0);
        var low =
                new ServiceWindow(
                        w.service(),
                        w.start(),
                        w.end(),
                        Source.MOCK,
                        true,
                        1.0,
                        w.p95Ms(),
                        w.errorCount(),
                        w.retryAttempts(),
                        w.successfulRetries(),
                        w.rejections(),
                        w.inFlight(),
                        w.successfulThroughput(),
                        true,
                        true,
                        500.0,
                        100.0,
                        List.of());
        assertEquals(Health.INSUFFICIENT_DATA, c.engine.service(low).assessment().health());
    }

    @Test
    void retrySafetyAndRepeatedWindowsPreventUnsafeChanges() {
        var c = new Context();
        c.policy(120, .05, 1000);
        c.learnService(false);
        c.engine.service(c.service(100, 120, 100, 40.0));
        assertTrue(c.engine.service(c.service(100, 120, 100, 40.0)).changes().isEmpty());
        c.clock.next(60);
        var w = c.service(100, 120, 100, 40.0);
        var unsafe =
                new ServiceWindow(
                        w.service(),
                        w.start(),
                        w.end(),
                        w.source(),
                        true,
                        w.originalRequests(),
                        w.p95Ms(),
                        w.errorCount(),
                        w.retryAttempts(),
                        w.successfulRetries(),
                        w.rejections(),
                        w.inFlight(),
                        w.successfulThroughput(),
                        true,
                        false,
                        500.0,
                        100.0,
                        List.of());
        assertTrue(
                c.engine.service(unsafe).changes().stream()
                        .noneMatch(p -> p.proposal().action() == Action.RETRY_BUDGET));
    }

    @Test
    void mediansAndMadMatchTheDocumentAndZeroMadUsesOnlyTheFloor() {
        assertEquals(
                new Statistic(100, 10),
                RobustStatistics.summarize(new double[] {80, 90, 100, 110, 120}));
        var c = new Context();
        c.policy(120, .05, 1000);
        for (int i = 0; i < 5; i++) {
            c.engine.service(c.service(100, 120, 0, 0.0));
            c.clock.next(60);
        }
        c.clock.next(300);
        assertEquals(
                Health.HEALTHY,
                c.engine.service(c.service(101, 120, 0, 0.0)).assessment().health());
    }

    @Test
    void deadlineFailuresMustPersistAcrossDistinctFreshCyclesAndGapsResetThem() {
        var c = new Context();
        c.policy(120, .05, 1000);
        c.learnChain();
        var first = c.chain(1200, 20, Health.STRESSED);
        assertTrue(c.engine.chain(first).changes().isEmpty());
        assertTrue(
                c.engine.chain(first).changes().isEmpty(),
                "Refreshing the same window is not a second cycle");
        c.clock.next(180);
        assertTrue(
                c.engine.chain(c.chain(1200, 20, Health.STRESSED)).changes().isEmpty(),
                "A collection gap resets persistence");
        c.clock.next(60);
        var next = c.engine.chain(c.chain(1200, 20, Health.STRESSED));
        assertEquals(1440, c.action(next, Action.CHAIN_DEADLINE).proposal().proposedValue());
        c.clock.next(60);
        var good = c.chain(1200, 20, Health.STRESSED);
        var incomplete =
                new ChainWindow(
                        good.chain(),
                        good.services(),
                        good.start(),
                        good.end(),
                        good.source(),
                        false,
                        good.requests(),
                        good.deadlineFailures(),
                        good.p99Ms(),
                        good.health(),
                        good.traceIds());
        assertEquals(Health.INSUFFICIENT_DATA, c.engine.chain(incomplete).assessment().health());
        assertTrue(
                c.workflow.list().stream()
                        .noneMatch(
                                item ->
                                        item.delivery() == Delivery.READY
                                                || item.delivery()
                                                        == Delivery.WAITING_FOR_COMPONENT_3));
    }
}
