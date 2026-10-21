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

    /** P0 = median app time of the learning windows; null until {@code minSamples} are collected. */
    public Double p0(int minSamples) {
        if (appSamples.size() >= minSamples) return RobustStatistics.median(appSamples.stream().mapToDouble(Double::doubleValue).toArray());
        // Shedding from day one (guide §4.5): after minSamples full-queue windows without a baseline, use the lowest app time seen.
        return shedWindowsWithoutBaseline >= minSamples && lowestShedApp != null ? lowestShedApp : null;
    }
    /**
     * Learns P0 from a window: always from clean windows (queue below the low mark, nothing shed, errors normal); with
     * {@code allowShed} also from windows that shed but have normal errors. Keeps the last {@code maxSamples}.
     */
    public void learnApp(Double appMs, boolean clean, boolean shedding, boolean errorsNormal, boolean allowShed, boolean queueFull, int maxSamples) {
        if (appMs == null || !Double.isFinite(appMs)) return;
        if (errorsNormal && (clean || allowShed && shedding)) {
            appSamples.addLast(appMs); while (appSamples.size() > maxSamples) appSamples.removeFirst();
        }
        if (queueFull && errorsNormal && appSamples.isEmpty()) {
            shedWindowsWithoutBaseline++; lowestShedApp = lowestShedApp == null ? appMs : Math.min(lowestShedApp, appMs);
        }
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
    public Double brokeAt(Instant now) { if (brokeAtUntil != null && !now.isBefore(brokeAtUntil)) { brokeAt = null; brokeAtUntil = null; } return brokeAt; }
    public void rememberBrokeAt(double rate, Instant now, Duration memory) { brokeAt = rate; brokeAtUntil = now.plus(memory); }
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
