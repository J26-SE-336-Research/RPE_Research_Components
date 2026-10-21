package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Every demo scenario, run through the same path as POST /api/v2/intelligence/demo/{scenario} (MockScenarios → real
 * AdaptiveEngine + PolicyWorkflow), in the default mode and with ?mode=auto, with the expected result (DEMO_AND_TESTING.md).
 */
class C4ScenarioTests {
    static final MockScenarios SCENARIOS = new MockScenarios(AdaptiveConfig.Settings.defaults(), AdaptiveEngineTests.connections(""));

    @SuppressWarnings("unchecked") static List<PolicyChange> changes(Map<String, Object> r) { return (List<PolicyChange>) r.get("changes"); }
    static List<PolicyChange> active(Map<String, Object> r) {
        return changes(r).stream().filter(c -> c.delivery() != Delivery.SUPERSEDED && c.delivery() != Delivery.EXPIRED).toList();
    }
    @SuppressWarnings("unchecked") static Map<String, Object> rate(Map<String, Object> r) { return ((List<Map<String, Object>>) r.get("rateEvidence")).get(0); }
    @SuppressWarnings("unchecked") static List<ServiceSimulation.Step> steps(Map<String, Object> r, Action action) {
        return ((List<ServiceSimulation.Step>) rate(r).get("steps")).stream().filter(s -> s.action() == action).toList();
    }
    static PolicyChange only(Map<String, Object> r, Action action) {
        var list = active(r).stream().filter(c -> c.proposal().action() == action).toList();
        assertEquals(1, list.size(), list.toString()); return list.get(0);
    }
    static void waiting(PolicyChange c, double from, double to) {
        assertEquals(from, c.proposal().currentValue(), 1e-9); assertEquals(to, c.proposal().proposedValue(), 1e-9);
        assertEquals(Decision.PENDING_APPROVAL, c.decision(), "waiting for a person");
    }
    @SuppressWarnings("unchecked") static DeadlineEvidence deadline(Map<String, Object> r) {
        return (DeadlineEvidence) ((List<Map<String, Object>>) r.get("deadlineEvidence")).get(0).get("evidence");
    }

    // ---- the 8 scenarios of the Admin Panel ----

    @Test void serviceScenariosRecommendTheRateAndWaitForAPerson() {
        waiting(only(SCENARIOS.run("healthy"), Action.LEAKY_BUCKET), 120, 132);
        waiting(only(SCENARIOS.run("stressed"), Action.LEAKY_BUCKET), 120, 131.3);
        var overloaded = SCENARIOS.run("overloaded");
        waiting(only(overloaded, Action.LEAKY_BUCKET), 120, 64);
        waiting(only(overloaded, Action.CHAIN_DEADLINE), 600, 510);
        waiting(only(SCENARIOS.run("retry-stressed"), Action.LEAKY_BUCKET), 120, 132);
        waiting(only(SCENARIOS.run("recovery"), Action.LEAKY_BUCKET), 60, 66);
    }
    @Test void deadlineScenariosMakeDeadlineCardsThatWaitForAPerson() {
        waiting(only(SCENARIOS.run("deadline-increase"), Action.CHAIN_DEADLINE), 1000, 1500);
        waiting(only(SCENARIOS.run("deadline-recovery"), Action.CHAIN_DEADLINE), 1440, 1160);
        var multiple = active(SCENARIOS.run("multiple-chains"));
        assertEquals(Set.of("1000->1500", "1200->960", "600->490"), multiple.stream()
                .map(c -> Math.round(c.proposal().currentValue()) + "->" + Math.round(c.proposal().proposedValue())).collect(java.util.stream.Collectors.toSet()));
        assertTrue(multiple.stream().allMatch(c -> c.decision() == Decision.PENDING_APPROVAL));
    }

    // ---- deadline v2 examples (simulated reviewer, unchanged) ----

