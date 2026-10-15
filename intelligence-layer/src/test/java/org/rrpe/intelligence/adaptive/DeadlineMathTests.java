package org.rrpe.intelligence.adaptive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeadlineMathTests {
    @Test void correctedMarginBandsHaveExactBoundaries() {
        assertEquals(.10, DeadlineMath.increase(1100, 1000).safetyMargin());
        assertEquals(1210, DeadlineMath.increase(1100, 1000).milliseconds());
        assertEquals(.20, DeadlineMath.increase(1101, 1000).safetyMargin());
        assertEquals(.20, DeadlineMath.increase(1250, 1000).safetyMargin());
        assertEquals(1500, DeadlineMath.increase(1250, 1000).milliseconds());
        assertEquals(.30, DeadlineMath.increase(1251, 1000).safetyMargin());
        assertEquals(1440, DeadlineMath.increase(1200, 1000).milliseconds());
    }
    @Test void restorationNeverDropsBelowTheHealthyTargetOrMoreThanTenPercent() {
        assertEquals(1296, DeadlineMath.restore(1000, 1440));
        assertEquals(1167, DeadlineMath.restore(1000, 1296));
        assertEquals(1100, DeadlineMath.restore(1000, 1167));
        assertEquals(1100, DeadlineMath.restore(1000, 1100));
    }
    @Test void missingOrInvalidPercentilesCannotCreateADeadline() {
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.increase(1200, 0));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.increase(Double.NaN, 1000));
        assertThrows(IllegalArgumentException.class, () -> DeadlineMath.restore(1000, Double.POSITIVE_INFINITY));
    }
}
