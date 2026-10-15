package org.rrpe.intelligence.adaptive;

import java.util.Arrays;
import org.rrpe.intelligence.adaptive.AdaptiveModels.Statistic;

public final class RobustStatistics {
    private RobustStatistics() {}
    public static double median(double[] input) {
        if (input.length == 0) throw new IllegalArgumentException("Median requires samples");
        double[] values = input.clone(); Arrays.sort(values); int middle = values.length / 2;
        return values.length % 2 == 0 ? values[middle - 1] / 2 + values[middle] / 2 : values[middle];
    }
    public static Statistic summarize(double[] input) {
        double median = median(input);
        return new Statistic(median, median(Arrays.stream(input).map(value -> Math.abs(value - median)).toArray()));
    }
}
