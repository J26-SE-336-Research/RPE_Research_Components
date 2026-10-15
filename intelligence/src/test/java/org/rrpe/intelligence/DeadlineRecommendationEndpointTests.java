package org.rrpe.intelligence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "debug=false"})
class DeadlineRecommendationEndpointTests {
    @LocalServerPort
    int port;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ObjectNode fixture() {
        Instant end = Instant.now().minusSeconds(1);
        var body = mapper.createObjectNode();
        body.put("chainId", "order-payment");
        body.putArray("services").add("order-service").add("payment-service");
        body.put("chainHealth", "STRESSED");
        body.put("windowStart", end.minusSeconds(300).toString()).put("windowEnd", end.toString());
        body.put("requestCount", 1000).put("deadlineFailureCount", 20);
        body.put("p99LatencyMs", 1200).put("currentDeadlineMs", 1000);
        return body;
    }

    private HttpResponse<String> post(ObjectNode body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/recommendations/deadlines"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode result(ObjectNode body) throws Exception {
        var response = post(body);
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private void noSuggestion(ObjectNode body, String status) throws Exception {
        var response = result(body);
        assertEquals(status, response.get("status").asString());
        assertTrue(response.get("proposedDeadlineMs").isNull());
        assertTrue(response.get("action").isNull());
        assertTrue(response.get("expiresAt").isNull());
        assertFalse(response.get("requiresApproval").asBoolean());
    }

    @Test
    void chainP99WithMarginProducesAnAdvisoryDeadlineRequiringApproval() throws Exception {
        var response = result(fixture());
        assertEquals("READY", response.get("status").asString());
        assertTrue(response.get("ready").asBoolean());
        assertEquals("ADVISORY", response.get("mode").asString());
        assertEquals("CHANGE_CHAIN_DEADLINE", response.get("action").asString());
        assertEquals(1440, response.get("proposedDeadlineMs").asLong());
        assertTrue(response.get("requiresApproval").asBoolean());
        assertEquals(1200, response.get("evidence").get("p99LatencyMs").asDouble());
        assertEquals(1000, response.get("evidence").get("currentDeadlineMs").asLong());
        assertEquals(300, Duration.between(Instant.parse(response.get("generatedAt").asString()),
                Instant.parse(response.get("expiresAt").asString())).toSeconds());
    }

    @Test
    void noDeadlineFailuresDoNotProduceAnIncrease() throws Exception {
        var body = fixture();
        body.put("deadlineFailureCount", 0);
        noSuggestion(body, "NO_CHANGE");
    }

    @Test
    void anAlreadySufficientDeadlineIsLeftAlone() throws Exception {
        var body = fixture();
        body.put("currentDeadlineMs", 1500);
        noSuggestion(body, "NO_CHANGE");
    }

    @Test
    void insufficientRequestsOrIncompleteWindowsAreNormalWaitingStates() throws Exception {
        var body = fixture();
        body.put("requestCount", 50);
        noSuggestion(body, "INSUFFICIENT_DATA");
        body = fixture();
        body.put("windowStart", Instant.parse(body.get("windowEnd").asString()).minusSeconds(10).toString());
        noSuggestion(body, "INSUFFICIENT_DATA");
    }

    @Test
    void staleChainTelemetryDoesNotProduceASuggestion() throws Exception {
        var body = fixture();
        Instant end = Instant.now().minusSeconds(130);
        body.put("windowStart", end.minusSeconds(300).toString()).put("windowEnd", end.toString());
        noSuggestion(body, "WAITING_FOR_FRESH_DATA");
    }

    @Test
    void overloadDoesNotLeadToALongerDeadline() throws Exception {
        var body = fixture();
        body.put("chainHealth", "OVERLOADED");
        noSuggestion(body, "BLOCKED_BY_OVERLOAD");
    }

    @Test
    void excessiveIncreasesAndAbsoluteLimitsRequireReview() throws Exception {
        var body = fixture();
        body.put("p99LatencyMs", 2000);
        noSuggestion(body, "REVIEW_REQUIRED");
        body = fixture();
        body.put("currentDeadlineMs", 10000).put("p99LatencyMs", 10000);
        noSuggestion(body, "REVIEW_REQUIRED");
    }

    @Test
    void malformedEvidenceReturnsBadRequest() throws Exception {
        assertEquals(400, post(mapper.createObjectNode()).statusCode());
        var body = fixture();
        body.put("deadlineFailureCount", 1001);
        assertEquals(400, post(body).statusCode());
        body = fixture();
        body.put("p99LatencyMs", -1);
        assertEquals(400, post(body).statusCode());
        body = fixture();
        body.put("windowEnd", Instant.now().plusSeconds(60).toString());
        assertEquals(400, post(body).statusCode());
    }
}
