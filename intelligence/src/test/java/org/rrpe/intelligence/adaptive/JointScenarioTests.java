package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

/** POST /demo/joint-recovery and /demo/joint-capacity-drop always give one joint card with the example numbers. */
class JointScenarioTests {
    /** The demo cluster's values (deploy/k8s/platform/intelligence.yaml): window 1 min, cycle 15 s, 5 baseline samples, cooldown 1 min. */
    static AdaptiveConfig.Settings clusterSettings() {
        var s = AdaptiveConfig.Settings.defaults();
        return new AdaptiveConfig.Settings(Duration.ofMinutes(1), Duration.ofSeconds(15), 5, s.minimumServiceRequests(), s.minimumChainRequests(), s.persistence(),
                s.recoveryWindows(), Duration.ofMinutes(1), s.ttl(), s.stabilityTolerance(), s.stressedMad(), s.overloadedMad(), s.zeroMadRelativeFloor(),
                s.ratioFloor(), s.maximumRateChange(), s.maximumRetryRatio(), s.minimumDeadlineMs(), s.maximumDeadlineMs(), s.maximumDeadlineChange());
    }
    static MockScenarios production() { return new MockScenarios(AdaptiveConfig.Settings.defaults(), AdaptiveEngineTests.connections("")); }
    static MockScenarios cluster() {
        var m = new MockScenarios(clusterSettings(), AdaptiveEngineTests.connections(""));
        m.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
        return m;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> evidence(Map<String, Object> r) { return ((List<Map<String, Object>>) r.get("jointEvidence")).get(0); }
    @SuppressWarnings("unchecked")
    static List<PolicyChange> changes(Map<String, Object> r) { return (List<PolicyChange>) r.get("changes"); }

    @Test void bothScenariosShowOneJointCardWithC3sNumbersUnderProductionAndClusterSettings() {
        for (var scenarios : List.of(production(), cluster())) {
            var recovery = scenarios.run("joint-recovery");
            assertEquals(50.0, evidence(recovery).get("rateFrom")); assertEquals(100.0, evidence(recovery).get("rateTo"));
            assertEquals(875.0, evidence(recovery).get("deadlineFromMs")); assertEquals(780.0, evidence(recovery).get("deadlineToMs"));
            var drop = scenarios.run("joint-capacity-drop");
            assertEquals(100.0, evidence(drop).get("rateFrom")); assertEquals(60.0, evidence(drop).get("rateTo"));
            assertEquals(780.0, evidence(drop).get("deadlineFromMs")); assertEquals(850.0, evidence(drop).get("deadlineToMs"));
            for (var r : List.of(recovery, drop)) {
                assertEquals("MOCK", r.get("source"));
                assertEquals(1, ((List<?>) r.get("joints")).size(), "one joint card");
                var parts = changes(r).stream().filter(c -> c.delivery() != Delivery.SUPERSEDED).toList();
                assertEquals(2, parts.size(), parts.toString());
                assertTrue(parts.stream().allMatch(c -> c.decision() == Decision.PENDING_APPROVAL), "waits for a reviewer");
                assertTrue(parts.stream().allMatch(c -> c.proposal().source() == Source.MOCK), "never delivered");
            }
        }
    }
    @Test void theScenarioNamesAreKnown() {
        assertEquals(List.of("joint-recovery", "joint-capacity-drop"), MockScenarios.JOINT_SCENARIOS);
    }
}
