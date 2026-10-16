package dev.rpe.protocol;

import static dev.rpe.protocol.RpeHeaders.*;

/**
 * The response context {@code X-RPE-Result} (spec §2.4).
 *
 * @param version     protocol version
 * @param outcome     ok | partial | rejected | stopped | failed | unknown
 * @param reason      none | deadline | cancelled | overload | insufficient-time | ...
 * @param terminal    the request MUST NOT be retried (R-R02)
 * @param bpLevel     backpressure level 0..3
 * @param bpUtil      backpressure utilisation 0..100
 * @param budgetUsed  retry budget used by the subtree ({@code rbu})
 * @param policyEpoch policy epoch applied ({@code pv}), "" when absent
 * @param late        the work completed after the deadline (point of no return)
 * @param children    fan-out child annotation "name:outcome:ms;..." ({@code kids}), "" when absent
 */
public record RpeResult(long version, String outcome, String reason, boolean terminal, long bpLevel, long bpUtil,
        long budgetUsed, String policyEpoch, boolean late, String children) {

    public static final String OK = "ok", PARTIAL = "partial", REJECTED = "rejected", STOPPED = "stopped", FAILED = "failed", UNKNOWN = "unknown";
    public static final String NONE = "none", DEADLINE = "deadline", CANCELLED = "cancelled", OVERLOAD = "overload",
            INSUFFICIENT_TIME = "insufficient-time", INVALID_CONTEXT = "invalid-context";

    public RpeResult {
        outcome = outcome == null ? "" : outcome;
        reason = reason == null ? "" : reason;
        policyEpoch = policyEpoch == null ? "" : policyEpoch;
        children = children == null ? "" : children;
    }

    public static RpeResult of(String outcome, String reason, boolean terminal, String policyEpoch) {
        return new RpeResult(1, outcome, reason, terminal, 0, 0, 0, policyEpoch, false, "");
    }

    /** Parses a serialized value. Defaults: v=1, out=unknown, rsn=none; unknown members are ignored. */
    public static RpeResult decode(String raw) {
        long v = 1, bp = 0, bpu = 0, rbu = 0;
        String out = UNKNOWN, rsn = NONE, pv = "", kids = "";
        boolean term = false, late = false;
        for (Sfv.Member m : Sfv.parseDictionary(RESULT, raw)) {
            switch (m.key()) {
                case "v" -> v = Sfv.wantInt(m, 1, 1L << 31);
                case "out" -> out = m.item().string();
                case "rsn" -> rsn = m.item().string();
                case "term" -> term = m.item().kind() == Sfv.Kind.BOOLEAN && m.item().bool();
                case "bp" -> bp = Sfv.wantInt(m, MIN_BACKPRESSURE_LEVEL, MAX_BACKPRESSURE_LEVEL);
                case "bpu" -> bpu = Sfv.wantInt(m, 0, 100);
                case "rbu" -> rbu = Sfv.wantInt(m, 0, MAX_RETRY_BUDGET);
                case "pv" -> pv = Sfv.wantString(m, 1, MAX_POLICY_VERSION_BYTES);
                case "late" -> late = m.item().kind() == Sfv.Kind.BOOLEAN && m.item().bool();
                case "kids" -> kids = Sfv.wantString(m, 0, 512);
                default -> { }
            }
        }
        return new RpeResult(v, out, rsn, term, bp, bpu, rbu, pv, late, kids);
    }

    public String encode() {
        Sfv.Writer w = new Sfv.Writer().integer("v", Math.max(version, 1)).token("out", outcome).token("rsn", reason)
                .bool("term", terminal).integer("bp", bpLevel).integer("bpu", bpUtil).integer("rbu", budgetUsed);
        if (!policyEpoch.isEmpty()) w.string("pv", policyEpoch);
        w.bool("late", late);
        if (!children.isEmpty()) w.string("kids", children);
        return w.toString();
    }
}
