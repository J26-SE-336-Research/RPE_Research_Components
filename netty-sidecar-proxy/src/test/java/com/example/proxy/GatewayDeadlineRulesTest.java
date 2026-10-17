package com.example.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayDeadlineRulesTest {
    @Test
    void policyRouteDeadlinesOverrideConfiguredRules() {
        GatewayDeadlineRules configured = GatewayDeadlineRules.parse("GET /api/orders=1000;POST /api/orders=2000");
        GatewayDeadlineRules effective = configured.overriddenBy(Map.of("GET /api/orders", 120L, "bad", 5L,
                "GET /api/items/{id}", 300L));
        assertEquals(120, effective.select("GET", "/api/orders", 5).deadlineMillis());
        assertEquals(2000, effective.select("POST", "/api/orders", 5).deadlineMillis());
        assertEquals(300, effective.select("GET", "/api/items/7", 5).deadlineMillis());
        assertEquals("/api/items/{id}", effective.select("GET", "/api/items/7", 5).route());
        assertEquals(5, effective.select("GET", "/other", 5).deadlineMillis());
        assertEquals(1000, configured.select("GET", "/api/orders", 5).deadlineMillis(), "the configured rules are unchanged");
    }

    @Test
    void metricBudgetsListEachRouteOnce() {
        GatewayDeadlineRules effective = GatewayDeadlineRules.parse("GET /api/orders=1000")
                .overriddenBy(Map.of("GET /api/orders", 120L));
        assertEquals(1, effective.metricBudgets().size());
        assertEquals(120, effective.metricBudgets().get(0).deadlineMillis());
    }
}
