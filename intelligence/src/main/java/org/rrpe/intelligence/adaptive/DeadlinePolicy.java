package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.util.OptionalLong;
import java.util.function.DoubleUnaryOperator;

/**
 * The v2 deadline decision table (guide §6.5), a pure function. Rows are evaluated in order and the first match wins.
 * The deadline is an <b>overload trigger</b>: quiet when healthy (≤ e_target of healthy requests expire), firing under
 * overload (≥ e_fire of overloaded requests expire). Every step moves D toward D* and never past it.
 */
public final class DeadlinePolicy {
    private DeadlinePolicy() {}

    /** Everything the table needs about one counted window of one chain. Streaks count disjoint windows. */
    public record ChainContext(boolean paused, String pausedReason, boolean counted, boolean complete, boolean mixedBudget,
            double unknownShare, double cutShare, String cutHint, boolean pendingRateOnPath, boolean cooldownActive,
            int proposalsLastHour, HealthyModel model, long current, double oShare, double stressedShare,
            Proportion hExceed, Proportion oExceed, boolean hSufficient, boolean oSufficient,
            int aStreak, int oStreak, int dStreak, Lever lever, Double predictedOAtTarget, DoubleUnaryOperator predictedH,
            Long pendingProposalMs) {
        boolean identified() { return "IDENTIFIED".equals(model.status()) && model.targetMs() != null; }
        boolean censored() { return "CENSORED".equals(model.status()); }
        Long target() { return model.targetMs(); }
    }
    /** {@code withdrawPendingIncrease}: Case C keeps D and withdraws a pending increase of this chain. */
    public record DeadlineDecision(DeadlineCase decisionCase, Long proposedMs, String holdReason, boolean withdrawPendingIncrease,
            Double predictedH, Double predictedO, String rule) {
        public boolean proposes() { return proposedMs != null; }
    }

    public static DeadlineDecision decide(ChainContext ctx, AdaptiveConfig.DeadlineSettings cfg, long maxMs) {
        DeadlineDecision decision = table(ctx, cfg, maxMs);
        // A pending deadline proposal for this chain: the same value is re-offered (keeps it fresh); Case C may withdraw a
        // pending increase; anything else waits for the human decision.
        if (ctx.pendingProposalMs() != null) {
            if (decision.proposes() && decision.proposedMs().equals(ctx.pendingProposalMs())) return decision;
            if (decision.decisionCase() == DeadlineCase.C_KEEP_FIRING) return decision;
            return hold("PENDING_PROPOSAL", "a recommendation for this chain is waiting for review");
        }
        return decision;
    }

