package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Instant;
import java.util.*;

/** Small builders for deadline v2 tests. */
final class DeadlineFixtures {
    private DeadlineFixtures() {}
    static final Instant T0 = Instant.parse("2026-10-13T00:00:00Z");

    static DeadlineSample sample(double ms, long budget, String... path) {
        return new DeadlineSample("t", "GET", "/r", T0, ms, 200, budget, new TreeSet<>(List.of(path.length == 0 ? new String[]{"gateway", "order"} : path)),
                ms >= budget, ms >= budget, false, null, null);
    }
    static LabelledSample labelled(double ms, long budget, RequestLabel label, String... path) {
        var s = sample(ms, budget, path); return new LabelledSample(s, label, s.stratum());
    }
    /** n healthy samples: (n − tops) spread between lo and hi, then {@code tops} at {@code top}. */
    static List<LabelledSample> healthy(int n, double lo, double hi, int tops, double top, long budget, String... path) {
        var out = new ArrayList<LabelledSample>();
        int body = n - tops;
        for (int i = 0; i < body; i++) out.add(labelled(body == 1 ? lo : lo + (hi - lo) * i / (body - 1), budget, RequestLabel.H, path));
        for (int i = 0; i < tops; i++) out.add(labelled(top, budget, RequestLabel.H, path));
        return out;
    }
    static AdaptiveConfig.DeadlineSettings demo() { return AdaptiveConfig.DeadlineSettings.demo(); }
}
