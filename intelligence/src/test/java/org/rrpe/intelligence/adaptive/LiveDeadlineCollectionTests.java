package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LiveDeadlineCollectionTests {
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private final AdaptiveConfig.Settings settings = AdaptiveEngineTests.settings();
    private final AdaptiveConfig.Connections connections = new AdaptiveConfig.Connections(true, "http://localhost:9090", "http://localhost:3200",
            "default", List.of("api-gateway", "order-service"), "api-gateway", Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofSeconds(30), 5000, "", "", Map.of());
    private class ExistingTelemetry extends TelemetryHttp {
        // a request that takes the whole budget counts as expired, so the healthy chain takes 900 ms, not 1000
        double duration = 900; double failures; boolean mixedBudgets;
        ExistingTelemetry() { super(connections); }
        public Double prometheus(String query, Instant end) {
            // this telemetry has no load data (backpressure, breaker, cAdvisor), so it is unavailable
            if (query.contains("rpe_backpressure") || query.contains("rpe_circuit_breaker") || query.contains("container_") || query.contains("reason=\"overload\"")) return null;
            // retries and proxy rejections are not reported yet: full service classification must wait for them
            if (query.contains("rpe_original_requests_total") || query.contains("rpe_retries_total") || query.contains("rpe_rejections_total")) return null;
            if (query.contains("rpe_chain_deadline_exceeded_total")) return failures;
            if (query.contains("rpe_chain_requests_total")) return 100.0;
            if (query.contains("timestamp")) return 0.0;
            if (query.contains("rpe_request_duration_seconds_bucket")) return 100.0;
            if (query.contains("outcome=\"failed\"")) return 0.0;
            if (query.contains("outcome=\"ok\"")) return 100.0 / 300;
            if (query.contains("rpe_inflight_requests")) return 1.0;
            if (query.contains("count_over_time")) return 20.0;
            if (query.contains("min_over_time")) return 1.0;
            if (query.contains("rpe_requests_total")) return 100.0;
            throw new AssertionError("Unexpected query: " + query);
        }
        public JsonNode get(String url) {
            var json = JsonMapper.builder().build();
            if (url.contains("/api/search")) {
                var root = json.createObjectNode(); var traces = root.putArray("traces");
                for (int i = 0; i < 100; i++) traces.addObject().put("traceID", String.format("%032x", i + 1));
                root.putObject("metrics").put("totalJobs", 1).put("completedJobs", 1); return root;
            }
            long start = (clock.instant().getEpochSecond() - 2) * 1_000_000_000L;
            int budget = mixedBudgets && url.endsWith("1?span_pruning=false") ? 2000 : 1000;
            long end = start + (long) (duration * 1_000_000);
            return json.readTree("""
                {"trace":{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api-gateway"}}]},
                "scopeSpans":[{"spans":[{"spanId":"gateway","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"%d","endTimeUnixNano":"%d",
                "attributes":[{"key":"rpe.chain.route","value":{"stringValue":"/api/orders"}},{"key":"http.request.method","value":{"stringValue":"POST"}},
                {"key":"rpe.deadline.budget_ms","value":{"intValue":"%d"}}]},
                {"spanId":"client","parentSpanId":"gateway","kind":"SPAN_KIND_CLIENT","startTimeUnixNano":"%d","endTimeUnixNano":"%d",
                "attributes":[{"key":"rpe.direction","value":{"stringValue":"outbound"}},{"key":"peer.service","value":{"stringValue":"order-service"}}]}]}]},
                {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"order-service"}}]},"scopeSpans":[{"spans":[
                {"spanId":"server","parentSpanId":"client","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"%d","endTimeUnixNano":"%d","attributes":[]}]}]}]},"status":"COMPLETE"}
                """.formatted(start, end, budget, start + 100_000_000, end - 50_000_000, start + 150_000_000, end - 100_000_000));
        }
    }
    // streaks count separate windows; the hidden healthy time gives Case A, a raise by half
    @Test void existingComponent2TelemetryProducesAnApprovalWithoutManualPolicyOrRetryMetrics() {
        var http = new ExistingTelemetry(); var workflow = new PolicyWorkflow(connections, settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock); var prometheus = new PrometheusWindows(http, connections, settings);
        engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
        var tempo = new TempoWindows(http, connections, settings);
        var collector = new AdaptiveCollector(connections, engine, prometheus, tempo, new DeadlineHealth(settings, connections, clock), workflow, clock);
        for (int i = 0; i < 9; i++) { collector.collect(); clock.next(60); }
        String target = "POST /api/orders";
        assertEquals(1000, workflow.current(target, Source.TEMPO).deadlineMs());
        assertTrue(engine.assessments().stream().filter(a -> a.source() == Source.PROMETHEUS).allMatch(a -> a.health() == Health.INSUFFICIENT_DATA),
                "Full service classification remains incomplete without retries, independently of deadlines");
        clock.next(300); http.duration = 1200; http.failures = 100;
        collector.collect(); assertTrue(workflow.list().isEmpty(), "First failing counted window alone cannot recommend a deadline");
        clock.next(60); collector.collect(); assertTrue(workflow.list().isEmpty(), "An overlapping window is not a second counted window");
        clock.next(240); collector.collect();
        var proposed = workflow.list().get(0);
        assertEquals(1500, proposed.proposal().proposedValue());
        assertEquals(Decision.PENDING_APPROVAL, proposed.decision());
        assertEquals(100, proposed.proposal().measurements().get("completeTraceCount"));
        assertEquals(100, proposed.proposal().measurements().get("prometheusRequestCount"));
        assertEquals(10, proposed.proposal().measurements().get("traceCountTolerance"));
        assertTrue(proposed.proposal().baseVersion().startsWith("prometheus-config:"));
        assertTrue(workflow.deliverable().isEmpty(), "No Component 3 or approval is needed to calculate; unapproved changes are not sent");
        clock.next(60); http.mixedBudgets = true; collector.collect();
        assertEquals(Delivery.SUPERSEDED, workflow.find(proposed.proposal().id()).delivery(), "Mixed replica budgets invalidate approval candidates");
    }
    @Test void routeDeadlineComesFromTheGatewayStampedBudgetAndMixedBudgetsAreRejected() {
        var http = new ExistingTelemetry(); var tempo = new TempoWindows(http, connections, settings);
        var data = tempo.collect(clock.instant(), Map.of("api-gateway", Health.HEALTHY, "order-service", Health.HEALTHY)).get(0);
        assertEquals(1000, data.currentDeadlineMs());
        http.mixedBudgets = true;
        data = tempo.collect(clock.instant(), Map.of("api-gateway", Health.HEALTHY, "order-service", Health.HEALTHY)).get(0);
        assertNull(data.currentDeadlineMs());
        assertFalse(data.window().complete());
    }
    @Test void confirmedAppliedPolicyIsNeverOverwrittenByAStaleStartupGauge() {
        var workflow = new PolicyWorkflow(connections, settings, clock);
        String target = "POST /api/orders | api-gateway > order-service";
        workflow.putPolicy(new Policy(target, "component3-v2", clock.now.plusSeconds(3600), null, null, null, 1440.0), Source.TEMPO);
        assertFalse(workflow.observeConfiguredDeadline(target, 1000, clock.instant()));
        assertEquals(1440, workflow.current(target, Source.TEMPO).deadlineMs());
    }
}
