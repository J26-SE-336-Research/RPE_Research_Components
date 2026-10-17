package com.example.proxy;

import static com.example.proxy.TestHttp.headers;
import static org.junit.jupiter.api.Assertions.*;

import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.RpeContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the proxy as an RPE-injected service sidecar (REDIRECT inbound listener, outbound listener, control API,
 * metrics) against recording backends: the co-located application and a downstream service.
 */
class ServiceProxyIntegrationTest {
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
        env.put("RPE_ROLE", "service");
        env.put("RPE_SERVICE_NAME", "payment-svc");
        env.put("RPE_POD_NAME", "payment-svc-0");
        env.put("RPE_INBOUND_MODE", "redirect");
        env.put("RPE_APP_PORT", Integer.toString(app.port()));
        env.put("RPE_MISSING_CONTEXT", "passthrough");
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
        downstream.reply = request -> TestHttp.Reply.ok();
    }

    private static String context(String rid, String iid, String pid, long ttl, long budget, String cs) {
        return "v=1, rid=\"" + rid + "\", iid=\"" + iid + "\", pid=\"" + pid + "\", ttl=" + ttl + ", bud=" + budget
                + ", rb=2, cs=" + cs + ", pe=\"e7\", rt=\"GET /api/orders\"";
    }

    private static String rid() { return RpeWire.newRequestId(); }

    private TestHttp.Response inbound(String rid, long ttl, String cs) throws Exception {
        return TestHttp.send(inboundPort, "GET", "/pay",
                headers("X-RPE-Context", context(rid, "00f067aa0ba902b7", "1111111111111111", ttl, 5000, cs)), null);
    }

    private TestHttp.Response outbound(Map<String, String> headers) throws Exception {
        Map<String, String> all = new java.util.LinkedHashMap<>();
        all.put("Host", "127.0.0.1:" + downstream.port());
        all.putAll(headers);
        return TestHttp.send(outboundPort, "GET", "/reserve", all, null);
    }

    @Test
    void inboundContextIsDecodedAndReEncodedForTheApplication() throws Exception {
        String rid = rid();
        app.reply = request -> new TestHttp.Reply(200, Map.of("X-RPE-Result", "v=1, out=ok, rsn=none"), "ok", 0);
        TestHttp.Response response = inbound(rid, 4000, "none");
        assertEquals(200, response.status());

        TestHttp.Received atApp = app.next();
        RpeContext context = RpeContext.decode(atApp.header("X-RPE-Context"));
        assertEquals(rid, context.rid());
        assertEquals("00f067aa0ba902b7", context.iid(), "the hop to the application keeps the invocation id");
        assertEquals("1111111111111111", context.pid());
        assertEquals(5000, context.budgetMillis());
        assertEquals(2, context.retryBudget());
        assertEquals("e7", context.policyEpoch());
        assertEquals("GET /api/orders", context.route());
        assertEquals(CancelState.NONE, context.cancel());
        assertTrue(context.ttlMillis() <= 4000 && context.ttlMillis() > 3000, "ttl is the time left: " + context.ttlMillis());
        assertEquals(rid, atApp.header("Request_id"), "the application gets Request_id to propagate");
        for (String internal : new String[]{"deadlinevalue", "deadlineExceded", "cancellation_Triggered",
                "Request_route", "Request_deadline_millis", "Request_method", "X-RPE-Stored-Context",
                "X-Proxy-Peer-Key", "X-Proxy-Caller-Token"}) {
            assertNull(atApp.header(internal), internal + " must not reach the application");
        }
        assertEquals(rid, dev.rpe.protocol.Ids.traceIdFromTraceparent(atApp.header("traceparent")), "trace binding");
        assertEquals("v=1, out=ok, rsn=none, cs=none", response.header("X-RPE-Result"));
        assertNull(response.header("deadlineExceded"));
    }

    @Test
    void outboundCallKeepsTheApplicationsChildContext() throws Exception {
        String rid = rid();
        inbound(rid, 4000, "none");
        app.next();
        String child = context(rid, "aaaaaaaaaaaaaaaa", "00f067aa0ba902b7", 3500, 5000, "none");
        assertEquals(200, outbound(headers("X-RPE-Context", child)).status());

        TestHttp.Received atDownstream = downstream.next();
        RpeContext context = RpeContext.decode(atDownstream.header("X-RPE-Context"));
        assertEquals(rid, context.rid());
        assertEquals("aaaaaaaaaaaaaaaa", context.iid());
        assertEquals("00f067aa0ba902b7", context.pid());
        assertTrue(context.ttlMillis() <= 3500, "ttl never increases (R-T02): " + context.ttlMillis());
        assertNull(atDownstream.header("Request_id"), "only X-RPE-Context travels between services");
        assertNull(atDownstream.header("deadlinevalue"));
        assertNotNull(atDownstream.header("X-Proxy-Peer-Key"), "backpressure feedback registration is unchanged");
        assertNotNull(atDownstream.header("X-Proxy-Caller-Token"));
    }

    @Test
    void requestIdOnlyCallGetsAChildOfTheStoredContext() throws Exception {
        String rid = rid();
        inbound(rid, 4000, "none");
        app.next();
        assertEquals(200, outbound(headers("Request_id", rid)).status());

        RpeContext context = RpeContext.decode(downstream.next().header("X-RPE-Context"));
        assertEquals(rid, context.rid());
        assertEquals("00f067aa0ba902b7", context.pid(), "pid is the inbound invocation id (R-T06)");
        assertNotEquals("00f067aa0ba902b7", context.iid(), "the call gets a fresh invocation id");
        assertEquals(5000, context.budgetMillis());
        assertTrue(context.ttlMillis() <= 4000 && context.ttlMillis() > 3000);
        assertEquals(CancelState.NONE, context.cancel());
    }

    @Test
    void expiredDeadlineIsSignalledNotRejected() throws Exception {
        String rid = rid();
        assertEquals(200, inbound(rid, 300, "none").status());
        app.next();
        Thread.sleep(500); // the proxy's deadline timer marks the chain
        TestHttp.Response response = outbound(headers("Request_id", rid));
        assertEquals(200, response.status(), "the proxy never rejects because of the deadline");

        RpeContext context = RpeContext.decode(downstream.next().header("X-RPE-Context"));
        assertEquals(CancelState.EXPIRED, context.cancel(), "downstream services get the cancellation signal");
        assertEquals(0, context.ttlMillis());
        assertEquals("v=1, cs=expired", response.header("X-RPE-Result"), "the signal also goes back upstream");
    }

    @Test
    void contextExpiredOnArrivalStillReachesTheApplication() throws Exception {
        TestHttp.Response response = inbound(rid(), 0, "none");
        assertEquals(200, response.status());
        RpeContext context = RpeContext.decode(app.next().header("X-RPE-Context"));
        assertEquals(CancelState.EXPIRED, context.cancel(), "the application decides what to stop");
        assertEquals("v=1, cs=expired", response.header("X-RPE-Result"));
    }

    @Test
    void cancellationReportedByADownstreamResponseReachesLaterCalls() throws Exception {
        String rid = rid();
        inbound(rid, 4000, "none");
        app.next();
        downstream.reply = request -> new TestHttp.Reply(200,
                Map.of("X-RPE-Result", "v=1, out=ok, rsn=none, cs=cancel"), "ok", 0);
        assertEquals("v=1, out=ok, rsn=none, cs=cancel", outbound(headers("Request_id", rid)).header("X-RPE-Result"));
        downstream.next();

        downstream.reply = request -> TestHttp.Reply.ok();
        outbound(headers("Request_id", rid));
        assertEquals(CancelState.CANCEL, RpeContext.decode(downstream.next().header("X-RPE-Context")).cancel());
    }

    @Test
    void cancellationStateNeverReturnsToNone() throws Exception {
        TestHttp.Response response = inbound(rid(), 4000, "cancel");
        RpeContext context = RpeContext.decode(app.next().header("X-RPE-Context"));
        assertEquals(CancelState.CANCEL, context.cancel());
        assertEquals("v=1, cs=cancel", response.header("X-RPE-Result"));
    }

    @Test
    void requestsWithoutContextPassThroughUnchanged() throws Exception {
        TestHttp.Response response = TestHttp.send(inboundPort, "GET", "/healthz", headers("X-Probe", "1"), null);
        assertEquals(200, response.status());
        TestHttp.Received atApp = app.next();
        assertEquals("/healthz", atApp.path());
        assertEquals("1", atApp.header("X-Probe"));
        assertNull(atApp.header("X-RPE-Context"));
        assertNull(atApp.header("Request_id"));
        assertNull(response.header("X-RPE-Result"));

        assertEquals(200, outbound(headers()).status(), "an outbound call without context also passes through");
        assertNull(downstream.next().header("X-RPE-Context"));
        String metrics = TestHttp.send(metricsPort, "GET", "/metrics", headers(), null).body();
        String labels = "service=\"payment-svc\",pod=\"payment-svc-0\"";
        assertTrue(sample(metrics, "rpe_passthrough_requests_total{" + labels + ",direction=\"inbound\"}") >= 1, metrics);
        assertTrue(sample(metrics, "rpe_passthrough_requests_total{" + labels + ",direction=\"outbound\"}") >= 1, metrics);
    }

    @Test
    void invalidContextIsForwardedForTheApplicationToReject() throws Exception {
        app.reply = request -> new TestHttp.Reply(400, Map.of(), "invalid", 0);
        TestHttp.Response response = TestHttp.send(inboundPort, "GET", "/pay",
                headers("X-RPE-Context", "v=1, rid=nonsense"), null);
        assertEquals(400, response.status());
        assertEquals("v=1, rid=nonsense", app.next().header("X-RPE-Context"));
    }

    @Test
    void controlApiManagesPolicyEpochs() throws Exception {
        TestHttp.Response health = TestHttp.send(adminPort, "GET", "/rpe/v1/health", headers(), null);
        assertEquals(200, health.status());
        assertTrue(health.body().contains("\"role\":\"service\""), health.body());
        assertTrue(health.body().contains("\"service\":\"payment-svc\""), health.body());

        assertEquals(404, TestHttp.send(adminPort, "GET", "/rpe/v1/policy/active", headers(), null).status());
        TestHttp.Response staged = TestHttp.send(adminPort, "PUT", "/rpe/v1/policy", headers(),
                "{\"epoch\":\"e9\",\"version\":3,\"hash\":\"h\",\"state\":\"staged\",\"policy\":{\"routes\":{}}}");
        assertEquals(200, staged.status());
        // the hash is computed by the sidecar from the received policy (A3.5 v0.3), not taken from the request
        assertTrue(staged.body().startsWith("{\"epochs\":[{\"epoch\":\"e9\",\"version\":3,\"hash\":\""
                + PolicyStore.contentHash("{\"policy\":{\"routes\":{}}}") + "\",\"state\":\"staged\",\"inflight\":0}],\"activeEpoch\":\"\""),
                staged.body());
        assertTrue(staged.body().contains("\"mismatched\":0"), staged.body());
        TestHttp.Response activated = TestHttp.send(adminPort, "POST", "/rpe/v1/policy/activate", headers(),
                "{\"epoch\":\"e9\"}");
        assertTrue(activated.body().contains("\"activeEpoch\":\"e9\""), activated.body());
        TestHttp.Response active = TestHttp.send(adminPort, "GET", "/rpe/v1/policy/active", headers(), null);
        assertTrue(active.body().contains("\"epoch\":\"e9\""), active.body());
        assertEquals(409, TestHttp.send(adminPort, "DELETE", "/rpe/v1/policy/e9", headers(), null).status());
        assertEquals(400, TestHttp.send(adminPort, "PUT", "/rpe/v1/policy", headers(), "{\"epoch\":\"x\"}").status());
        assertEquals(404, TestHttp.send(adminPort, "POST", "/rpe/v1/policy/activate", headers(), "{\"epoch\":\"nope\"}").status());
    }

    @Test
    void metricsFollowTheSidecarContract() throws Exception {
        inbound(rid(), 4000, "none");
        app.next();
        String metrics = TestHttp.send(metricsPort, "GET", "/metrics", headers(), null).body();
        String labels = "service=\"payment-svc\",pod=\"payment-svc-0\"";
        assertTrue(metrics.contains("rpe_requests_total{" + labels + ",direction=\"inbound\",outcome=\"ok\"}"), metrics);
        assertTrue(metrics.contains("rpe_original_requests_total{" + labels + ",direction=\"outbound\"}"));
        assertTrue(metrics.contains("rpe_proxy_overhead_seconds_count{" + labels + "}"));
        assertTrue(metrics.contains("rpe_request_duration_seconds_bucket{" + labels + ",direction=\"inbound\",le=\"+Inf\"}"));
        assertTrue(metrics.contains("rpe_inflight_requests{" + labels + "}"));
        assertTrue(metrics.contains("rpe_rejections_total{" + labels + ",reason=\"overload\"}"));
        assertTrue(metrics.contains("rpe_retries_total{" + labels + ",result=\"attempted\"} 0"));
        assertTrue(metrics.contains("rpe_inbound_requests_total "), "the proxy's own metrics are renamed");
        assertTrue(metrics.contains("rpe_backpressure_inbound_queue_depth "));
        assertFalse(metrics.contains("sidecar_"), "no metric keeps the old sidecar_ prefix");
        double inboundOk = sample(metrics, "rpe_requests_total{" + labels + ",direction=\"inbound\",outcome=\"ok\"}");
        assertTrue(inboundOk >= 1, "inbound requests are counted: " + inboundOk);
    }

    private static double sample(String metrics, String series) {
        for (String line : metrics.split("\n")) {
            if (line.startsWith(series + " ")) return Double.parseDouble(line.substring(series.length() + 1).trim());
        }
        throw new AssertionError("missing series " + series);
    }
}
