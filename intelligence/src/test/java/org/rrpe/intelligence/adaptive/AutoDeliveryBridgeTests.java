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

/**
 * An automatic (c4-auto) rate change on a fresh system (no Component 3 policy, the sidecar's rate as reference) goes through
 * the bridge to Component 3 as a new policy c4-&lt;service&gt; approved by c4-auto, only for that service, and becomes APPLIED.
 */
class AutoDeliveryBridgeTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<JsonNode> policies = new CopyOnWriteArrayList<>(), rollouts = new CopyOnWriteArrayList<>();
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
                out = "{\"policy\":{\"policyId\":\"" + in.path("policyId").asString() + "\",\"version\":1,\"epoch\":\"" + in.path("policyId").asString() + "-v1\"}}";
            } else if (path.equals("/api/k8s/rollouts") && method.equals("POST")) {
                rollouts.add(json.readTree(body)); code = 201; out = "{\"metadata\":{\"name\":\"ro-1\"},\"status\":{\"phase\":\"Pending\"}}";
            } else if (path.startsWith("/api/k8s/rollouts/")) out = "{\"status\":{\"phase\":\"" + phase + "\"}}";
            else { code = 404; out = "{}"; }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var connections = new AdaptiveConfig.Connections(false, "http://localhost:9090", "http://localhost:3200", "rpe-demo",
                List.of("payment-svc"), "gateway", Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(30), 5000,
                "http://127.0.0.1:" + server.getAddress().getPort(), "", Map.of());
        workflow = new PolicyWorkflow(connections, AdaptiveEngineTests.settings(), clock);
        bridge = new Component3Bridge(workflow, connections, AdaptiveEngineTests.settings());
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void anAutomaticRateChangeOnAFreshSystemReachesOnlyItsServiceThroughComponent3() {
        bridge.run(); // no effective policy at all
        assertTrue(workflow.observeConfiguredRate("payment-svc", 100, clock.now), "the sidecar's rate is the reference");
        var base = workflow.current("payment-svc", Source.PROMETHEUS).version();
        var change = workflow.offer(new Proposal(UUID.randomUUID(), "payment-svc", Action.LEAKY_BUCKET, Source.PROMETHEUS, base, 100, 150, "requests/second",
                clock.now, clock.now.plusSeconds(300), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("RATE_TOO_LOW")),
                new Review("c4-auto", true, "Approved automatically by policy"));
        assertEquals(Decision.APPROVED, change.decision());
        bridge.run();
        assertEquals(1, policies.size());
        JsonNode sent = policies.get(0);
        assertEquals("c4-payment-svc", sent.path("policyId").asString(), "a new policy for the service (it had none)");
        assertEquals("payment-svc", sent.path("scope").path("service").asString(), "only the target service");
        assertEquals("c4-auto", sent.path("approval").path("approvedBy").asString(), "approved automatically, no person");
        assertEquals(150, sent.path("spec").path("leakyBucket").path("ratePerSec").asDouble());
        assertFalse(sent.has("parts"), "a single rate change, not a joint card");
        assertEquals("two-phase", rollouts.get(0).path("mode").asString());
        assertEquals("c4-auto", rollouts.get(0).path("requestedBy").asString());
        // the rollout completes; Component 3 now has the policy and the sidecar enforces 150
        phase = "Complete";
        effective = "{\"payment-svc\":{\"policyId\":\"c4-payment-svc\",\"epoch\":\"c4-payment-svc-v1\",\"route\":\"\",\"spec\":\"{\\\"leakyBucket\\\":{\\\"ratePerSec\\\":150}}\"}}";
        workflow.observeConfiguredRate("payment-svc", 150, clock.now); // during the rollout: keeps the reference, never supersedes the change
        bridge.run();
        var applied = workflow.find(change.proposal().id());
        assertEquals(Delivery.APPLIED, applied.delivery());
        assertEquals("c4-payment-svc-v1", applied.appliedVersion());
        assertEquals(150, workflow.current("payment-svc", Source.PROMETHEUS).bucketRate());
        assertFalse(workflow.observeConfiguredRate("payment-svc", 150, clock.now), "from now on the Component 3 rate wins");
    }
}
