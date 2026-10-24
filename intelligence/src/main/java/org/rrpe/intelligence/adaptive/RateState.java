package org.rrpe.intelligence.adaptive;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * What we remember about the rate of one service: the healthy rate, the last rate that passed its check, the rate at
 * which the app last slowed down, the normal app time P0, recent demand, the current diagnosis, the change being
 * checked, and the undos, flips and pauses.
 */
public final class RateState {
    Double healthyRate, goodRate, brokeAt; Instant brokeAtUntil;
    final Deque<Double> appSamples = new ArrayDeque<>(); Double lowestShedApp; int shedWindowsWithoutBaseline;
    final Deque<Double> demand = new ArrayDeque<>();
    RateDiagnosis.Diagnosis diagnosis; int diagnosisStreak; int healthyStreak;
    Instant lastChangeAt, relaxSince;
    Verification verification; UUID trackedApplied, lastProposalId; String lastKind;
    final List<Instant> undos = new ArrayList<>(), flips = new ArrayList<>(); int lastDirection; Instant autoPausedUntil;
    Double lastAppMs, lastShed; String lastVerification;
    /** The last cut. A second cut only happens if this one helped. */
    record Cut(double fromRate, double toRate, double appBefore) {}
    Cut lastCut; boolean antiRatchetHold;
    AdaptiveEngine.RateDecision lastDecision;
    /** Windows in a row in which retries rarely succeeded. */
    int retryLowStreak;
    /** The largest queue depth of each healthy window. Only collected when joint recalibration is on. */
    final Deque<Double> healthyDepth = new ArrayDeque<>();
    /** The median of those depths, or null until there are enough samples. */
    public Double healthyQueue(int minSamples) {
        return healthyDepth.size() < minSamples ? null : RobustStatistics.median(healthyDepth.stream().mapToDouble(Double::doubleValue).toArray());
    }
    /** The latest automatically approved (or reverted) rate change, followed until it is applied or dropped. */
    UUID autoProposalId, lastAutoAudited;
    /** The last healthy target seen in the policy, and a changed target that is not reached yet. */
    Double seenHealthyTarget, healthyTargetChange;
    /** We started lowering a rate that is too generous. It goes down in small steps to the target. */
    boolean relaxing;
    /** Rate and retry changes held this cycle. They are tried again next cycle. */
    final List<String> held = new ArrayList<>();
    /**
     * The check of one automatic rate change, for the status API. The result is kept, undone or inconclusive. App times
     * and rejections are shown before and after; normalMs is the limit the app time was checked against.
     */
    public record Check(String proposalId, String kind, double fromRate, double toRate, String result, Double appBeforeMs, Double appAfterMs,
            Double normalMs, Double shedBeforePerSecond, Double shedAfterPerSecond, String at) {}
    /** Check results of automatic rate changes, newest first, at most 20. */
    final Deque<Check> checks = new ArrayDeque<>();
    void recordCheck(Check check) { checks.addFirst(check); while (checks.size() > 20) checks.removeLast(); }

    /** An applied change being checked on the next disjoint windows. */
    static final class Verification {
        final UUID proposalId; final String kind; final double fromRate, toRate, appBefore, shedBefore; int windows, appWindows; double appSum, shedSum;
        Instant startAt, lastEnd;
        Verification(UUID proposalId, String kind, double fromRate, double toRate, double appBefore, double shedBefore) {
            this.proposalId = proposalId; this.kind = kind; this.fromRate = fromRate; this.toRate = toRate; this.appBefore = appBefore; this.shedBefore = shedBefore;
        }
        double appAfter() { return appWindows == 0 ? Double.NaN : appSum / appWindows; }
        double shedAfter() { return windows == 0 ? Double.NaN : shedSum / windows; }
    }

