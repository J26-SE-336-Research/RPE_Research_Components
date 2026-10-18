package org.rrpe.intelligence.adaptive;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeadlineMathTests {
    // v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md) - the 10/20/30% bands are replaced by m = clamp(3σ̂/T_h, 0.10, 0.30)
    @Test void correctedMarginBandsHaveExactBoundaries() {
        assertEquals(.10, DeadlineMath.margin(10, 1000, 3, .10, .30), "small spread: the 10% floor");
        assertEquals(.10, DeadlineMath.margin(1000 * .10 / 3, 1000, 3, .10, .30), 1e-12);
        assertEquals(.2224, DeadlineMath.margin(29.652, 400, 3, .10, .30), 1e-4, "guide example 1");
        assertEquals(.30, DeadlineMath.margin(100, 1000, 3, .10, .30), 1e-12);
        assertEquals(.30, DeadlineMath.margin(500, 1000, 3, .10, .30), "large spread: the 30% ceiling");
        assertEquals(490, DeadlineMath.target(400, .2224, 100, 10000), "D* = ceil10(T_h × (1 + m))");
        assertEquals(100, DeadlineMath.target(20, .10, 100, 10000), "bounded below");
        assertEquals(10000, DeadlineMath.target(9500, .30, 100, 10000), "bounded above");
        assertEquals(1130, DeadlineMath.ceil10(1125)); assertEquals(850, DeadlineMath.ceil10(850)); assertEquals(850, DeadlineMath.ceil10(847.5));
    }
    // v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md) - restoration is now Case D, max(D*, ceil10(0.80·D)), never below D*
    @Test void restorationNeverDropsBelowTheHealthyTargetOrMoreThanTenPercent() {
        assertEquals(680, DeadlineMath.stepDownHealthy(850, 490, .80));
        assertEquals(550, DeadlineMath.stepDownHealthy(680, 490, .80));
        assertEquals(490, DeadlineMath.stepDownHealthy(550, 490, .80), "never below the healthy target");
        assertEquals(490, DeadlineMath.stepDownHealthy(490, 490, .80));
    }
    // v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md) - the same validation, for the v2 functions
    @Test void missingOrInvalidPercentilesCannotCreateADeadline() {
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.margin(10, 0, 3, .1, .3));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.margin(Double.NaN, 1000, 3, .1, .3));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.target(Double.NaN, .1, 100, 10000));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.ceil10(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.stepUp(0, OptionalLong.empty(), 1.5, 10000));
    }
    // v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md) - increaseCensored/clamp are replaced by the bounded geometric steps
    @org.junit.jupiter.api.Test void censoredLatencyUsesTheBaselineAsALowerBoundAndStepsAreBounded() {
        // a too-tight deadline censors the healthy time: raise ×1.5 without a known target, capped at the maximum
        assertEquals(150, DeadlineMath.stepUp(100, OptionalLong.empty(), 1.5, 10000));
        assertEquals(450, DeadlineMath.stepUp(300, OptionalLong.empty(), 1.5, 10000));
        assertEquals(680, DeadlineMath.stepUp(450, OptionalLong.empty(), 1.5, 10000));
        assertEquals(10000, DeadlineMath.stepUp(8000, OptionalLong.empty(), 1.5, 10000));
        assertEquals(620, DeadlineMath.stepUp(450, OptionalLong.of(620), 1.5, 10000), "a known target is never crossed");
        // guide example 1: lowering under overload 2000 → 1500 → 1130 → 850 with D* = 490
        long d = 2000; long[] expected = {1500, 1130, 850, 640, 490, 490};
        for (long e : expected) { d = DeadlineMath.stepDownOverload(d, 490, .75); assertEquals(e, d); }
    }
}
