package org.rrpe.intelligence.adaptive;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overlapping windows are not independent evidence. A window counts for persistence, learning and verification only
 * when it does not overlap the last counted window of the same chain: {@code windowEnd − lastCountedEnd ≥ window}.
 * With a 5 min window and a 1 min cycle that is once every 5 cycles; with 1 min / 15 s, once every 4.
 */
public class DisjointWindowTracker {
    private record Streak(int count, Instant lastEnd) {}
    private final Map<String, Instant> lastCounted = new ConcurrentHashMap<>();
    private final Map<String, Streak> streaks = new ConcurrentHashMap<>();

    public boolean isDisjoint(String chainKey, Instant windowEnd, Duration window) {
        Instant last = lastCounted.get(chainKey);
        return last == null || !Duration.between(last, windowEnd).minus(window).isNegative();
    }
    public void markCounted(String chainKey, Instant windowEnd) { lastCounted.put(chainKey, windowEnd); }
    public Instant lastCounted(String chainKey) { return lastCounted.get(chainKey); }

    /**
     * Updates and returns the number of consecutive counted windows in which {@code condition} held. Call it once per
     * counted window. It resets to 0 when the condition is false, and restarts at 1 after a gap (no counted window for
     * more than two window lengths).
     */
    public int streak(String chainKey, String condition, boolean holds, Instant windowEnd, Duration window) {
        String key = chainKey + "|" + condition;
        if (!holds) { streaks.remove(key); return 0; }
        Streak previous = streaks.get(key);
        int count = previous == null || Duration.between(previous.lastEnd(), windowEnd).compareTo(window.multipliedBy(2)) > 0 ? 1 : previous.count() + 1;
        streaks.put(key, new Streak(count, windowEnd));
        return count;
    }
    /** Current streak without updating it. */
    public int current(String chainKey, String condition) { Streak s = streaks.get(chainKey + "|" + condition); return s == null ? 0 : s.count(); }

    public void forget(String chainKey) {
        lastCounted.remove(chainKey);
        streaks.keySet().removeIf(key -> key.startsWith(chainKey + "|"));
    }
    public void forgetIf(java.util.function.Predicate<String> chainKey) {
        lastCounted.keySet().removeIf(chainKey);
        streaks.keySet().removeIf(key -> chainKey.test(key.substring(0, key.lastIndexOf('|'))));
    }
}