    /** App time and load (successful requests per second) of one learning window. */
    record LoadSample(double appMs, double load) {}
    final Deque<LoadSample> loadSamples = new ArrayDeque<>();
    /** How the latest P0 was found, for the evidence: null for the plain median. */
    String p0Note;
    /** The remembered limit expired, so raises go in small steps until the service is healthy again. */
    boolean probing;

    /** P0 = median app time of the learning windows; null until {@code minSamples} are collected. */
    public Double p0(int minSamples) { return p0(minSamples, minSamples, null); }
    /**
     * The normal app time P0. When the load clearly varies, we take the median of the quietest windows, because a rate
     * that is too high only slows the app in the busy ones. Until there are enough quiet windows P0 is unknown.
     * Otherwise we use the median once there are enough windows, a rough 25th percentile before that, and the lowest
     * app time of full-queue windows when nothing else could be learned.
     */
    public Double p0(int minSamples, int provisional, AdaptiveConfig.BaselineSettings b) {
        p0Note = null;
        if (b != null && loadSamples.size() >= provisional) {
            var byLoad = loadSamples.stream().sorted(Comparator.comparingDouble(LoadSample::load)).toList();
            int quarter = (int) Math.ceil(b.quietShare() * byLoad.size());
            var quiet = byLoad.subList(0, quarter); var busy = byLoad.subList(byLoad.size() - quarter, byLoad.size());
            double quietLoad = median(quiet, LoadSample::load), busyLoad = median(busy, LoadSample::load);
            if (busyLoad > 0 && busyLoad >= b.loadSpread() * quietLoad) {
                if (quarter < b.minQuietWindows()) return null; // the load varies: wait for enough quiet windows
                double p0 = median(quiet, LoadSample::appMs);
                p0Note = String.format(Locale.ROOT, "from the %d quietest of %d windows (%.1f vs %.1f req/s)", quarter, byLoad.size(), quietLoad, busyLoad);
                return p0;
            }
        }
        double[] apps = appSamples.stream().mapToDouble(Double::doubleValue).toArray();
        if (apps.length >= minSamples) return RobustStatistics.median(apps);
        if (apps.length >= provisional) {
            p0Note = "provisional: 25th percentile of " + apps.length + " windows";
            return percentile(apps, .25);
        }
        // Rejecting from the start: use the lowest app time we saw.
        if (shedWindowsWithoutBaseline >= provisional && lowestShedApp != null) { p0Note = "provisional: lowest app time of " + shedWindowsWithoutBaseline + " full-queue windows"; return lowestShedApp; }
        return null;
    }
    /**
     * Learns P0 from a window. Clean windows always count. With allowShed, windows with a full queue but normal errors
     * count too, since the bucket limited the app there. A window in which the app was overloaded never counts, but it
     * is still kept with its load. Keeps the last maxSamples.
     */
    public void learnApp(Double appMs, boolean clean, boolean shedding, boolean errorsNormal, boolean allowShed, boolean queueFull, int maxSamples) {
        learnApp(appMs, null, clean, shedding, errorsNormal, allowShed, queueFull, false, maxSamples, maxSamples);
    }
    /** Same as above. With a load, the window is also kept together with its load. */
    public void learnApp(Double appMs, Double load, boolean clean, boolean shedding, boolean errorsNormal, boolean allowShed, boolean queueFull,
            boolean overloaded, int maxSamples, int maxLoadSamples) {
        if (appMs == null || !Double.isFinite(appMs)) return;
        if (errorsNormal && (clean || allowShed && (shedding || queueFull))) {
            if (!overloaded) { appSamples.addLast(appMs); while (appSamples.size() > maxSamples) appSamples.removeFirst(); }
            if (load != null && Double.isFinite(load) && load >= 0) {
                loadSamples.addLast(new LoadSample(appMs, load)); while (loadSamples.size() > maxLoadSamples) loadSamples.removeFirst();
            }
        }
        if (queueFull && errorsNormal && appSamples.isEmpty()) {
            shedWindowsWithoutBaseline++; lowestShedApp = lowestShedApp == null ? appMs : Math.min(lowestShedApp, appMs);
        }
    }
    /**
     * Checks whether the app gets slower as the load grows. This works even without a good P0: in the last n windows,
     * the busiest third is clearly slower than the quietest third, and this window is busy and slow too.
     * Returns the reason, or null. The queue wait is already taken out of appMs.
     */
    public String loadGrowth(Double appMs, double load, int n, double factor, double minLoadRatio) {
        if (appMs == null || !Double.isFinite(appMs) || loadSamples.size() < n) return null;
        var all = new ArrayList<>(loadSamples);
        var last = all.subList(all.size() - n, all.size()).stream().sorted(Comparator.comparingDouble(LoadSample::load)).toList();
        int third = Math.max(1, n / 3);
        var quiet = last.subList(0, third); var busy = last.subList(n - third, n);
        double quietApp = median(quiet, LoadSample::appMs), quietLoad = median(quiet, LoadSample::load);
        double busyApp = median(busy, LoadSample::appMs), busyLoad = median(busy, LoadSample::load);
        boolean grows = busyApp > factor * quietApp && busyLoad >= minLoadRatio * quietLoad;
        boolean now = appMs > factor * quietApp && load >= minLoadRatio * quietLoad;
        return grows && now ? String.format(Locale.ROOT, "app time grows with load: %.0f ms at %.1f req/s, the quietest third of the last %d windows %.0f ms at %.1f req/s",
                appMs, load, n, quietApp, quietLoad) : null;
    }
    private static double median(List<LoadSample> s, java.util.function.ToDoubleFunction<LoadSample> f) { return RobustStatistics.median(s.stream().mapToDouble(f).toArray()); }
    /** Percentile q between 0 and 1, interpolated. */
    static double percentile(double[] values, double q) {
        double[] v = values.clone(); Arrays.sort(v);
        double pos = q * (v.length - 1); int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return v[lo] + (v[hi] - v[lo]) * (pos - lo);
    }
    /** Demand of the last {@code windows} windows; the healthy rule uses their maximum so repeating bursts are covered. */
    public double maxDemand(double current, int windows) {
        demand.addLast(current); while (demand.size() > windows) demand.removeFirst();
        return demand.stream().mapToDouble(Double::doubleValue).max().orElse(current);
    }
    /** How many cycles in a row this diagnosis has held. */
    public int observe(RateDiagnosis.Diagnosis d) {
        diagnosisStreak = d == diagnosis ? diagnosisStreak + 1 : 1; diagnosis = d;
        healthyStreak = d == RateDiagnosis.Diagnosis.HEALTHY ? healthyStreak + 1 : 0;
        return diagnosisStreak;
    }
    /** The rate at which the app slowed down, remembered for {@code memory}. */
    public Double brokeAt(Instant now) {
        if (brokeAtUntil != null && !now.isBefore(brokeAtUntil)) { brokeAt = null; brokeAtUntil = null; probing = true; }
        return brokeAt;
    }
    public void rememberBrokeAt(double rate, Instant now, Duration memory) { brokeAt = rate; brokeAtUntil = now.plus(memory); probing = false; }
    /** Records an applied direction; returns true when it reverses the previous one (a flip). */
    public boolean recordDirection(int direction, Instant now, Duration period) {
        boolean flip = lastDirection != 0 && direction != 0 && direction != lastDirection;
        if (direction != 0) lastDirection = direction;
        if (flip) flips.add(now);
        flips.removeIf(t -> Duration.between(t, now).compareTo(period) >= 0);
        return flip;
    }
    public void recordUndo(Instant now, Duration period) { undos.add(now); undos.removeIf(t -> Duration.between(t, now).compareTo(period) >= 0); }
    public boolean autoPaused(Instant now) { return autoPausedUntil != null && now.isBefore(autoPausedUntil); }
}
