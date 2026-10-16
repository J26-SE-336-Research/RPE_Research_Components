package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Delivery of human-approved recommendations to a fake Component 3 policy API (contract A3.4). */
class Component3BridgeTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<JsonNode> policies = new CopyOnWriteArrayList<>();
    private final List<JsonNode> rollouts = new CopyOnWriteArrayList<>();
    private volatile String phase = "Activating";
    private volatile String effective = "{}";
    private HttpServer server;
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private PolicyWorkflow workflow;
    private Component3Bridge bridge;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/k8s/", exchange -> {
            String path = exchange.getRequestURI().getPath(); String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out; int code = 200;
            if (path.equals("/api/k8s/effective")) out = effective;
            else if (path.equals("/api/k8s/policies") && method.equals("POST")) {
                JsonNode in = json.readTree(body); policies.add(in); code = 201;
                out = "{\"policy\":{\"policyId\":\"" + in.path("policyId").asString() + "\",\"version\":" + policies.size() + ",\"epoch\":\"e" + (10 + policies.size()) + "\"}}";
            } else if (path.equals("/api/k8s/rollouts") && method.equals("POST")) {
                rollouts.add(json.readTree(body)); code = 201; out = "{\"metadata\":{\"name\":\"ro-1\"},\"status\":{\"phase\":\"Pending\"}}";
            } else if (path.equals("/api/k8s/rollouts/ro-1")) out = "{\"metadata\":{\"name\":\"ro-1\"},\"status\":{\"phase\":\"" + phase + "\"}}";
            else { code = 404; out = "{}"; }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var connections = new AdaptiveConfig.Connections(false, "http://localhost:9090", "http://localhost:3200", "rpe-demo",
                List.of("payment-svc"), "gateway", Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(30), 5000, url, "", Map.of());
        workflow = new PolicyWorkflow(connections, AdaptiveEngineTests.settings(), clock);
        bridge = new Component3Bridge(workflow, connections, AdaptiveEngineTests.settings());
    }
    @AfterEach void stop() { server.stop(0); }

    private Proposal proposal(String target, Action action, double current, double proposed, String base) {
        return new Proposal(UUID.randomUUID(), target, action, Source.TEMPO == sourceOf(action) ? Source.TEMPO : Source.PROMETHEUS, base, current, proposed,
                "unit", clock.now, clock.now.plusSeconds(300), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("evidence"));
    }
    private static Source sourceOf(Action action) { return action == Action.CHAIN_DEADLINE ? Source.TEMPO : Source.PROMETHEUS; }

    @Test void approvedRecommendationBecomesAnApprovedPolicyVersionAndTwoPhaseRollout() {
        effective = "{\"payment-svc\":{\"policyId\":\"payment-limits\",\"epoch\":\"e3\",\"route\":\"\",\"spec\":\"{\\\"leakyBucket\\\":{\\\"ratePerSec\\\":120,\\\"burst\\\":20},\\\"retry\\\":{\\\"budgetRatio\\\":0.05}}\"}}";
        bridge.run(); // sync: Component 3's effective policy becomes the engine's reference
        assertEquals("e3", workflow.current("payment-svc", Source.PROMETHEUS).version());
        var change = workflow.offer(proposal("payment-svc", Action.LEAKY_BUCKET, 120, 90, "e3"));
        bridge.run();
        assertTrue(policies.isEmpty(), "Nothing is sent before a human approves");
        workflow.decide(change.proposal().id(), new Review("sre-alice", true, "looks right"));
        bridge.run();
        assertEquals(1, policies.size());
        JsonNode sent = policies.get(0);
        assertEquals("payment-limits", sent.path("policyId").asString());
        assertEquals("c4-recommendation", sent.path("source").asString());
        assertEquals(change.proposal().id().toString(), sent.path("recommendationId").asString());
        assertEquals("sre-alice", sent.path("approval").path("approvedBy").asString());
        assertEquals(90, sent.path("spec").path("leakyBucket").path("ratePerSec").asDouble());
        assertEquals(20, sent.path("spec").path("leakyBucket").path("burst").asInt(), "Other fields of the current spec are kept");
        assertEquals(0.05, sent.path("spec").path("retry").path("budgetRatio").asDouble());
        assertEquals("two-phase", rollouts.get(0).path("mode").asString());
        assertEquals(Delivery.SENT, workflow.find(change.proposal().id()).delivery());
        // the rollout completes and Component 3 now reports the new epoch as effective
        phase = "Complete";
        effective = effective.replace("e3", "e11").replace("120", "90");
        bridge.run();
        var applied = workflow.find(change.proposal().id());
        assertEquals(Delivery.APPLIED, applied.delivery());
        assertEquals("e11", applied.appliedVersion());
        assertEquals(90, workflow.current("payment-svc", Source.PROMETHEUS).bucketRate());
        assertEquals(1, policies.size(), "An applied recommendation is delivered exactly once");
    }

    @Test void chainDeadlineIsARouteBudgetOnTheGatewayPolicy() {
        workflow.observeConfiguredDeadline("GET /api/orders", 1000, clock.instant());
        var change = workflow.offer(proposal("GET /api/orders", Action.CHAIN_DEADLINE, 1000, 1440, "prometheus-config:1000"));
        workflow.decide(change.proposal().id(), new Review("sre-bob", true, null));
        bridge.run();
        JsonNode sent = policies.get(0);
        assertEquals("gateway", sent.path("scope").path("service").asString());
        assertEquals("c4-get-api-orders", sent.path("policyId").asString());
        assertEquals(1440, sent.path("spec").path("routes").path("GET /api/orders").path("deadlineMs").asLong());
    }

    @Test void routeScopedGatewayPolicyKeepsItsScopeDeadlineConsistent() {
        effective = "{\"gateway\":{\"policyId\":\"gw\",\"epoch\":\"e5\",\"route\":\"GET /api/orders\",\"spec\":\"{\\\"deadlineMs\\\":100,\\\"routes\\\":{\\\"GET /api/orders\\\":{\\\"deadlineMs\\\":100}}}\"}}";
        bridge.run();
        var change = workflow.offer(proposal("GET /api/orders", Action.CHAIN_DEADLINE, 100, 150, "e5"));
        workflow.decide(change.proposal().id(), new Review("sre", true, null));
        bridge.run();
        JsonNode spec = policies.get(0).path("spec");
        assertEquals(150, spec.path("deadlineMs").asLong());
        assertEquals(150, spec.path("routes").path("GET /api/orders").path("deadlineMs").asLong());
        assertEquals("gw", policies.get(0).path("policyId").asString());
    }

    @Test void rejectedRecommendationIsNeverDelivered() {
        workflow.observeConfiguredDeadline("GET /api/orders", 1000, clock.instant());
        var change = workflow.offer(proposal("GET /api/orders", Action.CHAIN_DEADLINE, 1000, 1440, "prometheus-config:1000"));
        workflow.decide(change.proposal().id(), new Review("sre-bob", false, "not during the release"));
        bridge.run();
        assertTrue(policies.isEmpty());
    }

    @Test void slugIsAValidPolicyId() {
        assertEquals("post-api-orders", Component3Bridge.slug("POST /api/orders"));
    }
}
