package com.example.proxy;

import static com.example.proxy.TestHttp.headers;
import static org.junit.jupiter.api.Assertions.*;

import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.Ids;
import dev.rpe.protocol.RpeContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the proxy as the gateway sidecar: the trust boundary that creates every request chain. */
class GatewayProxyIntegrationTest {
    private static TestHttp.Backend app;
    private static TestHttp.Backend downstream;
    private static ProxyServer proxy;
    private static int outboundPort, inboundPort, adminPort, metricsPort;

    @BeforeAll
    static void start() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(io.netty.channel.epoll.Epoll.isAvailable(),
                "the proxy needs Linux native epoll");
        app = new TestHttp.Backend();
        downstream = new TestHttp.Backend();
        outboundPort = TestHttp.freePort();
        inboundPort = TestHttp.freePort();
        adminPort = TestHttp.freePort();
        metricsPort = TestHttp.freePort();
        Map<String, String> env = new HashMap<>();
        env.put("RPE_ROLE", "gateway");
        env.put("RPE_SERVICE_NAME", "gateway");
        env.put("RPE_POD_NAME", "gateway-0");
        env.put("RPE_INBOUND_MODE", "redirect");
        env.put("RPE_APP_PORT", Integer.toString(app.port()));
        env.put("RPE_DEFAULT_DEADLINE_MS", "1000");
        env.put("RPE_ROUTE_DEADLINES", "GET /api/orders=1500");
        env.put("PROXY_LISTEN_HOST", "127.0.0.1");
        env.put("PROXY_LISTEN_PORT", Integer.toString(outboundPort));
        env.put("PROXY_INBOUND_LISTEN_PORT", Integer.toString(inboundPort));
        env.put("PROXY_ADMIN_LISTEN_PORT", Integer.toString(adminPort));
        env.put("METRICS_LISTEN_HOST", "127.0.0.1");
        env.put("METRICS_LISTEN_PORT", Integer.toString(metricsPort));
        proxy = new ProxyServer(ProxyConfig.from(env));
    }

    @AfterAll
    static void stop() {
        if (proxy != null) proxy.close();
        if (app != null) app.close();
        if (downstream != null) downstream.close();
    }

    @BeforeEach
    void reset() {
        app.drain();
        downstream.drain();
        app.reply = request -> TestHttp.Reply.ok();
    }

    @Test
    void gatewayCreatesTheRootContextAndIgnoresClientValues() throws Exception {
        String forged = "v=1, rid=\"4bf92f3577b34da6a3ce929d0e0e4736\", iid=\"00f067aa0ba902b7\", "
                + "pid=\"0000000000000000\", ttl=99999, bud=99999, rb=99, cs=expired, rt=\"GET /api/orders\"";
        assertEquals(200, TestHttp.send(inboundPort, "GET", "/api/orders?x=1",
                headers("X-RPE-Context", forged, "Request_id", "attacker-chosen"), null).status());
        TestHttp.Received atApp = app.next();
        RpeContext root = RpeContext.decode(atApp.header("X-RPE-Context"));
        assertNotEquals("4bf92f3577b34da6a3ce929d0e0e4736", root.rid(), "R-S01: the client cannot choose the chain");
        assertTrue(RpeWire.isRequestId(root.rid()));
        assertEquals("0000000000000000", root.pid());
        assertEquals(1500, root.budgetMillis(), "route budget from the gateway rules");
        assertEquals("GET /api/orders", root.route());
        assertEquals(CancelState.NONE, root.cancel());
        assertEquals(3, root.retryBudget());
        assertEquals(root.rid(), atApp.header("Request_id"));
        assertEquals(root.rid(), Ids.traceIdFromTraceparent(atApp.header("traceparent")), "rid is the trace-id");
    }

    @Test
    void applicationCallsReuseTheChainCreatedAtTheGateway() throws Exception {
        TestHttp.send(inboundPort, "GET", "/api/orders", headers(), null);
        RpeContext root = RpeContext.decode(app.next().header("X-RPE-Context"));
        // the gateway application derives a child context for its call to order-svc
        RpeContext child = root.child(5, 5, 3);
        assertEquals(200, TestHttp.send(outboundPort, "GET", "/orders",
                headers("Host", "127.0.0.1:" + downstream.port(), "X-RPE-Context", child.encode()), null).status());
        RpeContext atOrder = RpeContext.decode(downstream.next().header("X-RPE-Context"));
        assertEquals(root.rid(), atOrder.rid());
        assertEquals(child.iid(), atOrder.iid());
        assertEquals(root.iid(), atOrder.pid());
        assertTrue(atOrder.ttlMillis() <= child.ttlMillis());
    }

    @Test
    void activePolicyRouteDeadlineAndEpochApplyToNewChains() throws Exception {
        String policy = "{\"epoch\":\"pol-e3\",\"version\":3,\"hash\":\"h3\",\"state\":\"staged\","
                + "\"policy\":{\"routes\":{\"GET /api/orders\":{\"deadlineMs\":300}}}}";
        assertEquals(200, TestHttp.send(adminPort, "PUT", "/rpe/v1/policy", headers(), policy).status());
        TestHttp.send(inboundPort, "GET", "/api/orders", headers(), null);
        assertEquals(1500, RpeContext.decode(app.next().header("X-RPE-Context")).budgetMillis(),
                "a staged policy is not enforced");

        assertEquals(200, TestHttp.send(adminPort, "POST", "/rpe/v1/policy/activate", headers(),
                "{\"epoch\":\"pol-e3\"}").status());
        TestHttp.send(inboundPort, "GET", "/api/orders", headers(), null);
        RpeContext root = RpeContext.decode(app.next().header("X-RPE-Context"));
        assertEquals(300, root.budgetMillis(), "the active policy's route deadline wins");
        assertEquals("pol-e3", root.policyEpoch());
        String metrics = TestHttp.send(metricsPort, "GET", "/metrics", headers(), null).body();
        assertTrue(metrics.contains("rpe_gateway_deadline_budget_milliseconds{method=\"GET\",route=\"/api/orders\"} 300"), metrics);
        assertEquals(1, metrics.lines().filter(line -> line.startsWith(
                "rpe_gateway_deadline_budget_milliseconds{method=\"GET\",route=\"/api/orders\"}")).count());

        // restore the configured budget for the other tests
        TestHttp.send(adminPort, "PUT", "/rpe/v1/policy", headers(),
                "{\"epoch\":\"pol-e4\",\"version\":4,\"hash\":\"h4\",\"state\":\"active\",\"policy\":{\"routes\":{}}}");
    }

    @Test
    void lateChainIsSignalledUpstreamAndCounted() throws Exception {
        app.reply = request -> new TestHttp.Reply(200, Map.of(), "slow", 1700);
        TestHttp.Response response = TestHttp.send(inboundPort, "GET", "/api/orders", headers(), null);
        app.next();
        assertEquals(200, response.status(), "a late response is delivered, not replaced by an error");
        assertEquals("v=1, cs=expired", response.header("X-RPE-Result"));
        String metrics = TestHttp.send(metricsPort, "GET", "/metrics", headers(), null).body();
        String labels = "service=\"gateway\",pod=\"gateway-0\",method=\"GET\",route=\"/api/orders\"";
        assertTrue(sample(metrics, "rpe_chain_requests_total{" + labels + "}") >= 1, metrics);
        assertTrue(sample(metrics, "rpe_chain_deadline_exceeded_total{" + labels + "}") >= 1, metrics);
    }

    private static double sample(String metrics, String series) {
        for (String line : metrics.split("\n")) {
            if (line.startsWith(series + " ")) return Double.parseDouble(line.substring(series.length() + 1).trim());
        }
        throw new AssertionError("missing series " + series + " in\n" + metrics);
    }
}