    @Test void deadlineExamples() {
        var tooLong = SCENARIOS.run("deadline-too-long-under-overload");
        assertTrue(changes(tooLong).stream().allMatch(c -> c.proposal().proposedValue() < c.proposal().currentValue()), "under overload: only lowers");
        var tooShort = changes(SCENARIOS.run("deadline-too-short-day-one"));
        assertTrue(tooShort.stream().anyMatch(c -> c.proposal().currentValue() == 300 && c.proposal().proposedValue() == 450), "Case A 300 → 450");
        for (String hold : List.of("services-slowing", "mixed-paths")) {
            var r = SCENARIOS.run(hold);
            assertTrue(changes(r).isEmpty(), hold); assertEquals("STRESS_GATE", deadline(r).holdReason(), hold);
        }
        var queue = SCENARIOS.run("queue-rate-not-deadline");
        assertTrue(changes(queue).isEmpty()); assertEquals(Lever.RATE, deadline(queue).lever(), "the lever is the rate, not the deadline");
        assertEquals(ChainDeadlineState.PAUSED, deadline(SCENARIOS.run("rejecting-middleware-504")).state());
        for (String id : MockScenarios.EXAMPLE_SCENARIOS)
            assertTrue(changes(SCENARIOS.run(id)).stream().allMatch(c -> c.decision() != Decision.APPROVED || "simulated-reviewer".equals(c.reviewer())), id);
    }

    // ---- rate and retry v2: default mode (a simulated reviewer approves every step) ----

    @Test void rateScenariosWithTheSimulatedReviewer() {
        var tooLow = SCENARIOS.run("rate-too-low");
        assertTrue(steps(tooLow, Action.LEAKY_BUCKET).stream().allMatch(s -> s.to() > s.from() && "simulated-reviewer".equals(s.reviewer())), "RATE_TOO_LOW: only raises");
        assertTrue((double) rate(tooLow).get("finalRate") >= 187.5); assertEquals(0.0, rate(tooLow).get("shedAtEnd"));
        var overload = SCENARIOS.run("rate-real-overload");
        assertTrue((double) rate(overload).get("lowestRate") >= 50, "never below the floor 50");
        assertEquals(0, steps(overload, Action.RETRY_BUDGET).get(0).to(), "retry → 0 while the app is overloaded");
        var during = steps(overload, Action.LEAKY_BUCKET).stream().filter(s -> s.phase().startsWith("app capacity 60")).toList();
        double settled = during.get(during.size() - 1).to();
        assertTrue(settled >= 50 && settled <= 66, "settles near the app capacity 60 (≈ 54.7): " + settled);
        assertTrue(steps(SCENARIOS.run("rate-short-burst"), Action.LEAKY_BUCKET).isEmpty(), "one short burst: no change");
        var bursts = SCENARIOS.run("rate-bursts");
        assertTrue(steps(bursts, Action.LEAKY_BUCKET).stream().allMatch(s -> s.to() > s.from()), "repeating bursts: raise");
        assertEquals(169.5, (double) rate(bursts).get("finalRate"), 1e-9);
        var storm = SCENARIOS.run("retry-storm-rate");
        assertTrue(steps(storm, Action.RETRY_BUDGET).isEmpty(), "the retry budget is held"); assertEquals(.05, (double) rate(storm).get("finalRetry"), 1e-9);
        assertTrue(steps(SCENARIOS.run("rate-recovery"), Action.LEAKY_BUCKET).stream().allMatch(s -> s.to() > s.from()));
    }

    // ---- rate and retry v2: ?mode=auto (automatic approval on, isolated, never delivered) ----

