package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PolicyDispatcherTests {
    private record Received(String path, String method, String key, String authorization, String contentType, String body) {}
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private HttpServer server;
    private String base;
    private String response = "{\"status\":\"ACCEPTED\"}";
    @BeforeEach void startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stopReceiver() { server.stop(0); }
    private PolicyChange offer(PolicyWorkflow workflow, Action action, Source source) {
        String target = action == Action.CHAIN_DEADLINE ? "POST /api/orders" : "order";
        workflow.putPolicy(new Policy(target, "v1", clock.now.plusSeconds(3600), 120.0, 120.0, .05, 1000.0), source);
        return workflow.offer(new Proposal(UUID.randomUUID(), target, action, source, "v1",
                action == Action.CHAIN_DEADLINE ? 1000 : action == Action.LEAKY_BUCKET ? 120 : .05,
                action == Action.CHAIN_DEADLINE ? 1440 : action == Action.LEAKY_BUCKET ? 90 : .02,
                action == Action.CHAIN_DEADLINE ? "milliseconds" : action == Action.LEAKY_BUCKET ? "requests/second" : "extra attempts/original request",
                clock.now, clock.now.plusSeconds(300), Map.of("windowEndEpochSeconds", (double) clock.now.getEpochSecond()), List.of("fixture evidence")));
    }
    private AdaptiveConfig.DeliveryEndpoints endpoints() {
        return new AdaptiveConfig.DeliveryEndpoints(base + "/api/v1/policies/deadlines", base + "/api/v1/policies/leaky-bucket", base + "/api/v1/policies/retry-budget");
    }
    @Test void routesEachTypeWithRealPostRequestsAndOmitsUnconfiguredAuthorization() {
        var connections = AdaptiveEngineTests.connections(""); var endpoints = endpoints();
        var workflow = new PolicyWorkflow(connections, AdaptiveEngineTests.settings(), clock, endpoints);
        var dispatcher = new PolicyDispatcher(workflow, connections, endpoints);
        var deadline = offer(workflow, Action.CHAIN_DEADLINE, Source.TEMPO);
        offer(workflow, Action.LEAKY_BUCKET, Source.PROMETHEUS);
        offer(workflow, Action.RETRY_BUDGET, Source.PROMETHEUS);
        dispatcher.dispatch(); assertEquals(2, received.size(), "Deadline cannot be posted before approval");
        workflow.decide(deadline.proposal().id(), new Review("reviewer", true, null));
        dispatcher.dispatch(); assertEquals(3, received.size());
        var json = JsonMapper.builder().build();
        for (var request : received) {
            var body = json.readTree(request.body()); var proposal = body.get("proposal");
            assertEquals("POST", request.method()); assertEquals("application/json", request.contentType()); assertNull(request.authorization());
            assertEquals(proposal.get("id").asString(), request.key()); assertEquals("v1", proposal.get("baseVersion").asString());
            assertTrue(proposal.has("expiresAt")); assertTrue(proposal.has("evidence"));
            String expected = switch (proposal.get("action").asString()) {
                case "CHAIN_DEADLINE" -> "/api/v1/policies/deadlines";
                case "LEAKY_BUCKET" -> "/api/v1/policies/leaky-bucket";
                case "RETRY_BUDGET" -> "/api/v1/policies/retry-budget";
                default -> throw new AssertionError("Unexpected action");
            };
            assertEquals(expected, request.path());
        }
        assertEquals(Delivery.SENT, workflow.find(deadline.proposal().id()).delivery());
        assertEquals(1000, workflow.current("POST /api/orders", Source.TEMPO).deadlineMs());
        workflow.deliveryResult(deadline.proposal().id(), new Acknowledgement("APPLIED", "v2", 1440.0), null);
        assertEquals(1440, workflow.current("POST /api/orders", Source.TEMPO).deadlineMs());
        assertEquals("v2", workflow.current("POST /api/orders", Source.TEMPO).version());
    }
    @Test void missingDestinationsAndSyntheticUpdatesStayPendingWithoutNetworkCalls() {
        var connections = AdaptiveEngineTests.connections("");
        var endpoints = new AdaptiveConfig.DeliveryEndpoints(base + "/deadlines", "", "");
        var workflow = new PolicyWorkflow(connections, AdaptiveEngineTests.settings(), clock, endpoints);
        var bucket = offer(workflow, Action.LEAKY_BUCKET, Source.PROMETHEUS);
        var mock = offer(workflow, Action.CHAIN_DEADLINE, Source.MOCK);
        workflow.decide(mock.proposal().id(), new Review("reviewer", true, null));
        new PolicyDispatcher(workflow, connections, endpoints).dispatch();
        assertTrue(received.isEmpty()); assertEquals(0, workflow.find(bucket.proposal().id()).attempts());
        assertEquals(Delivery.WAITING_FOR_COMPONENT_3, bucket.delivery());
        assertTrue(workflow.deliveryConfigured());
    }
    @Test void retriesFailedResponsesWithSameIdAndSupportsLegacySingleUrlFallback() {
        var connections = AdaptiveEngineTests.connections(base + "/all-updates");
        var endpoints = AdaptiveConfig.DeliveryEndpoints.disabled();
        var workflow = new PolicyWorkflow(connections, AdaptiveEngineTests.settings(), clock, endpoints);
        var bucket = offer(workflow, Action.LEAKY_BUCKET, Source.PROMETHEUS);
        var dispatcher = new PolicyDispatcher(workflow, connections, endpoints);
        response = "{}"; dispatcher.dispatch();
        assertEquals(Delivery.FAILED, workflow.find(bucket.proposal().id()).delivery());
        response = "{\"status\":\"ACCEPTED\"}"; clock.next(31); dispatcher.dispatch();
        assertEquals(2, received.size()); assertEquals(received.get(0).key(), received.get(1).key());
        assertEquals("/all-updates", received.get(1).path());
        assertEquals(Delivery.SENT, workflow.find(bucket.proposal().id()).delivery());
    }
    @Test void immediateAppliedResponseRequiresExactValueAndNewVersion() {
        var connections = AdaptiveEngineTests.connections(""); var endpoints = endpoints();
        var workflow = new PolicyWorkflow(connections, AdaptiveEngineTests.settings(), clock, endpoints);
        var bucket = offer(workflow, Action.LEAKY_BUCKET, Source.PROMETHEUS);
        var dispatcher = new PolicyDispatcher(workflow, connections, endpoints);
        response = "{\"status\":\"APPLIED\",\"policyVersion\":\"v1\",\"appliedValue\":90}";
        dispatcher.dispatch(); assertEquals(Delivery.FAILED, workflow.find(bucket.proposal().id()).delivery());
        assertEquals(120, workflow.current("order", Source.PROMETHEUS).bucketRate());
        response = "{\"status\":\"APPLIED\",\"policyVersion\":\"v2\",\"appliedValue\":90}";
        clock.next(31); dispatcher.dispatch();
        assertEquals(Delivery.APPLIED, workflow.find(bucket.proposal().id()).delivery());
        assertEquals(90, workflow.current("order", Source.PROMETHEUS).bucketRate());
    }
    @Test void configuredUrlsRejectEmbeddedCredentialsAndNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveConfig.DeliveryEndpoints("ftp://example.com/updates", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveConfig.DeliveryEndpoints("http://user:password@example.com/updates", "", ""));
    }
}
