package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The retry budget, with simulated retry data, since Component 2 does not count retries yet. */
class RetryV2Tests {
    static final ServiceLoadSnapshot QUIET = AdaptiveEngineTests.quietQueue();

    static Optional<PolicyChange> retry(Outcome o) { return o.changes().stream().filter(c -> c.proposal().action() == Action.RETRY_BUDGET).findFirst(); }
    static void apply(AdaptiveEngineTests.Context c, PolicyChange change, String version) {
        c.workflow.decide(change.proposal().id(), new Review("sre", true, null));
        c.workflow.deliveryResult(change.proposal().id(), new Acknowledgement("APPLIED", version, change.proposal().proposedValue()), null);
    }

    @Test void anOverloadedAppGetsNoRetries() {
        var r = BucketV2Tests.run("rate-real-overload");
        var cuts = r.steps().stream().filter(s -> s.action() == Action.RETRY_BUDGET && s.phase().startsWith("app capacity 60")).toList();
        assertFalse(cuts.isEmpty(), r.steps().toString());
        assertEquals(0, cuts.get(0).to(), "APP_OVERLOADED for 2 cycles: retry 0");
        assertTrue(r.steps().stream().filter(s -> s.action() == Action.RETRY_BUDGET).allMatch(s -> s.to() <= s.from() || !s.phase().startsWith("app capacity 60")),
                "never an increase while the app is overloaded");
    }

    @Test void aRetryStormFromOurOwnSheddingHoldsTheBudgetAndRaisesTheRate() {
        // every rejected 503 is retried once and only 10 percent succeed. The old rules cut the rate and set retry 0; now we
        // see that the bucket is the bottleneck, raise the rate and do not punish retries for our own rejections
        var r = BucketV2Tests.run("retry-storm-rate");
        assertTrue(r.steps().stream().noneMatch(s -> s.action() == Action.RETRY_BUDGET), "hold: " + r.steps());
        assertTrue(BucketV2Tests.rate(r, null).stream().allMatch(s -> s.to() > s.from()), "only raises: " + r.steps());
        assertEquals(.05, r.finalRetry());
        assertEquals(0, r.shedAtEnd());
        assertTrue(r.outcomes().stream().anyMatch(o -> o.assessment().evidence().stream().anyMatch(e -> e.contains("hold while the bucket sheds"))));
    }

    @Test void retriesThatRarelySucceedStepDownTo002ThenTo0() {
        var c = new AdaptiveEngineTests.Context(); c.policy(120, .05, 1000); AdaptiveEngineTests.learnServiceWithLoad(c, false);
        assertTrue(retry(c.engine.service(c.service(100, 120, 100, 10.0), QUIET)).isEmpty(), "one window is not persistent");
        c.clock.next(60); var first = retry(c.engine.service(c.service(100, 120, 100, 10.0), QUIET)).orElseThrow();
        assertEquals(.02, first.proposal().proposedValue(), 1e-10);
        assertEquals(.1, first.proposal().measurements().get("retrySuccessRate"), 1e-10);
        apply(c, first, "v2");
        PolicyChange second = null;
        for (int i = 0; i < 10 && second == null; i++) { c.clock.next(60); second = retry(c.engine.service(c.service(100, 120, 100, 10.0), QUIET)).orElse(null); }
        assertNotNull(second, "after the cooldown");
        assertEquals(0, second.proposal().proposedValue(), "then 0");
    }

    @Test void retriesThatOftenSucceedAreNeverCutForLowSuccess() {
        // 30 of 100 succeed: the Wilson upper bound is above 0.20, so no step down
        var c = new AdaptiveEngineTests.Context(); c.policy(120, .05, 1000); AdaptiveEngineTests.learnServiceWithLoad(c, false);
        for (int i = 0; i < 5; i++) { assertTrue(retry(c.engine.service(c.service(100, 120, 100, 30.0), QUIET)).isEmpty()); c.clock.next(60); }
    }

    @Test void healthyServiceMovesTowardTheBaseRatioByTwoPointsUpToTenPercent() {
        var c = new AdaptiveEngineTests.Context(); c.policy(120, .06, 1000); AdaptiveEngineTests.learnServiceWithLoad(c, true);
        var applied = new ArrayList<Double>(); int version = 2;
        for (int i = 0; i < 40; i++) {
            var change = retry(c.engine.service(c.service(100, 120, 100, 40.0), QUIET));
            if (change.isPresent()) {
                assertEquals(Decision.PENDING_APPROVAL, change.get().decision(), "no automatic approval by default");
                apply(c, change.get(), "v" + version++); applied.add(change.get().proposal().proposedValue());
            }
            c.clock.next(60);
        }
        assertEquals(List.of(.08, .1), applied, "+0.02 per stabilisation period, capped at the base ratio 0.10 (the workflow maximum)");
    }

    @Test void withoutRetryDataAnIncreaseIsOnlyAdvisory() {
        var c = new AdaptiveEngineTests.Context(); c.policy(120, .05, 1000); AdaptiveEngineTests.learnServiceWithLoad(c, true); // fewer retries than the baseline are no breach
        Outcome last = null;
        for (int i = 0; i < 4; i++) { last = c.engine.service(c.service(100, 120, 0, 0.0), QUIET); assertTrue(retry(last).isEmpty()); c.clock.next(60); }
        assertTrue(last.assessment().evidence().contains("Advisory: retry 0.05 → 0.07 not proposed (retry-data-unavailable)"), last.assessment().evidence().toString());
        // some retries, but fewer than 30 attempts: still advisory
        var few = c.engine.service(c.service(100, 120, 20, 10.0), QUIET);
        assertTrue(retry(few).isEmpty());
        assertTrue(few.assessment().evidence().stream().anyMatch(e -> e.contains("too few retry attempts: 20 < 30")), few.assessment().evidence().toString());
    }
}
