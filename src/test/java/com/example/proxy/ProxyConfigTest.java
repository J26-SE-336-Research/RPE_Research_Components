package com.example.proxy;

import org.junit.jupiter.api.Test;

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
    }
}
