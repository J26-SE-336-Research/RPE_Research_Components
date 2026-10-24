package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** After a joint card is applied, its deadline part settles, is checked as RECALIBRATE, and the healthy time is learned again. */
class JointAfterAppliedTests {
    @Test void anAppliedJointDeadlineIsSettledVerifiedAndRelearned() {
        var e = JointSimulation.recovery();
        var b = JointEngineTests.bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        var parts = JointEngineTests.jointParts(b, b.cycle(JointEngineTests.healthy(e)));
        b.workflow.decide(parts.get(0).proposal().id(), new Review("sre", true, "joint"));
        for (var p : parts) b.apply(b.workflow.find(p.proposal().id()));
        assertEquals(100, b.rate()); assertEquals(780, b.deadlineMs());
        // after the switch the healthy queue wait is 80 ms shorter: healthy time ≈ 620 ms
        var faster = ChainSimulation.profile(300, 560, 580, 620, 660);
        b.cycle(new JointSimulation.Bed.Traffic(faster, e.demand(), e.depth()));
        var ev = b.engine.deadlineEvidence(JointSimulation.CHAIN, Source.MOCK);
        assertEquals(ChainDeadlineState.SETTLING, ev.state(), "the joint's deadline part is settling");
        String verification = null; int fewestWindows = Integer.MAX_VALUE;
        for (int i = 0; i < 40 && verification == null; i++) {
            b.cycle(new JointSimulation.Bed.Traffic(faster, e.demand(), e.depth()));
            var now = b.engine.deadlineEvidence(JointSimulation.CHAIN, Source.MOCK);
            fewestWindows = Math.min(fewestWindows, now.model().windows());
            verification = now.verification();
        }
        assertTrue(fewestWindows <= 2, "healthy-time learning restarted at the switch (windows " + fewestWindows + ")");
        assertNotNull(verification);
        assertTrue(verification.startsWith("PASS after RECALIBRATE 875 → 780 ms"), verification);
        // after verification: no further joint and no rate change (the rate is at its target)
        var later = b.cycle(new JointSimulation.Bed.Traffic(faster, e.demand(), e.depth()));
        assertTrue(later.stream().noneMatch(c -> b.workflow.isJointPart(c.proposal().id()) && c.decision() == Decision.PENDING_APPROVAL), later.toString());
    }
}
