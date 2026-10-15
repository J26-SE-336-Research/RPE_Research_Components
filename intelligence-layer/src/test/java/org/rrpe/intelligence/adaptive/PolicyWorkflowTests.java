package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyWorkflowTests {
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private final PolicyWorkflow workflow = new PolicyWorkflow(AdaptiveEngineTests.connections("http://localhost:8088/updates"), clock);
    private Proposal proposal(Action action, Source source, double current, double proposed) {
        return new Proposal(UUID.randomUUID(), "target", action, source, "v1", current, proposed, "milliseconds", clock.now,
                clock.now.plusSeconds(300), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("test evidence"));
    }
    private void policy(Source source) {
        workflow.putPolicy(new Policy("target", "v1", clock.now.plusSeconds(3600), 120.0, 120.0, .05, 1000.0), source);
    }
    @Test void deadlinesCannotBeDeliveredBeforeApprovalAndSentIsNotApplied() {
        policy(Source.TEMPO);
        var record = workflow.offer(proposal(Action.CHAIN_DEADLINE, Source.TEMPO, 1000, 1440));
        assertTrue(workflow.deliverable().isEmpty());
        workflow.deliveryResult(record.proposal().id(), new Acknowledgement("APPLIED", "v2", 1440.0), null);
        assertEquals(1000, workflow.current("target", Source.TEMPO).deadlineMs());
        workflow.decide(record.proposal().id(), new Review("authorised-reviewer", true, "approve"));
        assertEquals(1, workflow.deliverable().size());
        workflow.deliveryResult(record.proposal().id(), new Acknowledgement("ACCEPTED", null, null), null);
        assertEquals(Delivery.SENT, workflow.find(record.proposal().id()).delivery());
        assertEquals(1000, workflow.current("target", Source.TEMPO).deadlineMs());
        workflow.deliveryResult(record.proposal().id(), new Acknowledgement("APPLIED", "v2", 1440.0), null);
        assertEquals(1440, workflow.current("target", Source.TEMPO).deadlineMs());
    }
    @Test void expiryAndStalenessCannotExtendDeadlineApprovalValidity() {
        policy(Source.TEMPO); var record = workflow.offer(proposal(Action.CHAIN_DEADLINE, Source.TEMPO, 1000, 1440));
        clock.next(300);
        assertThrows(IllegalArgumentException.class, () -> workflow.decide(record.proposal().id(), new Review("reviewer", true, null)));
        assertEquals(Delivery.EXPIRED, workflow.find(record.proposal().id()).delivery());
        assertEquals(1000, workflow.current("target", Source.TEMPO).deadlineMs());
        var fresh = workflow.offer(proposal(Action.CHAIN_DEADLINE, Source.TEMPO, 1000, 1400)); clock.next(121);
        assertTrue(workflow.deliverable().isEmpty()); assertEquals(Delivery.SUPERSEDED, workflow.find(fresh.proposal().id()).delivery());
    }
    @Test void mocksNeverDeliverAndAutomaticServiceUpdatesHaveNoHumanApproval() {
        policy(Source.MOCK); var mock = workflow.offer(proposal(Action.LEAKY_BUCKET, Source.MOCK, 120, 90));
        assertEquals(Decision.AUTOMATIC, mock.decision()); assertTrue(workflow.deliverable().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> workflow.decide(mock.proposal().id(), new Review("reviewer", true, null)));
        policy(Source.PROMETHEUS); var live = workflow.offer(proposal(Action.LEAKY_BUCKET, Source.PROMETHEUS, 120, 90));
        assertEquals(Decision.AUTOMATIC, live.decision()); assertEquals(1, workflow.deliverable().size());
    }
    @Test void policyVersionChangesWithdrawOlderPendingDecisions() {
        policy(Source.TEMPO); var record = workflow.offer(proposal(Action.CHAIN_DEADLINE, Source.TEMPO, 1000, 1440));
        workflow.putPolicy(new Policy("target", "v2", clock.now.plusSeconds(3600), 120.0, 120.0, .05, 1200.0), Source.TEMPO);
        assertEquals(Delivery.SUPERSEDED, workflow.find(record.proposal().id()).delivery());
        assertThrows(IllegalArgumentException.class, () -> workflow.decide(record.proposal().id(), new Review("reviewer", true, null)));
    }
}
