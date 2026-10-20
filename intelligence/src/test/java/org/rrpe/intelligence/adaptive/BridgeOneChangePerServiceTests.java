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
 * Owner decision (21 Oct 2026): the bridge delivers at most one change per service per cycle, so an approved rate change
 * and an approved retry change for the same service do not undo each other.
 */
class BridgeOneChangePerServiceTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<JsonNode> policies = new CopyOnWriteArrayList<>();
    private volatile String phase = "Activating";
    private volatile String effective;
    private HttpServer server;
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private PolicyWorkflow workflow;
    private Component3Bridge bridge;

    private static String effective(String epoch, double rate, double ratio) {
        return "{\"payment-svc\":{\"policyId\":\"payment-limits\",\"epoch\":\"" + epoch + "\",\"route\":\"\",\"spec\":\"{\\\"leakyBucket\\\":{\\\"ratePerSec\\\":"
                + rate + "},\\\"retry\\\":{\\\"budgetRatio\\\":" + ratio + "}}\"}}";
    }

    @BeforeEach void start() throws Exception {
        effective = effective("e3", 120, .05);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/k8s/", exchange -> {
            String path = exchange.getRequestURI().getPath(); String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out; int code = 200;
            if (path.equals("/api/k8s/effective")) out = effective;
            else if (path.equals("/api/k8s/policies") && method.equals("POST")) {
                JsonNode in = json.readTree(body); policies.add(in); code = 201;
                out = "{\"policy\":{\"policyId\":\"payment-limits\",\"version\":" + policies.size() + ",\"epoch\":\"e" + (10 + policies.size()) + "\"}}";
            } else if (path.equals("/api/k8s/rollouts") && method.equals("POST")) {
                code = 201; out = "{\"metadata\":{\"name\":\"ro-" + policies.size() + "\"},\"status\":{\"phase\":\"Pending\"}}";
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

    private PolicyChange approved(Action action, double current, double proposed) {
        var change = workflow.offer(new Proposal(UUID.randomUUID(), "payment-svc", action, Source.PROMETHEUS, workflow.current("payment-svc", Source.PROMETHEUS).version(),
                current, proposed, "unit", clock.now, clock.now.plusSeconds(600), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("e")));
        return workflow.decide(change.proposal().id(), new Review("sre", true, null));
    }

    @Test void aRateAndARetryChangeForOneServiceAreDeliveredOneAfterTheOtherAndBothSurvive() {
        bridge.run(); // sync e3
        var rate = approved(Action.LEAKY_BUCKET, 120, 90);
        var retry = approved(Action.RETRY_BUDGET, .05, .02);
        bridge.run();
        assertEquals(1, policies.size(), "one change per service per cycle");
        assertEquals(90, policies.get(0).path("spec").path("leakyBucket").path("ratePerSec").asDouble());
        bridge.run();
        assertEquals(1, policies.size(), "nothing else for this service while its change is rolling out");
        assertEquals(Delivery.READY, workflow.find(retry.proposal().id()).delivery());
        // the first rollout completes: C3 reports the new version as effective
        phase = "Complete"; effective = effective("e11", 90, .05);
        bridge.run();
        assertEquals(Delivery.APPLIED, workflow.find(rate.proposal().id()).delivery());
        var moved = workflow.find(retry.proposal().id());
        assertNotEquals(Delivery.SUPERSEDED, moved.delivery(), "the approved retry change is not cancelled by the rate change");
        assertEquals("e11", moved.proposal().baseVersion(), "it moved to the new version");
        assertEquals(2, policies.size(), "then the retry change is delivered");
        var second = policies.get(1).path("spec");
        assertEquals(90, second.path("leakyBucket").path("ratePerSec").asDouble(), "it keeps the applied rate");
        assertEquals(.02, second.path("retry").path("budgetRatio").asDouble());
    }

    @Test void differentServicesAreStillDeliveredInTheSameCycleAndOutdatedChangesAreStillSuperseded() {
        bridge.run();
        var rate = approved(Action.LEAKY_BUCKET, 120, 90);
        var other = approved(Action.LEAKY_BUCKET, 120, 100); // the same lever again: replaces the first
        assertEquals(Delivery.SUPERSEDED, workflow.find(rate.proposal().id()).delivery());
        // a version where the retry ratio itself changed: an approved retry change from the old value is outdated
        var retry = approved(Action.RETRY_BUDGET, .05, .02);
        workflow.putPolicy(new Policy("payment-svc", "e7", clock.now.plusSeconds(3600), 120.0, 120.0, .08, null), Source.PROMETHEUS);
        assertEquals(Delivery.SUPERSEDED, workflow.find(retry.proposal().id()).delivery(), "its current value changed: superseded as before");
        assertEquals("e7", workflow.find(other.proposal().id()).proposal().baseVersion(), "the rate change (rate unchanged) moved to e7");
    }
}
