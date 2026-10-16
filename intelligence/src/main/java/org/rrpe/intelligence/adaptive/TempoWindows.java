package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Fetches gateway-root traces (span attributes rpe.*), validates spans and computes chain p99 from
 * complete root durations. Trace counts are cross-checked against the gateway sidecar's per-route chain counters.
 */
@Component
public class TempoWindows {
    private final TelemetryHttp http;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.Settings settings;
    public TempoWindows(TelemetryHttp http, AdaptiveConfig.Connections connections, AdaptiveConfig.Settings settings) {
        this.http = http; this.connections = connections; this.settings = settings;
    }
    private record Span(String id, String parent, String service, String route, String method, long start, long end, boolean server, String peer) {}
    public record TraceSample(String id, String route, String method, List<String> services, double durationMs, long start, long end,
            Double deadlineMs, int orphanSpans) {
        public TraceSample(String id, String route, String method, List<String> services, double durationMs, long start, long end, Double deadlineMs) {
            this(id, route, method, services, durationMs, start, end, deadlineMs, 0);
        }
    }
    public record PathEvidence(List<String> services, int traceCount, double p99Ms) {}
    public record Coverage(int traceCount, Double prometheusCount, Double tolerance, boolean accepted) {}
    public record Collection(ChainWindow window, Double currentDeadlineMs, List<String> problems,
            List<PathEvidence> paths, Coverage coverage) {
        public Collection(ChainWindow window, Double currentDeadlineMs, List<String> problems) {
            this(window, currentDeadlineMs, problems, List.of(), null);
        }
    }

    public static double coverageTolerance(double count) { return Math.max(1.0, count * .10); }
    public static boolean sufficientCoverage(int traces, Double count) {
        return traces > 0 && count != null && Double.isFinite(count) && count > 0
                && Math.abs(traces - count) <= coverageTolerance(count);
    }

