package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The healthy time T_h of each chain: the p99 of requests whose own path only touched healthy services, over the last
 * learning windows that are separate, complete and not cut. Each path is weighted by its share of the traffic.
 * <ul>
 *   <li>Expired or cut requests count as endless, since we only know they took at least the deadline.</li>
 *   <li>IDENTIFIED: T_h is below the smallest deadline of any learning window with such a request, so the p99 is
 *       exact. Otherwise CENSORED: we only know the p99 is at least the deadline, so the deadline is too short.</li>
 *   <li>Each path is weighted by its share of all requests over its share of healthy requests, so a fast path that
 *       stays healthy cannot pull T_h down while a slow path is stressed. A path with 5 percent of the traffic or more
 *       but too few healthy samples is a gap: T_h keeps its last known value and tightening is blocked.</li>
 *   <li>Learning tolerates deadline exceedances and is not reset when the deadline changes; only {@link #reset} clears it.</li>
 *   <li>Drift: if the healthy p99 of a window differs from T_h by more than 25% for k windows, older history is dropped.</li>
 * </ul>
 */
public class HealthyLatencyModel {
    /** One healthy request: duration (+∞ if censored) and its stratum. */
    private record Entry(double ms, String stratum) {}
    /** One eligible learning window. */
    private record Window(Instant end, long budget, List<Entry> healthy, Map<String, Integer> allByStratum, Double healthyP99) {
        boolean hadCensored() { return healthy.stream().anyMatch(e -> Double.isInfinite(e.ms())); }
    }
    private static class ChainModel {
        final Deque<Window> windows = new ArrayDeque<>();
        Double lastIdentifiedTh; Double driftReference; int driftStreak; boolean relearned;
    }
    private final Map<String, ChainModel> chains = new ConcurrentHashMap<>();

    /**
     * Adds an eligible window (when {@code eligible}) and returns the current model.
     * @param nLearn the number of learning windows kept (existing setting {@code baseline-samples})
     */
    public HealthyModel update(String chainKey, Instant windowEnd, List<LabelledSample> window, long budgetMs, boolean eligible,
            AdaptiveConfig.DeadlineSettings cfg, int nLearn, long minMs, long maxMs) {
        ChainModel chain = chains.computeIfAbsent(chainKey, ignored -> new ChainModel());
        synchronized (chain) {
            if (eligible && !window.isEmpty()) {
                var healthy = new ArrayList<Entry>(); var all = new TreeMap<String, Integer>();
                for (var item : window) {
                    all.merge(item.stratum(), 1, Integer::sum);
                    if (item.label() != RequestLabel.H) continue;
                    var s = item.sample();
                    healthy.add(new Entry(s.expired() || s.cut() ? Double.POSITIVE_INFINITY : s.durationMs(), item.stratum()));
                }
                // The window's own healthy p99 (for the spread and drift) only when every busy path has enough healthy requests;
                // otherwise a change in the path mix would look like a change in the healthy time.
                var healthyByStratum = new HashMap<String, Integer>();
                for (var e : healthy) healthyByStratum.merge(e.stratum(), 1, Integer::sum);
                boolean covered = all.entrySet().stream().noneMatch(e -> e.getValue() / (double) window.size() >= cfg.coverageMinShare()
                        && healthyByStratum.getOrDefault(e.getKey(), 0) < cfg.coverageMinSamples());
                Double p99 = null;
                if (covered && healthy.size() >= cfg.coverageMinSamples()) {
                    double value = RobustStatistics.weightedNearestRank(healthy.stream().map(e -> new double[]{e.ms(), 1}).toList(), cfg.quantile());
                    if (Double.isFinite(value)) p99 = value;
                }
                HealthyModel before = compute(chain, cfg, minMs, maxMs);
                // A window with no healthy request teaches nothing about the healthy time; it must not push older
                // healthy history out (during a full overload T_h stays what it was).
                if (healthy.isEmpty()) return before;
                chain.windows.addLast(new Window(windowEnd, budgetMs, List.copyOf(healthy), Map.copyOf(all), p99));
                while (chain.windows.size() > nLearn) chain.windows.removeFirst();
                // Drift: the healthy p99 of new windows keeps differing by more than the threshold from T_h as it was when
                // the drift started (the pooled T_h itself already moves toward the new windows).
                Double reference = chain.driftStreak > 0 ? chain.driftReference : "IDENTIFIED".equals(before.status()) ? before.thMs() : null;
                if (p99 != null && reference != null && Math.abs(p99 - reference) / reference > cfg.driftThreshold()) {
                    if (chain.driftStreak++ == 0) chain.driftReference = reference;
                    if (chain.driftStreak >= cfg.driftWindows()) {
                        while (chain.windows.size() > cfg.driftWindows()) chain.windows.removeFirst();
                        chain.lastIdentifiedTh = null; chain.driftStreak = 0; chain.driftReference = null; chain.relearned = true;
                    }
                } else if (p99 != null) { chain.driftStreak = 0; chain.driftReference = null; }
            }
            return compute(chain, cfg, minMs, maxMs);
        }
    }

    public HealthyModel model(String chainKey, AdaptiveConfig.DeadlineSettings cfg, long minMs, long maxMs) {
        ChainModel chain = chains.get(chainKey);
        if (chain == null) return empty();
        synchronized (chain) { return compute(chain, cfg, minMs, maxMs); }
    }

    /** e_pred_H(D'): the weighted share of the healthy learning set that would exceed {@code candidateMs}. */
    public double predictedExceed(String chainKey, double candidateMs) {
        ChainModel chain = chains.get(chainKey);
        if (chain == null) return 1;
        synchronized (chain) {
            var weighted = weighted(chain);
            double total = weighted.stream().mapToDouble(v -> v[1]).sum();
            if (!(total > 0)) return 1;
            return weighted.stream().filter(v -> v[0] > candidateMs).mapToDouble(v -> v[1]).sum() / total;
        }
    }

    /** True once after a drift re-learn (for the audit trail). */
    public boolean consumeRelearned(String chainKey) {
        ChainModel chain = chains.get(chainKey);
        if (chain == null) return false;
        synchronized (chain) { boolean r = chain.relearned; chain.relearned = false; return r; }
    }

    public void reset(String chainKey) { chains.remove(chainKey); }
    public void resetIf(Predicate<String> chainKey) { chains.keySet().removeIf(chainKey); }

    private static HealthyModel empty() { return new HealthyModel("LEARNING", false, null, null, null, null, 0, 0, 0, false, Map.of()); }

    /** Stratum weights: share among all requests / share among healthy requests, over the learning windows. */
    private static Map<String, Double> weights(ChainModel chain, Map<String, Double> allShares) {
        var healthyCount = new HashMap<String, Integer>(); int healthyTotal = 0;
        for (var w : chain.windows) for (var e : w.healthy()) { healthyCount.merge(e.stratum(), 1, Integer::sum); healthyTotal++; }
        var weights = new HashMap<String, Double>();
        for (var entry : healthyCount.entrySet()) {
            double healthyShare = entry.getValue() / (double) healthyTotal;
            weights.put(entry.getKey(), allShares.getOrDefault(entry.getKey(), 0.0) / healthyShare);
        }
        return weights;
    }
    private static Map<String, Double> allShares(ChainModel chain) {
        var all = new TreeMap<String, Integer>(); int total = 0;
        for (var w : chain.windows) for (var e : w.allByStratum().entrySet()) { all.merge(e.getKey(), e.getValue(), Integer::sum); total += e.getValue(); }
        var shares = new TreeMap<String, Double>();
        for (var e : all.entrySet()) shares.put(e.getKey(), e.getValue() / (double) total);
        return shares;
    }
    private static List<double[]> weighted(ChainModel chain) {
        var w = weights(chain, allShares(chain));
        var result = new ArrayList<double[]>();
        for (var window : chain.windows) for (var e : window.healthy()) {
            double weight = w.getOrDefault(e.stratum(), 0.0);
            if (weight > 0) result.add(new double[]{e.ms(), weight});
        }
        return result;
    }

    private static HealthyModel compute(ChainModel chain, AdaptiveConfig.DeadlineSettings cfg, long minMs, long maxMs) {
        var allShares = allShares(chain);
        var samples = weighted(chain);
        int requests = (int) chain.windows.stream().mapToLong(w -> w.healthy().size()).sum();
        boolean ready = chain.windows.size() >= cfg.minLearningWindows() && requests >= cfg.minLearningRequests();
        if (samples.isEmpty()) {
            return new HealthyModel("LEARNING", false, chain.lastIdentifiedTh, null, null, null, chain.windows.size(), requests, 0, false, allShares);
        }
        double totalWeight = samples.stream().mapToDouble(v -> v[1]).sum();
        double censoredShare = samples.stream().filter(v -> Double.isInfinite(v[0])).mapToDouble(v -> v[1]).sum() / totalWeight;
        double th = RobustStatistics.weightedNearestRank(samples, cfg.quantile());
        long smallestCensoredBudget = chain.windows.stream().filter(Window::hadCensored).mapToLong(Window::budget).min().orElse(Long.MAX_VALUE);
        String status = Double.isFinite(th) && th < smallestCensoredBudget ? "IDENTIFIED" : "CENSORED";
        // A gap: a path with a real share of the traffic but too few healthy samples.
        var healthyCount = new HashMap<String, Integer>();
        for (var w : chain.windows) for (var e : w.healthy()) healthyCount.merge(e.stratum(), 1, Integer::sum);
        boolean gap = allShares.entrySet().stream().anyMatch(e -> e.getValue() >= cfg.coverageMinShare()
                && healthyCount.getOrDefault(e.getKey(), 0) < cfg.coverageMinSamples());
        if (gap && chain.lastIdentifiedTh != null) { th = chain.lastIdentifiedTh; status = "IDENTIFIED"; }
        if (!Double.isFinite(th)) {
            return new HealthyModel("CENSORED", ready, null, null, null, null, chain.windows.size(), requests, censoredShare, gap, allShares);
        }
        if ("IDENTIFIED".equals(status) && !gap) chain.lastIdentifiedTh = th;
        var perWindow = chain.windows.stream().map(Window::healthyP99).filter(Objects::nonNull).toList();
        double sigma = perWindow.isEmpty() ? .05 * th
                : Math.max(RobustStatistics.scaledMad(perWindow), .05 * RobustStatistics.median(perWindow.stream().mapToDouble(Double::doubleValue).toArray()));
        double m = DeadlineMath.margin(sigma, th, cfg.marginK(), cfg.marginMin(), cfg.marginMax());
        long target = DeadlineMath.target(th, m, minMs, maxMs);
        return new HealthyModel(status, ready, th, sigma, m, "IDENTIFIED".equals(status) ? target : null, chain.windows.size(), requests,
                censoredShare, gap, allShares);
    }
}
