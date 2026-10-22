package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.util.Set;

/**
 * Safety rules for approving a rate or retry recommendation automatically (guide §6.3). A recommendation is approved by
 * {@code c4-auto} only when its kind is in {@code rrpe.adaptive.auto-approve-actions}, it is not synthetic, it stays within
 * the floor and ceiling and the step limits (rate up ≤ +50%, down ≤ −20%; retry ±0.02, or down to the protective 0.02 or
 * 0; lowering a generous rate ≤ −10%), its condition is persistent, its data is complete (rate diagnosis not UNKNOWN), every chain through the service has a
 * settled deadline (cross-lever lock), and automatic approval is not paused for the service. An undo always goes through. Otherwise the
 * change is held (automatic mode) or a human decides (automatic approval off). The existing
 * cooldown is checked before any recommendation is made. Chain deadlines have no kind and are never approved here.
 */
final class AutoApprovalGuard {
    private AutoApprovalGuard() {}
    private static final double EPS = 1e-9;

    record Request(AdaptiveConfig.AutoKind kind, Source source, double from, double to, Double floor, Double ceiling,
                   boolean persistent, boolean undo, String unsettledDeadline, boolean dataComplete, boolean paused) {}
    record Result(boolean approve, String reason) {
        static Result no(String reason) { return new Result(false, reason); }
    }

    static Result check(Request r, Set<AdaptiveConfig.AutoKind> allowed, AdaptiveConfig.RateSettings rate, AdaptiveConfig.RetrySettings retry) {
        if (r.kind() == null) return Result.no("this kind of change is always decided by a reviewer");
        if (r.source() == Source.MOCK) return Result.no("synthetic recommendations are never approved automatically");
        // An undo of a change that did not help always goes through (owner decision 22 Oct): never behind a lock or a pause.
        if (r.undo() && r.kind() == AdaptiveConfig.AutoKind.BUCKET_RAISE && r.to() > r.from()) return new Result(true, "undo of a change that did not help");
        if (!allowed.contains(r.kind())) return Result.no(r.kind() + " is not in rrpe.adaptive.auto-approve-actions");
        if (r.paused()) return Result.no("automatic approval is paused for this service");
        if (!r.dataComplete()) return Result.no("insufficient data for automatic change (rate diagnosis UNKNOWN)");
        if (r.unsettledDeadline() != null) return Result.no("deadline not settled: " + r.unsettledDeadline() + " (cross-lever lock)");
        if (!r.persistent()) return Result.no("the condition is not yet persistent");
        return switch (r.kind()) {
            case BUCKET_RAISE -> !(r.to() > r.from()) ? Result.no("not a raise")
                    : r.undo() ? new Result(true, "undo of a change that did not help")
                    : r.to() > r.from() * (1 + rate.maxRaise()) + EPS ? Result.no("raise above +" + Math.round(100 * rate.maxRaise()) + "% per step")
                    : r.ceiling() != null && r.to() > r.ceiling() + EPS ? Result.no("above the ceiling " + r.ceiling())
                    : new Result(true, "raise within the step limit and the ceiling");
            case BUCKET_CUT -> !(r.to() < r.from()) ? Result.no("not a cut")
                    : r.floor() != null && r.to() < r.floor() - EPS ? Result.no("below the floor " + r.floor())
                    : r.to() < r.from() * rate.cutFactor() - EPS ? Result.no("cut larger than " + Math.round(100 * (1 - rate.cutFactor())) + "% per step")
                    : new Result(true, "cut within the step limit and above the floor");
            case BUCKET_RELAX -> !(r.to() < r.from()) ? Result.no("not a lowering")
                    : r.floor() != null && r.to() < r.floor() - EPS ? Result.no("below the floor " + r.floor())
                    : r.to() < r.from() * (1 - rate.relaxStep()) - EPS ? Result.no("lowering larger than " + Math.round(100 * rate.relaxStep()) + "% per step")
                    : new Result(true, "a too-generous rate lowered within the step limit and above the floor");
            case RETRY_DECREASE -> !(r.to() < r.from()) ? Result.no("not a decrease")
                    : r.to() == 0 || Math.abs(r.to() - retry.step()) < EPS || r.from() - r.to() <= retry.step() + EPS
                    ? new Result(true, "protective retry decrease") : Result.no("retry decrease larger than " + retry.step());
            case RETRY_INCREASE -> !(r.to() > r.from()) ? Result.no("not an increase")
                    : r.to() - r.from() <= retry.step() + EPS ? new Result(true, "retry increase within " + retry.step())
                    : Result.no("retry increase larger than " + retry.step());
        };
    }
}
