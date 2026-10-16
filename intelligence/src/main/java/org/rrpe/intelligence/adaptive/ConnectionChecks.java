package org.rrpe.intelligence.adaptive;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/** Read-only probes distinguish an unreachable server from one without fresh gateway evidence. */
@Component
public class ConnectionChecks {
    public record Check(String state, String detail) {}

    private final TelemetryHttp http;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.Settings settings;

    public ConnectionChecks(
            TelemetryHttp http,
            AdaptiveConfig.Connections connections,
            AdaptiveConfig.Settings settings) {
        this.http = http;
        this.connections = connections;
        this.settings = settings;
    }

    public Map<String, Check> check() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Check prometheus;
        Check tempo;
        try {
            String selector =
                    "{namespace=\"" + TelemetryHttp.escape(connections.namespace()) + "\"}";
            Double series = http.prometheus("count(rpe_inflight_requests" + selector + ")", now);
            Double age =
                    http.prometheus(
                            "max(time()-timestamp(rpe_inflight_requests" + selector + "))", now);
            Double requests =
                    http.prometheus(
                            "sum(increase(rpe_chain_requests_total{namespace=\""
                                    + TelemetryHttp.escape(connections.namespace())
                                    + "\",service=\""
                                    + TelemetryHttp.escape(connections.gatewayService())
                                    + "\"}["
                                    + settings.window().toSeconds()
                                    + "s]))",
                            now);
            boolean live =
                    series != null
                            && series > 0
                            && age != null
                            && age <= connections.maximumScrapeAge().toSeconds()
                            && requests != null
                            && requests > 0;
            prometheus =
                    new Check(
                            live ? "LIVE_DATA" : "NO_FRESH_DATA",
                            live
                                    ? "Fresh sidecar metrics and recent completed gateway requests"
                                          + " found"
                                    : "Server responded, but fresh sidecar metrics or recent"
                                          + " gateway traffic are missing");
        } catch (RuntimeException exception) {
            prometheus = new Check("UNREACHABLE_OR_QUERY_FAILED", exception.getMessage());
        }
        try {
            String query =
                    "{ resource.service.name = \""
                            + TelemetryHttp.escape(connections.gatewayService())
                            + "\" && span.rpe.direction = \"inbound\" }";
            var response =
                    http.get(
                            connections.tempoUrl().replaceAll("/$", "")
                                    + "/api/search?q="
                                    + TelemetryHttp.encode(query)
                                    + "&start="
                                    + now.minus(settings.window()).getEpochSecond()
                                    + "&end="
                                    + now.getEpochSecond()
                                    + "&limit=1");
            if (!response.path("traces").isArray())
                throw new IllegalStateException("Invalid Tempo search response");
            tempo =
                    new Check(
                            response.path("traces").size() > 0 ? "LIVE_DATA" : "NO_FRESH_DATA",
                            response.path("traces").size() > 0
                                    ? "Recent gateway traces found; completeness is checked during"
                                          + " collection"
                                    : "Server responded, but no recent gateway traces were found");
        } catch (RuntimeException exception) {
            tempo = new Check("UNREACHABLE_OR_QUERY_FAILED", exception.getMessage());
        }
        return Map.of("prometheus", prometheus, "tempo", tempo);
    }
}
