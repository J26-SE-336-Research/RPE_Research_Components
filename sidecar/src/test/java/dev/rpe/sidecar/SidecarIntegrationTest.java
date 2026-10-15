package dev.rpe.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SidecarIntegrationTest {
    private HttpServer backend;
    private SidecarServer sidecar;
    private SidecarConfig cfg;
    private final AtomicReference<String> lastHopHeader = new AtomicReference<>("unset");
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ex -> {
            lastHopHeader.set(ex.getRequestHeaders().getFirst("X-RPE-Hop"));
            byte[] body = ("hello " + ex.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        backend.start();
        cfg = SidecarConfig.from(Map.of(
                "RPE_SERVICE_NAME", "test-svc",
                "RPE_APP_PORT", Integer.toString(backend.getAddress().getPort()),
                "RPE_OUTBOUND_PORT", Integer.toString(freePort()),
                "RPE_INBOUND_PORT", Integer.toString(freePort()),
                "RPE_ADMIN_PORT", Integer.toString(freePort()),
                "RPE_METRICS_PORT", Integer.toString(freePort())));
        sidecar = new SidecarServer(cfg);
    }

    @AfterEach
    void stop() {
        sidecar.close();
        backend.stop(0);
    }

    @Test
    void inboundForwardsToLocalAppAndStripsHopHeader() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cfg.inboundPort() + "/api/orders?x=1"))
                .header("X-RPE-Hop", "att=1").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("hello /api/orders?x=1", r.body());
        assertNull(lastHopHeader.get(), "X-RPE-Hop must be stripped before the application");
        assertEquals(1, sidecar.metrics.requestCount("inbound"));
    }

    @Test
    void outboundUsesHostHeaderAndReusesConnections() throws Exception {
        int backendPort = backend.getAddress().getPort();
        for (int i = 0; i < 5; i++) {
            // Simulates an app call that iptables REDIRECTed to the outbound port: Host names the real target.
            String raw = "GET /pay/" + i + " HTTP/1.1\r\nHost: 127.0.0.1:" + backendPort + "\r\nConnection: close\r\n\r\n";
            try (Socket s = new Socket("127.0.0.1", cfg.outboundPort())) {
                s.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
                String resp = new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(resp.startsWith("HTTP/1.1 200"), resp);
                assertTrue(resp.endsWith("hello /pay/" + i), resp);
            }
        }
        assertEquals(5, sidecar.metrics.requestCount("outbound"));
        assertTrue(sidecar.metrics.render().contains("rpe_requests_total{service=\"test-svc\",pod=\"unknown-pod\",direction=\"outbound\",outcome=\"ok\"} 5"));
    }

    @Test
    void outboundToDeadUpstreamReturns502() throws Exception {
        String raw = "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + freePort() + "\r\nConnection: close\r\n\r\n";
        try (Socket s = new Socket("127.0.0.1", cfg.outboundPort())) {
            s.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            String resp = new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(resp.startsWith("HTTP/1.1 502"), resp);
        }
    }

    @Test
    void policyApiStagesActivatesAndRetires() throws Exception {
        String admin = "http://127.0.0.1:" + cfg.adminPort();
        assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(admin + "/rpe/v1/policy/active")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "no active policy yet");
        HttpResponse<String> put = client.send(HttpRequest.newBuilder(URI.create(admin + "/rpe/v1/policy"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"epoch\":\"e1\",\"version\":1,\"hash\":\"h1\",\"state\":\"staged\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode());
        assertTrue(put.body().contains("\"state\":\"staged\""), put.body());
        HttpResponse<String> act = client.send(HttpRequest.newBuilder(URI.create(admin + "/rpe/v1/policy/activate"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"epoch\":\"e1\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(act.body().contains("\"activeEpoch\":\"e1\""), act.body());
        HttpResponse<String> active = client.send(HttpRequest.newBuilder(URI.create(admin + "/rpe/v1/policy/active")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, active.statusCode());
        assertTrue(active.body().contains("\"epoch\":\"e1\""), active.body());
        HttpResponse<String> del = client.send(HttpRequest.newBuilder(URI.create(admin + "/rpe/v1/policy/e1")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, del.statusCode(), "the active epoch cannot be retired");
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(admin + "/rpe/v1/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
    }

    @Test
    void metricsEndpointServesPrometheusText() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cfg.metricsPort() + "/metrics")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("# TYPE rpe_inflight_requests gauge"));
    }

    @Test
    void gatewayChainCountersAreCappedPerRoute() {
        Metrics m = new Metrics("gw", "gw-0");
        m.chain("GET", "/api/orders", false);
        m.chain("GET", "/api/orders", true);
        for (int i = 0; i < 100; i++) {
            m.chain("GET", "/x/" + i, false);
        }
        String text = m.render();
        assertTrue(text.contains("rpe_chain_requests_total{service=\"gw\",pod=\"gw-0\",method=\"GET\",route=\"/api/orders\"} 2"), text);
        assertTrue(text.contains("rpe_chain_deadline_exceeded_total{service=\"gw\",pod=\"gw-0\",method=\"GET\",route=\"/api/orders\"} 1"), text);
        assertTrue(text.contains("route=\"other\""), "routes beyond the cap are folded into other");
        assertTrue(text.lines().filter(l -> l.startsWith("rpe_chain_requests_total")).count() <= 65);
    }

    @Test
    void outboundTargetParsing() {
        var abs = ProxyHandler.outboundTarget(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://payment-svc:8080/pay?a=1"));
        assertEquals("payment-svc", abs.address().getHostString());
        assertEquals(8080, abs.address().getPort());
        assertEquals("/pay?a=1", abs.uri());
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/x");
        req.headers().set("Host", "inventory-svc");
        assertEquals(80, ProxyHandler.outboundTarget(req).address().getPort());
        assertThrows(IllegalArgumentException.class,
                () -> ProxyHandler.outboundTarget(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/nohost")));
    }

    @Test
    void configValidation() {
        assertThrows(IllegalArgumentException.class, () -> SidecarConfig.from(Map.of("RPE_ROLE", "edge")));
        assertThrows(IllegalArgumentException.class, () -> SidecarConfig.from(Map.of("RPE_APP_PORT", "70000")));
        SidecarConfig d = SidecarConfig.from(Map.of());
        assertEquals(15001, d.outboundPort());
        assertEquals(SidecarConfig.InboundMode.REDIRECT, d.inboundMode());
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
