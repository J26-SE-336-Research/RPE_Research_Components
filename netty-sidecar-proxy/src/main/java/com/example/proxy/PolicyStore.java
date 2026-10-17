package com.example.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Policies delivered by Component 3 (Operator and Admin Panel), keyed by epoch (control API contract A3.5). An
 * epoch is staged (held inactive) or active. The document is stored as received; the gateway route deadlines it
 * carries ({@code policy.routes["METHOD /path"].deadlineMs}) are applied when it becomes active.
 */
final class PolicyStore {
    enum State { STAGED, ACTIVE }

    record Entry(String epoch, long version, String hash, State state, String document,
                 Map<String, Long> routeDeadlines) {}

    static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Entry> epochs = new ConcurrentHashMap<>();
    private final Consumer<Entry> onActivated;
    private volatile String activeEpoch = "";

    PolicyStore(Consumer<Entry> onActivated) {
        this.onActivated = onActivated == null ? entry -> { } : onActivated;
    }

    /** Stores a policy document; {@code state} is staged or active. */
    synchronized Entry put(String epoch, long version, String hash, State state, String document) {
        if (epoch == null || epoch.isBlank()) throw new IllegalArgumentException("epoch is required");
        Entry entry = new Entry(epoch, version, hash == null ? "" : hash, state, document, routeDeadlines(document));
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

    Entry active() {
        String epoch = activeEpoch;
        return epoch.isEmpty() ? null : epochs.get(epoch);
    }

    String activeEpoch() { return activeEpoch; }

    String toJson() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode items = root.putArray("epochs");
        epochs.values().stream().sorted(Comparator.comparing(Entry::epoch)).forEach(entry -> items.addObject()
                .put("epoch", entry.epoch())
                .put("version", entry.version())
                .put("hash", entry.hash())
                .put("state", entry.state().name().toLowerCase()));
        root.put("activeEpoch", activeEpoch);
        return root.toString();
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
}
