package com.example.proxy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Bounded-cardinality route and service metrics exposed to Prometheus. */
public final class ProxyMetrics {
    private static final LongAdder INBOUND_REQUESTS = new LongAdder();
    private static final AtomicLong INBOUND_IN_FLIGHT = new AtomicLong();
    private static final LongAdder INBOUND_SUCCESSFUL_REQUESTS = new LongAdder();
    private static final LongAdder INBOUND_FAILED_REQUESTS = new LongAdder();
    private static final LongAdder INBOUND_DEADLINE_EXCEEDED_REQUESTS = new LongAdder();
    private static final LongAdder INBOUND_CANCELLATION_REQUESTS = new LongAdder();
    private static final double[] LATENCY_BUCKETS_SECONDS = {
            0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0
    };
    private static final ConcurrentHashMap<RouteKey, RouteStats> ROUTE_STATS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ServiceStats> SERVICE_STATS = new ConcurrentHashMap<>();
    private static volatile List<GatewayDeadlineRules.DeadlineBudget> GATEWAY_DEADLINE_BUDGETS = List.of();
    private static volatile boolean GATEWAY_INSTANCE;
    private static volatile BackpressureController BACKPRESSURE;

    private ProxyMetrics() {}

    private record RouteKey(String service, String method, String route, long budgetMillis) {}

    private static final class RouteStats {
        final LongAdder requests = new LongAdder();
        final LongAdder successful = new LongAdder();
        final LongAdder failed = new LongAdder();
        final LongAdder deadlineDetections = new LongAdder();
        final LongAdder cancellations = new LongAdder();
        final LongAdder gatewayChainsCompleted = new LongAdder();
        final LongAdder gatewayChainsExceeded = new LongAdder();
        final AtomicLong inFlight = new AtomicLong();
        final AtomicLongArray gatewayDurationBuckets = new AtomicLongArray(LATENCY_BUCKETS_SECONDS.length);
        final LongAdder gatewayDurationCount = new LongAdder();
        final LongAdder gatewayDurationSumNanos = new LongAdder();
    }

    private static final class ServiceStats {
        final AtomicLongArray buckets = new AtomicLongArray(LATENCY_BUCKETS_SECONDS.length);
        final LongAdder durationCount = new LongAdder();
        final LongAdder durationSumNanos = new LongAdder();
    }

    public static InboundRequestTracker beginInboundRequest(String service, String method,
                                                            String route, long budgetMillis,
                                                            boolean gatewayChain) {
        RouteKey key = new RouteKey(clean(service), clean(method), clean(route), Math.max(0, budgetMillis));
        RouteStats stats = ROUTE_STATS.computeIfAbsent(key, ignored -> new RouteStats());
        ServiceStats serviceStats = SERVICE_STATS.computeIfAbsent(key.service(), ignored -> new ServiceStats());
        INBOUND_REQUESTS.increment();
        INBOUND_IN_FLIGHT.incrementAndGet();
        stats.requests.increment();
        stats.inFlight.incrementAndGet();
        return new InboundRequestTracker(stats, serviceStats, gatewayChain);
    }

    public static final class InboundRequestTracker {
        private final RouteStats stats;
        private final ServiceStats serviceStats;
        private final boolean gatewayChain;
        private final AtomicBoolean finished = new AtomicBoolean();
        private InboundRequestTracker(RouteStats stats, ServiceStats serviceStats, boolean gatewayChain) {
            this.stats = stats;
            this.serviceStats = serviceStats;
            this.gatewayChain = gatewayChain;
        }
        public void finish() {
            if (finished.compareAndSet(false, true)) {
                INBOUND_IN_FLIGHT.decrementAndGet();
                stats.inFlight.decrementAndGet();
            }
        }
    }

    public static long inboundRequestsInFlight() { return INBOUND_IN_FLIGHT.get(); }

    public static void recordInboundSuccessfulRequest(int status, InboundRequestTracker tracker) {
        if (status >= 200 && status < 500 && status != 429) {
            INBOUND_SUCCESSFUL_REQUESTS.increment();
            if (tracker != null) tracker.stats.successful.increment();
        }
    }

    public static void recordInboundFailure(int status, InboundRequestTracker tracker) {
        if (status == 429 || status >= 500) {
            INBOUND_FAILED_REQUESTS.increment();
            if (tracker != null) tracker.stats.failed.increment();
        }
    }

    public static void recordDeadlineDetected(String service, String method, String route, long budgetMillis) {
        INBOUND_DEADLINE_EXCEEDED_REQUESTS.increment();
        routeStats(service, method, route, budgetMillis).deadlineDetections.increment();
    }

    public static void recordCancellation(String service, String method, String route, long budgetMillis) {
        INBOUND_CANCELLATION_REQUESTS.increment();
        routeStats(service, method, route, budgetMillis).cancellations.increment();
    }

