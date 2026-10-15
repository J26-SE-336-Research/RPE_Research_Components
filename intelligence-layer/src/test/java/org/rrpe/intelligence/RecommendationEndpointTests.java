package org.rrpe.intelligence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.rrpe.intelligence.analysis.api.ClassificationRequest;
import org.rrpe.intelligence.analysis.config.RecommendationPolicyConfig.Settings;
import org.rrpe.intelligence.analysis.model.RecommendationPlan.Action;
import org.rrpe.intelligence.analysis.model.ServiceMetrics;
import org.rrpe.intelligence.analysis.service.RecommendationService;
import org.rrpe.intelligence.analysis.service.ServiceHealthClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "debug=false"})
class RecommendationEndpointTests {
    @LocalServerPort
    int port;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("examples/member3/" + name + ".request.json"))
                .replace("\uFEFF", ""));
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/recommendations"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode plan(String name) throws Exception {
        var response = post(mapper.writeValueAsString(fixture(name)));
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    @Test
    void healthyLeavesExistingPoliciesAlone() throws Exception {
        var plan = plan("healthy");
        assertEquals("HEALTHY", plan.get("status").asString());
        assertEquals(0, plan.get("recommendations").size());
    }

    @Test
    void overloadedHasConsumableActionsAndExpiry() throws Exception {
        var plan = plan("overloaded");
        assertEquals("1.0", plan.get("schemaVersion").asString());
        assertEquals("ADVISORY", plan.get("mode").asString());
        assertEquals("order-service", plan.get("serviceName").asString());
        assertEquals("OVERLOADED", plan.get("status").asString());
        Set<String> actions = new HashSet<>();
        for (var item : plan.get("recommendations")) {
            actions.add(item.get("action").asString());
        }
        assertEquals(Set.of("RATE_LIMIT", "DISABLE_RETRIES"), actions);
        assertEquals(75.0, plan.get("recommendations").get(0).get("parameters").get("maxRequestsPerSecond").asDouble());
        assertEquals(1, plan.get("recommendations").get(1).get("parameters").get("maxAttempts").asLong());
        assertEquals(300, Duration.between(Instant.parse(plan.get("generatedAt").asString()),
                Instant.parse(plan.get("expiresAt").asString())).toSeconds());
    }

    @Test
    void stressedRecommendsOnlyTrafficAndRetryControls() throws Exception {
        var plan = plan("stressed");
        assertEquals("STRESSED", plan.get("status").asString());
        Set<String> actions = new HashSet<>();
        for (var item : plan.get("recommendations")) {
            actions.add(item.get("action").asString());
        }
        assertEquals(2, plan.get("recommendations").size());
        assertEquals(Set.of("RATE_LIMIT", "RETRY_BUDGET"), actions);
        var budget = plan.get("recommendations").get(1);
        assertEquals(0.05, budget.get("parameters").get("retryBudgetRatio").asDouble());
        assertFalse(budget.get("parameters").has("maxAttempts"));
    }

    @Test
    void retrySuggestionsRequireDegradationAndIncreasedRetries() throws Exception {
        for (String name : new String[]{"stressed", "overloaded", "healthy"}) {
            var body = fixture(name);
            ((ObjectNode) body.get("recent")).put("retryCount", body.get("baseline").get("retryCount").asLong());
            var response = post(mapper.writeValueAsString(body));
            assertEquals(200, response.statusCode());
            for (var recommendation : mapper.readTree(response.body()).get("recommendations")) {
                assertEquals("RATE_LIMIT", recommendation.get("action").asString());
            }
        }
        var body = fixture("healthy");
        ((ObjectNode) body.get("recent")).put("retryCount", 100);
        var response = post(mapper.writeValueAsString(body));
        assertEquals(200, response.statusCode());
        assertEquals(0, mapper.readTree(response.body()).get("recommendations").size());
    }

    @Test
    void customSettingsControlBothHealthStatusesAndZeroBudgetDisablesRetries() {
        Instant now = Instant.now();
        var baseline = new ServiceMetrics("custom-service", now.minusSeconds(300), 100, 100, 0,
                0, 0, 0, 0, 20, 100);
        var custom = new Settings(0.8, 0.6, 0.03, 0.02, Duration.ofMinutes(10), 1.2, 1.5, 10000, 100);
        var service = new RecommendationService(new ServiceHealthClassifier(), custom);
        for (boolean overloaded : new boolean[]{false, true}) {
            var recent = new ServiceMetrics("custom-service", now, 150, overloaded ? 250 : 160,
                    overloaded ? 0.12 : 0.06, 30, 0, 0, 0, 70, 90);
            var plan = service.recommend(new ClassificationRequest(recent, baseline));
            assertEquals(overloaded ? 60.0 : 80.0,
                    plan.recommendations().get(0).parameters().get("maxRequestsPerSecond").doubleValue());
            assertEquals(Action.RETRY_BUDGET, plan.recommendations().get(1).action());
            assertEquals(overloaded ? 0.02 : 0.03,
                    plan.recommendations().get(1).parameters().get("retryBudgetRatio").doubleValue());
            assertEquals(Duration.ofMinutes(10), Duration.between(plan.generatedAt(), plan.expiresAt()));
            var zeroBudgets = new Settings(0.8, 0.6, 0, 0, Duration.ofMinutes(5), 1.2, 1.5, 10000, 100);
            var zeroPlan = new RecommendationService(new ServiceHealthClassifier(), zeroBudgets)
                    .recommend(new ClassificationRequest(recent, baseline));
            assertEquals(Action.DISABLE_RETRIES, zeroPlan.recommendations().get(1).action());
            assertEquals(1, zeroPlan.recommendations().get(1).parameters().get("maxAttempts").intValue());
        }
    }

    @Test
    void missingSnapshotsReturnBadRequest() throws Exception {
        assertEquals(400, post("{}").statusCode());
    }

    @Test
    void invalidMetricsAndIncompatibleSnapshotsReturnBadRequest() throws Exception {
        for (String field : new String[]{"requestRate", "p95LatencyMs", "errorRate", "retryCount",
                "rejectedRequestCount", "deadlineFailureCount", "cancellationCount", "inFlightRequests", "successfulThroughput"}) {
            var body = fixture("overloaded");
            ((ObjectNode) body.get("recent")).put(field, -1);
            assertEquals(400, post(mapper.writeValueAsString(body)).statusCode(), field);
        }
        var body = fixture("overloaded");
        ((ObjectNode) body.get("recent")).put("errorRate", 1.2);
        assertEquals(400, post(mapper.writeValueAsString(body)).statusCode());
        body = fixture("overloaded");
        ((ObjectNode) body.get("baseline")).put("serviceName", "payment-service");
        assertEquals(400, post(mapper.writeValueAsString(body)).statusCode());
        body = fixture("overloaded");
        ((ObjectNode) body.get("baseline")).put("collectedAt", "2026-10-02T04:00:00Z");
        assertEquals(400, post(mapper.writeValueAsString(body)).statusCode());
    }
}
