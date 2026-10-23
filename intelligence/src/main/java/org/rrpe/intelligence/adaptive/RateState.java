package org.rrpe.intelligence.adaptive;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * C4's own memory of one service's rate (rate/retry guide §4.1, §4.6, §6): the stable healthy rate (no longer overwritten
 * by the C3 sync), the last rate that passed verification, the rate at which the app last slowed down, the app-time baseline
 * P0, recent demand, the persistent diagnosis, the change being verified, and the undo/flip/pause bookkeeping.
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
    /** The last cut (for the anti-ratchet rule: a second cut only if this one helped). */
    record Cut(double fromRate, double toRate, double appBefore) {}
    Cut lastCut; boolean antiRatchetHold;
    AdaptiveEngine.RateDecision lastDecision;
    /** Retry v2: consecutive windows with a clearly low retry success rate. */
    int retryLowStreak;
    /** Joint recalibration: per-window maximum queue depth of HEALTHY windows (only collected when joint is enabled). */
    final Deque<Double> healthyDepth = new ArrayDeque<>();
    /** Q = median healthy per-window maximum queue depth; null until {@code minSamples} are collected. */
    public Double healthyQueue(int minSamples) {
        return healthyDepth.size() < minSamples ? null : RobustStatistics.median(healthyDepth.stream().mapToDouble(Double::doubleValue).toArray());
    }
    /** The latest automatically approved (or reverted) rate change, followed until it is applied or dropped. */
    UUID autoProposalId, lastAutoAudited;
    /** Joint recalibration: the last healthy target seen in the policy, and a changed target not yet reached (flag on only). */
    Double seenHealthyTarget, healthyTargetChange;
    /** Lowering a too-generous rate has started (it continues in -relax-step steps down to the target). */
    boolean relaxing;
    /** Rate and retry changes held this cycle (automatic mode: tried again every cycle, never a card for a person). */
    final List<String> held = new ArrayList<>();
    /**
     * The check of one automatic rate change, for the status API: {@code result} is kept, undone or inconclusive; app times
     * and shed rates before and after (null when not measured); {@code normalMs} is the limit the app time was checked against.
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

    /** App time and load (successful requests per second) of one learning window (rate learning fix B). */
    record LoadSample(double appMs, double load) {}
    final Deque<LoadSample> loadSamples = new ArrayDeque<>();
    /** How the latest P0 was found, for the evidence: null for the plain median. */
    String p0Note;
    /** The remembered brokeAt expired: raises probe in small steps until the service is healthy again (fix C). */
    boolean probing;

    /** P0 = median app time of the learning windows; null until {@code minSamples} are collected. */
    public Double p0(int minSamples) { return p0(minSamples, minSamples, null); }
    /**
     * P0 (rate learning fix A, B1). When the load of the learning windows clearly varies, P0 is the median app time of the
     * quietest windows: a rate that was too high from the start overloads the app in the busy windows only, so the quiet
     * ones show its real speed. Until there are enough quiet windows P0 stays unknown. Otherwise: the median after
     * {@code minSamples} windows, a provisional 25th percentile after {@code provisional} windows, and the lowest app time of
     * full-queue windows when nothing could be learned.
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
        // Shedding from day one (guide §4.5): after the provisional windows without a baseline, use the lowest app time seen.
        if (shedWindowsWithoutBaseline >= provisional && lowestShedApp != null) { p0Note = "provisional: lowest app time of " + shedWindowsWithoutBaseline + " full-queue windows"; return lowestShedApp; }
        return null;
    }
    /**
     * Learns P0 from a window: always from clean windows (queue below the low mark, nothing shed, errors normal); with
     * {@code allowShed} also from windows with a full or shedding queue but normal errors (the bucket limited the app),
     * never from a window in which the app was overloaded (its slow time is not normal; such a window is still kept with
     * its load, for fix B). Keeps the last {@code maxSamples}.
     */
    public void learnApp(Double appMs, boolean clean, boolean shedding, boolean errorsNormal, boolean allowShed, boolean queueFull, int maxSamples) {
        learnApp(appMs, null, clean, shedding, errorsNormal, allowShed, queueFull, false, maxSamples, maxSamples);
    }
    /** As above; with a {@code load} the window is also kept with its load (the last {@code maxLoadSamples}) for fix B. */
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
     * "App time grows with load" (rate learning fix B2), also without a trustworthy P0: over the last {@code n} learning
     * windows the busiest third (by load) is more than {@code factor} × slower than the quietest third with at least
     * {@code minLoadRatio} × its load, and this window ({@code appMs} at {@code load}) is in that slow, busy range too.
     * Returns the reason, or null. The queue wait is already taken out of {@code appMs}.
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
    /** Linear-interpolated percentile {@code q} (0–1). */
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
    /** Persistence: how many cycles in a row this diagnosis has held. */
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
