package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** A waiting deadline card stays for the reviewer while the decision only waits for a rate change on the same chain. */
class DeadlineCardWaitsForRateTests {
    @Test void aWaitingDeadlineCardStaysWhileARateChangeOnTheChainIsInProgress() {
        var e = JointSimulation.recovery();
        var b = JointEngineTests.bed(e, AdaptiveConfig.JointSettings.defaults(), Source.MOCK);
        // healthy requests exceed the 875 ms deadline: Case A raise
        var overSignalled = new JointSimulation.Bed.Traffic(ChainSimulation.profile(500, 900, 950, 980, 1000), e.demand(), e.depth());
        PolicyChange card = null;
        for (int i = 0; i < 30 && card == null; i++) {
            card = b.cycle(overSignalled).stream().filter(c -> c.proposal().action() == Action.CHAIN_DEADLINE).findFirst().orElse(null);
        }
        assertNotNull(card, "a deadline card is offered");

        // more traffic than the rate lets through: C4 proposes a rate raise for a service on the chain, which waits too
        var busy = new JointSimulation.Bed.Traffic(overSignalled.chain(), b.rate() * 2, e.depth());
        boolean ratePending = false;
        for (int i = 0; i < 20 && !ratePending; i++) {
            b.cycle(busy);
            ratePending = b.workflow.pending(JointSimulation.SERVICE, Action.LEAKY_BUCKET, Source.MOCK).isPresent();
        }
        assertTrue(ratePending, "a rate change for the service waits as well");

        for (int i = 0; i < 8; i++) b.cycle(busy);
        assertTrue(b.workflow.pending(JointSimulation.SERVICE, Action.LEAKY_BUCKET, Source.MOCK).isPresent(), "the rate change still waits");
        var after = b.workflow.find(card.proposal().id());
        assertEquals(Decision.PENDING_APPROVAL, after.decision(), "the card still waits for the reviewer");
        assertNotEquals(Delivery.SUPERSEDED, after.delivery(), "the card did not go stale: " + after.detail());
        assertNotEquals(Delivery.EXPIRED, after.delivery(), after.detail());
    }
}
