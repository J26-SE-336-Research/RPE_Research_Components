package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Joint recalibration in the engine and its priority rules: the normal rate, retry and deadline
 * recommendations come first; a joint card never blocks, delays or replaces them.
 */
class JointEngineTests {
    static final Instant T0 = Instant.parse("2026-10-21T00:00:00Z");
    static JointSimulation.Bed bed(JointSimulation.Example e, AdaptiveConfig.JointSettings joint, Source source) {
        return JointSimulation.learned(e, DeadlineExamplesTests.settings(), AdaptiveConfig.DeadlineSettings.demo(), joint, source, T0);
    }
    static JointSimulation.Bed.Traffic healthy(JointSimulation.Example e) { return new JointSimulation.Bed.Traffic(e.healthy(), e.demand(), e.depth()); }
    static List<PolicyChange> jointParts(JointSimulation.Bed b, List<PolicyChange> changes) {
        return changes.stream().filter(c -> b.workflow.isJointPart(c.proposal().id())).toList();
    }
    static PolicyChange part(List<PolicyChange> parts, Action action) { return parts.stream().filter(c -> c.proposal().action() == action).findFirst().orElseThrow(); }
    static boolean evidence(JointSimulation.Bed b, String text) { return b.lastService.assessment().evidence().stream().anyMatch(e -> e.contains(text)); }