    public static void recordRequestDuration(long nanos, InboundRequestTracker tracker) {
        long duration = Math.max(0, nanos);
        if (tracker != null) {
            recordHistogram(duration, tracker.serviceStats.buckets,
                    tracker.serviceStats.durationCount, tracker.serviceStats.durationSumNanos);
            if (tracker.gatewayChain) {
                recordHistogram(duration, tracker.stats.gatewayDurationBuckets,
                        tracker.stats.gatewayDurationCount, tracker.stats.gatewayDurationSumNanos);
            }
        }
    }

    public static void recordGatewayChainCompleted(String service, String method, String route,
                                                   long budgetMillis, boolean exceeded) {
        RouteStats stats = routeStats(service, method, route, budgetMillis);
        stats.gatewayChainsCompleted.increment();
        if (exceeded) stats.gatewayChainsExceeded.increment();
        RpeContractMetrics.chain(clean(method), clean(route), exceeded);
    }

    public static void configureGatewayDeadlineBudgets(List<GatewayDeadlineRules.DeadlineBudget> budgets) {
        GATEWAY_DEADLINE_BUDGETS = List.copyOf(budgets);
        GATEWAY_INSTANCE = true;
    }

    public static void configureBackpressure(BackpressureController controller) {
        BACKPRESSURE = controller;
    }

