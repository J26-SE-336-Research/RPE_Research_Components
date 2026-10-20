package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class DisjointWindowTrackerTests {
    private int countedIn(Duration window, Duration cycle, int cycles) {
        var tracker = new DisjointWindowTracker(); Instant t = Instant.parse("2026-10-18T00:00:00Z"); int counted = 0;
        for (int i = 0; i < cycles; i++, t = t.plus(cycle)) {
            if (tracker.isDisjoint("c", t, window)) { tracker.markCounted("c", t); counted++; }
        }
        return counted;
    }
    @Test void fiveMinuteWindowsWithOneMinuteCyclesCountOncePerFiveCycles() { assertEquals(4, countedIn(Duration.ofMinutes(5), Duration.ofMinutes(1), 20)); }
    @Test void oneMinuteWindowsWithFifteenSecondCyclesCountOncePerFourCycles() { assertEquals(5, countedIn(Duration.ofMinutes(1), Duration.ofSeconds(15), 20)); }
    @Test void chainsAreIndependent() {
        var tracker = new DisjointWindowTracker(); Instant t = Instant.parse("2026-10-18T00:00:00Z");
        tracker.markCounted("a", t);
        assertFalse(tracker.isDisjoint("a", t.plusSeconds(60), Duration.ofMinutes(5)));
        assertTrue(tracker.isDisjoint("b", t.plusSeconds(60), Duration.ofMinutes(5)));
    }
    @Test void streaksCountConsecutiveCountedWindowsAndResetOnFalseOrGap() {
        var tracker = new DisjointWindowTracker(); Instant t = Instant.parse("2026-10-18T00:00:00Z"); Duration w = Duration.ofMinutes(5);
        assertEquals(1, tracker.streak("c", "o", true, t, w));
        assertEquals(2, tracker.streak("c", "o", true, t.plus(w), w));
        assertEquals(0, tracker.streak("c", "o", false, t.plus(w.multipliedBy(2)), w));
        assertEquals(1, tracker.streak("c", "o", true, t.plus(w.multipliedBy(3)), w));
        assertEquals(2, tracker.streak("c", "o", true, t.plus(w.multipliedBy(4)), w));
        assertEquals(1, tracker.streak("c", "o", true, t.plus(w.multipliedBy(7)), w), "a gap of more than two windows restarts the streak");
        assertEquals(1, tracker.current("c", "o"));
        assertEquals(0, tracker.current("c", "other"));
    }
    @Test void forgetClearsAChain() {
        var tracker = new DisjointWindowTracker(); Instant t = Instant.parse("2026-10-18T00:00:00Z");
        tracker.markCounted("POST /x | a > b", t); tracker.streak("POST /x | a > b", "o", true, t, Duration.ofMinutes(1));
        tracker.forgetIf(key -> key.startsWith("POST"));
        assertNull(tracker.lastCounted("POST /x | a > b")); assertEquals(0, tracker.current("POST /x | a > b", "o"));
    }
}
