package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Practice (MOCK) cards never affect live decisions: the same live run, without and with practice cards on the SAME target
 * names (approved and rejected while it runs), gives exactly the same live recommendations. That covers the pending-proposal
 * locks, the deadline proposals per hour and safety counters, the settled-deadline lock, the automatic-approval guard and
 * pause, the rule that a reviewer change makes a check INCONCLUSIVE, and delivery.
 */
class PracticeIsolationTests {
    static final String CHAIN = DeadlineSettledLockTests.CHAIN;

    /** Practice cards on the live names: a gateway rate and retry card and a deadline card for the live chain. */
    static void practice(DeadlineSettledLockTests.Live live, String tag) {
        var now = live.clock.now;
        var changes = new ArrayList<PolicyChange>();
        for (var p : List.of(
                new Proposal(UUID.randomUUID(), "gateway", Action.LEAKY_BUCKET, Source.MOCK, "x", 100, 150, "requests/second", now, now.plusSeconds(3600), Map.of(), List.of(tag)),
                new Proposal(UUID.randomUUID(), "gateway", Action.RETRY_BUDGET, Source.MOCK, "x", .1, 0, "extra attempts/original request", now, now.plusSeconds(3600), Map.of(), List.of(tag)),
                new Proposal(UUID.randomUUID(), CHAIN, Action.CHAIN_DEADLINE, Source.MOCK, "x", 100, 150, "milliseconds", now, now.plusSeconds(3600), Map.of(), List.of(tag))))
            changes.add(new PolicyChange(p, Decision.PENDING_APPROVAL, null, null, Delivery.WAITING_FOR_COMPONENT_3, "", 0, null, null));
        var added = PracticeCards.add("isolation-" + tag, Map.of("changes", changes), live.workflow, now);
        assertEquals(3, added.size());
        // a reviewer approves the rate card and rejects the deadline card, as in a practice session
        var ids = added.stream().map(a -> UUID.fromString((String) a.get("proposalId"))).toList();
        live.workflow.decide(ids.get(0), new Review("sre", true, "practice"));
        live.workflow.decide(ids.get(2), new Review("sre", false, "practice"));
    }

    static List<String> liveRun(boolean withPractice) {
        var live = new DeadlineSettledLockTests.Live();
        live.learn();
        var t0 = live.clock.now;
        if (withPractice) practice(live, "a");
        for (int i = 0; i < 15; i++) live.cycle(500, 150);                         // RATE_TOO_LOW: automatic raises
        if (withPractice) {
            practice(live, "b");
            assertFalse(live.workflow.reviewerApprovedSince("gateway", Source.PROMETHEUS, t0), "a practice approval is not a live reviewer approval");
            assertTrue(live.workflow.deliverable().stream().noneMatch(c -> c.proposal().source() == Source.MOCK), "practice cards are never delivered");
        }
        live.policies("op-v2", 100, .1, 100);                                      // the operator's tight deadline (e2e 95)
        for (int i = 0; i < 40; i++) live.cycle(100, 60, 100.0, 10.0);              // deadline case A, retry step down
        if (withPractice) assertTrue(PracticeCards.clear(live.workflow) > 0);
        // rates never wait for a person: held changes are part of the live decisions compared below
        var out = new java.util.ArrayList<String>(live.workflow.list().stream().filter(c -> c.proposal().source() != Source.MOCK)
                .sorted(Comparator.comparing((PolicyChange c) -> c.proposal().generatedAt()).thenComparing(c -> c.proposal().action()))
                .map(c -> c.proposal().generatedAt() + " " + c.proposal().action() + " " + c.proposal().target() + " " + c.proposal().currentValue() + "->"
                        + c.proposal().proposedValue() + " " + c.decision() + " " + c.reviewer() + " " + c.delivery())
                .toList());
        live.held.stream().distinct().forEach(h -> out.add("HELD " + h));
        return out;
    }
    @Test void practiceCardsNeverChangeLiveDecisions() {
        var without = liveRun(false);
        var with = liveRun(true);
        assertEquals(without, with, "identical live recommendations with and without practice cards");
        assertTrue(without.stream().anyMatch(s -> s.contains("c4-auto")), "the run includes automatic approvals: " + without);
        assertTrue(without.stream().anyMatch(s -> s.contains("CHAIN_DEADLINE")), "and a deadline recommendation: " + without);
        assertTrue(without.stream().anyMatch(s -> s.startsWith("HELD held: retry ratio")), "and a (held) retry recommendation: " + without);
    }

    @Test void practiceCardsOnLiveNamesCreateNoLiveLock() {
        var live = new DeadlineSettledLockTests.Live();
        live.learn();
        practice(live, "c");
        assertTrue(live.workflow.pending("gateway", Action.LEAKY_BUCKET, Source.PROMETHEUS).isEmpty(), "no live pending rate");
        assertTrue(live.workflow.pending("gateway", Action.RETRY_BUDGET, Source.PROMETHEUS).isEmpty(), "no live pending retry");
        assertTrue(live.workflow.pending(CHAIN, Action.CHAIN_DEADLINE, Source.TEMPO).isEmpty(), "no live pending deadline");
        assertTrue(live.workflow.activeJoint("gateway", Source.PROMETHEUS).isEmpty());
        assertFalse(live.workflow.reviewerApprovedSince("gateway", Source.PROMETHEUS, live.clock.now.minusSeconds(60)), "not a live reviewer change");
        assertEquals(100, live.workflow.current("gateway", Source.PROMETHEUS).bucketRate(), "the live policy reference is untouched");
        assertEquals(500, live.workflow.current(CHAIN, Source.TEMPO).deadlineMs());
    }
}
