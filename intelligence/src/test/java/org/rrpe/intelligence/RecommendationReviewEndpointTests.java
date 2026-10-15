package org.rrpe.intelligence;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "debug=false"})
class RecommendationReviewEndpointTests {
    @LocalServerPort int port;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> request(String path, ObjectNode body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String path, ObjectNode body) throws Exception {
        var response = request(path, body);
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private ObjectNode metrics(String name) throws Exception {
        var body = (ObjectNode) mapper.readTree(Files.readString(Path.of("examples/member3/" + name + ".request.json"))
                .replace("\uFEFF", ""));
        String service = "review-test-" + UUID.randomUUID();
        ((ObjectNode) body.get("baseline")).put("serviceName", service).put("collectedAt", Instant.now().minusSeconds(360).toString());
        ((ObjectNode) body.get("recent")).put("serviceName", service).put("collectedAt", Instant.now().minusSeconds(1).toString());
        return body;
    }

    private ObjectNode decision(JsonNode review, String decision) {
        return mapper.createObjectNode().put("recommendationId", review.get("recommendationId").asString())
                .put("decision", decision).put("reviewer", "test-reviewer").put("comment", "integration test");
    }

    @Test
    void approvalPreservesTheOriginalPlanAndReplayPreservesAuditTime() throws Exception {
        var submission = ok("/api/v1/reviews/deadline", deadline());
        var pending = submission.get("review");
        assertEquals("PENDING", pending.get("status").asString());
        assertEquals(submission.get("analysis"), pending.get("deadlinePlan"));
        var approved = ok("/api/v1/reviews/decisions", decision(pending, "APPROVE"));
        assertEquals("APPROVED", approved.get("status").asString());
        assertEquals(pending.get("deadlinePlan"), approved.get("deadlinePlan"));
        assertEquals(pending.get("submittedAt"), approved.get("submittedAt"));
        assertEquals(approved, ok("/api/v1/reviews/decisions", decision(pending, "APPROVE")));
        assertEquals(approved, ok("/api/v1/reviews/" + pending.get("recommendationId").asString(), null));
    }

    @Test
    void rejectionCannotBeOverwrittenByApproval() throws Exception {
        var pending = ok("/api/v1/reviews/deadline", deadline()).get("review");
        var rejected = ok("/api/v1/reviews/decisions", decision(pending, "REJECT"));
        assertEquals("REJECTED", rejected.get("status").asString());
        var conflict = request("/api/v1/reviews/decisions", decision(pending, "APPROVE"));
        assertEquals(409, conflict.statusCode());
        assertTrue(mapper.readTree(conflict.body()).get("detail").asString().contains("already"));
        assertEquals(rejected, ok("/api/v1/reviews/" + pending.get("recommendationId").asString(), null));
    }

    @Test
    void healthyAndLearningStatesDoNotCreateReviewRecords() throws Exception {
        var healthy = ok("/api/v1/reviews/service", metrics("healthy"));
        assertTrue(healthy.get("ready").asBoolean());
        assertTrue(healthy.get("review").isNull());
        var waiting = ok("/api/v1/metrics/unknown-" + UUID.randomUUID() + "/reviews", mapper.createObjectNode());
        assertFalse(waiting.get("ready").asBoolean());
        assertTrue(waiting.get("review").isNull());
        assertEquals("WAITING_FOR_OBSERVATIONS", waiting.get("analysis").get("status").asString());
    }

    private ObjectNode deadline() {
        Instant end = Instant.now().minusSeconds(1);
        var body = mapper.createObjectNode().put("chainId", "review-chain-" + UUID.randomUUID()).put("chainHealth", "STRESSED")
                .put("windowStart", end.minusSeconds(300).toString()).put("windowEnd", end.toString())
                .put("requestCount", 1000).put("deadlineFailureCount", 20).put("p99LatencyMs", 1200).put("currentDeadlineMs", 1000);
        body.putArray("services").add("order").add("payment");
        return body;
    }

    @Test
    void serviceControlsAreAutomaticAndCannotReceiveHumanDecisions() throws Exception {
        var service = ok("/api/v1/reviews/service", metrics("overloaded")).get("review");
        assertEquals("AUTO_APPROVED", service.get("status").asString());
        assertEquals("automatic-policy-controller", service.get("reviewer").asString());
        assertEquals(409, request("/api/v1/reviews/decisions", decision(service, "APPROVE")).statusCode());
        assertEquals(409, request("/api/v1/reviews/decisions", decision(service, "REJECT")).statusCode());
        var updates = ok("/api/v2/intelligence/outbox", null);
        assertEquals(0, updates.size(), "Legacy/synthetic decisions never enter the corrected live outbox");
    }

    @Test
    void deadlineReviewContainsOnlyTheServerGeneratedPlan() throws Exception {
        Instant end = Instant.now().minusSeconds(1);
        var body = mapper.createObjectNode().put("chainId", "review-chain").put("chainHealth", "STRESSED")
                .put("windowStart", end.minusSeconds(300).toString()).put("windowEnd", end.toString())
                .put("requestCount", 1000).put("deadlineFailureCount", 20).put("p99LatencyMs", 1200).put("currentDeadlineMs", 1000);
        body.putArray("services").add("order").add("payment");
        var submitted = ok("/api/v1/reviews/deadline", body);
        var pending = submitted.get("review");
        assertTrue(pending.get("servicePlan").isNull());
        assertEquals(1440, pending.get("deadlinePlan").get("proposedDeadlineMs").asLong());
        assertEquals(submitted.get("analysis"), pending.get("deadlinePlan"));
        var approved = ok("/api/v1/reviews/decisions", decision(pending, "APPROVE"));
        assertEquals(pending.get("deadlinePlan"), approved.get("deadlinePlan"));
        body.put("deadlineFailureCount", 0);
        assertTrue(ok("/api/v1/reviews/deadline", body).get("review").isNull());
    }

    @Test
    void listingIsBoundedAndIncludesLatestStoredReviews() throws Exception {
        var pending = ok("/api/v1/reviews/service", metrics("overloaded")).get("review");
        var list = ok("/api/v1/reviews?limit=100", null);
        assertTrue(list.size() <= 100);
        boolean found = false;
        for (var item : list) if (item.get("recommendationId").equals(pending.get("recommendationId"))) found = true;
        assertTrue(found);
        assertEquals(1, ok("/api/v1/reviews?limit=1", null).size());
        assertEquals(400, request("/api/v1/reviews?limit=0", null).statusCode());
        assertEquals(400, request("/api/v1/reviews?limit=101", null).statusCode());
    }

    @Test
    void malformedAndUnknownReviewRequestsAreRejected() throws Exception {
        assertEquals(400, request("/api/v1/reviews/decisions", mapper.createObjectNode()).statusCode());
        var body = mapper.createObjectNode().put("recommendationId", UUID.randomUUID().toString())
                .put("decision", "APPROVE").put("reviewer", "reviewer");
        assertEquals(404, request("/api/v1/reviews/decisions", body).statusCode());
        body.put("decision", "APPLY");
        assertEquals(400, request("/api/v1/reviews/decisions", body).statusCode());
        assertEquals(400, request("/api/v1/reviews/service", mapper.createObjectNode()).statusCode());
        assertEquals(300, ok("/api/v1/reviews/config", null).get("recentWindowSeconds").asLong());
    }
}
