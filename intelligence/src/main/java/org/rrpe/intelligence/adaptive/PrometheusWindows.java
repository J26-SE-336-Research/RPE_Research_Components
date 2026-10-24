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
        // Metrics contract A3.3 (Component 2 sidecar). Inbound traffic describes the load this service receives.
        String inbound = selector.replace("}", ",direction=\"inbound\"}");
        queries.put("originalRequests", "sum(increase(rpe_original_requests_total" + inbound + range + "))");
        queries.put("observedRequests", "sum(increase(rpe_requests_total" + inbound + range + "))");
        queries.put("p95Ms", "1000 * histogram_quantile(0.95,sum by (le) (rate(rpe_request_duration_seconds_bucket" + inbound + range + ")))");
        // Service errors only: 429/504 rejections (deadline or overload protection at work) are not service faults.
        queries.put("errorCount", "sum(increase(rpe_requests_total" + inbound.replace("}", ",outcome=\"failed\"}") + range + "))");
        queries.put("successfulThroughput", "sum(rate(rpe_requests_total" + inbound.replace("}", ",outcome=\"ok\"}") + range + "))");
        // mean concurrency over the window (an instant sample of the gauge is too noisy to compare with a baseline)
        queries.put("inFlight", "sum(avg_over_time(rpe_inflight_requests" + selector + range + "))");
        queries.put("retryAttempts", "sum(increase(rpe_retries_total" + selector.replace("}", ",result=\"attempted\"}") + range + "))");
        queries.put("successfulRetries", "sum(increase(rpe_retries_total" + selector.replace("}", ",result=\"succeeded\"}") + range + "))");
        queries.put("rejections", "sum(increase(rpe_rejections_total" + selector + range + "))");
        connections.metricNames().forEach((key, metric) -> queries.put(key, "sum(increase(" + metric + selector + range + "))"));
        queries.put("scrapeAge", "max(time()-timestamp(rpe_inflight_requests" + selector + "))");
        queries.put("coverage", "min(min_over_time(up" + selector + range + "))");
        queries.put("scrapeSamples", "min(count_over_time(up" + selector + range + "))");
        return Map.copyOf(queries);
    }
    /**
     * The load data of each service, with the real sidecar metric names. Each query
     * aggregates to one series. CPU, throttling and memory come from cAdvisor / kube-state-metrics, which the RPE
     * Prometheus does not scrape today: they return no data and are treated as unavailable.
     */
    public Map<String, String> loadQueries(String service) {
        String selector = "{service=\"" + TelemetryHttp.escape(service) + "\",namespace=\"" + TelemetryHttp.escape(connections.namespace()) + "\"}";
        String range = "[" + settings.window().toSeconds() + "s]";
        var queries = new LinkedHashMap<String, String>();
        queries.put("loadQueuePressurePercent", "max(max_over_time(rpe_backpressure_inbound_queue_utilization_percent" + selector + range + "))");
        queries.put("loadOverloadRejections", "sum(increase(rpe_rejections_total" + selector.replace("}", ",reason=\"overload\"}") + range + "))");
        queries.put("loadBackpressureRejections", "sum(increase(rpe_backpressure_inbound_rejected_total" + selector + range + "))");
        queries.put("loadBreakerOpenPeers", "max(max_over_time(rpe_circuit_breaker_open_peers" + selector + range + "))");
        // The most requests waiting in the queue during the window, on the worst pod. We use it to take the queue wait
        // out of p95. We use the maximum, not the average: when the queue fills in bursts, p95 includes those waits,
        // and an average depth would make the app look slow.
        queries.put("loadQueueDepth", "max(max_over_time(rpe_backpressure_inbound_queue_depth" + selector + range + "))");
        // The rate the sidecar uses right now. We start from it when Component 3 has no policy rate for the service,
        // the same way we start from the configured deadline.
        queries.put("loadRatePerSecond", "max(rpe_backpressure_inbound_rate_per_second" + selector + ")");
        String app = selector.replace("}", ",container!=\"rpe-sidecar\"}");
        queries.put("loadCpuUtilisation", "sum(rate(container_cpu_usage_seconds_total" + app + range + ")) / sum(kube_pod_container_resource_limits"
                + app.replace("}", ",resource=\"cpu\"}") + ")");
        queries.put("loadCpuThrottling", "sum(rate(container_cpu_cfs_throttled_periods_total" + app + range + ")) / sum(rate(container_cpu_cfs_periods_total" + app + range + "))");
        queries.put("loadMemoryUtilisation", "max(container_memory_working_set_bytes" + app + ") / max(kube_pod_container_resource_limits"
                + app.replace("}", ",resource=\"memory\"}") + ")");
        return Map.copyOf(queries);
    }
    /** Collects {@link #loadQueries}; never throws and never affects {@link #collect}: a failed query is just unavailable. */
    public ServiceLoadSnapshot collectLoad(String service, Instant end) {
        var values = new HashMap<String, Double>(); var problems = new ArrayList<String>();
        for (var query : loadQueries(service).entrySet()) {
            try { Double value = http.prometheus(query.getValue(), end); if (value != null) values.put(query.getKey(), value); }
            catch (RuntimeException exception) { problems.add(query.getKey() + ": " + exception.getMessage()); }
            if (Thread.currentThread().isInterrupted()) break;
        }
        return new ServiceLoadSnapshot(service, values.get("loadQueuePressurePercent"), values.get("loadOverloadRejections"),
                values.get("loadBackpressureRejections"), values.get("loadBreakerOpenPeers"), values.get("loadCpuUtilisation"),
                values.get("loadCpuThrottling"), values.get("loadMemoryUtilisation"), null, problems, values.get("loadQueueDepth"), values.get("loadRatePerSecond"));
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
