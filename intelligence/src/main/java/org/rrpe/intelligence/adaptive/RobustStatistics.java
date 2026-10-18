package org.rrpe.intelligence.adaptive;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.rrpe.intelligence.adaptive.AdaptiveModels.Proportion;
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

    /** Consistency constant: 1.4826 × MAD estimates σ for normally distributed data (Rousseeuw & Croux 1993). */
    public static final double MAD_SCALE = 1.4826;

    /** Scaled MAD, σ̂ = 1.4826 × MAD. */
    public static double scaledMad(List<Double> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Scaled MAD requires samples");
        return MAD_SCALE * summarize(values.stream().mapToDouble(Double::doubleValue).toArray()).mad();
    }

    /**
     * One-sided Wilson score bounds for x events out of n (Brown, Cai & DasGupta 2001). The effective sample size is
     * {@code n / designEffect}, so clustered samples (requests of the same burst) widen the interval. n = 0 gives [0, 1].
     */
    public static Proportion wilsonOneSided(int x, int n, double z, double designEffect) {
        if (x < 0 || n < 0 || x > n || z <= 0 || designEffect < 1) throw new IllegalArgumentException("Invalid proportion");
        if (n == 0) return new Proportion(0, 0, 0, 0, 1);
        double ne = n / designEffect; double p = (double) x / n;
        double z2 = z * z, den = 1 + z2 / ne, c = p + z2 / (2 * ne);
        double h = z * Math.sqrt(p * (1 - p) / ne + z2 / (4 * ne * ne));
        return new Proportion(x, n, p, Math.max(0, (c - h) / den), Math.min(1, (c + h) / den));
    }

    /**
     * Weighted nearest-rank quantile: the smallest value whose cumulative weight reaches {@code q} of the total.
     * Each entry is {value, weight}; a censored value is {@code +∞} and can be the answer. With equal weights this is the
     * ordinary nearest-rank quantile {@code x[ceil(q·n)]}.
     */
    public static double weightedNearestRank(List<double[]> valueWeight, double q) {
        if (valueWeight.isEmpty() || !(q > 0 && q <= 1)) throw new IllegalArgumentException("Quantile requires samples and 0 < q ≤ 1");
        var sorted = valueWeight.stream().sorted(Comparator.comparingDouble(v -> v[0])).toList();
        double total = sorted.stream().mapToDouble(v -> v[1]).sum();
        if (!(total > 0)) throw new IllegalArgumentException("Quantile requires positive weight");
        double target = q * total - 1e-9 * total, cumulative = 0;
        for (double[] entry : sorted) { cumulative += entry[1]; if (cumulative >= target) return entry[0]; }
        return sorted.get(sorted.size() - 1)[0];
    }
}
