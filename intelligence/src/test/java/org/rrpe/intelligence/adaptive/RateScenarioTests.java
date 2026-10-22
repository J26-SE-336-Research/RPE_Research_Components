package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Rate/retry v2 demo scenarios (POST /demo/{scenario}) and the queue signals of the existing service scenarios (R1). */
class RateScenarioTests {
    final MockScenarios scenarios = new MockScenarios(AdaptiveConfig.Settings.defaults(), AdaptiveEngineTests.connections(""));

    @SuppressWarnings("unchecked") static List<PolicyChange> changes(Map<String, Object> r) { return (List<PolicyChange>) r.get("changes"); }
    @SuppressWarnings("unchecked") static Map<String, Object> evidence(Map<String, Object> r) { return ((List<Map<String, Object>>) r.get("rateEvidence")).get(0); }
    @SuppressWarnings("unchecked") static List<ServiceSimulation.Step> steps(Map<String, Object> r) {
        return ((List<ServiceSimulation.Step>) evidence(r).get("steps")).stream().filter(s -> s.action() == Action.LEAKY_BUCKET).toList();
    }

    @Test void everyRateScenarioRunsIsolatedWithSimulatedApprovals() {
        for (String id : MockScenarios.RATE_SCENARIOS) {
            var r = scenarios.run(id);
            assertEquals("MOCK", r.get("source"), id); assertEquals(true, r.get("simulatedApprovals"), id);
            assertTrue(evidence(r).containsKey("finalRate") && evidence(r).containsKey("evidence"), id);
            assertTrue(changes(r).stream().allMatch(c -> c.proposal().source() == Source.MOCK), id);
        }
        assertTrue(MockScenarios.RATE_SCENARIOS.containsAll(List.of("rate-too-low", "rate-real-overload", "rate-recovery", "rate-bursts")));
    }

    @Test void theRateScenariosShowTheGuideExamples() {
        for (String id : List.of("rate-too-low", "rate-recovery", "rate-bursts")) {
            var r = scenarios.run(id);
            assertFalse(steps(r).isEmpty(), id);
            assertTrue(steps(r).stream().allMatch(s -> s.to() > s.from()), id + ": only raises " + steps(r));
            assertEquals(0.0, evidence(r).get("shedAtEnd"), id);
        }
        var overload = scenarios.run("rate-real-overload");
        assertTrue((double) evidence(overload).get("lowestRate") >= 50, "never below the floor");
        assertTrue(steps(overload).stream().anyMatch(s -> s.to() < s.from()));
        assertTrue((double) evidence(scenarios.run("rate-recovery")).get("finalRate") >= 90 * 1.25, "back above demand 90 × 1.25");
    }

    @Test void theServiceScenariosNowProduceRateDecisions() {
        // production settings: the health baseline is still learning, so the retry rule waits; rate v2 learns its own P0
        var overloaded = changes(scenarios.run("overloaded")).stream().filter(c -> c.proposal().action() == Action.LEAKY_BUCKET).toList();
        assertEquals(1, overloaded.size());
        assertEquals(120, overloaded.get(0).proposal().currentValue()); assertEquals(64, overloaded.get(0).proposal().proposedValue(), "max(floor 60, 0.8 × accepted 80)");
        assertTrue(overloaded.get(0).proposal().evidence().stream().anyMatch(e -> e.contains("APP_OVERLOADED")));
        var recovery = changes(scenarios.run("recovery")).stream().filter(c -> c.proposal().action() == Action.LEAKY_BUCKET).toList();
        assertEquals(66, recovery.get(0).proposal().proposedValue(), "60 + 0.10 × 60 toward demand × 1.25");
        for (String name : MockScenarios.SERVICE_SCENARIOS)
            assertTrue(changes(scenarios.run(name)).stream().allMatch(c -> c.decision() == Decision.PENDING_APPROVAL), name + ": every recommendation waits for a reviewer");
    }
}
