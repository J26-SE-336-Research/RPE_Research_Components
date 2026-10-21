package com.example.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The backpressure mode for the comparison experiment: "full" (default) is the complete mechanism; "basic" keeps only
 * the inbound leaky bucket and the circuit breaker (no pressure signals, no per-destination limit or outbound queue).
 */
class BackpressureModeTest {
    private static final String PEER = "payment-svc:8080";

    private static String metric(BackpressureController controller, String series) {
        return controller.prometheusText().lines().filter(line -> line.startsWith(series + " "))
                .findFirst().orElseThrow(() -> new AssertionError("no " + series)).substring(series.length() + 1);
    }

    private static int dispatch(BackpressureController controller, int calls) {
        AtomicInteger started = new AtomicInteger();
        for (int i = 0; i < calls; i++) assertTrue(controller.enqueueOutbound(PEER, started::incrementAndGet, () -> { }));
        return started.get();
    }

    @Test
    void fullIsTheDefaultAndOnlyKnownModesAreAccepted() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        assertEquals("full", controller.mode());
        assertEquals("1", metric(controller, "rpe_backpressure_adaptive_enabled"));
        assertFalse(controller.setMode("off"));
        assertFalse(controller.setMode(null));
        assertEquals("full", controller.mode());
        assertTrue(controller.setMode("basic"));
        assertEquals("basic", controller.mode());
        assertEquals("0", metric(controller, "rpe_backpressure_adaptive_enabled"));
        assertTrue(controller.setMode("full"));
        assertEquals("full", controller.mode());
    }

    @Test
    void fullModeLimitsAndQueuesCallsPerDestination() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        assertEquals(32, dispatch(controller, 40), "the starting limit is 32 calls at once");
        assertTrue(controller.prometheusText().contains("rpe_backpressure_peer_queued{peer=\"" + PEER + "\"} 8\n"));
    }

    @Test
    void basicModeSendsEveryCallWithoutLimitOrQueue() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        controller.setMode("basic");
        assertEquals(40, dispatch(controller, 40), "no per-destination limit");
        assertTrue(controller.prometheusText().contains("rpe_backpressure_peer_queued{peer=\"" + PEER + "\"} 0\n"), "no outbound queue");
    }

    @Test
    void basicModeIgnoresPressureButTheBreakerStillOpens() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        controller.setMode("basic");
        controller.recordResponse(PEER, 200, true, 80, false);
        assertTrue(controller.prometheusText().contains("rpe_backpressure_peer_concurrency_limit{peer=\"" + PEER + "\"} 32\n"),
                "a pressure header does not cut the limit");
        assertEquals("0", metric(controller, "rpe_backpressure_peer_pressure_signals_total"), "and is not counted as a signal");
        for (int i = 0; i < 10; i++) controller.recordResponse(PEER, 503, true, 100, false);
        assertTrue(controller.prometheusText().contains("rpe_circuit_breaker_peer_state{peer=\"" + PEER + "\"} 1\n"),
                "ten failures in a row still open the breaker");
        assertFalse(controller.admitOutbound(PEER).admitted(), "and an open breaker still fails calls fast");
    }

    @Test
    void switchingBackToFullRestoresTheAdaptiveReaction() {
        BackpressureController controller = new BackpressureController(ProxyConfig.from(Map.of()));
        controller.setMode("basic");
        controller.setMode("full");
        controller.recordResponse(PEER, 200, true, 50, false);
        assertTrue(controller.prometheusText().contains("rpe_backpressure_peer_concurrency_limit{peer=\"" + PEER + "\"} 16\n"));
    }
}
