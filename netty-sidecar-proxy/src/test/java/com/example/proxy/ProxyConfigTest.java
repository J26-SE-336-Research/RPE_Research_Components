package com.example.proxy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProxyConfigTest {
    @Test
    void recordStoresConfiguration() {
        ProxyConfig config = new ProxyConfig("0.0.0.0", 8080, "0.0.0.0", 9090, 5000, 20000, true, false,
                GatewayDeadlineRules.parse(""), "test-service", "", 100.0, 200, 200,
                5, 5000, "", new int[]{50, 65, 80, 95}, 30);

        assertEquals("0.0.0.0", config.listenHost());
        assertEquals(8080, config.listenPort());
        assertEquals(5000, config.defaultDeadlineMillis());
        assertTrue(config.detectInboundDeadline());
        assertEquals(0, config.integration().inboundListenPort(), "standalone: one TPROXY listener");
    }

    @Test
    void standaloneDefaultsAreUnchanged() {
        ProxyConfig config = ProxyConfig.from(Map.of());
        assertEquals(8080, config.listenPort());
        assertEquals(9090, config.metricsListenPort());
        assertEquals(5000, config.defaultDeadlineMillis());
        assertFalse(config.gatewaySidecar());
        assertEquals(0, config.integration().inboundListenPort());
        assertEquals(0, config.integration().adminListenPort());
        assertTrue(config.integration().missingContextPassthrough(), "requests without context pass through by default");
        assertEquals(1000, config.outboundQueueCapacity(), "outbound waiting queue per destination (C2 owner, 21 Oct 2026)");
        assertEquals(500, ProxyConfig.from(Map.of("BACKPRESSURE_OUTBOUND_QUEUE_CAPACITY", "500")).outboundQueueCapacity(),
                "the environment still overrides it");
    }

    @Test
    void pressureSignalsStartAt70PercentByDefault() {
        ProxyConfig config = ProxyConfig.from(Map.of());
        assertArrayEquals(new int[]{70, 80, 90, 95}, config.backpressureSignalThresholdsPercent(), "C2 owner, 22 Oct 2026");
        assertEquals(50, config.backpressureSignalClearPercent(), "the clear signal at 50 % (C2 owner, 22 Oct 2026; was 30)");
        assertArrayEquals(new int[]{50, 65, 80, 95}, ProxyConfig.from(Map.of("BACKPRESSURE_SIGNAL_THRESHOLDS_PERCENT", "50,65,80,95", "BACKPRESSURE_SIGNAL_CLEAR_PERCENT", "30"))
                .backpressureSignalThresholdsPercent(), "the environment still overrides it (the clear level must stay below the first level)");
        assertThrows(IllegalArgumentException.class, () -> ProxyConfig.from(Map.of("BACKPRESSURE_SIGNAL_THRESHOLDS_PERCENT", "50,65,80,95")),
                "levels starting at 50 % need a clear level below 50 % now that the default clear is 50 %");
    }

    @Test
    void theGatewayReleasesFasterAndHoldsMoreByDefault() {
        assertEquals(100.0, ProxyConfig.from(Map.of("RPE_ROLE", "service")).inboundRequestsPerSecond(), "a service sidecar");
        assertEquals(300.0, ProxyConfig.from(Map.of("RPE_ROLE", "gateway")).inboundRequestsPerSecond(), "the gateway sidecar");
        assertEquals(300.0, ProxyConfig.from(Map.of("PROXY_ROLE", "gateway")).inboundRequestsPerSecond());
        assertEquals(50.0, ProxyConfig.from(Map.of("RPE_ROLE", "gateway", "BACKPRESSURE_INBOUND_RATE_PER_SECOND", "50")).inboundRequestsPerSecond(),
                "the environment still overrides it");
        assertEquals(1000, ProxyConfig.from(Map.of("RPE_ROLE", "gateway")).inboundQueueCapacity(), "the gateway holds more waiting requests");
        assertEquals(200, ProxyConfig.from(Map.of("RPE_ROLE", "service")).inboundQueueCapacity(), "a service sidecar keeps 200");
        assertEquals(300, ProxyConfig.from(Map.of("RPE_ROLE", "gateway", "BACKPRESSURE_INBOUND_QUEUE_CAPACITY", "300")).inboundQueueCapacity(),
                "the environment still overrides it");
    }

    @Test
    void webhookEnvironmentSelectsTheRpeSidecarPorts() {
        ProxyConfig config = ProxyConfig.from(Map.of(
                "RPE_ROLE", "gateway", "RPE_SERVICE_NAME", "gateway", "RPE_INBOUND_MODE", "redirect",
                "RPE_APP_PORT", "8080", "RPE_DEFAULT_DEADLINE_MS", "1000", "RPE_POD_NAME", "gateway-abc",
                "RPE_MISSING_CONTEXT", "passthrough", "RPE_ROUTE_DEADLINES", "GET /api/orders=1000"));
        assertTrue(config.gatewaySidecar());
        assertEquals("gateway", config.serviceName());
        assertEquals(15001, config.listenPort());
        assertEquals(15090, config.metricsListenPort());
        assertEquals(1000, config.defaultDeadlineMillis());
        assertEquals(15006, config.integration().inboundListenPort());
        assertFalse(config.integration().inboundTransparent());
        assertEquals(8080, config.integration().appPort());
        assertEquals(15020, config.integration().adminListenPort());
        assertEquals("gateway-abc", config.integration().podName());
        assertTrue(config.integration().missingContextPassthrough());
        assertEquals(1000, config.gatewayDeadlineRules().select("GET", "/api/orders", 5).deadlineMillis());
    }

    @Test
    void proxyVariablesWinOverWebhookVariables() {
        ProxyConfig config = ProxyConfig.from(Map.of("RPE_ROLE", "gateway", "PROXY_ROLE", "service",
                "RPE_DEFAULT_DEADLINE_MS", "1000", "DEFAULT_DEADLINE_MILLIS", "2500", "PROXY_LISTEN_PORT", "8080"));
        assertFalse(config.gatewaySidecar());
        assertEquals(2500, config.defaultDeadlineMillis());
        assertEquals(8080, config.listenPort());
        assertTrue(config.integration().inboundTransparent(), "TPROXY is the default inbound mode");
    }

    @Test
    void strictModeAnswersMissingContextWith400() {
        assertFalse(ProxyConfig.from(Map.of("PROXY_MISSING_CONTEXT", "reject")).integration().missingContextPassthrough());
        assertFalse(ProxyConfig.from(Map.of("RPE_ROLE", "service", "RPE_MISSING_CONTEXT", "reject"))
                .integration().missingContextPassthrough());
    }

    @Test
    void invalidModesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProxyConfig.from(Map.of("PROXY_INBOUND_MODE", "nat")));
        assertThrows(IllegalArgumentException.class, () -> ProxyConfig.from(Map.of("PROXY_MISSING_CONTEXT", "drop")));
    }
}
