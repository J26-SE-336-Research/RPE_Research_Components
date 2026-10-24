package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every row of the deadline decision table, including every guard. */
class DeadlinePolicyTests {
    private final AdaptiveConfig.DeadlineSettings cfg = AdaptiveConfig.DeadlineSettings.demo(); // k = 2

    /** A mutable starting point: a ready, identified chain (T_h 400, D* 490) at 600 ms, healthy and in band. */
    private static class Ctx {
        boolean paused, counted = true, complete = true, mixed, pendingRate, cooldown;
        double unknown, cut, oShare, stressed, predictedH = 0;
        int proposals, aStreak, oStreak, dStreak;
        String status = "IDENTIFIED"; boolean ready = true, gap; Long target = 490L;
        long current = 600; Lever lever = Lever.DEADLINE; Double predictedO;
        Proportion h = RobustStatistics.wilsonOneSided(0, 300, 1.645, 1), o = RobustStatistics.wilsonOneSided(0, 0, 1.645, 1);
        boolean hSufficient = true, oSufficient; Long pending;
        DeadlinePolicy.ChainContext build() {
            var model = new HealthyModel(status, ready, "CENSORED".equals(status) ? null : 400.0, 29.652, .2224, target, 10, 3000, 0, gap, Map.of());
            return new DeadlinePolicy.ChainContext(paused, "two reversals in an hour", counted, complete, mixed, unknown, cut, "REJECTING_MIDDLEWARE",
                    pendingRate, cooldown, proposals, model, current, oShare, stressed, h, o, hSufficient, oSufficient, aStreak, oStreak, dStreak,
                    lever, predictedO, candidate -> predictedH, pending);
        }
    }
    private DeadlinePolicy.DeadlineDecision decide(Ctx c) { return DeadlinePolicy.decide(c.build(), cfg, 10000); }
    private void holds(String reason, Ctx c) { var d = decide(c); assertEquals(DeadlineCase.HOLD, d.decisionCase(), d.rule()); assertEquals(reason, d.holdReason()); }
    /** Overloaded and not firing: Case B conditions. */
    private Ctx overloaded() {
        var c = new Ctx(); c.current = 2000; c.oShare = 1; c.oStreak = 2; c.oSufficient = true; c.hSufficient = false;
        c.o = RobustStatistics.wilsonOneSided(0, 300, 1.645, 1); c.predictedO = 1.0; return c;
    }
    /** Healthy requests over-signalled: Case A conditions (censored). */
    private Ctx overSignalled() {
        var c = new Ctx(); c.current = 300; c.status = "CENSORED"; c.target = null; c.aStreak = 2;
        c.h = RobustStatistics.wilsonOneSided(75, 300, 1.645, 1); return c;
    }

