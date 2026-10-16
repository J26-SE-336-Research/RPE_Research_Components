package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeadlineMathTests {
    @Test
    void correctedMarginBandsHaveExactBoundaries() {
        assertEquals(.10, DeadlineMath.increase(1100, 1000).safetyMargin());
        assertEquals(1210, DeadlineMath.increase(1100, 1000).milliseconds());
        assertEquals(.20, DeadlineMath.increase(1101, 1000).safetyMargin());
        assertEquals(.20, DeadlineMath.increase(1250, 1000).safetyMargin());
        assertEquals(1500, DeadlineMath.increase(1250, 1000).milliseconds());
        assertEquals(.30, DeadlineMath.increase(1251, 1000).safetyMargin());
        assertEquals(1440, DeadlineMath.increase(1200, 1000).milliseconds());
    }

    @Test
    void restorationNeverDropsBelowTheHealthyTargetOrMoreThanTenPercent() {
        assertEquals(1296, DeadlineMath.restore(1000, 1440));
        assertEquals(1167, DeadlineMath.restore(1000, 1296));
        assertEquals(1100, DeadlineMath.restore(1000, 1167));
        assertEquals(1100, DeadlineMath.restore(1000, 1100));
    }

    @Test
    void missingOrInvalidPercentilesCannotCreateADeadline() {
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.increase(1200, 0));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.increase(Double.NaN, 1000));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeadlineMath.restore(1000, Double.POSITIVE_INFINITY));
    }

    @org.junit.jupiter.api.Test
    void censoredLatencyUsesTheBaselineAsALowerBoundAndStepsAreBounded() {
        // a too-tight 100 ms deadline cuts requests at ~100 ms while the uncensored baseline p99 is
        // 140 ms
        assertEquals(154, DeadlineMath.increaseCensored(100, 140, 100).milliseconds());
        assertEquals(
                1440,
                DeadlineMath.increaseCensored(1200, 1000, 1000).milliseconds(),
                "uncensored increases are unchanged");
        assertEquals(
                1210,
                DeadlineMath.increaseCensored(1100, 1000, 2000).milliseconds(),
                "not censored below the deadline");
        assertEquals(150, DeadlineMath.clamp(154, 100, .5));
        assertEquals(154, DeadlineMath.clamp(154, 140, .5));
    }
}
