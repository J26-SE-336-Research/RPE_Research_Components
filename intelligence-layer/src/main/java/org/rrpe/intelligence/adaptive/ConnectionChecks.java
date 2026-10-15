package org.rrpe.intelligence.adaptive;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Read-only probes distinguish an unreachable server from one without fresh gateway evidence. */
@Component
public class ConnectionChecks {
    public record Check(String state, String detail) {}
    private final TelemetryHttp http;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.Settings settings;
    public ConnectionChecks(TelemetryHttp http, AdaptiveConfig.Connections connections, AdaptiveConfig.Settings settings) {
        this.http = http; this.connections = connections; this.settings = settings;
    }
    public Map<String, Check> check() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Check prometheus; Check tempo;
        try {
            String gauge = "sidecar_gateway_deadline_budget_milliseconds{namespace=\"" + TelemetryHttp.escape(connections.namespace()) + "\"}";
            Double series = http.prometheus("count(" + gauge + ")", now);
            Double age = http.prometheus("max(time()-timestamp(" + gauge + "))", now);
            // Component 2 currently exports per-service route attempts, not the optional completed-chain counter.
            Double requests = http.prometheus("sum(increase(sidecar_route_requests_total{namespace=\""
                    + TelemetryHttp.escape(connections.namespace()) + "\"}[" + settings.window().toSeconds() + "s]))", now);
            boolean live = series != null && series > 0 && age != null && age <= connections.maximumScrapeAge().toSeconds() && requests != null && requests > 0;
            prometheus = new Check(live ? "LIVE_DATA" : "NO_FRESH_DATA", live ? "Fresh configured gateway deadlines and recent sidecar route traffic found"
                    : "Server responded, but fresh deadline gauges or recent gateway traffic are missing");
        } catch (RuntimeException exception) { prometheus = new Check("UNREACHABLE_OR_QUERY_FAILED", exception.getMessage()); }
        try {
            // Current sidecar traces contain gateway egress plus downstream ingress, not a gateway inbound span.
            String query = "{ resource.service.name = \"" + TelemetryHttp.escape(connections.gatewayService()) + "\" }";
            var response = http.get(connections.tempoUrl().replaceAll("/$", "") + "/api/search?q=" + TelemetryHttp.encode(query)
                    + "&start=" + now.minus(settings.window()).getEpochSecond() + "&end=" + now.getEpochSecond() + "&limit=1");
            if (!response.path("traces").isArray()) throw new IllegalStateException("Invalid Tempo search response");
            tempo = new Check(response.path("traces").size() > 0 ? "LIVE_DATA" : "NO_FRESH_DATA",
                    response.path("traces").size() > 0 ? "Recent gateway-associated traces found; path completeness is checked during collection" : "Server responded, but no recent gateway-associated traces were found");
        } catch (RuntimeException exception) { tempo = new Check("UNREACHABLE_OR_QUERY_FAILED", exception.getMessage()); }
        return Map.of("prometheus", prometheus, "tempo", tempo);
    }
}
