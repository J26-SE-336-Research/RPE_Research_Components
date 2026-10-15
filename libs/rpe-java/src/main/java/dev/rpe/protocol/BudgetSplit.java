package dev.rpe.protocol;

/** Retry-budget division across fan-out children (R-F05): the children's grants never exceed the parent's budget. */
public final class BudgetSplit {
    public enum Mode {
        /** Each child gets floor((rb - reserve) / n); the remainder goes to the first children. */
        PARALLEL,
        /** Every child may use the whole unallocated budget; the parent deducts the reported rbu afterwards. */
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
