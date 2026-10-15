package org.rrpe.intelligence.adaptive;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class TempoWindowsTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final TempoWindows tempo = new TempoWindows(new TelemetryHttp(AdaptiveEngineTests.connections("")),
            AdaptiveEngineTests.connections(""), AdaptiveEngineTests.settings());
    private String attributes(String service) {
        return "[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"" + service + "\"}}]";
    }
    private String trace() {
        return """
                {"trace":{"resourceSpans":[{"resource":{"attributes":%s},"scopeSpans":[{"spans":[
                {"spanId":"gateway","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1000000000","endTimeUnixNano":"2200000000",
                "attributes":[{"key":"rrpe.chain.route","value":{"stringValue":"/api/orders"}},{"key":"http.request.method","value":{"stringValue":"POST"}}]},
                {"spanId":"client","parentSpanId":"gateway","kind":"SPAN_KIND_CLIENT","startTimeUnixNano":"1100000000","endTimeUnixNano":"2100000000",
                "attributes":[{"key":"rrpe.proxy.direction","value":{"stringValue":"outbound"}},{"key":"peer.service","value":{"stringValue":"order-service"}}]}
                ]}]},{"resource":{"attributes":%s},"scopeSpans":[{"spans":[
                {"spanId":"server","parentSpanId":"client","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1200000000","endTimeUnixNano":"2000000000","attributes":[]}
                ]}]}]},"status":"COMPLETE"}
                """.formatted(attributes("api-gateway"), attributes("order-service"));
    }
    @Test void p99UsesCompleteRootDurationsNotTheSumOfServicePercentiles() {
        var sample = tempo.parseTrace("example", json.readTree(trace()));
        assertEquals(1200, sample.durationMs());
        assertEquals(java.util.List.of("api-gateway", "order-service"), sample.services());
        assertEquals(99, TempoWindows.percentile99(java.util.stream.IntStream.rangeClosed(1, 100).asDoubleStream().toArray()));
    }
    @Test void missingParentPartialTraceAndMissingDownstreamEvidenceAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> tempo.parseTrace("example", json.readTree(trace().replace("\"parentSpanId\":\"client\"", "\"parentSpanId\":\"missing\""))));
        assertThrows(IllegalArgumentException.class, () -> tempo.parseTrace("example", json.readTree(trace().replace("\"COMPLETE\"", "\"PARTIAL\""))));
        assertThrows(IllegalArgumentException.class, () -> tempo.parseTrace("example", json.readTree(trace().replace(attributes("order-service"), attributes("wrong-service")))));
    }
    @Test void invalidOrUnfinishedSpanDurationsCannotEnterDeadlineEvidence() {
        assertThrows(IllegalArgumentException.class, () -> tempo.parseTrace("example", json.readTree(trace().replace("2200000000", "0"))));
        assertThrows(IllegalArgumentException.class, () -> tempo.parseTrace("example", json.readTree(trace().replace("2000000000", "2300000000"))));
    }
}
