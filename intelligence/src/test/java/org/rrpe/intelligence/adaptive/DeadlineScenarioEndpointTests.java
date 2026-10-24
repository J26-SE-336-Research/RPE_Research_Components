package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The deadline logic in the API: evidence, load queries and the simulations, with production settings. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "debug=false", "rrpe.connections.collect=false", "rrpe.mock-enabled=true", "rrpe.control-token=test-secret"})
class DeadlineScenarioEndpointTests {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpResponse<String> request(String path, String method) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (method.equals("GET")) request.GET(); else request.method(method, HttpRequest.BodyPublishers.ofString("{}"));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode demo(String scenario) throws Exception {
        var response = request("/api/v2/intelligence/demo/" + scenario, "POST");
        assertEquals(200, response.statusCode(), scenario + ": " + response.body());
        var body = json.readTree(response.body());
        assertEquals("MOCK", body.get("source").asString()); assertTrue(body.get("virtualTime").asBoolean());
        return body;
    }
    private static List<JsonNode> steps(JsonNode demo) { var out = new ArrayList<JsonNode>(); demo.get("deadlineEvidence").get(0).get("steps").forEach(out::add); return out; }

    @Test void statusHasAnAdditiveDeadlineEvidenceObjectAndQueriesListTheLoadSignals() throws Exception {
        var status = json.readTree(request("/api/v2/intelligence", "GET").body());
        for (String field : List.of("collection", "assessments", "changes", "mockEnabled")) assertTrue(status.has(field), field);
        assertTrue(status.get("deadlineEvidence").isObject());
        var queries = json.readTree(request("/api/v2/intelligence/queries?service=order", "GET").body());
        assertTrue(queries.get("p95Ms").asString().contains("rpe_request_duration_seconds_bucket"), "existing queries unchanged");
        assertTrue(queries.get("loadQueuePressurePercent").asString().contains("rpe_backpressure_inbound_queue_utilization_percent"));
        assertTrue(queries.has("loadBreakerOpenPeers"));
    }
    @Test void example1LowersUnderOverloadWithCaseB() throws Exception {
        var demo = demo("deadline-too-long-under-overload");
        assertTrue(demo.get("simulatedApprovals").asBoolean());
        var lowered = steps(demo).stream().filter(s -> "B_LOWER_OVERLOAD".equals(s.get("decisionCase").asString())).toList();
        assertFalse(lowered.isEmpty());
        assertTrue(lowered.stream().allMatch(s -> s.get("toMs").asLong() < s.get("fromMs").asLong()));
        assertTrue(steps(demo).stream().noneMatch(s -> s.get("toMs").asLong() > s.get("fromMs").asLong()), "never an increase");
        assertEquals(0, json.readTree(request("/api/v2/intelligence/outbox", "GET").body()).size(), "never delivered");
    }
    @Test void example3RaisesATooShortDeadline() throws Exception {
        var raised = steps(demo("deadline-too-short-day-one"));
        assertEquals(300, raised.get(0).get("fromMs").asLong()); assertEquals(450, raised.get(0).get("toMs").asLong());
        assertTrue(raised.stream().allMatch(s -> "A_RAISE".equals(s.get("decisionCase").asString())));
    }
    @Test void examples2And5AreHeldAndExample4PointsAtTheRate() throws Exception {
        for (String scenario : List.of("services-slowing", "mixed-paths")) {
            var demo = demo(scenario);
            assertTrue(steps(demo).isEmpty(), scenario);
            assertEquals("STRESS_GATE", demo.get("deadlineEvidence").get(0).get("evidence").get("holdReason").asString(), scenario);
        }
        var queue = demo("queue-rate-not-deadline");
        assertTrue(steps(queue).isEmpty(), "no deadline proposal");
        assertEquals("RATE", queue.get("deadlineEvidence").get(0).get("evidence").get("lever").asString());
        var cut = demo("rejecting-middleware-504");
        assertEquals("PAUSED", cut.get("deadlineEvidence").get(0).get("evidence").get("state").asString());
    }
    @Test void serviceScenariosKeepTheirRateResultsAndAddTheDeadlineSide() throws Exception {
        var overloaded = demo("overloaded");
        assertTrue(overloaded.get("outcomes").size() >= 3, "the service outcomes are still returned");
        var deadline = new ArrayList<JsonNode>(); overloaded.get("changes").forEach(c -> { if ("CHAIN_DEADLINE".equals(c.get("proposal").get("action").asString())) deadline.add(c); });
        assertEquals(1, deadline.size());
        assertTrue(deadline.get(0).get("proposal").get("proposedValue").asDouble() < 600, "Case B lowers");
        for (String scenario : List.of("stressed", "retry-stressed")) {
            var demo = demo(scenario);
            demo.get("changes").forEach(c -> assertFalse("CHAIN_DEADLINE".equals(c.get("proposal").get("action").asString())
                    && c.get("proposal").get("proposedValue").asDouble() > c.get("proposal").get("currentValue").asDouble(), scenario + ": no deadline increase"));
        }
        for (String scenario : List.of("healthy", "recovery")) assertEquals(200, request("/api/v2/intelligence/demo/" + scenario, "POST").statusCode());
        assertEquals(400, request("/api/v2/intelligence/demo/unknown", "POST").statusCode());
    }
}