    public static byte[] prometheusText() {
        StringBuilder out = new StringBuilder();
        RpeContractMetrics.render(out);
        out.append("# HELP rpe_inbound_requests_total Total inbound requests observed by this sidecar.\n")
                .append("# TYPE rpe_inbound_requests_total counter\nrpe_inbound_requests_total ").append(INBOUND_REQUESTS.sum()).append('\n')
                .append("# HELP rpe_inbound_requests_in_flight Current inbound requests being handled by this sidecar.\n")
                .append("# TYPE rpe_inbound_requests_in_flight gauge\nrpe_inbound_requests_in_flight ").append(inboundRequestsInFlight()).append('\n')
                .append("# HELP rpe_inbound_requests_successful_total Successfully forwarded inbound requests.\n")
                .append("# TYPE rpe_inbound_requests_successful_total counter\nrpe_inbound_requests_successful_total ").append(INBOUND_SUCCESSFUL_REQUESTS.sum()).append('\n')
                .append("# HELP rpe_inbound_requests_failed_total Inbound requests returning overload or proxy failure statuses.\n")
                .append("# TYPE rpe_inbound_requests_failed_total counter\nrpe_inbound_requests_failed_total ").append(INBOUND_FAILED_REQUESTS.sum()).append('\n')
                .append("# HELP rpe_deadline_exceeded_requests_total Deadline detections recorded by this sidecar.\n")
                .append("# TYPE rpe_deadline_exceeded_requests_total counter\nrpe_deadline_exceeded_requests_total ").append(INBOUND_DEADLINE_EXCEEDED_REQUESTS.sum()).append('\n')
                .append("# HELP rpe_cancellation_requests_total Cancellation signals recorded by this sidecar.\n")
                .append("# TYPE rpe_cancellation_requests_total counter\nrpe_cancellation_requests_total ").append(INBOUND_CANCELLATION_REQUESTS.sum()).append('\n')
                .append("# HELP rpe_gateway_deadline_budget_milliseconds Configured gateway deadline budget for each explicit route.\n")
                .append("# TYPE rpe_gateway_deadline_budget_milliseconds gauge\n")
                .append(gatewayDeadlineBudgetText())
                .append("# HELP rpe_route_requests_total Inbound requests by service and gateway chain route.\n")
                .append("# TYPE rpe_route_requests_total counter\n")
                .append("# HELP rpe_route_requests_in_flight Current inbound requests by service and gateway chain route.\n")
                .append("# TYPE rpe_route_requests_in_flight gauge\n")
                .append("# HELP rpe_route_requests_successful_total Successfully completed inbound requests by route.\n")
                .append("# TYPE rpe_route_requests_successful_total counter\n")
                .append("# HELP rpe_route_requests_failed_total Failed or overloaded inbound requests by route.\n")
                .append("# TYPE rpe_route_requests_failed_total counter\n")
                .append("# HELP rpe_route_deadline_detections_total Requests whose deadline this service detected as exceeded.\n")
                .append("# TYPE rpe_route_deadline_detections_total counter\n")
                .append("# HELP rpe_route_cancellations_total Cancellation signals observed for requests on this route.\n")
                .append("# TYPE rpe_route_cancellations_total counter\n")
                .append("# HELP rpe_gateway_request_chains_completed_total Completed end-to-end gateway request chains.\n")
                .append("# TYPE rpe_gateway_request_chains_completed_total counter\n")
                .append("# HELP rpe_gateway_request_chains_deadline_exceeded_total End-to-end gateway chains that exceeded their deadline.\n")
                .append("# TYPE rpe_gateway_request_chains_deadline_exceeded_total counter\n")
                .append("# HELP rpe_gateway_request_chain_duration_seconds Proxy-observed end-to-end gateway request duration by configured route.\n")
                .append("# TYPE rpe_gateway_request_chain_duration_seconds histogram\n")
                .append("# HELP rpe_service_request_duration_seconds Proxy-observed inbound request duration by service.\n")
                .append("# TYPE rpe_service_request_duration_seconds histogram\n");
        ROUTE_STATS.forEach((key, stats) -> appendRouteStats(out, key, stats));
        if (BACKPRESSURE != null) out.append(BACKPRESSURE.prometheusText());
        SERVICE_STATS.forEach((service, stats) -> appendHistogram(out,
                "rpe_service_request_duration_seconds", "{service=\"" + label(service) + "\"}",
                stats.buckets, stats.durationCount, stats.durationSumNanos));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRouteStats(StringBuilder out, RouteKey key, RouteStats stats) {
        String labels = "{service=\"" + label(key.service()) + "\",method=\"" + label(key.method())
                + "\",route=\"" + label(key.route()) + "\",deadline_ms=\"" + key.budgetMillis() + "\"}";
        sample(out, "rpe_route_requests_total", labels, stats.requests.sum());
        sample(out, "rpe_route_requests_in_flight", labels, stats.inFlight.get());
        sample(out, "rpe_route_requests_successful_total", labels, stats.successful.sum());
        sample(out, "rpe_route_requests_failed_total", labels, stats.failed.sum());
        sample(out, "rpe_route_deadline_detections_total", labels, stats.deadlineDetections.sum());
        sample(out, "rpe_route_cancellations_total", labels, stats.cancellations.sum());
        if (GATEWAY_INSTANCE) {
            sample(out, "rpe_gateway_request_chains_completed_total", labels, stats.gatewayChainsCompleted.sum());
            sample(out, "rpe_gateway_request_chains_deadline_exceeded_total", labels, stats.gatewayChainsExceeded.sum());
            appendHistogram(out, "rpe_gateway_request_chain_duration_seconds", labels,
                    stats.gatewayDurationBuckets, stats.gatewayDurationCount, stats.gatewayDurationSumNanos);
        }
    }

    private static void appendHistogram(StringBuilder out, String name, String labels,
                                        AtomicLongArray buckets, LongAdder count, LongAdder sumNanos) {
        for (int i = 0; i < LATENCY_BUCKETS_SECONDS.length; i++) {
            String bucketLabels = labels.isEmpty() ? "{le=\"" + LATENCY_BUCKETS_SECONDS[i] + "\"}"
                    : labels.substring(0, labels.length() - 1) + ",le=\"" + LATENCY_BUCKETS_SECONDS[i] + "\"}";
            sample(out, name + "_bucket", bucketLabels, buckets.get(i));
        }
        String inf = labels.isEmpty() ? "{le=\"+Inf\"}" : labels.substring(0, labels.length() - 1) + ",le=\"+Inf\"}";
        sample(out, name + "_bucket", inf, count.sum());
        sample(out, name + "_count", labels, count.sum());
        sample(out, name + "_sum", labels, sumNanos.sum() / 1_000_000_000.0);
    }

    private static void recordHistogram(long nanos, AtomicLongArray buckets, LongAdder count, LongAdder sumNanos) {
        double seconds = nanos / 1_000_000_000.0;
        for (int i = 0; i < LATENCY_BUCKETS_SECONDS.length; i++) {
            if (seconds <= LATENCY_BUCKETS_SECONDS[i]) buckets.incrementAndGet(i);
        }
        count.increment();
        sumNanos.add(nanos);
    }

    private static void sample(StringBuilder out, String metric, String labels, Number value) {
        out.append(metric).append(labels).append(' ').append(value).append('\n');
    }

    private static String gatewayDeadlineBudgetText() {
        StringBuilder out = new StringBuilder();
        for (GatewayDeadlineRules.DeadlineBudget budget : GATEWAY_DEADLINE_BUDGETS) {
            out.append("rpe_gateway_deadline_budget_milliseconds{method=\"")
                    .append(label(budget.method())).append("\",route=\"")
                    .append(label(budget.route())).append("\"} ")
                    .append(budget.deadlineMillis()).append('\n');
        }
        return out.toString();
    }

    private static RouteStats routeStats(String service, String method, String route, long budgetMillis) {
        RouteKey key = new RouteKey(clean(service), clean(method), clean(route), Math.max(0, budgetMillis));
        return ROUTE_STATS.computeIfAbsent(key, ignored -> new RouteStats());
    }

    private static String clean(String value) { return value == null || value.isBlank() ? "unknown" : value; }
    private static String label(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