    public List<Collection> collect(Instant end, Map<String, Health> health) {
        Instant start = end.minus(settings.window());
        String query = "{ resource.service.name = \"" + TelemetryHttp.escape(connections.gatewayService()) + "\" && span.rpe.direction = \"inbound\" }";
        var search = http.get(connections.tempoUrl().replaceAll("/$", "") + "/api/search?q=" + TelemetryHttp.encode(query)
                + "&start=" + start.getEpochSecond() + "&end=" + end.getEpochSecond() + "&limit=" + connections.traceLimit());
        var traces = search.path("traces");
        if (!traces.isArray()) throw new IllegalStateException("Tempo search lacks traces array");
        boolean complete = traces.size() < connections.traceLimit()
                && search.path("metrics").path("completedJobs").asInt(0) >= search.path("metrics").path("totalJobs").asInt(0);
        var groups = new LinkedHashMap<String, List<TraceSample>>(); var seen = new HashSet<String>();
        for (var trace : traces) {
            String id = trace.path("traceID").asString();
            if (id == null || !id.matches("[a-fA-F0-9]{16,32}") || !seen.add(id)) { complete = false; continue; }
            try {
                var sample = parseTrace(id, http.get(connections.tempoUrl().replaceAll("/$", "") + "/api/v2/traces/" + id + "?span_pruning=false"));
                // Prometheus completion counters describe requests finishing in this window.
                if (sample.end() < start.getEpochSecond() * 1_000_000_000L || sample.end() > end.getEpochSecond() * 1_000_000_000L) continue;
                String chain = sample.method() + " " + sample.route();
                groups.computeIfAbsent(chain, ignored -> new ArrayList<>()).add(sample);
            } catch (RuntimeException exception) { complete = false; }
            if (Thread.currentThread().isInterrupted()) break;
        }
        var windows = new ArrayList<Collection>();
        for (var group : groups.entrySet()) {
            var samples = group.getValue(); TraceSample first = samples.get(0);
            String labels = "{service=\"" + TelemetryHttp.escape(connections.gatewayService()) + "\",namespace=\"" + TelemetryHttp.escape(connections.namespace())
                    + "\",method=\"" + TelemetryHttp.escape(first.method()) + "\",route=\"" + TelemetryHttp.escape(first.route()) + "\"}";
            String range = "[" + settings.window().toSeconds() + "s]";
            Double count = http.prometheus("sum(increase(rpe_chain_requests_total" + labels + range + "))", end);
            Double failures = http.prometheus("sum(increase(rpe_chain_deadline_exceeded_total" + labels + range + "))", end);
            var problems = new ArrayList<String>();
            // The enforced route budget is what the gateway stamped into each root context (rpe.deadline.budget_ms).
            Double stamped = first.deadlineMs();
            boolean consistentBudget = stamped != null && stamped > 0 && samples.stream().allMatch(sample -> Objects.equals(sample.deadlineMs(), stamped));
            Double deadline = stamped;
            if (deadline == null) problems.add("Current route deadline is missing from the gateway spans");
            else if (!consistentBudget) problems.add("The route deadline changed within the window (mixed budgets across traces)");
            if (!consistentBudget) deadline = null;
            int orphans = samples.stream().mapToInt(TraceSample::orphanSpans).sum();
            if (orphans > 0) problems.add("Orphan work: " + orphans + " downstream span(s) kept running after the gateway responded");
            // Counters and policy identity are route-level. Pool complete request durations across paths.
            boolean covered = complete && consistentBudget && sufficientCoverage(samples.size(), count)
                    && failures != null && Double.isFinite(failures) && failures >= 0 && failures <= count;
            if (!complete) problems.add("Tempo search is truncated, incomplete or contains invalid traces");
            if (!sufficientCoverage(samples.size(), count)) problems.add("Trace count is outside the allowed Prometheus count tolerance");
            if (failures == null || !Double.isFinite(failures) || failures < 0 || count == null || failures > count)
                problems.add("Missing or inconsistent route deadline-failure counts");
            var pathGroups = new LinkedHashMap<List<String>, List<TraceSample>>();
            var services = new LinkedHashSet<String>();
            for (var sample : samples) {
                pathGroups.computeIfAbsent(sample.services(), ignored -> new ArrayList<>()).add(sample);
                services.addAll(sample.services());
            }
            var paths = pathGroups.entrySet().stream().map(path -> new PathEvidence(path.getKey(), path.getValue().size(),
                    percentile99(path.getValue().stream().mapToDouble(TraceSample::durationMs).toArray()))).toList();
            Health chainHealth = Health.HEALTHY;
            // Hops without a sidecar (not in the monitored service list) have no metrics: reported, not classified.
            var unmonitored = services.stream().filter(service -> !service.equals(connections.gatewayService())
                    && !connections.services().contains(service)).toList();
            if (!unmonitored.isEmpty()) problems.add("Unmonitored hop(s) without RPE metrics: " + String.join(", ", unmonitored));
            for (String service : services) {
                if (unmonitored.contains(service)) continue;
                Health current = health.getOrDefault(service, Health.INSUFFICIENT_DATA);
                if (!List.of(Health.HEALTHY, Health.STRESSED, Health.OVERLOADED).contains(current)) { chainHealth = Health.INSUFFICIENT_DATA; break; }
                if (current == Health.OVERLOADED) chainHealth = Health.OVERLOADED;
                else if (current == Health.STRESSED && chainHealth == Health.HEALTHY) chainHealth = Health.STRESSED;
            }
            windows.add(new Collection(new ChainWindow(group.getKey(), List.copyOf(services), start, end, Source.TEMPO, covered,
                    count, failures, percentile99(samples.stream().mapToDouble(TraceSample::durationMs).toArray()), chainHealth,
                    samples.stream().map(TraceSample::id).limit(5).toList()), deadline, List.copyOf(problems), paths,
                    new Coverage(samples.size(), count, count != null && Double.isFinite(count) && count > 0 ? coverageTolerance(count) : null, covered)));
        }
        return windows;
    }
    public TraceSample parseTrace(String id, JsonNode trace) {
        if ("PARTIAL".equals(trace.path("status").asString()) || trace.path("status").asInt(0) == 1) {
            throw new IllegalArgumentException("Tempo returned a partial trace");
        }
        if (trace.has("trace")) trace = trace.get("trace");
        var resourceSpans = trace.path("resourceSpans");
        if (!resourceSpans.isArray()) resourceSpans = trace.path("batches");
        if (!resourceSpans.isArray()) throw new IllegalArgumentException("Missing OTLP resource spans");
        var spans = new ArrayList<Span>();
        for (var resource : resourceSpans) {
            String service = attributes(resource.path("resource").path("attributes")).get("service.name");
            var scopes = resource.path("scopeSpans"); if (!scopes.isArray()) scopes = resource.path("instrumentationLibrarySpans");
            for (var scope : scopes) for (var raw : scope.path("spans")) {
                var attrs = attributes(raw.path("attributes"));
                String name = attrs.getOrDefault("service.name", service);
                long from = Long.parseLong(raw.path("startTimeUnixNano").asString()); long to = Long.parseLong(raw.path("endTimeUnixNano").asString());
                if (name == null || to <= from) throw new IllegalArgumentException("Incomplete span timing or service");
                boolean server = "SPAN_KIND_SERVER".equals(raw.path("kind").asString()) || raw.path("kind").asInt(0) == 2;
                spans.add(new Span(raw.path("spanId").asString(), raw.path("parentSpanId").asString(""), name,
                        attrs.get("rpe.chain.route"), attrs.get("http.request.method"), from, to, server,
                        "outbound".equals(attrs.get("rpe.direction")) ? attrs.get("peer.service") : null));
            }
        }
        var roots = spans.stream().filter(s -> s.server() && s.service().equals(connections.gatewayService())).toList();
        if (roots.size() != 1) throw new IllegalArgumentException("Trace requires exactly one gateway request span");
        Span root = roots.get(0);
        if (root.route() == null || root.method() == null || root.id() == null || root.id().isBlank()) throw new IllegalArgumentException("Gateway chain identity missing");
        Map<String, Span> byId = new HashMap<>();
        for (Span span : spans) if (span.id() == null || span.id().isBlank() || byId.put(span.id(), span) != null) throw new IllegalArgumentException("Invalid duplicate span ID");
        var ordered = spans.stream().sorted(Comparator.comparingLong(Span::start)).toList(); var services = new ArrayList<String>();
        int orphans = 0;
        for (Span span : ordered) {
            if (span == root) { if (!services.contains(span.service())) services.add(span.service()); continue; }
            var visited = new HashSet<String>(); Span cursor = span;
            while (cursor != root) {
                if (!visited.add(cursor.id()) || !byId.containsKey(cursor.parent())) throw new IllegalArgumentException("Orphaned/incomplete chain spans");
                cursor = byId.get(cursor.parent());
            }
            if (span.start() < root.start()) throw new IllegalArgumentException("Chain activity starts before the gateway span");
            // Work that outlives the root response is orphan work (expired or cancelled downstream), not an invalid trace.
            if (span.server() && span.end() > root.end()) orphans++;
            if (span.server()) services.add(span.service());
        }
        // An outbound hop without a downstream server span was answered by the transport or a proxy (e.g. a local
        // deadline rejection); the request duration is still complete, so the trace stays valid.
        if (services.isEmpty() || !services.get(0).equals(connections.gatewayService())) throw new IllegalArgumentException("Ordered path must start at gateway");
        String budget = null;
        for (var resource : resourceSpans) {
            var scopes = resource.path("scopeSpans"); if (!scopes.isArray()) scopes = resource.path("instrumentationLibrarySpans");
            for (var scope : scopes) for (var span : scope.path("spans")) if (root.id().equals(span.path("spanId").asString())) {
                budget = attributes(span.path("attributes")).get("rpe.deadline.budget_ms");
            }
        }
        return new TraceSample(id, root.route(), root.method(), List.copyOf(services), (root.end() - root.start()) / 1_000_000.0, root.start(), root.end(),
                budget == null ? null : Double.valueOf(budget), orphans);
    }
    private Map<String, String> attributes(JsonNode attributes) {
        var result = new HashMap<String, String>();
        for (var item : attributes) {
            var value = item.path("value");
            if (value.has("stringValue")) result.put(item.path("key").asString(), value.path("stringValue").asString());
            else if (value.has("intValue")) result.put(item.path("key").asString(), value.path("intValue").asString());
        }
        return result;
    }
    public static double percentile99(double[] durations) {
        if (durations.length == 0) throw new IllegalArgumentException("p99 requires durations");
        double[] values = durations.clone(); Arrays.sort(values); return values[(int) Math.ceil(values.length * .99) - 1];
    }
}
