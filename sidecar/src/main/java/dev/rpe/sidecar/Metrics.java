package dev.rpe.sidecar;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Minimal Prometheus exposition of the rpe_* metrics. Only the metrics implemented so far are exported;
 * the rest are added together with the mechanisms that produce them.
 */
public final class Metrics {
    private static final double[] BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

    private final String service;
    private final String pod;
    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final Map<String, Histogram> durations = new ConcurrentHashMap<>();
    private final Histogram overhead = new Histogram();
    private final AtomicLong inflight = new AtomicLong();
    // Every request is currently a first attempt (the retry mechanism is not implemented yet)
    // and the proxy does not yet reject on its own, so retries and proxy rejections are genuinely zero.
    private final Map<String, LongAdder> original = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> retries = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> rejections = new ConcurrentHashMap<>();
    // Gateway only: root requests per route and those that ended on the deadline. Route cardinality is capped.
    private static final int MAX_ROUTES = 64;
    private final Map<String, LongAdder> chains = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> chainDeadline = new ConcurrentHashMap<>();

    public Metrics(String service, String pod) {
        this.service = service;
        this.pod = pod;
        for (String d : new String[] {"inbound", "outbound"}) {
            original.put(d, new LongAdder());
            // every outcome series exists from the start, so rates and increases are 0 rather than missing
            for (String o : new String[] {"ok", "failed", "rejected"}) {
                requests.put(d + "|" + o, new LongAdder());
            }
        }
        for (String r : new String[] {"attempted", "succeeded", "denied_budget", "denied_deadline", "denied_pressure", "denied_terminal"}) {
            retries.put(r, new LongAdder());
        }
        for (String r : new String[] {"expired", "insufficient_time", "overload", "invalid_context", "pressure_fail_fast"}) {
            rejections.put(r, new LongAdder());
        }
    }

    public void begin() {
        inflight.incrementAndGet();
    }

    /** Records one proxied request. */
    public void record(String direction, String outcome, double seconds, double overheadSeconds) {
        inflight.decrementAndGet();
        original.get(direction).increment();
        requests.computeIfAbsent(direction + "|" + outcome, k -> new LongAdder()).increment();
        durations.computeIfAbsent(direction, k -> new Histogram()).observe(seconds);
        overhead.observe(overheadSeconds);
    }

    /** Records one completed root request at the gateway. */
    public void chain(String method, String route, boolean deadlineExceeded) {
        String key = method + " " + route;
        if (!chains.containsKey(key) && chains.size() >= MAX_ROUTES) {
            key = method + " other";
        }
        chains.computeIfAbsent(key, k -> new LongAdder()).increment();
        LongAdder failed = chainDeadline.computeIfAbsent(key, k -> new LongAdder());
        if (deadlineExceeded) {
            failed.increment();
        }
    }

    public long requestCount(String direction) {
        return requests.entrySet().stream()
                .filter(e -> e.getKey().startsWith(direction + "|"))
                .mapToLong(e -> e.getValue().sum()).sum();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        String base = "service=\"" + service + "\",pod=\"" + pod + "\"";
        sb.append("# HELP rpe_requests_total Requests proxied by the RPE sidecar.\n# TYPE rpe_requests_total counter\n");
        new TreeMap<>(requests).forEach((k, v) -> {
            String[] parts = k.split("\\|", 2);
            sb.append("rpe_requests_total{").append(base).append(",direction=\"").append(parts[0])
                    .append("\",outcome=\"").append(parts[1]).append("\"} ").append(v.sum()).append('\n');
        });
        sb.append("# HELP rpe_original_requests_total First attempts (denominator for rates).\n# TYPE rpe_original_requests_total counter\n");
        new TreeMap<>(original).forEach((d, v) -> sb.append("rpe_original_requests_total{").append(base).append(",direction=\"").append(d)
                .append("\"} ").append(v.sum()).append('\n'));
        sb.append("# HELP rpe_retries_total Retry activity by result.\n# TYPE rpe_retries_total counter\n");
        new TreeMap<>(retries).forEach((r, v) -> sb.append("rpe_retries_total{").append(base).append(",result=\"").append(r)
                .append("\"} ").append(v.sum()).append('\n'));
        sb.append("# HELP rpe_rejections_total Requests rejected by the proxy, by reason.\n# TYPE rpe_rejections_total counter\n");
        new TreeMap<>(rejections).forEach((r, v) -> sb.append("rpe_rejections_total{").append(base).append(",reason=\"").append(r)
                .append("\"} ").append(v.sum()).append('\n'));
        if (!chains.isEmpty()) {
            sb.append("# HELP rpe_chain_requests_total Root requests completed at the gateway, by route.\n# TYPE rpe_chain_requests_total counter\n");
            new TreeMap<>(chains).forEach((k, v) -> sb.append("rpe_chain_requests_total{").append(base).append(routeLabels(k))
                    .append("} ").append(v.sum()).append('\n'));
            sb.append("# HELP rpe_chain_deadline_exceeded_total Root requests that ended on the deadline, by route.\n# TYPE rpe_chain_deadline_exceeded_total counter\n");
            new TreeMap<>(chainDeadline).forEach((k, v) -> sb.append("rpe_chain_deadline_exceeded_total{").append(base).append(routeLabels(k))
                    .append("} ").append(v.sum()).append('\n'));
        }
        sb.append("# HELP rpe_request_duration_seconds Per-hop latency.\n# TYPE rpe_request_duration_seconds histogram\n");
        new TreeMap<>(durations).forEach((dir, h) -> h.render(sb, "rpe_request_duration_seconds", base + ",direction=\"" + dir + "\""));
        sb.append("# HELP rpe_proxy_overhead_seconds Time spent inside the sidecar.\n# TYPE rpe_proxy_overhead_seconds histogram\n");
        overhead.render(sb, "rpe_proxy_overhead_seconds", base);
        sb.append("# HELP rpe_inflight_requests In-flight requests.\n# TYPE rpe_inflight_requests gauge\n");
        sb.append("rpe_inflight_requests{").append(base).append("} ").append(inflight.get()).append('\n');
        return sb.toString();
    }

    private static String routeLabels(String key) {
        String[] parts = key.split(" ", 2);
        return ",method=\"" + escape(parts[0]) + "\",route=\"" + escape(parts[1]) + "\"";
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static final class Histogram {
        private final LongAdder[] counts = new LongAdder[BUCKETS.length];
        private final LongAdder count = new LongAdder();
        private final AtomicLong sumNanos = new AtomicLong();

        Histogram() {
            for (int i = 0; i < counts.length; i++) {
                counts[i] = new LongAdder();
            }
        }

        void observe(double seconds) {
            for (int i = 0; i < BUCKETS.length; i++) {
                if (seconds <= BUCKETS[i]) {
                    counts[i].increment();
                }
            }
            count.increment();
            sumNanos.addAndGet((long) (seconds * 1e9));
        }

        void render(StringBuilder sb, String name, String labels) {
            for (int i = 0; i < BUCKETS.length; i++) {
                sb.append(name).append("_bucket{").append(labels).append(",le=\"").append(BUCKETS[i]).append("\"} ")
                        .append(counts[i].sum()).append('\n');
            }
            sb.append(name).append("_bucket{").append(labels).append(",le=\"+Inf\"} ").append(count.sum()).append('\n');
            sb.append(name).append("_sum{").append(labels).append("} ").append(sumNanos.get() / 1e9).append('\n');
            sb.append(name).append("_count{").append(labels).append("} ").append(count.sum()).append('\n');
        }
    }
}
