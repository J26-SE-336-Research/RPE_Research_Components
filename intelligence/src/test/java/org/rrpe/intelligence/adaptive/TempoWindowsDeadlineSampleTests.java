package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Deadline v2 additions to TempoWindows: status, expiry event, exclusions and "cut" requests (guide §5.1, §5.3, §5.4). */
class TempoWindowsDeadlineSampleTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final TempoWindows tempo = new TempoWindows(new TelemetryHttp(AdaptiveEngineTests.connections("")),
            AdaptiveEngineTests.connections(""), AdaptiveEngineTests.settings());

    private String trace(String rootExtraAttributes, String rootEvents) {
        return """
                {"trace":{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api-gateway"}}]},"scopeSpans":[{"spans":[
                {"spanId":"gateway","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1000000000","endTimeUnixNano":"2200000000","events":[%s],
                "attributes":[{"key":"rpe.chain.route","value":{"stringValue":"/api/orders"}},{"key":"http.request.method","value":{"stringValue":"POST"}},
                {"key":"rpe.deadline.budget_ms","value":{"intValue":"1000"}}%s]},
                {"spanId":"client","parentSpanId":"gateway","kind":"SPAN_KIND_CLIENT","startTimeUnixNano":"1100000000","endTimeUnixNano":"2100000000",
                "attributes":[{"key":"rpe.direction","value":{"stringValue":"outbound"}},{"key":"peer.service","value":{"stringValue":"order-service"}}]}
                ]}]},{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"order-service"}}]},"scopeSpans":[{"spans":[
                {"spanId":"server","parentSpanId":"client","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1200000000","endTimeUnixNano":"2000000000","attributes":[]}
                ]}]}]},"status":"COMPLETE"}
                """.formatted(rootEvents, rootExtraAttributes);
    }
    private static TempoWindows.TraceSample sample(double ms, Integer status, boolean event) {
        return new TempoWindows.TraceSample("t" + ms + status, "/api/orders", "GET", List.of("gateway", "payment", "order"), ms, 0, 5_000_000_000L,
                1000.0, 0, status, event);
    }

    @Test void parsesTheRootStatusAndTheProxyDeadlineEvent() {
        var plain = tempo.parseTrace("a", json.readTree(trace(",{\"key\":\"http.response.status_code\",\"value\":{\"intValue\":\"200\"}}", "")));
        assertEquals(200, plain.status());
        assertFalse(plain.deadlineEvent());
        var expired = tempo.parseTrace("b", json.readTree(trace("", "{\"name\":\"deadline.exceeded\",\"attributes\":[]}")));
        assertNull(expired.status(), "a missing status stays unknown");
        assertTrue(expired.deadlineEvent());
        assertEquals(1200, expired.durationMs(), "existing parsing is unchanged");
    }
    @Test void booleanAndDoubleAttributesAreReadWithoutBreakingOthers() {
        var parsed = tempo.parseTrace("c", json.readTree(trace(",{\"key\":\"rpe.extra\",\"value\":{\"boolValue\":true}},{\"key\":\"rpe.d\",\"value\":{\"doubleValue\":1.5}}", "")));
        assertEquals(1000, parsed.deadlineMs());
    }
    @Test void expiryUsesTheEventAndFallsBackToDurationAtOrAboveTheBudget() {
        var set = TempoWindows.deadlineSamples(List.of(sample(900, 200, true), sample(1000, 200, false), sample(999, 200, false)));
        var byMs = new HashMap<Double, DeadlineSample>(); set.samples().forEach(s -> byMs.put(s.durationMs(), s));
        assertTrue(byMs.get(900.0).expired()); assertFalse(byMs.get(900.0).expiredInferred(), "the proxy said so");
        assertTrue(byMs.get(1000.0).expired()); assertTrue(byMs.get(1000.0).expiredInferred(), "duration >= budget");
        assertFalse(byMs.get(999.0).expired());
        assertEquals(new TreeSet<>(List.of("gateway", "order", "payment")), byMs.get(900.0).path(), "path is a sorted set");
        assertEquals("gateway>order>payment", byMs.get(900.0).stratum());
    }
    @Test void backpressureAndClientErrorsAreExcludedButSlowServerErrorsAreKept() {
        var set = TempoWindows.deadlineSamples(List.of(sample(5, 503, false), sample(20, 429, false), sample(30, 404, false),
                sample(1500, 500, false), sample(400, null, false)));
        assertEquals(Map.of("BACKPRESSURE_503", 1, "CLIENT_4XX", 2), set.excluded());
        assertEquals(2, set.samples().size());
        var slow = set.samples().stream().filter(s -> s.status() == 500).findFirst().orElseThrow();
        assertFalse(slow.cut()); assertTrue(slow.expired(), "a slow 5xx is real timing data");
    }
    @Test void requestsCutByOtherTimersAreMarkedWithAHint() {
        var set = TempoWindows.deadlineSamples(List.of(sample(990, 504, false), sample(1040, 504, false), sample(300, 504, false),
                sample(200, 499, false), sample(100, 502, false)));
        var hints = set.samples().stream().map(DeadlineSample::cutHint).toList();
        assertEquals(List.of("REJECTING_MIDDLEWARE", "REJECTING_MIDDLEWARE", "OUTER_TIMEOUT_OR_CLIENT", "OUTER_TIMEOUT_OR_CLIENT", "ABNORMAL_END"), hints);
        assertTrue(set.samples().stream().allMatch(DeadlineSample::cut));
    }
    @Test void tracesWithoutABudgetAreExcluded() {
        var noBudget = new TempoWindows.TraceSample("x", "/r", "GET", List.of("gateway"), 10, 0, 1, null);
        assertEquals(Map.of("NO_BUDGET", 1), TempoWindows.deadlineSamples(List.of(noBudget)).excluded());
    }
    @Test void perRequestSamplesStayOutOfTheJsonApi() {
        var window = new ChainWindow("GET /r", List.of("gateway"), null, null, Source.TEMPO, true, 1.0, 0.0, 1.0, Health.HEALTHY, List.of());
        var collection = new TempoWindows.Collection(window, 1000.0, List.of(), List.of(), null,
                TempoWindows.deadlineSamples(List.of(sample(900, 200, false))));
        var text = json.writeValueAsString(collection);
        assertFalse(text.contains("samples"), text);
        assertTrue(text.contains("currentDeadlineMs"));
    }
}
