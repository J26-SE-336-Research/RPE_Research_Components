package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PrometheusWindows {
    public record Collection(ServiceWindow window, Map<String, Double> available, Map<String, String> queries) {}
    private final TelemetryHttp http;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.Settings settings;
    public PrometheusWindows(TelemetryHttp http, AdaptiveConfig.Connections connections, AdaptiveConfig.Settings settings) {
        this.http = http; this.connections = connections; this.settings = settings;
    }
    public Map<String, String> queries(String service) {
        String selector = "{service=\"" + TelemetryHttp.escape(service) + "\",namespace=\"" + TelemetryHttp.escape(connections.namespace()) + "\"}";
        String range = "[" + settings.window().toSeconds() + "s]";
        var queries = new LinkedHashMap<String, String>();
        queries.put("observedRequests", "sum(increase(sidecar_route_requests_total" + selector + range + "))");
        queries.put("p95Ms", "1000 * histogram_quantile(0.95,sum by (le) (rate(sidecar_service_request_duration_seconds_bucket" + selector + range + ")))");
        queries.put("errorCount", "sum(increase(sidecar_route_requests_failed_total" + selector + range + "))");
        queries.put("successfulThroughput", "sum(rate(sidecar_route_requests_successful_total" + selector + range + "))");
        queries.put("inFlight", "sum(sidecar_route_requests_in_flight" + selector + ")");
        // New counter contract: the current sidecar cannot distinguish original requests from retries/rejections.
        for (var entry : Map.of("originalRequests", "sidecar_original_requests_total", "retryAttempts", "sidecar_retry_attempts_total",
                "successfulRetries", "sidecar_successful_retries_total", "rejections", "sidecar_rejected_requests_total").entrySet()) {
            queries.put(entry.getKey(), "sum(increase(" + connections.metricNames().getOrDefault(entry.getKey(), entry.getValue()) + selector + range + "))");
        }
        queries.put("scrapeAge", "max(time()-timestamp(sidecar_route_requests_total" + selector + "))");
        String app = switch (service) { case "api-gateway" -> "apigateway"; default -> service.replace("-service", ""); };
        String up = "{app=\"" + TelemetryHttp.escape(app) + "\",namespace=\"" + TelemetryHttp.escape(connections.namespace()) + "\",endpoint=\"metrics\"}";
        queries.put("coverage", "min(min_over_time(up" + up + range + "))");
        queries.put("scrapeSamples", "min(count_over_time(up" + up + range + "))");
        return Map.copyOf(queries);
    }
    public Collection collect(String service, Instant end) {
        var values = new HashMap<String, Double>(); var problems = new ArrayList<String>();
        Map<String, String> queries = queries(service);
        for (var query : queries.entrySet()) {
            try { Double value = http.prometheus(query.getValue(), end); if (value != null) values.put(query.getKey(), value); }
            catch (RuntimeException exception) { problems.add(query.getKey() + ": " + exception.getMessage()); }
            if (Thread.currentThread().isInterrupted()) break;
        }
        for (String required : List.of("originalRequests", "p95Ms", "errorCount", "retryAttempts", "rejections", "inFlight", "successfulThroughput")) {
            if (!values.containsKey(required)) problems.add("Missing required measurement: " + required);
        }
        if (values.getOrDefault("scrapeAge", Double.POSITIVE_INFINITY) > connections.maximumScrapeAge().toSeconds()) problems.add("Latest sidecar scrape is stale");
        if (values.getOrDefault("coverage", 0.0) < 1 || values.getOrDefault("scrapeSamples", 0.0)
                < settings.window().toSeconds() / (double) connections.scrapeInterval().toSeconds() * .8) problems.add("Incomplete scrape coverage");
        var window = new ServiceWindow(service, end.minus(settings.window()), end, Source.PROMETHEUS, problems.isEmpty(),
                values.get("originalRequests"), values.get("p95Ms"), values.get("errorCount"), values.get("retryAttempts"), values.get("successfulRetries"),
                values.get("rejections"), values.get("inFlight"), values.get("successfulThroughput"), null, null, null, null, problems);
        return new Collection(window, Map.copyOf(values), queries);
    }
}