    @Test void withTheFlagOffAHealthyTargetChangeChangesNothing() {
        var e = JointSimulation.recovery();
        var b = bed(e, AdaptiveConfig.JointSettings.defaults(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        for (int i = 0; i < 5; i++) {
            var changes = b.cycle(healthy(e));
            assertTrue(changes.stream().noneMatch(c -> c.proposal().target().equals(JointSimulation.SERVICE) || c.proposal().action() == Action.CHAIN_DEADLINE), changes.toString());
        }
        assertTrue(b.workflow.joints().isEmpty());
        assertFalse(evidence(b, "Joint"), "no joint evidence with the flag off");
    }

    @Test void example1RecoveryMakesOneJointCardWithC3sNumbers() {
        var e = JointSimulation.recovery();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        var parts = jointParts(b, b.cycle(healthy(e)));
        assertEquals(2, parts.size(), "one rate card plus one deadline card");
        var rate = part(parts, Action.LEAKY_BUCKET); var deadline = part(parts, Action.CHAIN_DEADLINE);
        assertEquals(50, rate.proposal().currentValue()); assertEquals(100, rate.proposal().proposedValue());
        assertEquals(875, deadline.proposal().currentValue()); assertEquals(780, deadline.proposal().proposedValue());
        assertEquals(b.workflow.jointOf(rate.proposal().id()), b.workflow.jointOf(deadline.proposal().id()), "linked by one joint id");
        assertTrue(parts.stream().allMatch(c -> c.decision() == Decision.PENDING_APPROVAL), "never automatic");
        var m = deadline.proposal().measurements();
        assertEquals(8, m.get("jointQ")); assertEquals(50, m.get("jointRateOld")); assertEquals(50, m.get("jointRateLastStep")); assertEquals(100, m.get("jointRateNew"));
        assertEquals(700, m.get("thMs"), 1e-9); assertEquals(620, m.get("thNewMs"), 1e-9); assertEquals(780, m.get("proposedMs"));
        assertTrue(deadline.proposal().evidence().get(0).startsWith("Joint recommendation"), deadline.proposal().evidence().toString());
        assertTrue(rate.proposal().evidence().get(0).startsWith("Joint recommendation"));
        assertEquals(1, b.workflow.joints().size());
    }

    @Test void example2CapacityDropMakesOneJointCardWithC3sNumbers() {
        var e = JointSimulation.capacityDrop();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        var parts = jointParts(b, b.cycle(healthy(e)));
        assertEquals(100, part(parts, Action.LEAKY_BUCKET).proposal().currentValue()); assertEquals(60, part(parts, Action.LEAKY_BUCKET).proposal().proposedValue());
        assertEquals(780, part(parts, Action.CHAIN_DEADLINE).proposal().currentValue()); assertEquals(850, part(parts, Action.CHAIN_DEADLINE).proposal().proposedValue());
    }

    @Test void theJointCardKeepsItsIdentityWhileItWaitsAndOneDecisionDecidesAllParts() {
        var e = JointSimulation.recovery();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        var first = jointParts(b, b.cycle(healthy(e)));
        for (int i = 0; i < 6; i++) {
            var again = jointParts(b, b.cycle(healthy(e)));
            assertEquals(first.stream().map(c -> c.proposal().id()).sorted().toList(), again.stream().map(c -> c.proposal().id()).sorted().toList(), "same cards, refreshed");
        }
        var deadline = part(first, Action.CHAIN_DEADLINE);
        b.workflow.decide(deadline.proposal().id(), new Review("sre", true, "joint"));
        assertTrue(first.stream().allMatch(c -> b.workflow.find(c.proposal().id()).decision() == Decision.APPROVED), "approving one card approves all");
        assertTrue(first.stream().allMatch(c -> "sre".equals(b.workflow.find(c.proposal().id()).reviewer())));
    }

    @Test void rejectingOneCardRejectsAll() {
        var e = JointSimulation.capacityDrop();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        var parts = jointParts(b, b.cycle(healthy(e)));
        b.workflow.decide(part(parts, Action.LEAKY_BUCKET).proposal().id(), new Review("sre", false, "not now"));
        assertTrue(parts.stream().allMatch(c -> b.workflow.find(c.proposal().id()).decision() == Decision.REJECTED));
    }

    @Test void aHealthyStepThatReachesItsTargetIsCheckedAndASmallEffectLetsTheRateGoAlone() {
        var e = JointSimulation.recovery();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        var demand = 46.0; // healthy target 46 * 1.25 = 57.5, reached in steps 50, 55, 57.5
        List<PolicyChange> rates = new ArrayList<>(); String joint = null;
        for (int i = 0; i < 60 && rates.size() < 2; i++) {
            for (var c : b.cycle(new JointSimulation.Bed.Traffic(e.healthy(), demand, e.depth()))) {
                if (c.proposal().action() != Action.LEAKY_BUCKET || c.decision() != Decision.PENDING_APPROVAL) continue;
                rates.add(c);
                if (c.proposal().proposedValue() == 57.5) joint = b.lastService.assessment().evidence().stream().filter(x -> x.startsWith("Joint healthy recalibration")).findFirst().orElse(null);
                b.workflow.decide(c.proposal().id(), new Review("sre", true, null)); b.apply(c);
            }
        }
        assertEquals(List.of(55.0, 57.5), rates.stream().map(c -> c.proposal().proposedValue()).toList(), rates.toString());
        assertTrue(rates.stream().noneMatch(c -> b.workflow.isJointPart(c.proposal().id())), "both are normal rate proposals");
        assertNotNull(joint, "the step that reaches the target was checked for a joint");
        assertTrue(joint.contains("too small, the rate goes alone"), joint);
    }

    // ---- priority rules ----

    @Test void rule2APendingNormalProposalMeansNoJointCard() {
        var e = JointSimulation.recovery();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        // a normal retry recommendation waits for a reviewer (offered after the target change, on the current policy version)
        b.workflow.offer(new Proposal(UUID.randomUUID(), JointSimulation.SERVICE, Action.RETRY_BUDGET, Source.MOCK, b.workflow.current(JointSimulation.SERVICE, Source.MOCK).version(),
                0, .02, "extra attempts/original request", b.clock.now, b.clock.now.plusSeconds(600), Map.of("windowEndEpochSeconds", (double) b.clock.now.getEpochSecond()), List.of("a normal retry proposal")));
        var changes = b.cycle(healthy(e));
        assertTrue(jointParts(b, changes).isEmpty(), changes.toString());
        assertTrue(b.workflow.joints().isEmpty());
        assertTrue(evidence(b, "a normal RETRY_BUDGET proposal is pending"), b.lastService.assessment().evidence().toString());
    }

    @Test void rule3AnOverloadWithdrawsTheWaitingJointAndTheNormalCutComesInTheSameCycle() {
        var e = JointSimulation.recovery();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        var parts = jointParts(b, b.cycle(healthy(e)));
        assertEquals(2, parts.size());
        PolicyChange cut = null; int cycles = 0;
        while (cut == null && cycles++ < 10) {
            var changes = b.cycle(new JointSimulation.Bed.Traffic(e.healthy(), e.demand(), e.depth(), 400, 0, 0)); // the app becomes slow
            assertTrue(jointParts(b, changes).stream().noneMatch(c -> c.proposal().id().equals(parts.get(0).proposal().id()) && c.decision() == Decision.PENDING_APPROVAL
                    && c.delivery() != Delivery.SUPERSEDED), "the joint card is not refreshed during the overload");
            cut = changes.stream().filter(c -> c.proposal().action() == Action.LEAKY_BUCKET && !b.workflow.isJointPart(c.proposal().id())
                    && c.proposal().proposedValue() < c.proposal().currentValue()).findFirst().orElse(null);
            if (cut == null) continue;
            for (var p : parts) {
                var now = b.workflow.find(p.proposal().id());
                assertEquals(Delivery.SUPERSEDED, now.delivery(), "withdrawn by the time the normal cut is offered");
                assertTrue(now.detail().contains("normal recommendation comes first") || now.detail().contains("A part of this joint recommendation was withdrawn"), now.detail());
            }
        }
        assertNotNull(cut, "the normal APP_OVERLOADED cut is offered");
        assertTrue(b.workflow.activeJoint(JointSimulation.SERVICE, Source.MOCK).isEmpty());
    }

    @Test void rule3ADeadlineCaseForTheChainWithdrawsTheWaitingJoint() {
        var e = JointSimulation.recovery();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.MOCK);
        b.healthyTarget(e.newTarget());
        var parts = jointParts(b, b.cycle(healthy(e)));
        // healthy requests now go over the 875 ms deadline, so the deadline logic needs Case A
        var overSignalled = ChainSimulation.profile(500, 900, 950, 980, 1000);
        PolicyChange raise = null;
        for (int i = 0; i < 30 && raise == null; i++) {
            raise = b.cycle(new JointSimulation.Bed.Traffic(overSignalled, e.demand(), e.depth())).stream()
                    .filter(c -> c.proposal().action() == Action.CHAIN_DEADLINE && !b.workflow.isJointPart(c.proposal().id())).findFirst().orElse(null);
        }
        assertNotNull(raise, "deadline v2's own Case A is offered");
        assertTrue(raise.proposal().proposedValue() > 875);
        assertTrue(parts.stream().allMatch(p -> b.workflow.find(p.proposal().id()).delivery() == Delivery.SUPERSEDED), "the joint card was withdrawn");
    }

    @Test void rule4ARetryChangeInTheSameCycleWinsAndNoJointIsMade() {
        var e = JointSimulation.recovery();
        var b = new JointSimulation.Bed(DeadlineExamplesTests.settings(), AdaptiveConfig.DeadlineSettings.demo(), AdaptiveConfig.JointSettings.on(), Source.MOCK, T0);
        b.policies(50, 50, .05, 875);
        double demand = 38.1; // with 5 percent retries and 25 percent headroom this is 50, so the healthy rule wants no change
        for (int i = 0; i < JointSimulation.learningCycles(b.settings, AdaptiveConfig.DeadlineSettings.demo()); i++)
            b.cycle(new JointSimulation.Bed.Traffic(e.healthy(), demand, e.depth()));
        b.cycle(new JointSimulation.Bed.Traffic(e.healthy(), demand, e.depth(), 40, 1, .1)); // retries rarely succeed (10 %)
        b.healthyTarget(e.newTarget());
        var changes = b.cycle(new JointSimulation.Bed.Traffic(e.healthy(), demand, e.depth(), 40, 1, .1));
        assertTrue(changes.stream().anyMatch(c -> c.proposal().action() == Action.RETRY_BUDGET), "the normal retry change is made: " + changes);
        assertTrue(jointParts(b, changes).isEmpty(), "and the joint card is skipped");
        assertTrue(b.workflow.joints().isEmpty());
    }

    @Test void d6NoJointCardWhileADeadlineChangeIsSettling() {
        // e2e 95: the operator changes the gateway deadline; live sources (PROMETHEUS / TEMPO) as in the cluster
        var e = JointSimulation.recovery();
        var b = bed(e, AdaptiveConfig.JointSettings.on(), Source.PROMETHEUS);
        b.deadline(870);
        b.healthyTarget(e.newTarget());
        for (int i = 0; i < 6; i++) {
            var changes = b.cycle(healthy(e));
            assertTrue(jointParts(b, changes).isEmpty(), "no joint card while the deadline change settles");
        }
        assertTrue(evidence(b, "deadline not settled"), b.lastService.assessment().evidence().toString());
        assertTrue(b.workflow.joints().isEmpty());
    }
}
