package com.example.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The per-destination metrics show the adaptive concurrency and the breaker as they are; reading them changes nothing. */
class BackpressurePeerMetricsTest {
    private static String peerMetric(BackpressureController controller, String name, String peer) {
        String prefix = name + "{peer=\"" + peer + "\"} ";
        return controller.prometheusText().lines()
                .filter(line -> line.startsWith(prefix))
                .findFirst().orElseThrow(() -> new AssertionError("no " + prefix)).substring(prefix.length());
    }

    @Test
    void noPeerSeriesBeforeAnyOutboundCall() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        String text = controller.prometheusText();
        assertTrue(text.contains("# TYPE rpe_backpressure_peer_concurrency_limit gauge"), "the families are declared");
        assertFalse(text.contains("rpe_backpressure_peer_concurrency_limit{"), "but have no samples yet");
    }

    @Test
    void aPressureSignalShowsTheCutLimit() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        String peer = "payment-svc:8080";
        controller.recordResponse(peer, 200, true, 50, false);

        assertEquals("16", peerMetric(controller, "rpe_backpressure_peer_concurrency_limit", peer), "32 x (100 - 50) / 100");
        assertEquals("32", peerMetric(controller, "rpe_backpressure_peer_healthy_concurrency_limit", peer));
        assertEquals("1", peerMetric(controller, "rpe_backpressure_peer_pressure_active", peer));
        assertEquals("50", peerMetric(controller, "rpe_backpressure_peer_reported_utilization_percent", peer));
        assertEquals("0", peerMetric(controller, "rpe_backpressure_peer_in_flight", peer));
        assertEquals("0", peerMetric(controller, "rpe_backpressure_peer_queued", peer));
        assertEquals("0", peerMetric(controller, "rpe_circuit_breaker_peer_state", peer), "closed");

        assertEquals(peerMetric(controller, "rpe_backpressure_peer_concurrency_limit", peer),
                peerMetric(controller, "rpe_backpressure_peer_concurrency_limit", peer), "reading the metrics changes nothing");
    }

    @Test
    void tenFailuresShowAnOpenBreakerForThatPeerOnly() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        String failing = "payment-svc:8080", healthy = "inventory-svc:8080";
        controller.recordResponse(healthy, 200, false, null, false);
        for (int i = 0; i < 10; i++) controller.recordResponse(failing, 503, true, 95, false);

        assertEquals("1", peerMetric(controller, "rpe_circuit_breaker_peer_state", failing), "open");
        assertEquals("0", peerMetric(controller, "rpe_circuit_breaker_peer_state", healthy), "the other destination stays closed");
        assertTrue(controller.prometheusText().contains("rpe_circuit_breaker_open_peers 1\n"));
    }
}
