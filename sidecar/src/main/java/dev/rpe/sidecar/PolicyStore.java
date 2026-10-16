package dev.rpe.sidecar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Policies held by this sidecar, keyed by epoch (contract A3.5). An epoch is staged (held inactive) or active.
 * The policy body is stored as received; the enforcement mechanisms read it as they are implemented.
 */
public final class PolicyStore {
    public enum State { STAGED, ACTIVE }

    public record Entry(String epoch, long version, String hash, State state, String policyJson) {}

    private final Map<String, Entry> epochs = new ConcurrentHashMap<>();
    private volatile String activeEpoch = "";

    public Entry put(String epoch, long version, String hash, State state, String policyJson) {
        if (epoch == null || epoch.isBlank()) {
            throw new IllegalArgumentException("epoch is required");
        }
        Entry e = new Entry(epoch, version, hash == null ? "" : hash, state, policyJson);
        epochs.put(epoch, e);
        if (state == State.ACTIVE) {
            activeEpoch = epoch;
        }
        return e;
    }

    public boolean activate(String epoch) {
        Entry e = epochs.get(epoch);
        if (e == null) {
            return false;
        }
        epochs.put(epoch, new Entry(e.epoch(), e.version(), e.hash(), State.ACTIVE, e.policyJson()));
        activeEpoch = epoch;
        return true;
    }

    public boolean retire(String epoch) {
        if (epoch.equals(activeEpoch)) {
            return false;
        }
        return epochs.remove(epoch) != null;
    }

    public Entry active() {
        return activeEpoch == null ? null : epochs.get(activeEpoch);
    }

    public String activeEpoch() {
        return activeEpoch;
    }

    public String toJson() {
        List<String> items = new ArrayList<>();
        epochs.values().stream().sorted((a, b) -> a.epoch().compareTo(b.epoch())).forEach(e -> items.add(
                "{\"epoch\":" + Json.quote(e.epoch()) + ",\"version\":" + e.version() + ",\"hash\":" + Json.quote(e.hash())
                        + ",\"state\":\"" + e.state().name().toLowerCase() + "\"}"));
        return "{\"epochs\":[" + String.join(",", items) + "],\"activeEpoch\":" + Json.quote(activeEpoch) + "}";
    }
}
