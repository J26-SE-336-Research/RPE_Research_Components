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

/** C3 J4: an approved joint recommendation is delivered as ONE policy version with parts and ONE two-phase rollout. */
class JointBridgeTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<JsonNode> policies = new CopyOnWriteArrayList<>(), rollouts = new CopyOnWriteArrayList<>();
    private volatile String phase = "Activating";
    private volatile String effective;
    private HttpServer server;
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private PolicyWorkflow workflow;
    private Component3Bridge bridge;
    static final String ROUTE = "GET /api/orders";

    private static String effective(String gwEpoch, long deadline, String payEpoch, String payPolicy, double rate) {
        return "{\"gateway\":{\"policyId\":\"gw-limits\",\"epoch\":\"" + gwEpoch + "\",\"route\":\"" + ROUTE + "\",\"spec\":\"{\\\"deadlineMs\\\":" + deadline
                + ",\\\"routes\\\":{\\\"" + ROUTE + "\\\":{\\\"deadlineMs\\\":" + deadline + "},\\\"GET /api/catalog\\\":{\\\"deadlineMs\\\":400}}}\"},"
                + "\"payment-svc\":{\"policyId\":\"" + payPolicy + "\",\"epoch\":\"" + payEpoch + "\",\"route\":\"\",\"spec\":\"{\\\"leakyBucket\\\":{\\\"ratePerSec\\\":"
                + rate + ",\\\"burst\\\":20},\\\"retry\\\":{\\\"budgetRatio\\\":0.05}}\"}}";
    }

    @BeforeEach void start() throws Exception {
        effective = effective("e3", 875, "p3", "payment-limits", 50);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/k8s/", exchange -> {
            String path = exchange.getRequestURI().getPath(); String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out; int code = 200;
            if (path.equals("/api/k8s/effective")) out = effective;
            else if (path.equals("/api/k8s/policies") && method.equals("POST")) {
                policies.add(json.readTree(body)); code = 201;
                out = "{\"policy\":{\"policyId\":\"gw-limits\",\"version\":" + (10 + policies.size()) + ",\"epoch\":\"gw-limits-v" + (10 + policies.size()) + "\"}}";
            } else if (path.equals("/api/k8s/rollouts") && method.equals("POST")) {
                rollouts.add(json.readTree(body)); code = 201; out = "{\"metadata\":{\"name\":\"ro-" + rollouts.size() + "\"},\"status\":{\"phase\":\"Pending\"}}";
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
        bridge.run(); // sync: payment's rate and the route deadline become the references
    }
    @AfterEach void stop() { server.stop(0); }

    private Proposal proposal(String target, Action action, Source source, double current, double proposed) {
        return new Proposal(UUID.randomUUID(), target, action, source, workflow.current(target, source).version(), current, proposed, "unit", clock.now,
                clock.now.plusSeconds(600), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("Joint recommendation (test)"));
    }
    private List<PolicyChange> approvedJoint() {
        var parts = workflow.offerJoint(List.of(proposal("payment-svc", Action.LEAKY_BUCKET, Source.PROMETHEUS, 50, 100),
                proposal(ROUTE, Action.CHAIN_DEADLINE, Source.TEMPO, 875, 780)));
        workflow.decide(parts.get(1).proposal().id(), new Review("sre-alice", true, "joint"));
        return parts;
    }

    @Test void anApprovedJointIsOnePolicyWithPartsAndOneRollout() {
        var parts = approvedJoint();
        UUID joint = workflow.jointOf(parts.get(0).proposal().id());
        bridge.run();
        assertEquals(1, policies.size(), "one policy version for the whole joint");
        assertEquals(1, rollouts.size(), "one rollout");
        JsonNode sent = policies.get(0);
        assertEquals("gw-limits", sent.path("policyId").asString(), "the gateway's effective policy");
        assertEquals(joint.toString(), sent.path("recommendationId").asString(), "idempotent by the joint id");
        assertEquals("c4-recommendation", sent.path("source").asString());
        assertEquals("sre-alice", sent.path("approval").path("approvedBy").asString());
        assertEquals(2, sent.path("parts").size());
        JsonNode gw = null, pay = null;
        for (var part : sent.path("parts")) {
            if ("gateway".equals(part.path("scope").path("service").asString())) gw = part; else pay = part;
        }
        assertNotNull(gw); assertNotNull(pay);
        assertEquals(ROUTE, gw.path("scope").path("route").asString());
        assertEquals(780, gw.path("spec").path("routes").path(ROUTE).path("deadlineMs").asInt());
        assertEquals(780, gw.path("spec").path("deadlineMs").asInt(), "a policy scoped to this route keeps its scope deadline consistent");
        assertEquals(400, gw.path("spec").path("routes").path("GET /api/catalog").path("deadlineMs").asInt(), "other routes are kept");
        assertEquals("payment-svc", pay.path("scope").path("service").asString());
        assertEquals(100, pay.path("spec").path("leakyBucket").path("ratePerSec").asDouble());
        assertEquals(20, pay.path("spec").path("leakyBucket").path("burst").asInt(), "the rest of payment's spec is kept");
        assertEquals(.05, pay.path("spec").path("retry").path("budgetRatio").asDouble());
        assertFalse(sent.has("spec"), "no top-level spec next to parts");
        assertEquals("two-phase", rollouts.get(0).path("mode").asString());
        assertEquals("gw-limits", rollouts.get(0).path("policyId").asString());
        assertTrue(parts.stream().allMatch(p -> workflow.find(p.proposal().id()).delivery() == Delivery.SENT));
        // the rollout completes: both parts are APPLIED with the same epoch, and nothing is sent twice
        phase = "Complete"; effective = effective("gw-limits-v11", 780, "gw-limits-v11", "gw-limits", 100);
        bridge.run();
        for (var p : parts) {
            var now = workflow.find(p.proposal().id());
            assertEquals(Delivery.APPLIED, now.delivery());
            assertEquals("gw-limits-v11", now.appliedVersion());
        }
        assertEquals(100, workflow.current("payment-svc", Source.PROMETHEUS).bucketRate());
        assertEquals(780, workflow.current(ROUTE, Source.TEMPO).deadlineMs());
        assertEquals(1, policies.size());
    }

    @Test void aJointWaitsWhileAnotherChangeOfItsServicesIsRollingOutAndNeverGoesHalf() {
        // a single gateway change is rolling out
        var single = workflow.offer(proposal(ROUTE, Action.CHAIN_DEADLINE, Source.TEMPO, 875, 900));
        workflow.decide(single.proposal().id(), new Review("sre", true, null));
        bridge.run();
        assertEquals(1, policies.size());
        assertFalse(policies.get(0).has("parts"), "a single proposal keeps the existing single-policy payload");
        // a joint on payment alone (rate) and the same gateway: nothing of it is sent while the gateway is busy
        var rate = proposal("payment-svc", Action.LEAKY_BUCKET, Source.PROMETHEUS, 50, 100);
        workflow.putPolicy(new Policy("other-route", "o1", clock.now.plusSeconds(3600), null, null, null, 600.0), Source.TEMPO);
        var parts = workflow.offerJoint(List.of(rate, proposal("other-route", Action.CHAIN_DEADLINE, Source.TEMPO, 600, 550)));
        workflow.decide(parts.get(0).proposal().id(), new Review("sre", true, null));
        bridge.run();
        assertEquals(1, policies.size(), "the joint waits; its payment part is not sent alone");
        assertTrue(parts.stream().allMatch(p -> workflow.find(p.proposal().id()).delivery() != Delivery.SENT));
    }
}
