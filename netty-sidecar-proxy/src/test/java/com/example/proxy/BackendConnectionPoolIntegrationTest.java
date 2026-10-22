package com.example.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sidecar keeps its backend connection open after a keep-alive response and reuses it for the next request,
 * instead of opening one TCP connection per request; PROXY_BACKEND_POOL=off restores one connection per request.
 */
class BackendConnectionPoolIntegrationTest {
    private static TestHttp.Backend app;
    private static TestHttp.Backend downstream;
    private static ProxyServer proxy;
    private static int inboundPort, outboundPort, metricsPort;
    // a keep-alive HTTP/1.1 client, like the Go clients of the demo services
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(io.netty.channel.epoll.Epoll.isAvailable(),
                "the proxy needs Linux native epoll");
        app = new TestHttp.Backend();
        downstream = new TestHttp.Backend();
        outboundPort = TestHttp.freePort();
        inboundPort = TestHttp.freePort();
        metricsPort = TestHttp.freePort();
        Map<String, String> env = new HashMap<>();
        env.put("RPE_ROLE", "service");
        env.put("RPE_SERVICE_NAME", "payment-svc");
        env.put("RPE_POD_NAME", "payment-svc-0");
        env.put("RPE_INBOUND_MODE", "redirect");
        env.put("RPE_APP_PORT", Integer.toString(app.port()));
        env.put("PROXY_LISTEN_HOST", "127.0.0.1");
        env.put("PROXY_LISTEN_PORT", Integer.toString(outboundPort));
        env.put("PROXY_INBOUND_LISTEN_PORT", Integer.toString(inboundPort));
        env.put("PROXY_ADMIN_LISTEN_PORT", Integer.toString(TestHttp.freePort()));
        env.put("METRICS_LISTEN_HOST", "127.0.0.1");
        env.put("METRICS_LISTEN_PORT", Integer.toString(metricsPort));
        proxy = new ProxyServer(ProxyConfig.from(env));
        appClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress("127.0.0.1", outboundPort)))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() {
        BackendConnectionPool.setEnabledForTest(true);
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

    // the application's outbound calls: a keep-alive client that goes through the sidecar's outbound listener
    private static HttpClient appClient;

    /** The application calls the downstream service with only Request_id; returns what the downstream received. */
    private static Hop callDownstream(String rid) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + downstream.port() + "/reserve"))
                .header("Request_id", rid).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = appClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        TestHttp.Received atDownstream = downstream.next();
        return new Hop(Integer.parseInt(atDownstream.headers().get("x-test-client-port")),
                atDownstream.headers().getOrDefault("x-rpe-context", ""),
                response.headers().firstValue("X-RPE-Result").orElse(""));
    }

    @Test
    void cancellationReachesTheNextServiceOverAReusedSidecarToSidecarConnection() throws Exception {
        BackendConnectionPool.setEnabledForTest(true);
        String onTime = RpeWire.newRequestId();
        send(onTime, 5000, "none");
        Hop first = callDownstream(onTime);
        assertTrue(first.contextAtApp().contains("cs=none"), first.contextAtApp());

        String cancelled = RpeWire.newRequestId();
        send(cancelled, 5000, "cancel"); // the chain was cancelled upstream
        Hop second = callDownstream(cancelled);
        assertEquals(first.port(), second.port(), "the outbound call reused the sidecar's connection to the next service");
        assertTrue(second.contextAtApp().contains("cs=cancel"), "downstream: the next service gets the signal: " + second.contextAtApp());
        assertTrue(second.resultUpstream().contains("cs=cancel"), "upstream: the caller's response carries it: " + second.resultUpstream());

        Hop third = callDownstream(onTime);
        assertEquals(first.port(), third.port());
        assertTrue(third.contextAtApp().contains("cs=none"), "another chain on the same connection is not affected: " + third.contextAtApp());
    }

    /** An on-time request carries no cancellation signal upstream (no X-RPE-Result, or one with cs=none). */
    private static void assertNoSignal(String result, String message) {
        assertFalse(result.contains("cs=cancel") || result.contains("cs=expired"), message + " [" + result + "]");
    }

    private static int send() throws Exception {
        return send(5000, "none").port();
    }

    /** One request over the keep-alive client; what the application received and what came back upstream. */
    private record Hop(int port, String contextAtApp, String resultUpstream) {}

    private static Hop send(long ttl, String cs) throws Exception {
        return send(RpeWire.newRequestId(), ttl, cs);
    }

    private static Hop send(String rid, long ttl, String cs) throws Exception {
        String context = "v=1, rid=\"" + rid + "\", iid=\"00f067aa0ba902b7\", pid=\"1111111111111111\", "
                + "ttl=" + ttl + ", bud=5000, rb=2, cs=" + cs + ", pe=\"e7\", rt=\"GET /api/orders\"";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + inboundPort + "/pay"))
                .header("X-RPE-Context", context).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "signal, never reject: the request is served");
        TestHttp.Received atApp = app.next();
        return new Hop(Integer.parseInt(atApp.headers().get("x-test-client-port")),
                atApp.headers().getOrDefault("x-rpe-context", ""),
                response.headers().firstValue("X-RPE-Result").orElse(""));
    }

    @Test
    void cancellationTravelsDownstreamAndUpstreamOverAReusedConnection() throws Exception {
        BackendConnectionPool.setEnabledForTest(true);
        Hop first = send(5000, "none");
        assertTrue(first.contextAtApp().contains("cs=none"), first.contextAtApp());
        assertNoSignal(first.resultUpstream(), first.resultUpstream());

        Hop cancelled = send(5000, "cancel");
        assertEquals(first.port(), cancelled.port(), "the second request reused the first one's connection");
        assertTrue(cancelled.contextAtApp().contains("cs=cancel"), "downstream: the application sees the signal: " + cancelled.contextAtApp());
        assertTrue(cancelled.resultUpstream().contains("cs=cancel"), "upstream: the response carries it back: " + cancelled.resultUpstream());

        Hop next = send(5000, "none");
        assertEquals(first.port(), next.port(), "still the same connection");
        assertTrue(next.contextAtApp().contains("cs=none"), "a later request on the same connection is not affected: " + next.contextAtApp());
        assertNoSignal(next.resultUpstream(), next.resultUpstream());
    }

    @Test
    void anExpiredDeadlineIsSignalledOverAReusedConnection() throws Exception {
        BackendConnectionPool.setEnabledForTest(true);
        int port = send(5000, "none").port();
        app.reply = request -> new TestHttp.Reply(200, Map.of(), "ok", 300); // the application is slower than the 50 ms left
        Hop late = send(50, "none");
        assertEquals(port, late.port(), "the late request reused the connection");
        assertTrue(late.resultUpstream().contains("cs=expired"), "upstream: the deadline passed, the signal goes back: " + late.resultUpstream());

        app.reply = request -> TestHttp.Reply.ok();
        Hop onTime = send(5000, "none");
        assertEquals(port, onTime.port(), "the connection is still reused afterwards");
        assertNoSignal(onTime.resultUpstream(), "the next request on it is on time again: " + onTime.resultUpstream());
    }

    private static long metric(String name) throws Exception {
        String body = TestHttp.send(metricsPort, "GET", "/metrics", TestHttp.headers(), null).body();
        return body.lines().filter(line -> line.startsWith(name + " ")).mapToLong(line -> Long.parseLong(line.split(" ")[1]))
                .findFirst().orElseThrow(() -> new AssertionError("no " + name));
    }

    @Test
    void consecutiveRequestsReuseOneBackendConnection() throws Exception {
        BackendConnectionPool.setEnabledForTest(true);
        long reusedBefore = metric("rpe_backend_connections_reused_total");
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < 5; i++) ports.add(send());
        assertEquals(1, ports.size(), "all five requests reached the application over the same connection: " + ports);
        assertTrue(metric("rpe_backend_connections_reused_total") - reusedBefore >= 4, "four of them reused it");
    }

    @Test
    void withThePoolOffEveryRequestOpensItsOwnConnection() throws Exception {
        BackendConnectionPool.setEnabledForTest(false);
        try {
            Set<Integer> ports = new HashSet<>();
            for (int i = 0; i < 3; i++) ports.add(send());
            assertEquals(3, ports.size(), "one connection per request: " + ports);
        } finally {
            BackendConnectionPool.setEnabledForTest(true);
        }
    }
}