    @Test void row0GuardsHoldInOrder() {
        var c = new Ctx(); c.paused = true; holds("PAUSED", c);
        c = new Ctx(); c.counted = false; holds("NOT_COUNTED", c);
        c = new Ctx(); c.complete = false; holds("INCOMPLETE", c);
        c = new Ctx(); c.mixed = true; holds("MIXED_BUDGET", c);
        c = new Ctx(); c.unknown = .11; holds("UNKNOWN_SHARE", c);
        c = new Ctx(); c.cut = .006; holds("CENSORED_OTHER", c);
        assertTrue(decide(c).rule().contains("REJECTING_MIDDLEWARE"), "the cut hint is reported");
        c = new Ctx(); c.pendingRate = true; holds("PENDING_RATE_PROPOSAL", c);
        c = new Ctx(); c.cooldown = true; holds("COOLDOWN", c);
        c = new Ctx(); c.proposals = 6; holds("MAX_PROPOSALS_PER_HOUR", c);
        c = new Ctx(); c.hSufficient = false; c.oSufficient = false; holds("INSUFFICIENT_DATA", c);
        c = new Ctx(); c.unknown = .10; c.cut = .005; holds("IN_BAND", c); // boundaries are allowed
    }
    @Test void learningHoldsExceptACensoredRaise() {
        var c = new Ctx(); c.ready = false; holds("LEARNING", c);
        c = overSignalled(); c.ready = false;
        assertEquals(DeadlineCase.A_RAISE, decide(c).decisionCase(), "a deadline too short from day one is fixed while learning");
        c = overloaded(); c.ready = false; holds("LEARNING", c);
    }
    @Test void row1BothRaiseAndLowerIsInconsistent() {
        var d = DeadlinePolicy.inconsistent(true, true);
        assertEquals(DeadlineCase.HOLD, d.decisionCase()); assertEquals("INCONSISTENT", d.holdReason());
        assertNull(DeadlinePolicy.inconsistent(true, false)); assertNull(DeadlinePolicy.inconsistent(false, true));
        // With one healthy-time status per chain the row is defensive only: A needs D < D* (or a censored T_h) while
        // B needs an identified T_h and D > 1.1·D*. A grid over every combination never makes both true.
        for (String status : new String[]{"IDENTIFIED", "CENSORED"}) for (long current : new long[]{300, 450, 490, 500, 540, 600, 2000})
            for (double oShare : new double[]{0, .04, .05, 1}) for (int streak = 0; streak <= 2; streak++) {
                var c = overloaded(); c.status = status; c.target = "CENSORED".equals(status) ? null : 490L; c.current = current; c.oShare = oShare;
                c.oStreak = streak; c.aStreak = streak; c.hSufficient = true; c.h = RobustStatistics.wilsonOneSided(75, 300, 1.645, 1);
                var ctx = c.build();
                assertFalse(DeadlinePolicy.caseA(ctx, cfg) && DeadlinePolicy.caseB(ctx, cfg), status + " " + current + " " + oShare + " " + streak);
            }
    }
    @Test void row2CaseCKeepsAFiringSignalAndWithdrawsAPendingIncrease() {
        var c = overloaded(); c.current = 850; c.o = RobustStatistics.wilsonOneSided(117, 300, 1.645, 1);
        var d = decide(c);
        assertEquals(DeadlineCase.C_KEEP_FIRING, d.decisionCase()); assertNull(d.proposedMs()); assertTrue(d.withdrawPendingIncrease());
        c.pending = 1200L;
        assertEquals(DeadlineCase.C_KEEP_FIRING, decide(c).decisionCase(), "Case C beats a pending increase");
        c = overloaded(); c.current = 850; c.o = RobustStatistics.wilsonOneSided(117, 300, 1.645, 1); c.oStreak = 1;
        holds("OVERLOAD_NOT_PERSISTENT", c);
    }
    @Test void row3WrongLeverHolds() {
        var c = overloaded(); c.lever = Lever.RATE; holds("LEVER_RATE", c);
        c = overloaded(); c.lever = Lever.CAPACITY; holds("LEVER_CAPACITY", c);
    }
    @Test void row4CaseBLowersOnlyWhenTheTargetWouldFire() {
        var d = decide(overloaded());
        assertEquals(DeadlineCase.B_LOWER_OVERLOAD, d.decisionCase()); assertEquals(1500, d.proposedMs()); assertEquals(1.0, d.predictedO());
        var c = overloaded(); c.current = 1130; assertEquals(850, decide(c).proposedMs());
        c = overloaded(); c.predictedO = .04; holds("DEADLINE_NOT_THE_LEVER", c);
        c = overloaded(); c.current = 530; holds("OVERLOAD_NOT_PERSISTENT", c); // within the 10% overload deadband of D* (539)
        c = overloaded(); c.status = "CENSORED"; c.target = null; holds("OVERLOAD_NOT_PERSISTENT", c);
    }
    @Test void row5CaseARaisesTowardTheTargetOrGeometricallyWhenCensored() {
        var d = decide(overSignalled());
        assertEquals(DeadlineCase.A_RAISE, d.decisionCase()); assertEquals(450, d.proposedMs());
        assertTrue(d.rule().contains("censored"));
        var c = new Ctx(); c.current = 400; c.aStreak = 2; c.h = RobustStatistics.wilsonOneSided(30, 300, 1.645, 1);
        assertEquals(490, decide(c).proposedMs(), "min(D*, ceil10(1.5·D))");
        c = overSignalled(); c.current = 10000; holds("AT_LIMIT", c);
        c = overSignalled(); c.aStreak = 1; holds("IN_BAND", c);
        c = overSignalled(); c.stressed = .05; holds("STRESS_GATE", c);
    }
    @Test void row6CaseDTightensWithAPassingPreviewOnly() {
        var c = new Ctx(); c.current = 850; c.dStreak = 2;
        var d = decide(c);
        assertEquals(DeadlineCase.D_TIGHTEN, d.decisionCase()); assertEquals(680, d.proposedMs()); assertEquals(0, d.predictedH());
        c.predictedH = .02; holds("PREVIEW_FAILED", c);
        c = new Ctx(); c.current = 850; c.dStreak = 2; c.gap = true; holds("IN_BAND", c);
        c = new Ctx(); c.current = 850; c.dStreak = 1; holds("IN_BAND", c);
    }
    @Test void row7HoldsWithAReason() {
        holds("IN_BAND", new Ctx());
        var c = new Ctx(); c.stressed = .3; holds("STRESS_GATE", c);
    }
    @Test void aPendingProposalIsRefreshedWithTheSameValueAndOtherwiseWaits() {
        var c = overloaded(); c.pending = 1500L;
        assertEquals(1500, decide(c).proposedMs(), "the same value is re-offered (kept fresh)");
        c.pending = 1600L; holds("PENDING_PROPOSAL", c);
    }
}
