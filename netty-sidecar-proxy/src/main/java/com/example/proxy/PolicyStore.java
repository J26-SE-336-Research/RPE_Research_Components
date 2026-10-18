package com.example.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Policies delivered by Component 3 (Operator and Admin Panel), keyed by epoch (control API contract A3.5). An
 * epoch is staged (held inactive) or active. The document is stored as received; the gateway route deadlines it
 * carries ({@code policy.routes["METHOD /path"].deadlineMs}) are applied when it becomes active.
 *
 * <p>Component 3 per-request policy consistency (A3.5 v0.3): every inbound request is served under the epoch it
 * carries ({@code pe}) when this sidecar holds it, otherwise under the active epoch ({@link #pin}). The store counts
 * in-flight requests per epoch (the Operator drains an old epoch until it reaches 0) and the requests served under a
 * different version of the same policy than the one they carry (mixed-policy hops). The {@code hash} of an epoch is
 * computed here from the received {@code policy} member ({@link #contentHash}), so the Operator can verify delivery.
 */
final class PolicyStore {
    enum State { STAGED, ACTIVE }

    record Entry(String epoch, long version, String hash, State state, String document,
                 Map<String, Long> routeDeadlines) {}

    /** The epoch one inbound request is served under; {@code mismatched} when it differs from the carried one. */
    record Pin(String epoch, boolean mismatched) {}

    static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TRACKED_EPOCHS = 64;

    private final Map<String, Entry> epochs = new ConcurrentHashMap<>();
    private final Consumer<Entry> onActivated;
    private volatile String activeEpoch = "";
    private final Map<String, AtomicLong> inflight = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> served = new ConcurrentHashMap<>();
    private final LongAdder mismatched = new LongAdder();

    PolicyStore(Consumer<Entry> onActivated) {
        this.onActivated = onActivated == null ? entry -> { } : onActivated;
    }

    /** Stores a policy document; {@code state} is staged or active. The hash is computed from the document. */
    synchronized Entry put(String epoch, long version, State state, String document) {
        if (epoch == null || epoch.isBlank()) throw new IllegalArgumentException("epoch is required");
        Entry entry = new Entry(epoch, version, contentHash(document), state, document, routeDeadlines(document));
        epochs.put(epoch, entry);
        if (state == State.ACTIVE) {
            activeEpoch = epoch;
            onActivated.accept(entry);
        }
        return entry;
    }

    synchronized boolean activate(String epoch) {
        Entry entry = epoch == null ? null : epochs.get(epoch);
        if (entry == null) return false;
        Entry active = new Entry(entry.epoch(), entry.version(), entry.hash(), State.ACTIVE, entry.document(),
                entry.routeDeadlines());
        epochs.put(epoch, active);
        activeEpoch = epoch;
        onActivated.accept(active);
        return true;
    }

    /** Removes a staged epoch; the active epoch cannot be retired. */
    synchronized boolean retire(String epoch) {
        if (epoch.equals(activeEpoch)) return false;
        return epochs.remove(epoch) != null;
    }

    Entry get(String epoch) { return epoch == null ? null : epochs.get(epoch); }

    Entry active() {
        String epoch = activeEpoch;
        return epoch.isEmpty() ? null : epochs.get(epoch);
    }

    String activeEpoch() { return activeEpoch; }

    /**
     * Pins one inbound request to a policy epoch: the epoch it carries when held here, otherwise the active one. At
     * the gateway ({@code stamping}) the carried epoch is the one the gateway just stamped. Returns null when no
     * policy applies. Every pin must be {@linkplain #release released} when the request completes.
     */
    Pin pin(String requestEpoch, boolean stamping) {
        String requested = requestEpoch == null ? "" : requestEpoch;
        String applied = stamping || epochs.containsKey(requested) ? requested : activeEpoch;
        if (applied.isEmpty()) return null;
        // Only a different version of the same policy is a mixed state; an epoch of another policy (the gateway
        // stamps the epoch of the last policy it activated) does not govern this service.
        boolean mismatch = !stamping && !requested.isEmpty() && !applied.equals(requested)
                && family(requested).equals(family(applied));
        if (mismatch) mismatched.increment();
        tracked(inflight, applied, AtomicLong::new).incrementAndGet();
        tracked(served, applied, LongAdder::new).increment();
        return new Pin(applied, mismatch);
    }

    void release(Pin pin) {
        AtomicLong count = inflight.get(tracked(pin.epoch()));
        if (count != null) count.decrementAndGet();
    }

    long inflight(String epoch) {
        AtomicLong count = inflight.get(epoch);
        return count == null ? 0 : Math.max(0, count.get());
    }

    long mismatched() { return mismatched.sum(); }

    /** Policy id of an epoch: Component 3 names epochs {@code <policyId>-v<version>}. */
    static String family(String epoch) {
        return epoch.replaceFirst("-v\\d+$", "");
    }

    private <T> T tracked(Map<String, T> map, String epoch, java.util.function.Supplier<T> create) {
        return map.computeIfAbsent(map.containsKey(epoch) || map.size() < MAX_TRACKED_EPOCHS ? epoch : "other",
                ignored -> create.get());
    }

    private String tracked(String epoch) {
        return inflight.containsKey(epoch) ? epoch : "other";
    }

    String toJson() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode items = root.putArray("epochs");
        epochs.values().stream().sorted(Comparator.comparing(Entry::epoch)).forEach(entry -> items.addObject()
                .put("epoch", entry.epoch())
                .put("version", entry.version())
                .put("hash", entry.hash())
                .put("state", entry.state().name().toLowerCase())
                .put("inflight", inflight(entry.epoch())));
        root.put("activeEpoch", activeEpoch);
        ObjectNode counts = root.putObject("inflight");
        new TreeMap<>(inflight).forEach((epoch, count) -> counts.put(epoch, Math.max(0, count.get())));
        ObjectNode servedCounts = root.putObject("served");
        new TreeMap<>(served).forEach((epoch, count) -> servedCounts.put(epoch, count.sum()));
        root.put("mismatched", mismatched.sum());
        return root.toString();
    }

    /** Policy metrics (A3.3): held epochs, in-flight and served requests per epoch, mixed-policy hops. */
    void render(StringBuilder sb, String base) {
        sb.append("# HELP rpe_policy_info Policy epochs held by this sidecar.\n# TYPE rpe_policy_info gauge\n");
        List<Entry> held = new ArrayList<>(epochs.values());
        held.sort(Comparator.comparing(Entry::epoch));
        String active = activeEpoch;
        for (Entry entry : held) {
            sb.append("rpe_policy_info{").append(base).append(",epoch=\"").append(escape(entry.epoch()))
                    .append("\",version=\"").append(entry.version()).append("\",hash=\"").append(entry.hash())
                    .append("\",state=\"").append(entry.epoch().equals(active) ? "active" : "staged").append("\"} 1\n");
        }
        sb.append("# HELP rpe_policy_inflight_requests In-flight inbound requests by the policy epoch they are pinned to.\n")
                .append("# TYPE rpe_policy_inflight_requests gauge\n");
        new TreeMap<>(inflight).forEach((epoch, count) -> sb.append("rpe_policy_inflight_requests{").append(base)
                .append(",epoch=\"").append(escape(epoch)).append("\"} ").append(Math.max(0, count.get())).append('\n'));
        sb.append("# HELP rpe_policy_requests_total Inbound requests by the policy epoch they were served under.\n")
                .append("# TYPE rpe_policy_requests_total counter\n");
        new TreeMap<>(served).forEach((epoch, count) -> sb.append("rpe_policy_requests_total{").append(base)
                .append(",epoch=\"").append(escape(epoch)).append("\"} ").append(count.sum()).append('\n'));
        sb.append("# HELP rpe_policy_mismatch_total Inbound requests served under another version of the policy than the epoch they carry.\n")
                .append("# TYPE rpe_policy_mismatch_total counter\n");
        sb.append("rpe_policy_mismatch_total{").append(base).append("} ").append(mismatched.sum()).append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** {@code policy.routes} of a delivered document as "METHOD /path" -> deadline in milliseconds. */
    static Map<String, Long> routeDeadlines(String document) {
        Map<String, Long> routes = new LinkedHashMap<>();
        try {
            JsonNode node = JSON.readTree(document == null ? "" : document);
            JsonNode policyRoutes = node == null ? null : node.path("policy").path("routes");
            if (policyRoutes != null && policyRoutes.isObject()) {
                policyRoutes.fields().forEachRemaining(route -> {
                    JsonNode deadline = route.getValue().path("deadlineMs");
                    if (deadline.isIntegralNumber() && deadline.asLong() > 0) {
                        routes.put(route.getKey(), deadline.asLong());
                    }
                });
            }
        } catch (java.io.IOException invalidDocument) {
            // the document is stored and served as received; it just carries no route deadlines
        }
        return Map.copyOf(routes);
    }

    /**
     * {@code policy.leakyBucket.ratePerSec} of a delivered document: the inbound release rate in requests per
     * second. Only the rate is read; any other leakyBucket member (queueCapacity, burst) is ignored. Null when the
     * document sets no valid rate (a missing or out-of-range value never changes the current rate).
     */
    static Double leakyBucketRate(String document) {
        try {
            JsonNode node = JSON.readTree(document == null ? "" : document);
            JsonNode rate = node == null ? null : node.path("policy").path("leakyBucket").path("ratePerSec");
            if (rate == null || !rate.isNumber() || !Double.isFinite(rate.asDouble()) || rate.asDouble() <= 0
                    || rate.asDouble() > 1_000_000) {
                return null;
            }
            return rate.asDouble();
        } catch (java.io.IOException invalidDocument) {
            return null;
        }
    }

    /**
     * Content hash of a delivered document: the first 16 hex digits of SHA-256 over the canonical JSON of its
     * {@code policy} member (keys sorted, no whitespace, integral numbers without a fraction). The Operator computes
     * the same hash of the spec it sends (k8s/operator specHash), so a matching hash proves what this sidecar holds.
     */
    static String contentHash(String document) {
        JsonNode policy = null;
        try {
            JsonNode node = JSON.readTree(document == null ? "" : document);
            policy = node == null ? null : node.get("policy");
        } catch (java.io.IOException invalidDocument) {
            // hashed as null
        }
        StringBuilder canonical = new StringBuilder();
        canonical(policy == null ? NullNode.getInstance() : policy, canonical);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    static void canonical(JsonNode node, StringBuilder sb) {
        switch (node.getNodeType()) {
            case OBJECT -> {
                List<String> names = new ArrayList<>();
                node.fieldNames().forEachRemaining(names::add);
                names.sort(null);
                sb.append('{');
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) sb.append(',');
                    quote(names.get(i), sb);
                    sb.append(':');
                    canonical(node.get(names.get(i)), sb);
                }
                sb.append('}');
            }
            case ARRAY -> {
                sb.append('[');
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) sb.append(',');
                    canonical(node.get(i), sb);
                }
                sb.append(']');
            }
            case STRING -> quote(node.asText(), sb);
            case NUMBER -> {
                double value = node.asDouble();
                if (value == Math.rint(value) && Math.abs(value) < 1e15) sb.append((long) value);
                else sb.append(new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString());
            }
            case BOOLEAN -> sb.append(node.asBoolean());
            default -> sb.append("null");
        }
    }

    private static void quote(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
    }
}
