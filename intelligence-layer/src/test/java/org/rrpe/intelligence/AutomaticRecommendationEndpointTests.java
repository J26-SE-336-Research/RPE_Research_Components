package org.rrpe.intelligence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the automatic HTTP flow with synthetic timestamps, without waiting or applying policies. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "logging.level.root=WARN", "debug=false",
        "rrpe.baseline.recent-window=30s", "rrpe.baseline.analysis-interval=15s",
        "rrpe.baseline.sample-interval=10s", "rrpe.baseline.baseline-window=1m",
        "rrpe.baseline.startup-exclusion=20s", "rrpe.baseline.max-sample-gap=20s",
        "rrpe.baseline.minimum-recent-samples=2", "rrpe.baseline.minimum-baseline-samples=4"
})
class AutomaticRecommendationEndpointTests {
    @LocalServerPort
    int port;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private String service() {
        return "automatic-test-" + UUID.randomUUID();
    }

    private HttpResponse<String> request(String path, ObjectNode body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/metrics/" + path))
                .timeout(Duration.ofSeconds(10));
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String path, ObjectNode body) throws Exception {
        var response = request(path, body);
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private ObjectNode observation(String service, Instant at, boolean degraded) {
        var node = mapper.createObjectNode();
        node.put("serviceName", service).put("collectedAt", at.toString());
        node.put("requestRate", degraded ? 150 : 100).put("p95LatencyMs", degraded ? 250 : 100);
        node.put("errorRate", degraded ? 0.12 : 0).put("retryCount", degraded ? 30 : 0);
        node.put("rejectedRequestCount", 0).put("deadlineFailureCount", 0).put("cancellationCount", 0);
        node.put("inFlightRequests", degraded ? 70 : 20).put("successfulThroughput", degraded ? 90 : 100);
        return node;
    }

    private void train(String service, Instant origin) throws Exception {
        for (int second = 0; second <= 70; second += 10) {
            ok("observations", observation(service, origin.plusSeconds(second), false));
        }
        assertEquals("READY", ok(service + "/baseline-learning", null).get("phase").asString());
    }

    private JsonNode waiting(String service, String status) throws Exception {
        var result = ok(service + "/recommendations", null);
        assertFalse(result.get("ready").asBoolean());
        assertEquals(status, result.get("status").asString());
        assertFalse(result.get("message").asString().isBlank());
        assertEquals(0, result.get("recommendations").size());
        assertTrue(result.get("expiresAt").isNull());
        return result;
    }

    @Test
    void missingObservationsAndStartupAreNormalWaitingStates() throws Exception {
        String service = service();
        waiting(service, "WAITING_FOR_OBSERVATIONS");
        var progress = ok("observations", observation(service, Instant.now().minusSeconds(110), false));
        assertEquals("STARTUP", progress.get("phase").asString());
        waiting(service, "LEARNING_BASELINE");
    }

    @Test
    void learnedBaselineAndSeparateDegradedWindowGenerateFiveMinuteAdvisoryPlan() throws Exception {
        String service = service();
        Instant origin = Instant.now().minusSeconds(110);
        train(service, origin);
        waiting(service, "WAITING_FOR_RECENT_DATA");
        for (int second = 80; second <= 110; second += 10) {
            ok("observations", observation(service, origin.plusSeconds(second), true));
        }
        var result = ok(service + "/recommendations", null);
        assertTrue(result.get("ready").asBoolean());
        assertEquals("OVERLOADED", result.get("status").asString());
        assertEquals("ADVISORY", result.get("mode").asString());
        assertEquals(100, result.get("baselineLearning").get("baseline").get("p95LatencyMs").asDouble());
        assertEquals(250, result.get("recentWindow").get("summary").get("p95LatencyMs").asDouble());
        assertFalse(result.get("recommendations").isEmpty());
        var services = ok("", null);
        boolean listed = false;
        for (var entry : services) {
            if (entry.get("serviceName").asString().equals(service)) {
                listed = true;
                assertEquals("OVERLOADED", entry.get("status").asString());
                assertTrue(entry.get("ready").asBoolean());
            }
        }
        assertTrue(listed);
        var queued = ok(service + "/reviews", mapper.createObjectNode());
        assertTrue(queued.get("ready").asBoolean());
        assertEquals("AUTO_APPROVED", queued.get("review").get("status").asString());
        assertEquals(result.get("recommendations"), queued.get("review").get("servicePlan").get("recommendations"));
        assertEquals(result.get("sourceCollectedAt"), queued.get("review").get("servicePlan").get("sourceCollectedAt"));
        assertEquals(300, Duration.between(Instant.parse(result.get("generatedAt").asString()),
                Instant.parse(result.get("expiresAt").asString())).toSeconds());

        int count = result.get("recentWindow").get("sampleCount").asInt();
        ok("observations", observation(service, origin.plusSeconds(110), true));
        assertEquals(count, ok(service + "/recent-window", null).get("sampleCount").asInt());
        var invalid = observation(service, origin.plusSeconds(120), true);
        invalid.put("p95LatencyMs", -1);
        assertEquals(400, request("observations", invalid).statusCode());
        assertEquals(250, ok(service + "/recent-window", null).get("summary").get("p95LatencyMs").asDouble());
    }

    @Test
    void baselineTrainingDataCannotBeUsedAsTheRecentComparison() throws Exception {
        String service = service();
        train(service, Instant.now().minusSeconds(70));
        var result = waiting(service, "WAITING_FOR_RECENT_DATA");
        assertEquals("READY", result.get("recentWindow").get("phase").asString());
        assertTrue(result.get("message").asString().contains("separate recent window"));
    }

    @Test
    void staleDataDoesNotProduceRecommendations() throws Exception {
        String service = service();
        Instant origin = Instant.now().minusSeconds(200);
        train(service, origin);
        for (int second = 80; second <= 110; second += 10) {
            ok("observations", observation(service, origin.plusSeconds(second), true));
        }
        var result = waiting(service, "WAITING_FOR_RECENT_DATA");
        assertEquals("INSUFFICIENT_DATA", result.get("recentWindow").get("phase").asString());
    }

    @Test
    void missingSamplesInsideAWindowBlockRecommendations() throws Exception {
        String service = service();
        Instant origin = Instant.now().minusSeconds(131);
        train(service, origin);
        for (int second : new int[]{100, 110, 131}) {
            ok("observations", observation(service, origin.plusSeconds(second), true));
        }
        var result = waiting(service, "WAITING_FOR_RECENT_DATA");
        assertEquals("Recent window contains a data gap", result.get("recentWindow").get("reason").asString());
    }

    @Test
    void failuresAndInstabilityRestartBaselineLearning() throws Exception {
        String service = service();
        Instant origin = Instant.now().minusSeconds(100);
        for (int second : new int[]{0, 10, 20}) {
            ok("observations", observation(service, origin.plusSeconds(second), false));
        }
        var failed = ok("observations", observation(service, origin.plusSeconds(30), true));
        assertEquals(0, failed.get("sampleCount").asInt());
        ok("observations", observation(service, origin.plusSeconds(40), false));
        var unstable = observation(service, origin.plusSeconds(50), false);
        unstable.put("p95LatencyMs", 150);
        var progress = ok("observations", unstable);
        assertEquals(1, progress.get("sampleCount").asInt());
        assertTrue(progress.get("baseline").isNull());
        waiting(service, "LEARNING_BASELINE");
    }
}
