package org.rrpe.intelligence.adaptive;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TelemetryHttp {
    private final HttpClient http;
    private final AdaptiveConfig.Connections settings;
    private final JsonMapper json = JsonMapper.builder().build();
    public TelemetryHttp(AdaptiveConfig.Connections settings) { this.settings = settings; http = HttpClient.newBuilder().connectTimeout(settings.timeout()).build(); }
    public JsonNode get(String url) {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(settings.timeout()).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Telemetry server HTTP " + response.statusCode());
            return json.readTree(response.body());
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("Telemetry query interrupted"); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Telemetry server unreachable", exception); }
    }
    public Double prometheus(String query, Instant time) {
        var root = get(settings.prometheusUrl().replaceAll("/$", "") + "/api/v1/query?query=" + encode(query) + "&time=" + time.getEpochSecond());
        if (!"success".equals(root.path("status").asString()) || !"vector".equals(root.path("data").path("resultType").asString())
                || root.path("warnings").size() > 0) throw new IllegalStateException("Incomplete Prometheus response");
        var values = root.path("data").path("result");
        if (values.size() == 0) return null;
        if (values.size() != 1) throw new IllegalStateException("Ambiguous service query");
        var sample = values.get(0).path("value");
        if (Math.abs(sample.get(0).asDouble() - time.getEpochSecond()) > settings.maximumScrapeAge().toSeconds()) throw new IllegalStateException("Stale query result");
        double value = Double.parseDouble(sample.get(1).asString());
        return Double.isFinite(value) && value >= 0 ? value : null;
    }
    public static String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    public static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}
