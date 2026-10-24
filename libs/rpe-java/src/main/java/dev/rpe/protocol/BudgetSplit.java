package dev.rpe.protocol;

/** Splits a retry budget over several children so together they never get more than the parent had. */
public final class BudgetSplit {
    public enum Mode {
        /** Each child gets (rb - reserve) / n and the leftover goes to the first ones. */
        PARALLEL,
        /** Each child can use all of what is left, and the parent takes off what the child reports it used. */
        SEQUENTIAL
    }

    private BudgetSplit() {}

    public static long[] split(Mode mode, long retryBudget, long reserve, int n) {
        long[] out = new long[Math.max(n, 0)];
        if (n <= 0) return out;
        long pool = Math.max(retryBudget - reserve, 0);
        if (mode == Mode.SEQUENTIAL) {
            java.util.Arrays.fill(out, pool);
            return out;
        }
        long share = pool / n, rem = pool % n;
        for (int i = 0; i < n; i++) {
            out[i] = share + (i < rem ? 1 : 0);
        }
        return out;
    }
}
