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
class CancellationAnalysisEndpointTests {
    @LocalServerPort
    int port;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ObjectNode fixture() {
        Instant end = Instant.now().minusSeconds(1);
        var body = mapper.createObjectNode();
        body.put("chainId", "order-payment");
        body.putArray("services").add("order-service").add("payment-service");
        body.put("windowStart", end.minusSeconds(300).toString()).put("windowEnd", end.toString());
        body.put("cancellationCount", 20).put("continuedProcessingCount", 15)
                .put("downstreamActivityCount", 12).put("observationGraceMs", 100)
                .put("telemetryComplete", true);
        return body;
    }

    private HttpResponse<String> post(ObjectNode body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/analyses/cancellations"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode result(ObjectNode body, String status, boolean ready, boolean review) throws Exception {
        var response = post(body);
        assertEquals(200, response.statusCode(), response.body());
        var json = mapper.readTree(response.body());
        assertEquals("1.0", json.get("schemaVersion").asString());
        assertEquals("ADVISORY", json.get("mode").asString());
        assertEquals(body.get("chainId").asString(), json.get("chainId").asString());
        assertEquals(status, json.get("status").asString());
        assertEquals(ready, json.get("ready").asBoolean());
        assertEquals(review, json.get("requiresReview").asBoolean());
        assertFalse(json.get("message").asString().isBlank());
        assertNotNull(Instant.parse(json.get("analysedAt").asString()));
        assertEquals(body, json.get("evidence"));
        // Cancellation diagnosis is not an executable resilience policy.
        assertFalse(json.has("action"));
        assertFalse(json.has("recommendations"));
        if (!review) {
            assertEquals(0, json.get("findings").size());
        }
        return json;
    }

    @Test
    void overlappingAffectedCountsRemainSeparateFindingsForReview() throws Exception {
        var response = result(fixture(), "PROBLEM_DETECTED", true, true);
        var findings = response.get("findings");
        assertEquals(2, findings.size());
        assertEquals("PROCESSING_AFTER_CANCELLATION", findings.get(0).get("code").asString());
        assertEquals(15, findings.get(0).get("affectedRequestCount").asLong());
        assertEquals("DOWNSTREAM_CALL_AFTER_CANCELLATION", findings.get(1).get("code").asString());
        assertEquals(12, findings.get(1).get("affectedRequestCount").asLong());
        for (var finding : findings) {
            assertFalse(finding.get("message").asString().isBlank());
            assertFalse(finding.get("suggestedCheck").asString().isBlank());
        }
    }

    @Test
    void eitherKindOfActivityCanBeDetectedIndependently() throws Exception {
        var body = fixture();
        body.put("downstreamActivityCount", 0);
        var findings = result(body, "PROBLEM_DETECTED", true, true).get("findings");
        assertEquals(1, findings.size());
        assertEquals("PROCESSING_AFTER_CANCELLATION", findings.get(0).get("code").asString());
        body = fixture();
        body.put("continuedProcessingCount", 0);
        findings = result(body, "PROBLEM_DETECTED", true, true).get("findings");
        assertEquals(1, findings.size());
        assertEquals("DOWNSTREAM_CALL_AFTER_CANCELLATION", findings.get(0).get("code").asString());
    }

    @Test
    void completeEvidenceWithoutActivityReportsNoProblemInThatWindow() throws Exception {
        var body = fixture();
        body.put("continuedProcessingCount", 0).put("downstreamActivityCount", 0);
        result(body, "NO_PROBLEM_OBSERVED", true, false);
    }

    @Test
    void noCancelledRequestsCannotValidateCancellationHandling() throws Exception {
        var body = fixture();
        body.put("cancellationCount", 0).put("continuedProcessingCount", 0).put("downstreamActivityCount", 0);
        var response = result(body, "NO_CANCELLATIONS_OBSERVED", true, false);
        assertTrue(response.get("message").asString().contains("cannot validate"));
    }

    @Test
    void incompleteTelemetryDoesNotBecomeEitherAProblemOrAHealthyResult() throws Exception {
        var body = fixture();
        body.put("telemetryComplete", false);
        result(body, "INSUFFICIENT_DATA", false, false);
        body.put("continuedProcessingCount", 0).put("downstreamActivityCount", 0);
        result(body, "INSUFFICIENT_DATA", false, false);
    }

    @Test
    void shortAndExcessiveWindowsReturnNormalWaitingStates() throws Exception {
        for (int span : new int[]{10, 316}) {
            var body = fixture();
            body.put("windowStart", Instant.parse(body.get("windowEnd").asString()).minusSeconds(span).toString());
            result(body, "INSUFFICIENT_DATA", false, false);
        }
    }

    @Test
    void staleEvidenceProducesNoFindings() throws Exception {
        var body = fixture();
        Instant end = Instant.now().minusSeconds(130);
        body.put("windowStart", end.minusSeconds(300).toString()).put("windowEnd", end.toString());
        result(body, "WAITING_FOR_FRESH_DATA", false, false);
    }

    @Test
    void invalidCountsAndGracePeriodsAreRejectedEvenWhenTelemetryIsIncomplete() throws Exception {
        for (String field : new String[]{"cancellationCount", "continuedProcessingCount",
                "downstreamActivityCount", "observationGraceMs"}) {
            var body = fixture();
            body.put(field, -1).put("telemetryComplete", false);
            assertEquals(400, post(body).statusCode(), field);
        }
        for (String field : new String[]{"continuedProcessingCount", "downstreamActivityCount"}) {
            var body = fixture();
            body.put(field, 21);
            assertEquals(400, post(body).statusCode(), field);
        }
        var body = fixture();
        body.put("observationGraceMs", 300001);
        assertEquals(400, post(body).statusCode());
    }

    @Test
    void missingIdentityAndInvalidTimeWindowsAreRejected() throws Exception {
        assertEquals(400, post(mapper.createObjectNode()).statusCode());
        var body = fixture();
        body.put("chainId", " ");
        assertEquals(400, post(body).statusCode());
        body = fixture();
        body.putArray("services");
        assertEquals(400, post(body).statusCode());
        body = fixture();
        body.putArray("services").add(" ");
        assertEquals(400, post(body).statusCode());
        body = fixture();
        body.put("windowStart", body.get("windowEnd").asString());
        assertEquals(400, post(body).statusCode());
        body = fixture();
        body.put("windowEnd", Instant.now().plusSeconds(60).toString());
        assertEquals(400, post(body).statusCode());
    }
}
