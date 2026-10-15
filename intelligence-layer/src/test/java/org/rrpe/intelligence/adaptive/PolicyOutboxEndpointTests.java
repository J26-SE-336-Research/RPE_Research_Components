package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PolicyOutboxEndpointTests {
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private final PolicyWorkflow workflow = new PolicyWorkflow(AdaptiveEngineTests.connections(""), clock);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdaptiveController(null, workflow, null, null, null, null, "", false)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private PolicyChange offer(Action action, Source source, String target) {
        workflow.putPolicy(new Policy(target, "v1", clock.now.plusSeconds(3600), 120.0, 120.0, .05, 1000.0), source);
        double current = action == Action.CHAIN_DEADLINE ? 1000 : action == Action.LEAKY_BUCKET ? 120 : .05;
        double proposed = action == Action.CHAIN_DEADLINE ? 1440 : action == Action.LEAKY_BUCKET ? 90 : .02;
        return workflow.offer(new Proposal(UUID.randomUUID(), target, action, source, "v1", current, proposed,
                action == Action.CHAIN_DEADLINE ? "milliseconds" : action == Action.LEAKY_BUCKET ? "requests/second" : "extra attempts/original request",
                clock.now, clock.now.plusSeconds(300), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("test evidence")));
    }
    private JsonNode read(String suffix) throws Exception {
        var response = mvc.perform(get("/api/v2/intelligence/outbox" + suffix)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var body = json.readTree(response.getContentAsString()); assertTrue(body.isArray()); return body;
    }
    @Test void eachEndpointFiltersItsActionAndPreservesDeadlineApproval() throws Exception {
        var deadline = offer(Action.CHAIN_DEADLINE, Source.TEMPO, "POST /api/orders");
        offer(Action.LEAKY_BUCKET, Source.PROMETHEUS, "order");
        offer(Action.RETRY_BUDGET, Source.PROMETHEUS, "order");
        assertEquals(0, read("/deadlines").size());
        assertEquals(2, read("").size());
        var bucket = read("/leaky-bucket"); assertEquals(1, bucket.size());
        assertEquals("LEAKY_BUCKET", bucket.get(0).get("proposal").get("action").asString());
        var retry = read("/retry-budget"); assertEquals(1, retry.size());
        assertEquals("RETRY_BUDGET", retry.get(0).get("proposal").get("action").asString());
        workflow.decide(deadline.proposal().id(), new Review("reviewer", true, null));
        var approved = read("/deadlines"); assertEquals(1, approved.size());
        assertEquals("CHAIN_DEADLINE", approved.get(0).get("proposal").get("action").asString());
        assertEquals("APPROVED", approved.get(0).get("decision").asString());
        assertEquals(deadline.proposal().id().toString(), approved.get(0).get("proposal").get("id").asString());
        assertEquals(3, read("").size());
    }
    @Test void syntheticAndExpiredUpdatesNeverAppearAndReadsDoNotConsumeUpdates() throws Exception {
        for (var action : Action.values()) offer(action, Source.MOCK, "mock-order");
        assertEquals(0, read("/deadlines").size()); assertEquals(0, read("/leaky-bucket").size()); assertEquals(0, read("/retry-budget").size());
        offer(Action.LEAKY_BUCKET, Source.PROMETHEUS, "order");
        assertEquals(read("/leaky-bucket"), read("/leaky-bucket"));
        clock.next(300);
        assertEquals(0, read("/leaky-bucket").size());
    }
    @Test void acceptedUpdatesWaitForAcknowledgementAndAreNotOfferedAgain() throws Exception {
        var deadline = offer(Action.CHAIN_DEADLINE, Source.TEMPO, "POST /api/orders");
        workflow.decide(deadline.proposal().id(), new Review("reviewer", true, null));
        assertEquals(1, read("/deadlines").size());
        workflow.deliveryResult(deadline.proposal().id(), new Acknowledgement("ACCEPTED", null, null), null);
        assertEquals(0, read("/deadlines").size());
        assertEquals(1000, workflow.current("POST /api/orders", Source.TEMPO).deadlineMs());
    }
}
