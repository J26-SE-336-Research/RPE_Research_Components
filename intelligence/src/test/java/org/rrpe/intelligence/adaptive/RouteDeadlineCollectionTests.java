package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RouteDeadlineCollectionTests {
    private final AdaptiveEngineTests.TestClock clock = new AdaptiveEngineTests.TestClock();
    private final AdaptiveConfig.Connections connections = new AdaptiveConfig.Connections(false, "http://localhost:9090", "http://localhost:3200",
            "default", List.of("order-service", "payment-service"), "api-gateway", Duration.ofSeconds(1), Duration.ofSeconds(15),
            Duration.ofSeconds(30), 5000, "", "", Map.of());
    private final AdaptiveConfig.Settings settings = AdaptiveEngineTests.settings();
    private final Map<String, Health> healthy = Map.of("api-gateway", Health.HEALTHY, "order-service", Health.HEALTHY, "payment-service", Health.HEALTHY);
    private class FixtureHttp extends TelemetryHttp {
        double count = 100, failures = 0;
        int traceCount = 100, completedJobs = 1;
        boolean degraded, invalidTrace, oldBudget, separateMethods, crossingWindow;
        FixtureHttp() { super(connections); }
        public Double prometheus(String query, Instant end) {
            if (query.contains("rpe_chain_requests_total")) return count;
            if (query.contains("rpe_chain_deadline_exceeded_total")) return failures;
            throw new AssertionError(query);
        }
        public JsonNode get(String url) {
            var json = JsonMapper.builder().build();
            if (!url.contains("/api/search")) return json.createObjectNode();
            var result = json.createObjectNode(); var traces = result.putArray("traces");
            for (int i = 0; i < traceCount; i++) traces.addObject().put("traceID", String.format("%032x", i + 1));
            result.putObject("metrics").put("totalJobs", 1).put("completedJobs", completedJobs);
            return result;
        }
        TempoWindows tempo() {
            return new TempoWindows(this, connections, settings) {
                public TraceSample parseTrace(String id, JsonNode unused) {
                    int index = Integer.parseInt(id, 16) - 1;
                    if (invalidTrace && index == 0) throw new IllegalArgumentException("Partial trace");
                    List<String> services = index < 90 ? List.of("api-gateway", "order-service") : List.of("api-gateway", "order-service", "payment-service");
                    double duration = degraded ? index < 90 ? 900 : 1200 : 1000;
                    long end = clock.instant().minusSeconds(crossingWindow ? 299 : 1).getEpochSecond() * 1_000_000_000L;
                    return new TraceSample(id, "/api/orders", separateMethods && index >= 90 ? "GET" : "POST", services,
                            duration, end - (long) (duration * 1_000_000), end, oldBudget && index == 0 ? 900.0 : 1000.0);
                }
            };
        }
    }
    @Test void coverageAllowsExtrapolationButRejectsLargeGapsInEitherDirection() {
        assertTrue(TempoWindows.sufficientCoverage(100, 100.0));
        assertTrue(TempoWindows.sufficientCoverage(10, 9.8));
        assertTrue(TempoWindows.sufficientCoverage(100, 102.0));
        assertTrue(TempoWindows.sufficientCoverage(90, 100.0));
        assertFalse(TempoWindows.sufficientCoverage(89, 100.0));
        assertFalse(TempoWindows.sufficientCoverage(111, 100.0));
        assertFalse(TempoWindows.sufficientCoverage(0, 1.0));
        assertFalse(TempoWindows.sufficientCoverage(100, null));
        assertFalse(TempoWindows.sufficientCoverage(100, Double.NaN));
        assertFalse(TempoWindows.sufficientCoverage(100, Double.POSITIVE_INFINITY));
    }
    // the engine decides from the per-request samples (Case A), not the 10/20/30% bands
    @Test void poolsDifferentPathsOnceAndProducesOneEnforceableRouteDeadline() {
        var http = new FixtureHttp(); var tempo = http.tempo();
        var workflow = new PolicyWorkflow(connections, settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureDeadline(AdaptiveConfig.DeadlineSettings.demo(), null, null);
        for (int i = 0; i < settings.baselineSamples(); i++) {
            var data = tempo.collect(clock.instant(), healthy).get(0);
            workflow.observeConfiguredDeadline(data.window().chain(), data.currentDeadlineMs(), clock.instant());
            engine.chain(data.window(), new ChainEvidence(data.samples(), Map.of(), healthy, Set.of()), Map.of(), List.of()); clock.next(60);
        }
        clock.next(300); http.degraded = true; http.failures = 20;
        for (int i = 0; i < 2; i++) {
            var windows = tempo.collect(clock.instant(), healthy);
            assertEquals(1, windows.size()); var data = windows.get(0);
            assertTrue(data.window().complete()); assertTrue(data.coverage().accepted());
            assertEquals("POST /api/orders", data.window().chain());
            assertEquals(2, data.paths().size());
            assertEquals(90, data.paths().get(0).traceCount()); assertEquals(10, data.paths().get(1).traceCount());
            assertEquals(900, data.paths().get(0).p99Ms()); assertEquals(1200, data.paths().get(1).p99Ms());
            assertEquals(1200, data.window().p99Ms(), "Pool raw request durations; do not average path p99s (1050) or sum them (2100)");
            assertEquals(100, data.window().requests()); assertEquals(20, data.window().deadlineFailures());
            assertEquals(100, data.samples().samples().size(), "one v2 sample per complete trace");
            workflow.observeConfiguredDeadline(data.window().chain(), data.currentDeadlineMs(), clock.instant());
            engine.chain(data.window(), new ChainEvidence(data.samples(), Map.of(), healthy, Set.of()), Map.of(), List.of()); clock.next(60);
        }
        assertEquals(1, workflow.list().size());
        var proposal = workflow.list().get(0);
        assertEquals("POST /api/orders", proposal.proposal().target());
        // every learning request took the whole 1000 ms budget, so the healthy time is hidden: raise by half
        assertEquals(1500, proposal.proposal().proposedValue());
        assertEquals(Decision.PENDING_APPROVAL, proposal.decision());
        assertTrue(workflow.deliverable().isEmpty());
    }
    @Test void routeCoverageUsesToleranceAndDoesNotHideIncompleteSearchesOrBudgetChanges() {
        var http = new FixtureHttp(); var tempo = http.tempo();
        http.count = 102;
        assertTrue(tempo.collect(clock.instant(), healthy).get(0).window().complete());
        http.count = 120;
        assertFalse(tempo.collect(clock.instant(), healthy).get(0).window().complete());
        http.count = 100; http.completedJobs = 0;
        assertFalse(tempo.collect(clock.instant(), healthy).get(0).window().complete());
        http.completedJobs = 1; http.invalidTrace = true;
        assertFalse(tempo.collect(clock.instant(), healthy).get(0).window().complete(), "Even one known invalid trace cannot be excused by count tolerance");
        http.invalidTrace = false; http.oldBudget = true;
        assertFalse(tempo.collect(clock.instant(), healthy).get(0).window().complete());
        http.oldBudget = false; http.traceCount = connections.traceLimit(); http.count = http.traceCount;
        assertFalse(tempo.collect(clock.instant(), healthy).get(0).window().complete(), "Reaching the search limit remains incomplete even with matching counts");
    }
    @Test void alternatePathHealthIsIncludedAndHttpMethodsRemainSeparate() {
        var http = new FixtureHttp(); var tempo = http.tempo();
        assertEquals(Health.INSUFFICIENT_DATA, tempo.collect(clock.instant(), Map.of("api-gateway", Health.HEALTHY, "order-service", Health.HEALTHY)).get(0).window().health());
        assertEquals(Health.OVERLOADED, tempo.collect(clock.instant(), Map.of("api-gateway", Health.HEALTHY, "order-service", Health.HEALTHY, "payment-service", Health.OVERLOADED)).get(0).window().health());
        http.separateMethods = true;
        var windows = tempo.collect(clock.instant(), healthy);
        assertEquals(Set.of("POST /api/orders", "GET /api/orders"), new HashSet<>(windows.stream().map(data -> data.window().chain()).toList()));
    }
    @Test void hopsWithoutASidecarAreReportedButDoNotBlockChainHealth() {
        var http = new FixtureHttp(); var tempo = http.tempo();
        var unmonitored = new TempoWindows(http, new AdaptiveConfig.Connections(false, "http://localhost:9090", "http://localhost:3200",
                "default", List.of("order-service"), "api-gateway", Duration.ofSeconds(1), Duration.ofSeconds(15),
                Duration.ofSeconds(30), 5000, "", "", Map.of()), settings) {
            public TraceSample parseTrace(String id, JsonNode unused) { return tempo.parseTrace(id, unused); }
        };
        var data = unmonitored.collect(clock.instant(), Map.of("api-gateway", Health.HEALTHY, "order-service", Health.HEALTHY)).get(0);
        assertEquals(Health.HEALTHY, data.window().health());
        assertTrue(data.problems().stream().anyMatch(p -> p.contains("payment-service")));
    }
    @Test void requestsStartingBeforeWindowButFinishingInsideItAreIncluded() {
        var http = new FixtureHttp(); http.crossingWindow = true; http.degraded = true;
        assertEquals(100, http.tempo().collect(clock.instant(), healthy).get(0).coverage().traceCount());
    }
}
