package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.util.*;

/**
 * Labels each request by the services on <b>its own</b> path, so a chain is not judged by its worst service
 * on some other path: UNKNOWN if any monitored service on the path is UNKNOWN, else O if any is OVERLOADED, else STRESSED
 * if any is STRESSED, else H. Services without a sidecar ({@code unmonitored}) are ignored, as in chain collection today.
 */
public final class RequestLabeler {
    private RequestLabeler() {}

    public static List<LabelledSample> label(List<DeadlineSample> samples, Map<String, ServiceLoad> loads, Set<String> unmonitored) {
        var result = new ArrayList<LabelledSample>(samples.size());
        for (DeadlineSample sample : samples) result.add(new LabelledSample(sample, label(sample.path(), loads, unmonitored), sample.stratum()));
        return result;
    }

    public static RequestLabel label(Collection<String> path, Map<String, ServiceLoad> loads, Set<String> unmonitored) {
        boolean overloaded = false, stressed = false;
        for (String service : path) {
            if (unmonitored.contains(service)) continue;
            ServiceLoad load = loads.getOrDefault(service, ServiceLoad.UNKNOWN);
            if (load == ServiceLoad.UNKNOWN) return RequestLabel.UNKNOWN;
            if (load == ServiceLoad.OVERLOADED) overloaded = true;
            if (load == ServiceLoad.STRESSED) stressed = true;
        }
        return overloaded ? RequestLabel.O : stressed ? RequestLabel.STRESSED : RequestLabel.H;
    }
}
