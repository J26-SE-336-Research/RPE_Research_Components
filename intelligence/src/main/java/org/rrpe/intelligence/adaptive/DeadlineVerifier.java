package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;

/**
 * Checks an applied deadline change on the windows after it (guide §6.7). A failed check leads to a revert proposal
 * (human-approved). The guide's worked examples step more than once (example 1: 2000 → 1500 → 1130 → 850; example 3:
 * 300 → 450 → 680), so a step that helped but has not reached the goal yet is CONTINUE, not FAIL: only a change that
 * harmed healthy requests (or did not help at all) is reverted.
 * <ul>
 *   <li>After A (raise): PASS if LB(p_H) ≤ e_target; CONTINUE if p_H fell; else FAIL.</li>
 *   <li>After D (tighten): PASS if LB(p_H) ≤ e_target; else FAIL (healthy requests are now over-signalled).</li>
 *   <li>After B (lower under overload): FAIL if LB(p_H) > e_target; PASS if p_O ≥ 0.5·e_fire or the overload cleared;
 *       else CONTINUE.</li>
 *   <li>Too few requests: INCONCLUSIVE.</li>
 * </ul>
 */
public final class DeadlineVerifier {
    private DeadlineVerifier() {}
    public enum Result { PASS, CONTINUE, FAIL, INCONCLUSIVE }
    public record Verification(Result result, String detail) {}

    public static Verification verify(DeadlineCase applied, Proportion hBefore, Proportion hAfter, Proportion oAfter, boolean overloadCleared,
            AdaptiveConfig.DeadlineSettings cfg, int minRequests) {
        boolean hEnough = hAfter.n() >= minRequests, oEnough = oAfter.n() >= minRequests;
        String h = "healthy exceed " + pct(hAfter.p()) + " (lower bound " + pct(hAfter.lower()) + ", n=" + hAfter.n() + ")";
        switch (applied) {
            case A_RAISE -> {
                if (!hEnough) return new Verification(Result.INCONCLUSIVE, "too few healthy requests after the raise");
                if (hAfter.lower() <= cfg.eTarget()) return new Verification(Result.PASS, h + " ≤ " + pct(cfg.eTarget()));
                if (hBefore != null && hBefore.n() > 0 && hAfter.p() < hBefore.p()) return new Verification(Result.CONTINUE, h + ", down from " + pct(hBefore.p()) + "; still above target");
                return new Verification(Result.FAIL, h + "; the raise did not reduce over-signalling");
            }
            case D_TIGHTEN -> {
                if (!hEnough) return new Verification(Result.INCONCLUSIVE, "too few healthy requests after tightening");
                if (hAfter.lower() <= cfg.eTarget()) return new Verification(Result.PASS, h + " ≤ " + pct(cfg.eTarget()));
                return new Verification(Result.FAIL, h + "; tightening over-signals healthy requests");
            }
            case B_LOWER_OVERLOAD -> {
                if (!hEnough && !oEnough && !overloadCleared) return new Verification(Result.INCONCLUSIVE, "too few requests after lowering");
                if (hEnough && hAfter.lower() > cfg.eTarget()) return new Verification(Result.FAIL, h + "; lowering over-signals healthy requests");
                if (overloadCleared) return new Verification(Result.PASS, "the overload cleared; " + h);
                if (oEnough && oAfter.p() >= .5 * cfg.eFire()) return new Verification(Result.PASS, "overload signal " + pct(oAfter.p()) + " ≥ " + pct(.5 * cfg.eFire()));
                return new Verification(Result.CONTINUE, "overloaded requests still not signalled (" + pct(oAfter.p()) + "); healthy requests unharmed");
            }
            default -> { return new Verification(Result.INCONCLUSIVE, "nothing to verify"); }
        }
    }
    private static String pct(double v) { return String.format(java.util.Locale.ROOT, "%.1f%%", 100 * v); }
}
