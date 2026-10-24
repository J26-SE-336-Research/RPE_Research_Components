package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * C3 experiment E8 as a simulation: after a persistent healthy-rate change, does the joint card keep the deadline calibrated
 * better than the rate alone with later relearning? Writes intelligence/evaluation/joint-e8-simulation.md rows when
 * {@code -Djoint.e8.out=<file>} is set.
 */
class JointE8Tests {
    static final AdaptiveConfig.Settings SETTINGS = DeadlineExamplesTests.settings();
    static final AdaptiveConfig.DeadlineSettings DEADLINE = AdaptiveConfig.DeadlineSettings.demo();
    static final int MINUTES = 15;

    record Case(JointSimulation.Example example, Integer overloadAt, double overloadMs, String measures) {}
    static final List<Case> CASES = List.of(
            new Case(JointSimulation.capacityDrop(), null, 0, "1: capacity drop 100 → 60/s, Q = 8"),
            new Case(JointSimulation.capacityDropBursty(), null, 0, "1b: capacity drop 100 → 60/s, bursty healthy traffic, Q = 24"),
            new Case(JointSimulation.recovery(), 3, 300, "2: recovery 50 → 100/s, Q = 8, overload (+300 ms) from minute 3"));

    @Test void jointKeepsTheDeadlineCalibratedAndPaymentCompletionIsUnchanged() throws Exception {
        var rows = new StringBuilder("| Case | Arm | Healthy false-signal rate | First cancel signal after the overload | Minutes outside the healthy band | "
                + "Reviewer decisions | Payment completion | Final deadline | Final rate |\n|---|---|---|---|---|---|---|---|---|\n");
        for (var c : CASES) {
            var a = JointSimulation.e8(c.example(), false, SETTINGS, DEADLINE, MINUTES, c.overloadAt(), c.overloadMs());
            var b = JointSimulation.e8(c.example(), true, SETTINGS, DEADLINE, MINUTES, c.overloadAt(), c.overloadMs());
            for (var r : List.of(a, b)) rows.append(row(c, r)).append('\n');
            assertTrue(b.falseSignalRate() <= a.falseSignalRate(), c.measures());
            assertTrue(b.minutesOutsideBand() <= a.minutesOutsideBand(), c.measures());
            assertTrue(b.proposals() <= a.proposals(), c.measures() + ": decisions");
            assertEquals(a.paymentCompletion(), b.paymentCompletion(), 1e-9, c.measures() + ": payment completion unchanged");
            if (c.overloadAt() != null) {
                assertNotNull(b.firstSignalMinutes(), "joint: the overload is signalled");
                assertTrue(a.firstSignalMinutes() == null || b.firstSignalMinutes() < a.firstSignalMinutes(), "joint signals earlier");
            }
        }
        // case 1b: the old deadline flags healthy requests, the joint one does not
        var q24a = JointSimulation.e8(JointSimulation.capacityDropBursty(), false, SETTINGS, DEADLINE, MINUTES, null, 0);
        var q24b = JointSimulation.e8(JointSimulation.capacityDropBursty(), true, SETTINGS, DEADLINE, MINUTES, null, 0);
        assertTrue(q24a.falseSignalRate() > q24b.falseSignalRate(), q24a + " vs " + q24b);
        String out = System.getProperty("joint.e8.out");
        if (out != null) Files.writeString(Path.of(out), rows.toString());
    }
    static String row(Case c, JointSimulation.E8Result r) {
        return String.format(Locale.ROOT, "| %s | %s | %.1f%% | %s | %d of %d | %d | %.0f%% | %d ms | %s/s |", c.measures(), r.arm(), 100 * r.falseSignalRate(),
                c.overloadAt() == null ? "n/a" : r.firstSignalMinutes() == null ? "none within " + (MINUTES - c.overloadAt()) + " min" : r.firstSignalMinutes() + " min",
                r.minutesOutsideBand(), c.overloadAt() == null ? MINUTES : c.overloadAt() - 1, r.proposals(), 100 * r.paymentCompletion(), r.finalDeadlineMs(),
                r.finalRate() == Math.rint(r.finalRate()) ? String.valueOf((long) r.finalRate()) : String.valueOf(r.finalRate()));
    }
}
