package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Practice cards (?to=queue): which cards a scenario gives, one decision for a joint card, never delivered, cap and clear. */
class PracticeCardsTests {
    static final MockScenarios SCENARIOS = new MockScenarios(AdaptiveConfig.Settings.defaults(), AdaptiveEngineTests.connections(""));

    static PolicyWorkflow workflow(AdaptiveEngineTests.TestClock clock) {
        // a delivery URL is configured: practice cards must still never be deliverable
        return new PolicyWorkflow(AdaptiveEngineTests.connections("http://component3.invalid"), AdaptiveEngineTests.settings(), clock);
    }
    static List<PolicyChange> cards(PolicyWorkflow w) { return w.list().stream().filter(c -> c.proposal().source() == Source.MOCK).toList(); }
    static String text(PolicyChange c) { return c.proposal().action() + " " + Math.round(c.proposal().currentValue() * 100) / 100.0 + "->" + Math.round(c.proposal().proposedValue() * 100) / 100.0; }

    @Test void eachScenarioGivesTheCardsAReviewerWouldDecide() {
        var clock = new AdaptiveEngineTests.TestClock(); var w = workflow(clock);
        PracticeCards.add("rate-real-overload", SCENARIOS.run("rate-real-overload"), w, clock.now);
        assertEquals(Set.of("LEAKY_BUCKET 100.0->50.0", "RETRY_BUDGET 0.05->0.0"), cards(w).stream().map(PracticeCardsTests::text).collect(java.util.stream.Collectors.toSet()),
                "the first rate and retry recommendation after learning");
        assertTrue(cards(w).stream().allMatch(c -> c.decision() == Decision.PENDING_APPROVAL), "waiting for a person");
        PracticeCards.clear(w);
        PracticeCards.add("deadline-too-short-day-one", SCENARIOS.run("deadline-too-short-day-one"), w, clock.now);
        assertEquals(List.of("CHAIN_DEADLINE 300.0->450.0"), cards(w).stream().map(PracticeCardsTests::text).toList());
        PracticeCards.clear(w);
        assertTrue(PracticeCards.add("rate-short-burst", SCENARIOS.run("rate-short-burst"), w, clock.now).isEmpty(), "no change: no card");
        var overloaded = PracticeCards.add("overloaded", SCENARIOS.run("overloaded"), w, clock.now);
        assertEquals(2, overloaded.size(), "rate cut and the deadline (Case B)");
        assertTrue(cards(w).stream().allMatch(c -> c.proposal().evidence().get(c.proposal().evidence().size() - 1).startsWith("Simulation: practice card")));
    }

    @Test void aJointPracticeCardIsOneDecisionAndNothingIsEverDelivered() {
        var clock = new AdaptiveEngineTests.TestClock(); var w = workflow(clock);
        PracticeCards.add("joint-recovery", SCENARIOS.run("joint-recovery"), w, clock.now);
        var parts = cards(w);
        assertEquals(Set.of("LEAKY_BUCKET 50.0->100.0", "CHAIN_DEADLINE 875.0->780.0"), parts.stream().map(PracticeCardsTests::text).collect(java.util.stream.Collectors.toSet()));
        assertNotNull(w.jointOf(parts.get(0).proposal().id()));
        assertEquals(w.jointOf(parts.get(0).proposal().id()), w.jointOf(parts.get(1).proposal().id()), "linked");
        w.decide(parts.get(0).proposal().id(), new Review("sre-alice", true, "practice"));
        assertTrue(cards(w).stream().allMatch(c -> c.decision() == Decision.APPROVED && "sre-alice".equals(c.reviewer())), "one decision for both parts");
        assertTrue(w.deliverable().isEmpty(), "approved practice cards are never delivered");
        assertTrue(cards(w).stream().allMatch(c -> c.delivery() == Delivery.WAITING_FOR_COMPONENT_3));
    }

    @Test void practiceCardsStayForTheirLifetimeAreCappedAndClearOnlyRemovesThem() {
        var clock = new AdaptiveEngineTests.TestClock(); var w = workflow(clock);
        // a live card on a live target
        w.putPolicy(new Policy("gateway", "e1", clock.now.plusSeconds(3600), 100.0, 100.0, .05, null), Source.PROMETHEUS);
        var live = w.offer(new Proposal(UUID.randomUUID(), "gateway", Action.LEAKY_BUCKET, Source.PROMETHEUS, "e1", 100, 120, "requests/second", clock.now,
                clock.now.plusSeconds(3 * 3600), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("live")));
        w.decide(live.proposal().id(), new Review("sre", true, "live"));
        PracticeCards.add("deadline-increase", SCENARIOS.run("deadline-increase"), w, clock.now);
        clock.next(20 * 60);
        assertEquals(1, cards(w).stream().filter(c -> c.delivery() != Delivery.SUPERSEDED && c.delivery() != Delivery.EXPIRED).count(), "still there after 20 minutes");
        for (String s : List.of("overloaded", "multiple-chains", "joint-recovery", "joint-capacity-drop", "rate-real-overload", "deadline-recovery"))
            for (int i = 0; i < 4; i++) { clock.next(1); PracticeCards.add(s, SCENARIOS.run(s), w, clock.now); }
        assertTrue(cards(w).size() <= PracticeCards.MAX_CARDS, "at most " + PracticeCards.MAX_CARDS + " practice cards: " + cards(w).size());
        assertTrue(w.list().stream().anyMatch(c -> c.proposal().id().equals(live.proposal().id())), "the live card is still listed");
        assertTrue(PracticeCards.clear(w) > 0);
        assertTrue(cards(w).isEmpty());
        assertEquals(Delivery.READY, w.find(live.proposal().id()).delivery(), "the live card is untouched");
        assertNotNull(w.current("gateway", Source.PROMETHEUS), "live policy references stay");
        clock.next(Duration.ofHours(2).toSeconds());
    }
}
