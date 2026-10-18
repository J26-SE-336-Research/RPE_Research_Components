package org.rrpe.intelligence.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deadline v2 statistics: one-sided Wilson bounds, scaled MAD and the weighted nearest-rank quantile. */
class RobustStatisticsTests {
    private static final double Z = 1.645;
    @Test void zeroEventsInOneHundredStillAllowAboutTwoPointSixPercent() {
        var p = RobustStatistics.wilsonOneSided(0, 100, Z, 1);
        assertEquals(0, p.p()); assertEquals(0, p.lower());
        assertEquals(.0263, p.upper(), .0005, "a zero rate cannot be proven at n = 100");
    }
    @Test void fiveHundredFortyOneRequestsProveAtMostHalfAPercent() {
        assertTrue(RobustStatistics.wilsonOneSided(0, 541, Z, 1).upper() <= .005);
    }
    @Test void lowerBoundIsMonotoneInEvents() {
        double previous = -1;
        for (int x = 0; x <= 30; x++) { double lb = RobustStatistics.wilsonOneSided(x, 100, Z, 1).lower(); assertTrue(lb >= previous); previous = lb; }
    }
    @Test void aDesignEffectWidensTheInterval() {
        var plain = RobustStatistics.wilsonOneSided(5, 100, Z, 1); var clustered = RobustStatistics.wilsonOneSided(5, 100, Z, 2);
        assertTrue(clustered.lower() < plain.lower()); assertTrue(clustered.upper() > plain.upper());
        assertEquals(plain.p(), clustered.p());
    }
    @Test void noRequestsMeansNothingIsKnown() {
        var p = RobustStatistics.wilsonOneSided(0, 0, Z, 1);
        assertEquals(0, p.lower()); assertEquals(1, p.upper());
        assertThrows(IllegalArgumentException.class, () -> RobustStatistics.wilsonOneSided(3, 2, Z, 1));
    }
    @Test void scaledMadEstimatesSigma() {
        assertEquals(29.652, RobustStatistics.scaledMad(List.of(380.0, 400.0, 420.0)), 1e-9);
    }
    @Test void weightedNearestRankMatchesNearestRankAndHandlesCensoredValues() {
        var equal = new ArrayList<double[]>();
        for (int i = 1; i <= 100; i++) equal.add(new double[]{i, 1});
        assertEquals(99, RobustStatistics.weightedNearestRank(equal, .99));
        assertEquals(TempoWindows.percentile99(java.util.stream.IntStream.rangeClosed(1, 100).asDoubleStream().toArray()),
                RobustStatistics.weightedNearestRank(equal, .99));
        var censored = new ArrayList<>(equal.subList(0, 98));
        censored.add(new double[]{Double.POSITIVE_INFINITY, 1}); censored.add(new double[]{Double.POSITIVE_INFINITY, 1});
        assertEquals(Double.POSITIVE_INFINITY, RobustStatistics.weightedNearestRank(censored, .99), "2% censored: p99 is only known to be ≥ D");
        var weighted = List.of(new double[]{100, 1}, new double[]{600, 3});
        assertEquals(600, RobustStatistics.weightedNearestRank(weighted, .5), "weights move the quantile");
    }
}
