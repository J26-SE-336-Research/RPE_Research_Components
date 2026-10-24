package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The load queries of each service, with the real sidecar metric names. */
class PrometheusLoadQueriesTests {
    private final AdaptiveConfig.Connections connections = AdaptiveEngineTests.connections("");
    private class Fixture extends TelemetryHttp {
        boolean failCpu;
        Fixture() { super(connections); }
        @Override public Double prometheus(String query, Instant end) {
            if (query.contains("rpe_backpressure_inbound_queue_utilization_percent")) return 95.0;
            if (query.contains("reason=\"overload\"")) return 3.0;
            if (query.contains("rpe_backpressure_inbound_rejected_total")) return 12.0;
            if (query.contains("rpe_circuit_breaker_open_peers")) return 0.0;
            if (query.contains("container_cpu_usage") && failCpu) throw new IllegalStateException("Ambiguous service query");
            return null; // cAdvisor / kube-state-metrics are not scraped
        }
    }
    @Test void usesTheRealSidecarMetricNamesAndOneSeriesPerQuery() {
        var queries = new PrometheusWindows(new Fixture(), connections, AdaptiveEngineTests.settings()).loadQueries("inventory");
        assertTrue(queries.get("loadQueuePressurePercent").contains("rpe_backpressure_inbound_queue_utilization_percent{service=\"inventory\",namespace=\"default\"}"));
        assertTrue(queries.get("loadOverloadRejections").contains("rpe_rejections_total{service=\"inventory\",namespace=\"default\",reason=\"overload\"}"));
        assertTrue(queries.get("loadBreakerOpenPeers").contains("rpe_circuit_breaker_open_peers"));
        assertTrue(queries.values().stream().allMatch(q -> q.startsWith("max(") || q.startsWith("sum(")), "aggregated to one series");
        assertFalse(queries.toString().contains("cancellation"));
    }
    @Test void pressureStaysInPercentAndMissingSeriesAreUnavailable() {
        var snapshot = new PrometheusWindows(new Fixture(), connections, AdaptiveEngineTests.settings()).collectLoad("inventory", Instant.now());
        assertEquals(95.0, snapshot.queuePressurePercent());
        assertEquals(3.0, snapshot.overloadRejections());
        assertEquals(12.0, snapshot.backpressureRejections());
        assertEquals(0.0, snapshot.breakerOpenPeers());
        assertNull(snapshot.cpuUtilisation()); assertNull(snapshot.cpuThrottling()); assertNull(snapshot.memoryUtilisation());
        assertTrue(snapshot.problems().isEmpty());
    }
    @Test void aFailedLoadQueryNeverThrowsAndNeverTouchesTheServiceWindow() {
        var fixture = new Fixture(); fixture.failCpu = true;
        var windows = new PrometheusWindows(fixture, connections, AdaptiveEngineTests.settings());
        var snapshot = windows.collectLoad("inventory", Instant.now());
        assertNull(snapshot.cpuUtilisation());
        assertEquals(1, snapshot.problems().size());
        assertFalse(windows.queries("inventory").containsKey("loadQueuePressurePercent"), "service-window queries are unchanged");
    }
}