    private static DeadlineDecision table(ChainContext ctx, AdaptiveConfig.DeadlineSettings cfg, long maxMs) {
        // Row 0: guards.
        if (ctx.paused()) return hold("PAUSED", ctx.pausedReason() == null ? "chain paused" : ctx.pausedReason());
        if (!ctx.counted()) return hold("NOT_COUNTED", "overlaps the last counted window");
        if (!ctx.complete()) return hold("INCOMPLETE", "window is incomplete");
        if (ctx.mixedBudget()) return hold("MIXED_BUDGET", "the deadline changed within the window");
        if (ctx.unknownShare() > cfg.unknownMaxShare()) return hold("UNKNOWN_SHARE", "too many requests touch a service with unknown load");
        if (ctx.cutShare() > cfg.cutMaxShare()) return hold("CENSORED_OTHER", "requests cut by another timer: " + ctx.cutHint());
        if (ctx.pendingRateOnPath()) return hold("PENDING_RATE_PROPOSAL", "a rate recommendation is pending for a service on this chain");
        if (ctx.cooldownActive()) return hold("COOLDOWN", "a deadline change was applied recently");
        if (ctx.proposalsLastHour() >= cfg.maxProposalsPerHour()) return hold("MAX_PROPOSALS_PER_HOUR", "proposal budget for this hour used");
        if (!ctx.hSufficient() && !ctx.oSufficient()) return hold("INSUFFICIENT_DATA", "too few requests even after widening the window");

        int k = cfg.persistenceWindows(); double share = cfg.shareThreshold();
        boolean a = caseA(ctx, cfg), b = caseB(ctx, cfg);
        if (!ctx.model().ready() && !(a && ctx.censored())) return hold("LEARNING", "learning the healthy time");
        // Row 1 (defensive: with a single healthy-time status A and B exclude each other).
        var inconsistent = inconsistent(a, b);
        if (inconsistent != null) return inconsistent;
        // Row 2: Case C, the signal fires under overload: keep D.
        if (ctx.oShare() >= share && ctx.oStreak() >= k && ctx.oSufficient() && ctx.oExceed().lower() >= cfg.eFire()) {
            return new DeadlineDecision(DeadlineCase.C_KEEP_FIRING, null, "SIGNAL_FIRING", true, null, null,
                    "Case C: the overload signal fires (≥ " + pct(cfg.eFire()) + " of overloaded requests expire); keep the deadline");
        }
        // Row 3: wrong lever.
        if (ctx.oShare() >= share && (ctx.lever() == Lever.RATE || ctx.lever() == Lever.CAPACITY)) {
            return hold("LEVER_" + ctx.lever(), ctx.lever() == Lever.RATE ? "consider the inbound rate" : "consider capacity");
        }
        // Row 4: Case B, overloaded but not firing: lower toward D*.
        if (b) {
            double predictedO = ctx.predictedOAtTarget() == null ? 0 : ctx.predictedOAtTarget();
            if (predictedO < cfg.eFire()) return hold("DEADLINE_NOT_THE_LEVER", "even D* would not make the signal fire");
            long proposed = DeadlineMath.stepDownOverload(ctx.current(), ctx.target(), cfg.stepDownOverload());
            return new DeadlineDecision(DeadlineCase.B_LOWER_OVERLOAD, proposed, null, false, ctx.predictedH().applyAsDouble(proposed), predictedO,
                    "Case B: overloaded requests no longer trigger the signal; lower max(D*, ceil10(" + cfg.stepDownOverload() + "·D))");
        }
        // Row 5: Case A, healthy requests over-signalled: raise.
        if (a) {
            long proposed = DeadlineMath.stepUp(ctx.current(), ctx.identified() ? OptionalLong.of(ctx.target()) : OptionalLong.empty(), cfg.stepUp(), maxMs);
            if (proposed <= ctx.current()) return hold("AT_LIMIT", "already at the target or the maximum");
            return new DeadlineDecision(DeadlineCase.A_RAISE, proposed, null, false, ctx.predictedH().applyAsDouble(proposed), null,
                    ctx.identified() ? "Case A: healthy requests over-signalled; raise min(D*, ceil10(" + cfg.stepUp() + "·D))"
                            : "Case A: the healthy time is censored at the deadline (p99 ≥ D is proven); raise min(max, ceil10(" + cfg.stepUp() + "·D))");
        }
        // Row 6: Case D, healthy and too long: tighten, never below D*.
        if (caseD(ctx, cfg)) {
            long proposed = DeadlineMath.stepDownHealthy(ctx.current(), ctx.target(), cfg.stepDownHealthy());
            if (proposed >= ctx.current()) return hold("IN_BAND", "at the target");
            double predicted = ctx.predictedH().applyAsDouble(proposed);
            if (predicted > cfg.eTarget()) return hold("PREVIEW_FAILED", "tightening would signal " + pct(predicted) + " of healthy requests");
            return new DeadlineDecision(DeadlineCase.D_TIGHTEN, proposed, null, false, predicted, null,
                    "Case D: healthy and longer than 1.25·D*; tighten max(D*, ceil10(" + cfg.stepDownHealthy() + "·D))");
        }
        // Row 7.
        if (ctx.stressedShare() >= share) return hold("STRESS_GATE", "requests on stressed paths are signalled early, as intended");
        if (ctx.oShare() >= share) return hold("OVERLOAD_NOT_PERSISTENT", "waiting for the overload to persist");
        return hold("IN_BAND", "the deadline is in the healthy band");
    }

    /** Row 1: raise and lower conditions both true → hold and flag for review. */
    static DeadlineDecision inconsistent(boolean caseA, boolean caseB) {
        return caseA && caseB ? hold("INCONSISTENT", "raise and lower conditions both hold; flagged for review") : null;
    }
    static boolean caseA(ChainContext ctx, AdaptiveConfig.DeadlineSettings cfg) {
        return ctx.oShare() < cfg.shareThreshold() && ctx.stressedShare() < cfg.shareThreshold() && ctx.hSufficient()
                && ctx.aStreak() >= cfg.persistenceWindows()
                && (ctx.censored() || ctx.identified() && ctx.current() < ctx.target());
    }
    static boolean caseB(ChainContext ctx, AdaptiveConfig.DeadlineSettings cfg) {
        return ctx.oStreak() >= cfg.persistenceWindows() && ctx.oSufficient() && ctx.oExceed().upper() < cfg.eFire()
                && ctx.identified() && ctx.current() > (1 + cfg.deadbandOverload()) * ctx.target();
    }
    static boolean caseD(ChainContext ctx, AdaptiveConfig.DeadlineSettings cfg) {
        return ctx.oShare() < cfg.shareThreshold() && ctx.stressedShare() < cfg.shareThreshold() && ctx.identified()
                && !ctx.model().coverageGap() && ctx.dStreak() >= cfg.persistenceWindows();
    }
    static DeadlineDecision hold(String reason, String detail) {
        return new DeadlineDecision(DeadlineCase.HOLD, null, reason, false, null, null, reason + ": " + detail);
    }
    private static String pct(double v) { return Math.round(v * 1000) / 10.0 + "%"; }
}
