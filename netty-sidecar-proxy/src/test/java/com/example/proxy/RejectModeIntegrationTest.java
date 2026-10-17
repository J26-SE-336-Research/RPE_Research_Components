package com.example.proxy;

import static com.example.proxy.TestHttp.headers;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** PROXY_MISSING_CONTEXT=reject: the strict option answers a request without any request context with 400. */
class RejectModeIntegrationTest {
    private static TestHttp.Backend app;
    private static ProxyServer proxy;
    private static int inboundPort, metricsPort;

    @BeforeAll
    static void start() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(io.netty.channel.epoll.Epoll.isAvailable(),
                "the proxy needs Linux native epoll");
        app = new TestHttp.Backend();
        inboundPort = TestHttp.freePort();
        metricsPort = TestHttp.freePort();
        Map<String, String> env = new HashMap<>();
        env.put("RPE_ROLE", "service");
        env.put("RPE_SERVICE_NAME", "strict-svc");
        env.put("RPE_INBOUND_MODE", "redirect");
        env.put("RPE_APP_PORT", Integer.toString(app.port()));
        env.put("PROXY_MISSING_CONTEXT", "reject");
        env.put("PROXY_LISTEN_HOST", "127.0.0.1");
        env.put("PROXY_LISTEN_PORT", Integer.toString(TestHttp.freePort()));
        env.put("PROXY_INBOUND_LISTEN_PORT", Integer.toString(inboundPort));
        env.put("PROXY_ADMIN_LISTEN_PORT", "0");
        env.put("METRICS_LISTEN_HOST", "127.0.0.1");
        env.put("METRICS_LISTEN_PORT", Integer.toString(metricsPort));
        proxy = new ProxyServer(ProxyConfig.from(env));
    }

    @AfterAll
    static void stop() {
        if (proxy != null) proxy.close();
        if (app != null) app.close();
    }

    @Test
    void requestWithoutContextIsRejected() throws Exception {
        assertEquals(400, TestHttp.send(inboundPort, "GET", "/healthz", headers(), null).status());
        assertTrue(app.drain().isEmpty(), "the application never sees the request");
        String metrics = TestHttp.send(metricsPort, "GET", "/metrics", headers(), null).body();
        assertTrue(metrics.contains("rpe_rejections_total{service=\"strict-svc\",pod=\"unknown-pod\",reason=\"invalid_context\"} 1"), metrics);
        assertTrue(metrics.contains("rpe_passthrough_requests_total{service=\"strict-svc\",pod=\"unknown-pod\",direction=\"inbound\"} 0"), metrics);
    }
}
