package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The check result of each automatic rate change is published in the rate evidence ({@code checks}, additive field). */
class RateCheckEvidenceTests {
    @SuppressWarnings("unchecked")
    static List<RateState.Check> checks(ServiceSimulation.Scenario scenario) {
        var settings = DeadlineExamplesTests.settings();
        var clock = new ChainSimulation.VirtualClock(Instant.parse("2026-10-20T00:00:00Z"));
        var workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureAutoApproval(new AdaptiveConfig.AutoApproval(AutoApprovalTests.DEMO));
        ServiceSimulation.run(engine, workflow, clock, settings, "inventory-svc", Source.PROMETHEUS, scenario, false);
        var evidence = engine.rateEvidence().get("inventory-svc");
        assertNotNull(evidence, "rate evidence for the live service");
        return (List<RateState.Check>) evidence.get("checks");
    }

    @Test void everyAutomaticCutIsListedWithItsCheck() {
        var checks = checks(ServiceSimulation.byId("rate-real-overload"));
        assertFalse(checks.isEmpty());
        var cuts = checks.stream().filter(c -> c.toRate() < c.fromRate() && "kept".equals(c.result())).toList();
        assertTrue(cuts.size() >= 2, "the stepped cuts are kept: " + checks);
        for (var c : cuts) {
            assertNotNull(c.appBeforeMs()); assertNotNull(c.appAfterMs()); assertNotNull(c.normalMs());
            assertNotNull(c.proposalId()); assertNotNull(c.at());
        }
        assertTrue(checks.size() <= 20, "at most 20 kept");
    }

    @Test void aRaiseThatSlowsTheAppIsListedAsUndone() {
        var checks = checks(AutoApprovalTests.RAISE_HURTS);
        var undone = checks.stream().filter(c -> "undone".equals(c.result())).findFirst().orElseThrow(() -> new AssertionError(checks.toString()));
        assertTrue(undone.toRate() > undone.fromRate(), "the raise is the undone change");
        assertTrue(undone.appAfterMs() > undone.normalMs(), "its app time went above normal: " + undone);
    }
}
