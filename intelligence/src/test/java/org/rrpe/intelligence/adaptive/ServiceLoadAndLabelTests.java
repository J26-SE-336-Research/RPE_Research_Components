package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.rrpe.intelligence.adaptive.DeadlineFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** ServiceLoadClassifier and RequestLabeler. */
class ServiceLoadAndLabelTests {
    private final AdaptiveConfig.LoadSettings t = AdaptiveConfig.LoadSettings.defaults();
    private static ServiceLoadSnapshot snap(Double pressure, Double overload, Double bpRejected, Double breaker, Double cpu, Double throttle, Double memory) {
        return new ServiceLoadSnapshot("s", pressure, overload, bpRejected, breaker, cpu, throttle, memory, null, List.of());
    }
    private ServiceLoad load(ServiceLoadSnapshot s, Health guard) { return ServiceLoadClassifier.classify(s, guard, t).load(); }

    @Test void everyOverloadThresholdAndTheGuardFoldIn() {
        assertEquals(ServiceLoad.OVERLOADED, load(snap(80.0, null, null, null, null, null, null), Health.HEALTHY), "pressure 80% (percent)");
        assertEquals(ServiceLoad.STRESSED, load(snap(79.9, null, null, null, null, null, null), Health.HEALTHY));
        assertEquals(ServiceLoad.OVERLOADED, load(snap(null, 1.0, null, null, null, null, null), Health.HEALTHY), "overload rejections");
        assertEquals(ServiceLoad.OVERLOADED, load(snap(null, null, 2.0, null, null, null, null), Health.HEALTHY), "queue-full 503s");
        assertEquals(ServiceLoad.OVERLOADED, load(snap(null, null, null, 1.0, null, null, null), Health.HEALTHY), "breaker open");
        assertEquals(ServiceLoad.OVERLOADED, load(snap(null, null, null, null, .85, null, null), Health.HEALTHY), "CPU");
        assertEquals(ServiceLoad.OVERLOADED, load(snap(null, null, null, null, null, .25, null), Health.HEALTHY), "throttling");
        assertEquals(ServiceLoad.OVERLOADED, load(snap(null, null, null, null, null, null, .90), Health.HEALTHY), "memory");
        assertEquals(ServiceLoad.OVERLOADED, load(ServiceLoadSnapshot.unavailable("s"), Health.OVERLOADED), "deadline guard");
    }
    @Test void everyStressThresholdAndHealthyOtherwise() {
        assertEquals(ServiceLoad.STRESSED, load(snap(50.0, null, null, null, null, null, null), Health.HEALTHY));
        assertEquals(ServiceLoad.HEALTHY, load(snap(49.9, 0.0, 0.0, 0.0, null, null, null), Health.HEALTHY));
        assertEquals(ServiceLoad.STRESSED, load(snap(null, null, null, null, .70, null, null), Health.HEALTHY));
        assertEquals(ServiceLoad.STRESSED, load(ServiceLoadSnapshot.unavailable("s"), Health.STRESSED));
    }
    @Test void unavailableOptionalSignalsNeverBlock() {
        var r = ServiceLoadClassifier.classify(ServiceLoadSnapshot.unavailable("s"), Health.HEALTHY, t);
        assertEquals(ServiceLoad.HEALTHY, r.load());
        assertEquals(ServiceLoad.HEALTHY, load(null, Health.HEALTHY));
    }
    @Test void unknownTakesPrecedenceOverEverySignal() {
        var r = ServiceLoadClassifier.classify(snap(99.0, 5.0, null, null, null, null, null), Health.LEARNING_BASELINE, t);
        assertEquals(ServiceLoad.UNKNOWN, r.load()); assertTrue(r.reason().contains("LEARNING_BASELINE"));
        assertEquals(ServiceLoad.UNKNOWN, load(snap(null, null, null, null, null, null, null), null));
    }
    @Test void theReasonNamesTheSignal() {
        assertTrue(ServiceLoadClassifier.classify(snap(95.0, null, null, null, null, null, null), Health.HEALTHY, t).reason().contains("queue pressure 95%"));
    }
    @Test void everyServiceIsClassified() {
        var map = ServiceLoadClassifier.classify(List.of("a", "b"), Map.of("a", snap(85.0, null, null, null, null, null, null)), Map.of("a", Health.HEALTHY, "b", Health.HEALTHY), t);
        assertEquals(ServiceLoad.OVERLOADED, map.get("a").load()); assertEquals(ServiceLoad.HEALTHY, map.get("b").load());
    }

    @Test void requestsAreLabelledByTheirOwnPath() {
        var loads = Map.of("gateway", ServiceLoad.HEALTHY, "order", ServiceLoad.HEALTHY, "payment", ServiceLoad.OVERLOADED, "inventory", ServiceLoad.STRESSED);
        var samples = List.of(sample(100, 1000, "gateway", "order"), sample(100, 1000, "gateway", "order", "payment"),
                sample(100, 1000, "gateway", "order", "inventory"), sample(100, 1000, "gateway", "payment", "inventory"));
        var labels = RequestLabeler.label(samples, loads, Set.of()).stream().map(LabelledSample::label).toList();
        assertEquals(List.of(RequestLabel.H, RequestLabel.O, RequestLabel.STRESSED, RequestLabel.O), labels,
                "a chain is not judged by its worst service on another path");
    }
    @Test void unknownBeatsOverloadedAndUnmonitoredHopsAreIgnored() {
        var loads = Map.of("gateway", ServiceLoad.HEALTHY, "payment", ServiceLoad.OVERLOADED, "order", ServiceLoad.UNKNOWN);
        assertEquals(RequestLabel.UNKNOWN, RequestLabeler.label(List.of("gateway", "order", "payment"), loads, Set.of()));
        assertEquals(RequestLabel.H, RequestLabeler.label(List.of("gateway", "legacy"), Map.of("gateway", ServiceLoad.HEALTHY), Set.of("legacy")));
        assertEquals(RequestLabel.UNKNOWN, RequestLabeler.label(List.of("gateway", "missing"), Map.of("gateway", ServiceLoad.HEALTHY), Set.of()));
    }
    @Test void theStratumIsTheSortedPath() {
        var labelled = RequestLabeler.label(List.of(sample(1, 10, "payment", "gateway", "order")), Map.of(), Set.of("gateway", "order", "payment"));
        assertEquals("gateway>order>payment", labelled.get(0).stratum());
    }
}
