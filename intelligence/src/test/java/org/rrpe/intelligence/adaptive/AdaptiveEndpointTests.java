package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "logging.level.root=WARN",
            "debug=false",
            "rrpe.connections.collect=false",
            "rrpe.mock-enabled=true",
            "rrpe.control-token=test-secret"
        })
class AdaptiveEndpointTests {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> request(String path, String body, String method) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json");
        if (body == null) request.GET();
        else request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void isolatedDeadlineScenarioMatchesCorrectedExampleAndNeverEntersLiveDelivery()
            throws Exception {
        var response = request("/api/v2/intelligence/demo/deadline-increase", "{}", "POST");
        assertEquals(200, response.statusCode(), response.body());
        var demo = json.readTree(response.body());
        var proposal = demo.get("changes").get(0);
        assertEquals(1440, proposal.get("proposal").get("proposedValue").asDouble());
        assertEquals("PENDING_APPROVAL", proposal.get("decision").asString());
        var status = json.readTree(request("/api/v2/intelligence", null, "GET").body());
        assertEquals(0, status.get("changes").size());
        assertEquals(0, status.get("assessments").size());
        var services = status.get("collection").get("configuredServices");
        assertEquals(
                5, services.size(), "Configured services remain visible when collection is off");
        assertEquals("apigateway", services.get(0).asString());
        assertEquals(
                0,
                json.readTree(request("/api/v2/intelligence/outbox", null, "GET").body()).size());
    }

    @Test
    void deadlineRestorationExampleIs1296AndRequiresApproval() throws Exception {
        var response = request("/api/v2/intelligence/demo/deadline-recovery", "{}", "POST");
        assertEquals(200, response.statusCode(), response.body());
        var proposal = json.readTree(response.body()).get("changes").get(0);
        assertEquals(1296, proposal.get("proposal").get("proposedValue").asDouble());
        assertEquals("PENDING_APPROVAL", proposal.get("decision").asString());
    }

    @Test
    void multipleChainsHaveIndependentEvidenceAndRecommendationsWithoutLiveDelivery()
            throws Exception {
        var response = request("/api/v2/intelligence/demo/multiple-chains", "{}", "POST");
        assertEquals(200, response.statusCode(), response.body());
        var demo = json.readTree(response.body());
        assertEquals(3, demo.get("chains").size());
        assertEquals(3, demo.get("changes").size());
        assertEquals(4, demo.get("chains").get(0).get("window").get("services").size());
        for (var change : demo.get("changes")) {
            var proposal = change.get("proposal");
            String target = proposal.get("target").asString();
            double expected =
                    target.startsWith("POST /api/orders")
                            ? 1440
                            : target.startsWith("GET /api/inventory") ? 715 : 1080;
            assertEquals(expected, proposal.get("proposedValue").asDouble(), target);
            assertEquals("MOCK", proposal.get("source").asString());
            assertEquals("PENDING_APPROVAL", change.get("decision").asString());
            assertEquals(
                    target.startsWith("POST /api/orders")
                            ? 20
                            : target.startsWith("GET /api/inventory") ? 35 : 0,
                    proposal.get("measurements").get("deadlineFailureCount").asDouble());
        }
        var status = json.readTree(request("/api/v2/intelligence", null, "GET").body());
        assertEquals(0, status.get("changes").size());
        assertEquals(
                0,
                json.readTree(request("/api/v2/intelligence/outbox", null, "GET").body()).size());
    }

    @Test
    void liveControlIsProtected() throws Exception {
        String policy =
                "{\"target\":\"order\",\"version\":\"v1\",\"validUntil\":\"2099-01-01T00:00:00Z\",\"bucketRate\":120,\"healthyBucketTarget\":120,\"retryRatio\":0.05}";
        assertEquals(401, request("/api/v2/intelligence/policies", policy, "PUT").statusCode());
        assertEquals(
                204,
                request("/api/v2/intelligence/policies?source=MOCK", policy, "PUT").statusCode());
        var queries =
                json.readTree(
                        request("/api/v2/intelligence/queries?service=order", null, "GET").body());
        assertTrue(queries.get("p95Ms").asString().contains("rpe_request_duration_seconds_bucket"));
        assertFalse(queries.toString().contains("cancellation"));
    }
}
