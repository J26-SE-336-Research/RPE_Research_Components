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
        double duration = 1000; double failures; double maximum = 1000; double age;
        ExistingTelemetry() { super(connections); }
        public Double prometheus(String query, Instant end) {
            if (query.contains("sidecar_original_requests_total") || query.contains("sidecar_retry_attempts_total")
                    || query.contains("sidecar_successful_retries_total") || query.contains("sidecar_rejected_requests_total")) return null;
            if (query.contains("sidecar_gateway_deadline_budget_milliseconds")) {
                assertFalse(query.contains("service="), "Budget gauge does not carry a service label");
                if (query.contains("timestamp")) return age;
                return query.startsWith("max(") ? maximum : 1000.0;
            }
            if (query.contains("sidecar_gateway_request_chains_deadline_exceeded_total")) return failures;
            if (query.contains("sidecar_gateway_request_chains_completed_total")) return 100.0;
            if (query.contains("sidecar_service_request_duration_seconds_bucket")) return 100.0;
            if (query.contains("sidecar_route_requests_failed_total")) return 0.0;
            if (query.contains("sidecar_route_requests_successful_total")) return 100.0 / 300;
            if (query.contains("sidecar_route_requests_in_flight")) return 1.0;
            if (query.contains("timestamp")) return 0.0;
            if (query.contains("count_over_time")) return 20.0;
            if (query.contains("min_over_time")) return 1.0;
            if (query.contains("sidecar_route_requests_total")) return 100.0;
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
            long end = start + (long) (duration * 1_000_000);
            return json.readTree("""
                {"trace":{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api-gateway"}}]},
                "scopeSpans":[{"spans":[{"spanId":"gateway","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"%d","endTimeUnixNano":"%d",
                "attributes":[{"key":"rrpe.chain.route","value":{"stringValue":"/api/orders"}},{"key":"http.request.method","value":{"stringValue":"POST"}},
                {"key":"rrpe.deadline.budget_ms","value":{"intValue":"1000"}}]},
                {"spanId":"client","parentSpanId":"gateway","kind":"SPAN_KIND_CLIENT","startTimeUnixNano":"%d","endTimeUnixNano":"%d",
                "attributes":[{"key":"rrpe.proxy.direction","value":{"stringValue":"outbound"}},{"key":"peer.service","value":{"stringValue":"order-service"}}]}]}]},
                {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"order-service"}}]},"scopeSpans":[{"spans":[
                {"spanId":"server","parentSpanId":"client","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"%d","endTimeUnixNano":"%d","attributes":[]}]}]}]},"status":"COMPLETE"}
                """.formatted(start, end, start + 100_000_000, end - 50_000_000, start + 150_000_000, end - 100_000_000));
        }
    }
    @Test void existingComponent2TelemetryProducesAnApprovalWithoutManualPolicyOrRetryMetrics() {
        var http = new ExistingTelemetry(); var workflow = new PolicyWorkflow(connections, settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock); var prometheus = new PrometheusWindows(http, connections, settings);
        var tempo = new TempoWindows(http, connections, settings);
        var collector = new AdaptiveCollector(connections, engine, prometheus, tempo, new DeadlineHealth(settings, connections, clock), workflow, clock);
        for (int i = 0; i < 9; i++) { collector.collect(); clock.next(60); }
        String target = "POST /api/orders";
        assertEquals(1000, workflow.current(target, Source.TEMPO).deadlineMs());
        assertTrue(engine.assessments().stream().filter(a -> a.source() == Source.PROMETHEUS).allMatch(a -> a.health() == Health.INSUFFICIENT_DATA),
                "Full service classification remains incomplete without retries, independently of deadlines");
        clock.next(300); http.duration = 1200; http.failures = 20;
        collector.collect(); assertTrue(workflow.list().isEmpty(), "First failing cycle alone cannot recommend a deadline");
        clock.next(60); collector.collect();
        var proposed = workflow.list().get(0);
        assertEquals(1440, proposed.proposal().proposedValue());
        assertEquals(Decision.PENDING_APPROVAL, proposed.decision());
        assertEquals(100, proposed.proposal().measurements().get("completeTraceCount"));
        assertEquals(100, proposed.proposal().measurements().get("prometheusRequestCount"));
        assertEquals(10, proposed.proposal().measurements().get("traceCountTolerance"));
        assertTrue(proposed.proposal().baseVersion().startsWith("prometheus-config:"));
        assertTrue(workflow.deliverable().isEmpty(), "No Component 3 or approval is needed to calculate; unapproved changes are not sent");
        clock.next(60); http.maximum = 2000; collector.collect();
        assertEquals(Delivery.SUPERSEDED, workflow.find(proposed.proposal().id()).delivery(), "Mixed replica budgets invalidate approval candidates");
    }
    @Test void missingStaleAndConflictingGaugeValuesAreRejected() {
        var http = new ExistingTelemetry(); var tempo = new TempoWindows(http, connections, settings);
        assertEquals(1000, tempo.configuredDeadline("POST", "/api/orders", clock.instant()));
        http.maximum = 2000; assertNull(tempo.configuredDeadline("POST", "/api/orders", clock.instant()));
        http.maximum = 1000; http.age = 31; assertNull(tempo.configuredDeadline("POST", "/api/orders", clock.instant()));
        var missing = new TelemetryHttp(connections) { public Double prometheus(String query, Instant time) { return null; } };
        assertNull(new TempoWindows(missing, connections, settings).configuredDeadline("POST", "/api/orders", clock.instant()));
    }
    @Test void confirmedAppliedPolicyIsNeverOverwrittenByAStaleStartupGauge() {
        var workflow = new PolicyWorkflow(connections, settings, clock);
        String target = "POST /api/orders | api-gateway > order-service";
        workflow.putPolicy(new Policy(target, "component3-v2", clock.now.plusSeconds(3600), null, null, null, 1440.0), Source.TEMPO);
        assertFalse(workflow.observeConfiguredDeadline(target, 1000, clock.instant()));
        assertEquals(1440, workflow.current(target, Source.TEMPO).deadlineMs());
    }
}