    @Test void rateScenariosInAutomaticMode() {
        var tooLow = SCENARIOS.run("rate-too-low", true);
        assertEquals("auto", tooLow.get("mode"));
        assertTrue(steps(tooLow, Action.LEAKY_BUCKET).stream().allMatch(s -> s.to() > s.from() && "c4-auto".equals(s.reviewer())), "raises approved by c4-auto");
        var overload = SCENARIOS.run("rate-real-overload", true);
        var during = steps(overload, Action.LEAKY_BUCKET).stream().filter(s -> s.phase().startsWith("app capacity 60")).toList();
        assertEquals(List.of(80.0, 64.0, 51.2), during.stream().filter(s -> s.to() < s.from()).limit(3).map(ServiceSimulation.Step::to).toList(),
                "stepped cuts of at most −20%: 100 → 80 → 64 → 51.2");
        assertEquals(57.6, during.get(3).to(), 1e-9, "then one step up to 57.6, under the app capacity 60");
        assertTrue(during.size() == 4 || during.get(4).cycle() - during.get(3).cycle() >= 10, "and it settles there: " + during);
        // a later probe up that slowed the app is undone automatically
        assertTrue(steps(overload, Action.LEAKY_BUCKET).stream().noneMatch(s -> "c4-auto-revert".equals(s.reviewer()) && s.to() < 50));
        assertTrue((double) rate(overload).get("lowestRate") >= 50, "never below the floor 50");
        var retry = steps(overload, Action.RETRY_BUDGET).get(0);
        assertEquals(0, retry.to()); assertEquals("c4-auto", retry.reviewer());
        assertTrue(steps(SCENARIOS.run("rate-short-burst", true), Action.LEAKY_BUCKET).isEmpty());
        assertTrue(steps(SCENARIOS.run("rate-bursts", true), Action.LEAKY_BUCKET).stream().allMatch(s -> s.to() > s.from() && "c4-auto".equals(s.reviewer())));
        for (String id : MockScenarios.RATE_SCENARIOS)
            assertTrue(changes(SCENARIOS.run(id, true)).stream().allMatch(c -> c.delivery() == Delivery.WAITING_FOR_COMPONENT_3 || c.delivery() == Delivery.APPLIED
                    || c.delivery() == Delivery.SUPERSEDED || c.delivery() == Delivery.EXPIRED), id + ": never sent to Component 3");
    }
    @Test void withoutModeAutoEverythingIsAsBeforeAndDeadlinesAndJointsAreNeverAutomatic() {
        assertFalse(SCENARIOS.run("rate-too-low").containsKey("mode"));
        for (String id : List.of("deadline-increase", "deadline-too-short-day-one", "joint-recovery", "overloaded")) {
            var r = SCENARIOS.run(id, true);
            assertEquals("auto", r.get("mode"));
            assertTrue(changes(r).stream().noneMatch(c -> c.reviewer() != null && c.reviewer().startsWith("c4-auto")), id + ": no automatic change");
        }
    }

    // ---- joint healthy recalibration ----

    @Test void jointScenariosInBothModes() {
        for (boolean auto : List.of(false, true)) {
            var recovery = SCENARIOS.run("joint-recovery", auto);
            waiting(only(recovery, Action.LEAKY_BUCKET), 50, 100); waiting(only(recovery, Action.CHAIN_DEADLINE), 875, 780);
            var drop = SCENARIOS.run("joint-capacity-drop", auto);
            waiting(only(drop, Action.LEAKY_BUCKET), 100, 60); waiting(only(drop, Action.CHAIN_DEADLINE), 780, 850);
        }
    }
    @Test void priorityWhenANormalRecommendationIsNeededNoJointCardAppears() {
        var e = JointSimulation.recovery();
        var bed = JointEngineTests.bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        bed.healthyTarget(e.newTarget());
        // the app is slow at the same time as the healthy-target change: the normal rate rule comes first
        var changes = new ArrayList<PolicyChange>();
        for (int i = 0; i < 4; i++) changes.addAll(bed.cycle(new JointSimulation.Bed.Traffic(e.healthy(), e.demand(), e.depth(), 400, 0, 0)));
        assertTrue(changes.stream().noneMatch(c -> bed.workflow.isJointPart(c.proposal().id())), "no joint card");
        assertTrue(changes.stream().anyMatch(c -> c.proposal().action() == Action.LEAKY_BUCKET && c.proposal().proposedValue() < c.proposal().currentValue()),
                "the normal APP_OVERLOADED cut is offered");
    }
}
