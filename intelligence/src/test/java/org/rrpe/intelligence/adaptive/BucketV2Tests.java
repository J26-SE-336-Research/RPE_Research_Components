package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The rate logic end to end on the service model: examples 1 to 4, the floor, the second-cut rule, headroom and the
 * ceiling. While learning, the rate already keeps 25 percent headroom above demand, so the problem phase may start a little
 * above the scenario's initial rate; the assertions use the steps of the problem phase.
 */
class BucketV2Tests {
    static ServiceSimulation.Result run(String id) { return run(ServiceSimulation.byId(id), AdaptiveConfig.BaselineSettings.defaults()); }
    static ServiceSimulation.Result run(ServiceSimulation.Scenario s, AdaptiveConfig.BaselineSettings baseline) {
        var settings = DeadlineExamplesTests.settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-20T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureRate(null, null, baseline, null);
        return ServiceSimulation.run(engine, workflow, clock, settings, "inventory-svc", Source.MOCK, s, true);
    }
    static List<ServiceSimulation.Step> rate(ServiceSimulation.Result r, String phasePrefix) {
        return r.steps().stream().filter(s -> s.action() == Action.LEAKY_BUCKET && (phasePrefix == null || s.phase().startsWith(phasePrefix))).toList();
    }

    @Test void example1ATooLowRateIsRaisedNotCut() {
        var r = run("rate-too-low");
        assertTrue(rate(r, null).stream().allMatch(s -> s.to() > s.from()), "only raises: " + r.steps());
        var problem = rate(r, "demand 150");
        assertFalse(problem.isEmpty());
        var first = problem.get(0);
        assertEquals(Math.min(187.5, Math.round(first.from() * 1.5 * 10) / 10.0), first.to(), "first step: target 150 × 1.25 = 187.5, at most +50%");
        assertTrue(r.finalRate() >= 187.5, "at least demand 150 × 1.25 (plus the 5% retry allowance, guide §5): " + r.finalRate());
        assertTrue(r.finalRate() <= 150 * 1.04 * 1.05 * 1.25 + .1, "not more than demand (with ±4% jitter) × retry allowance × headroom: " + r.finalRate());
        assertEquals(0, r.shedAtEnd(), "nothing shed at the end");
    }
    @Test void example1bAllowShedWindowsLearnsTheAppTimeAndRaisesFromDayOne() {
        for (var baseline : List.of(new AdaptiveConfig.BaselineSettings(true), AdaptiveConfig.BaselineSettings.defaults())) {
            var r = run(ServiceSimulation.byId("rate-too-low-day-one"), baseline);
            assertFalse(rate(r, null).isEmpty(), "allow-shed-windows=" + baseline.allowShedWindows());
            assertTrue(rate(r, null).stream().allMatch(s -> s.to() > s.from()), r.steps().toString());
            assertEquals(0, r.shedAtEnd());
        }
    }
    @Test void example2ARealOverloadIsCutToTheFloorAndProbesConvergeOnTheAppCapacity() {
        var r = run("rate-real-overload");
        var overload = rate(r, "app capacity 60");
        var cuts = overload.stream().filter(s -> s.to() < s.from()).toList();
        assertFalse(cuts.isEmpty());
        var first = cuts.get(0);
        assertEquals(Math.max(first.from() * .5, .8 * 60), first.to(), .051, "max(floor = 0.5 × good rate, 0.8 × accepted 60)");
        assertTrue(r.lowestRate() >= 50, "never below the floor 0.5 × good rate 100 (no ratchet; the old logic reached 0.045): " + r.lowestRate());
        // While the bucket rejects, the rate goes up; each raise that slows the app lowers the ceiling to 90 percent of it.
        double previousRaise = Double.MAX_VALUE;
        for (var raise : overload.stream().filter(s -> s.to() > s.from()).toList()) {
            assertTrue(raise.to() < previousRaise, "each probe is lower than the last: " + overload); previousRaise = raise.to();
        }
        double last = overload.get(overload.size() - 1).to();
        assertTrue(last >= 50 && last <= 66, "converges near the app capacity 60, between the floor 50 and 1.1 × capacity: " + last);
        // In automatic mode the flip limit pauses this; here a simulated person approves every step.
    }
    @Test void example3AShortBurstChangesNothing() {
        var r = run("rate-short-burst");
        assertTrue(rate(r, null).stream().noneMatch(s -> s.to() < s.from()), "never a cut: " + r.steps());
        assertTrue(rate(r, "burst").isEmpty() && rate(r, "back").stream().noneMatch(s -> s.to() < s.from()));
    }
    @Test void example4RepeatingBurstsAreCoveredWithHeadroom() {
        var r = run("rate-bursts");
        assertTrue(rate(r, null).stream().allMatch(s -> s.to() > s.from()), "only raises: " + r.steps());
        assertTrue(r.finalRate() >= 160, "covers the 160 bursts: " + r.finalRate());
        assertTrue(r.finalRate() <= 200, "at most max demand × 1.25: " + r.finalRate());
        assertEquals(0, r.shedAtEnd());
    }
    @Test void antiRatchetUndoesACutThatDidNotHelp() {
        // the app becomes slow for a reason the rate cannot fix (a slow dependency: 400 ms at any load): the cut does not
        // help, so it is undone and no second cut follows
        var s = new ServiceSimulation.Scenario("x", "x", 100, .05, List.of(
                new ServiceSimulation.Phase("healthy (learn)", 16, 90, 1000, 40),
                new ServiceSimulation.Phase("app slow at any rate", 40, 90, 1000, 400)), "undo the cut that did not help");
        var r = run(s, AdaptiveConfig.BaselineSettings.defaults());
        var steps = rate(r, "app slow");
        assertTrue(steps.size() >= 2, r.steps().toString());
        assertTrue(steps.get(0).to() < steps.get(0).from(), "first a cut");
        assertEquals(steps.get(0).from(), steps.get(1).to(), "then the cut is undone");
        assertTrue(steps.get(1).evidence().contains("Anti-ratchet"));
        assertEquals(1, steps.stream().filter(st -> st.to() < st.from()).count(), "no second cut: " + steps);
    }
}
