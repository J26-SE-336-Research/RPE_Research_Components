package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LeverDiagnosisTests {
    private final AdaptiveConfig.LeverSettings lever = AdaptiveConfig.LeverSettings.defaults();
    private final AdaptiveConfig.LoadSettings load = AdaptiveConfig.LoadSettings.defaults();
    private static ServiceLoadSnapshot snap(Double pressure, Double cpu, Double throttle, Double queueWait) {
        return new ServiceLoadSnapshot("inventory", pressure, null, null, null, cpu, throttle, null, queueWait, List.of());
    }
    @Test void queueWaitWithIdleCpuIsARateProblem() {
        var r = LeverDiagnosis.diagnose("inventory", snap(95.0, .25, null, .75), null, lever, load);
        assertEquals(Lever.RATE, r.lever()); assertFalse(r.inferred());
        assertEquals(Lever.RATE, LeverDiagnosis.diagnose("inventory", snap(null, null, null, .5), null, lever, load).lever(), "unknown CPU never blocks");
    }
    @Test void saturatedOrThrottledCpuIsACapacityProblem() {
        assertEquals(Lever.CAPACITY, LeverDiagnosis.diagnose("s", snap(null, .90, null, .2), null, lever, load).lever());
        assertEquals(Lever.CAPACITY, LeverDiagnosis.diagnose("s", snap(null, .5, .30, null), null, lever, load).lever());
        assertEquals(Lever.CAPACITY, LeverDiagnosis.diagnose("s", snap(null, .90, null, .9), null, lever, load).lever(), "busy CPU is not a rate problem");
    }
    @Test void withoutRateOrCapacitySignalsTheDeadlineIsTheLever() {
        assertEquals(Lever.DEADLINE, LeverDiagnosis.diagnose("s", snap(40.0, .5, null, .2), null, lever, load).lever());
        assertEquals(Lever.DEADLINE, LeverDiagnosis.diagnose("s", null, null, lever, load).lever());
    }
    @Test void withoutQueueWaitAFullQueueIsAnInferredRateProblem() {
        var r = LeverDiagnosis.diagnose("s", snap(85.0, null, null, null), null, lever, load);
        assertEquals(Lever.RATE, r.lever()); assertTrue(r.inferred());
        assertEquals(Lever.DEADLINE, LeverDiagnosis.diagnose("s", snap(85.0, .80, null, null), null, lever, load).lever(), "CPU not low");
    }
    @Test void theChainPrefersRateThenCapacityAndIsNoneWithoutOverload() {
        var snaps = Map.of("a", snap(null, .9, null, null), "b", snap(null, .2, null, .8), "c", snap(null, null, null, null));
        assertEquals(Lever.RATE, LeverDiagnosis.chain(List.of("a", "b", "c"), snaps, lever, load).lever());
        assertEquals(Lever.CAPACITY, LeverDiagnosis.chain(List.of("a", "c"), snaps, lever, load).lever());
        assertEquals(Lever.DEADLINE, LeverDiagnosis.chain(List.of("c"), snaps, lever, load).lever());
        assertEquals(Lever.NONE, LeverDiagnosis.chain(List.of(), snaps, lever, load).lever());
    }
}
