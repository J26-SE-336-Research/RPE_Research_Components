package org.rrpe.intelligence.adaptive;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeadlineMathTests {
    // the margin m comes from the spread of the healthy time, kept between 0.10 and 0.30
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
    // tightening is Case D: 80 percent of the deadline, rounded up, and never below the target
    @Test void restorationNeverDropsBelowTheHealthyTargetOrMoreThanTenPercent() {
        assertEquals(680, DeadlineMath.stepDownHealthy(850, 490, .80));
        assertEquals(550, DeadlineMath.stepDownHealthy(680, 490, .80));
        assertEquals(490, DeadlineMath.stepDownHealthy(550, 490, .80), "never below the healthy target");
        assertEquals(490, DeadlineMath.stepDownHealthy(490, 490, .80));
    }
    // input checks of the deadline functions
    @Test void missingOrInvalidPercentilesCannotCreateADeadline() {
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.margin(10, 0, 3, .1, .3));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.margin(Double.NaN, 1000, 3, .1, .3));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.target(Double.NaN, .1, 100, 10000));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.ceil10(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.stepUp(0, OptionalLong.empty(), 1.5, 10000));
    }
    // the steps up and down stay within their limits
    @org.junit.jupiter.api.Test void censoredLatencyUsesTheBaselineAsALowerBoundAndStepsAreBounded() {
        // a deadline that is too tight hides the healthy time: raise by half without a known target, up to the maximum
        assertEquals(150, DeadlineMath.stepUp(100, OptionalLong.empty(), 1.5, 10000));
        assertEquals(450, DeadlineMath.stepUp(300, OptionalLong.empty(), 1.5, 10000));
        assertEquals(680, DeadlineMath.stepUp(450, OptionalLong.empty(), 1.5, 10000));
        assertEquals(10000, DeadlineMath.stepUp(8000, OptionalLong.empty(), 1.5, 10000));
        assertEquals(620, DeadlineMath.stepUp(450, OptionalLong.of(620), 1.5, 10000), "a known target is never crossed");
        // example 1: lowering under overload goes 2000, 1500, 1130, 850 with a target of 490
        long d = 2000; long[] expected = {1500, 1130, 850, 640, 490, 490};
        for (long e : expected) { d = DeadlineMath.stepDownOverload(d, 490, .75); assertEquals(e, d); }
    }
}
