package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.rrpe.intelligence.adaptive.DeadlineFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HealthyLatencyModelTests {
    private final HealthyLatencyModel model = new HealthyLatencyModel();
    private final AdaptiveConfig.DeadlineSettings cfg = demo();
    private HealthyModel add(String chain, int i, List<LabelledSample> window, long budget) {
        return model.update(chain, T0.plusSeconds(300L * i), window, budget, true, cfg, 6, 100, 10000);
    }
    /** Example 1 healthy traffic: 300 requests, body 150–350, top 3% cycling 380 / 400 / 420. */
    private List<LabelledSample> example1(int i, long budget) { return healthy(300, 150, 350, 9, new double[]{380, 400, 420}[i % 3], budget); }

    @Test void identifiesTheGroundTruthHealthyTimeAndTargetOfExampleOne() {
        HealthyModel m = null;
        for (int i = 0; i < 6; i++) m = add("c", i, example1(i, 2000), 2000);
        assertEquals("IDENTIFIED", m.status()); assertTrue(m.ready());
        assertEquals(400, m.thMs());
        assertEquals(29.652, m.sigmaMs(), 1e-6, "σ̂ = 1.4826 × MAD(380, 400, 420)");
        assertEquals(.2224, m.margin(), 1e-4);
        assertEquals(490, m.targetMs(), "D* = ceil10(400 × 1.2224)");
    }
    @Test void learnsDespiteSomeExceedancesAndIsNotResetByADeadlineChange() {
        for (int i = 0; i < 3; i++) add("c", i, example1(i, 2000), 2000);
        // the deadline is changed to 390 ms: the 400/420 tops of some windows now exceed it (≤ 1% of requests)
        HealthyModel m = null;
        for (int i = 3; i < 6; i++) m = add("c", i, example1(i, 390), 390);
        assertTrue(m.ready(), "exceedances do not stop learning");
        assertTrue(m.censoredShare() > 0 && m.censoredShare() <= .01);
        assertEquals(6, m.windows(), "history kept across the deadline change");
    }
    @Test void becomesCensoredWhenAtLeastOnePercentOfHealthyRequestsExceedTheDeadline() {
        HealthyModel m = null;
        // Example 3: 25% of healthy requests take longer than the 300 ms deadline
        for (int i = 0; i < 3; i++) {
            var w = new ArrayList<LabelledSample>();
            w.addAll(healthy(225, 150, 295, 0, 0, 300)); w.addAll(healthy(75, 310, 445, 3, 500, 300));
            m = add("c", i, w, 300);
        }
        assertEquals("CENSORED", m.status()); assertNull(m.thMs()); assertNull(m.targetMs());
        assertEquals(.25, m.censoredShare(), 1e-9);
        assertTrue(model.predictedExceed("c", 299) >= .25);
    }
    @Test void pooledP99IsNotTheMedianOfPerWindowP99s() {
        // 5 windows of 100 requests with 2, 2, 1, 1, 0 slow ones (1.2% slow): every per-window "p99" is almost its maximum
        int[] slow = {2, 2, 1, 1, 0}; var perWindow = new ArrayList<Double>(); HealthyModel m = null;
        var settings = new AdaptiveConfig.DeadlineSettings(1, 1, .99, 3, .10, .30, .01, .05, 1.645, 1, java.time.Duration.ZERO, 2, .05, .10, .005,
                1.5, .75, .8, .1, .25, 1, 2, 6, .99, 10, .05, 1, java.time.Duration.ofMinutes(30));
        for (int i = 0; i < 5; i++) {
            var w = healthy(100, 100, 100, slow[i], 1000, 5000);
            perWindow.add(TempoWindows.percentile99(w.stream().mapToDouble(s -> s.sample().durationMs()).toArray()));
            m = model.update("p", T0.plusSeconds(300L * i), w, 5000, true, settings, 5, 100, 10000);
        }
        assertEquals(100, RobustStatistics.median(perWindow.stream().mapToDouble(Double::doubleValue).toArray()), "median of window p99s misses the tail");
        assertEquals(1000, m.thMs(), "the pooled p99 sees the 1.2% slow requests");
    }
    @Test void resetForgetsTheChain() {
        for (int i = 0; i < 6; i++) add("c", i, example1(i, 2000), 2000);
        model.reset("c");
        assertEquals("LEARNING", model.model("c", cfg, 100, 10000).status());
        assertEquals(0, model.model("c", cfg, 100, 10000).windows());
    }
    @Test void windowsWithoutHealthyRequestsDoNotPushHealthyHistoryOut() {
        for (int i = 0; i < 6; i++) add("c", i, example1(i, 2000), 2000);
        var overloaded = new ArrayList<LabelledSample>();
        for (int i = 0; i < 300; i++) overloaded.add(labelled(900, 2000, RequestLabel.O));
        HealthyModel m = null;
        for (int i = 6; i < 20; i++) m = add("c", i, overloaded, 2000);
        assertEquals(400, m.thMs()); assertEquals("IDENTIFIED", m.status());
    }
    @Test void driftRelearnsOnlyAfterKDisjointWindows() {
        for (int i = 0; i < 6; i++) add("c", i, example1(i, 2000), 2000);
        var slower = healthy(300, 300, 600, 9, 700, 2000);
        var m = add("c", 6, slower, 2000);
        assertFalse(model.consumeRelearned("c"), "one drifted window is not enough (k = 2)");
        m = add("c", 7, slower, 2000);
        assertTrue(model.consumeRelearned("c"));
        assertEquals(2, m.windows()); assertEquals(700, m.thMs());
    }
    @Test void aCoverageGapFreezesTheHealthyTimeOfMixedPaths() {
        // Example 5: 70% cache path (p99 150), 30% database path (p99 600)
        java.util.function.IntFunction<List<LabelledSample>> mixed = i -> {
            var w = new ArrayList<LabelledSample>();
            w.addAll(healthy(210, 50, 140, 6, new double[]{145, 150, 155}[i % 3], 700, "gateway", "catalog-cache"));
            w.addAll(healthy(90, 300, 580, 3, new double[]{590, 600, 610}[i % 3], 700, "gateway", "catalog-db"));
            return w;
        };
        HealthyModel before = null;
        for (int i = 0; i < 6; i++) before = add("m", i, mixed.apply(i), 700);
        assertEquals("IDENTIFIED", before.status()); assertFalse(before.coverageGap());
        double mixture = before.thMs();
        assertTrue(mixture >= 580, "the mixture p99 is set by the database path: " + mixture);
        // the database path becomes STRESSED: only the cache path stays healthy
        HealthyModel after = null;
        for (int i = 6; i < 14; i++) {
            var w = new ArrayList<LabelledSample>();
            w.addAll(healthy(210, 50, 140, 6, 150, 700, "gateway", "catalog-cache"));
            for (int k = 0; k < 90; k++) w.add(labelled(700, 700, RequestLabel.STRESSED, "gateway", "catalog-db"));
            after = add("m", i, w, 700);
        }
        assertTrue(after.coverageGap(), "30% of traffic without healthy samples");
        assertEquals(mixture, after.thMs(), 40, "T_h is frozen near the mixture p99, not pulled down to the cache p99 (~150)");
        assertFalse(model.consumeRelearned("m"), "a change in the path mix is not drift");
    }
}
