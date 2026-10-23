package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/**
 * Rate learning fix (c4-leaky-bucket-rate-learning-fix.md §6, tests 1–5) in automatic mode (c4-auto, live source): a
 * rate that is wrong from the start is found. The app takes 120 ms up to its capacity of 80 req/s; above that it handles
 * at most 80 req/s and gets slower (900 ms at 150 req/s admitted). Errors stay normal. Test 6 (no C3 rate policy) is
 * {@link RateStartingReferenceTests}; test 7 is the rest of the suite.
 */
class RateLearningTests {
    static final String SERVICE = "payment-svc";
    static final double CAPACITY = 80;

    /** App time of the model: 120 ms up to the capacity, then rising linearly to 900 ms at 150 req/s admitted. */
    static double saturating(double admitted) { return 120 + 780 * Math.max(0, admitted - CAPACITY) / 70; }

    record Cycle(int cycle, double rate, double demand, RateDiagnosis.Diagnosis diagnosis, String reason, Double p0, List<String> evidence) {}
    record Run(List<Cycle> cycles, List<ServiceSimulation.Step> steps) {
        List<ServiceSimulation.Step> rate() { return steps.stream().filter(s -> s.action() == Action.LEAKY_BUCKET).toList(); }
    }

    /**
     * One window per cycle: the bucket admits min(demand, rate) and sheds the rest (queue full, depth 200); the app
     * completes min(admitted, capacity) at {@code appMs(admitted)} (±4% jitter). Approved changes apply on the next cycle.
     */
    static Run run(double rate, int cycles, IntToDoubleFunction demandAt, DoubleUnaryOperator appMs, double capacity) {
        var settings = DeadlineExamplesTests.settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-20T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureAutoApproval(new AdaptiveConfig.AutoApproval(List.of("BUCKET_RAISE", "BUCKET_CUT", "BUCKET_RELAX", "RETRY_DECREASE", "RETRY_INCREASE")));
        Source source = Source.PROMETHEUS; int version = 1;
        workflow.putPolicy(new Policy(SERVICE, "sim-v1", clock.now.plus(Duration.ofDays(30)), rate, rate, 0.0, null), source);
        var seen = new HashSet<UUID>(); var toApply = new ArrayList<UUID>(); var steps = new ArrayList<ServiceSimulation.Step>(); var out = new ArrayList<Cycle>();
        double w = settings.window().toSeconds();
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (UUID id : toApply) {
                var c = workflow.find(id);
                if (c.decision() == Decision.APPROVED && c.delivery() != Delivery.APPLIED && c.delivery() != Delivery.SUPERSEDED && c.delivery() != Delivery.EXPIRED) {
                    workflow.deliveryResult(id, new Acknowledgement("APPLIED", "sim-v" + (++version), c.proposal().proposedValue()), null);
                    if (c.proposal().action() == Action.LEAKY_BUCKET) rate = c.proposal().proposedValue();
                }
            }
            toApply.clear();
            double jitter = 1 + .02 * ((cycle % 5) - 2), demand = demandAt.applyAsDouble(cycle);
            double admitted = Math.min(demand, rate), shed = Math.max(0, demand - rate), accepted = Math.min(admitted, capacity) * jitter;
            double app = appMs.applyAsDouble(admitted) * jitter, depth = shed > 0 ? 200 : 0, pct = shed > 0 ? 100 : Math.min(45, 40 * admitted / rate);
            var window = new ServiceWindow(SERVICE, clock.now.minus(settings.window()), clock.now, source, true, demand * w, app + depth / rate * 1000, 0.0,
                    0.0, 0.0, shed * w, 10.0, accepted, null, null, null, null, List.of());
            var load = new ServiceLoadSnapshot(SERVICE, pct, shed * w, 0.0, 0.0, null, null, null, null, List.of(), depth);
            var outcome = engine.service(window, load);
            for (var c : outcome.changes()) {
                if (!seen.add(c.proposal().id())) continue;
                if (c.decision() == Decision.APPROVED) toApply.add(c.proposal().id());
                steps.add(new ServiceSimulation.Step(cycle, "", c.proposal().action(), c.proposal().currentValue(), c.proposal().proposedValue(),
                        c.decision() == Decision.APPROVED ? c.reviewer() : "pending", String.join(" | ", c.proposal().evidence())));
            }
            var d = engine.rateState(SERVICE, source).lastDecision;
            out.add(new Cycle(cycle, rate, demand, d == null ? null : d.result().diagnosis(), d == null ? null : d.result().reason(),
                    d == null ? null : d.inputs().get("p0Ms"), outcome.assessment().evidence()));
            clock.now = clock.now.plus(settings.cycle());
        }
        return new Run(out, steps);
    }

    @Test void test1ARateTooLowFromTheStartIsRaisedAfterAProvisionalP0() {
        // rate 30, demand 60, the line is always full, the app is fast (120 ms)
        var r = run(30, 40, c -> 60, a -> 120, CAPACITY);
        var first = r.cycles().stream().filter(c -> c.diagnosis() != RateDiagnosis.Diagnosis.UNKNOWN).findFirst().orElseThrow();
        assertTrue(first.cycle() <= 5, "never UNKNOWN for more than 5 windows: " + first);
        assertEquals(RateDiagnosis.Diagnosis.RATE_TOO_LOW, first.diagnosis(), first.reason());
        assertEquals(120, first.p0(), 120 * .05, "P0 ≈ the app time 120 ms");
        assertTrue(first.evidence().stream().anyMatch(e -> e.contains("P0") && e.contains("provisional")), "shown as provisional: " + first.evidence());
        var raises = r.rate();
        assertFalse(raises.isEmpty());
        assertTrue(raises.stream().allMatch(s -> s.to() > s.from() && "c4-auto".equals(s.reviewer())), "only raises, by c4-auto: " + raises);
        assertEquals(45, raises.get(0).to(), 1e-9, "first raise 30 → 45 (+50%)");
        assertEquals(75, raises.get(raises.size() - 1).to(), 75 * .05, "up to about demand 60 × 1.25 = 75: " + raises);
    }

    @Test void test2ARateTooHighFromTheStartLearnsP0FromTheQuietWindowsAndCuts() {
        // rate 200, demand in blocks of 4 windows: 20 (app 120 ms) and 150 (app 900 ms); the line is always empty
        var r = run(200, 40, c -> (c / 4) % 2 == 0 ? 20 : 150, RateLearningTests::saturating, CAPACITY);
        var overloaded = r.cycles().stream().filter(c -> c.diagnosis() == RateDiagnosis.Diagnosis.APP_OVERLOADED).toList();
        assertFalse(overloaded.isEmpty(), "the overload is detected: " + r.cycles());
        assertTrue(overloaded.stream().allMatch(c -> c.demand() == 150), "only in the busy windows");
        var withP0 = r.cycles().stream().filter(c -> c.p0() != null).toList();
        assertTrue(withP0.stream().allMatch(c -> c.p0() < 120 * 1.1), "P0 ≈ 120 ms from the quiet windows, never the overloaded 900 ms: " + withP0);
        assertTrue(r.cycles().stream().anyMatch(c -> c.evidence().stream().anyMatch(e -> e.contains("quietest"))), "P0 from the quietest windows");
        // cut toward 0.8 × what the app accepted (about 80 req/s), in automatic steps of at most −20% (each target also at
        // most −50% of the current rate)
        var cuts = r.rate().stream().filter(s -> s.to() < s.from()).toList();
        assertTrue(cuts.size() >= 2, "cut in steps: " + r.rate());
        assertTrue(cuts.stream().allMatch(s -> "c4-auto".equals(s.reviewer()) && s.evidence().contains("APP_OVERLOADED: cut")
                && s.evidence().matches("(?s).*accepted (7[5-9]|8[0-4])\\.\\d req/s.*")), "computed from the accepted ≈ 80 req/s: " + cuts);
        assertTrue(cuts.get(cuts.size() - 1).to() <= 128, "well below the too-high 200: " + r.rate());
        assertTrue(r.rate().stream().noneMatch(s -> s.to() > 200), "never a raise above the too-high rate: " + r.rate());
    }

    @Test void test3AppTimeGrowingWithLoadIsAnOverloadEvenWithoutP0() {
        // rate 200, demand rising 25 → 150 over 6 windows, then 150: the app time rises 120 → 900 ms with it
        var r = run(200, 12, c -> Math.min(150, 25 * (c + 1)), RateLearningTests::saturating, CAPACITY);
        var grows = r.cycles().stream().filter(c -> c.diagnosis() == RateDiagnosis.Diagnosis.APP_OVERLOADED && c.reason().contains("app time grows with load")).toList();
        assertFalse(grows.isEmpty(), "APP_OVERLOADED with reason \"app time grows with load\": " + r.cycles());
        assertNull(grows.get(0).p0(), "detected before any P0 could be trusted");
        assertTrue(r.rate().stream().anyMatch(s -> s.to() < s.from() && "c4-auto".equals(s.reviewer())), "a cut by c4-auto: " + r.rate());
    }

    @Test void test4AFlatAppIsNeverOverloaded() {
        // healthy app: 120 ms whatever the load, demand varying 20 … 150 (blocks and a ramp), no capacity limit
        var r = run(200, 60, c -> c < 30 ? ((c / 4) % 2 == 0 ? 20 : 150) : 20 + 130 * ((c - 30) % 6) / 5.0, a -> 120, Double.MAX_VALUE);
        assertTrue(r.cycles().stream().noneMatch(c -> c.diagnosis() == RateDiagnosis.Diagnosis.APP_OVERLOADED), "no false alarm: " + r.cycles());
        assertTrue(r.rate().stream().noneMatch(s -> s.to() < s.from() && s.evidence().contains("APP_OVERLOADED")), r.rate().toString());
    }

    @Test void test5AfterACutTheRateSettlesAtWhatTheAppManaged() {
        // learn at demand 60 (app 120 ms; 20 cycles, the health baseline skips the windows that overlap it), then demand
        // 150 at the rate 200: the app handles 80 req/s at 900 ms
        var r = run(200, 90, c -> c < 20 ? 60 : 150, RateLearningTests::saturating, CAPACITY);
        var rate = r.rate();
        var firstCut = rate.stream().filter(s -> s.to() < s.from()).findFirst().orElseThrow(() -> new AssertionError(rate.toString()));
        assertEquals(200, firstCut.from(), 1e-9);
        double ceiling = .9 * CAPACITY * 1.04; // 0.9 × what the app managed (80 req/s, ±4% jitter) ≈ 72
        var last20 = r.cycles().subList(r.cycles().size() - 20, r.cycles().size());
        assertTrue(rate.stream().filter(s -> s.to() > s.from() && s.cycle() > firstCut.cycle()).allMatch(s -> s.to() <= ceiling),
                "raises after the cut stop at about 72 (0.9 × 80), not 0.9 × 200: " + rate);
        // The automatic −20% steps stop as soon as the app time is normal again (≤ 1.5 × P0), which can be just above 72:
        // the rate settles at about the capacity, never back near the too-high 200.
        assertTrue(last20.stream().allMatch(c -> c.rate() <= 1.05 * CAPACITY && c.rate() >= .5 * CAPACITY), "settled at about the capacity 80: " + rate);
        assertTrue(last20.stream().map(Cycle::rate).distinct().count() <= 2, "no up-and-down over the last 20 windows: " + rate);
        assertTrue(rate.stream().allMatch(s -> s.reviewer().startsWith("c4-auto")), "automatic (c4-auto), never a card for a person: " + rate);
    }
}
