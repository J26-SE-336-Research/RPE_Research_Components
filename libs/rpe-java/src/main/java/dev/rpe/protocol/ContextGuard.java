package dev.rpe.protocol;

import java.util.ArrayList;
import java.util.List;

/** Checks that a child context follows the rules from its parent. */
public final class ContextGuard {
    private ContextGuard() {}

    /**
     * Returns every rule the child breaks, or an empty list if it is fine.
     *
     * @param elapsedMillis how long the parent spent before sending the child request
     */
    public static List<ProtocolException> validateTransition(RpeContext in, RpeContext out, long elapsedMillis) {
        List<ProtocolException> v = new ArrayList<>();
        String h = RpeHeaders.CONTEXT;
        if (!in.rid().equals(out.rid()) || in.budgetMillis() != out.budgetMillis() || !in.route().equals(out.route())
                || !in.policyEpoch().equals(out.policyEpoch())) {
            v.add(new ProtocolException("R-T01", ErrorCode.INVALID_TRANSITION, h, "rid, bud, rt and pe are immutable"));
        }
        if (out.ttlMillis() > in.ttlMillis() - elapsedMillis) {
            v.add(new ProtocolException("R-T02", ErrorCode.INVALID_TRANSITION, h, "ttl must not increase"));
        }
        if (out.retryBudget() > in.retryBudget()) {
            v.add(new ProtocolException("R-T03", ErrorCode.INVALID_TRANSITION, h, "retry budget must not increase"));
        }
        if (in.cancel() != CancelState.NONE && out.cancel() != in.cancel()) {
            v.add(new ProtocolException("R-T04", ErrorCode.INVALID_TRANSITION, h, "cancellation state must not be cleared"));
        }
        if (out.version() < in.version()) {
            v.add(new ProtocolException("R-T05", ErrorCode.INVALID_TRANSITION, h, "version must not downgrade"));
        }
        if (!out.pid().equals(in.iid()) || out.iid().equals(in.iid())) {
            v.add(new ProtocolException("R-T06", ErrorCode.INVALID_TRANSITION, h, "child pid must equal parent iid and iid must be fresh"));
        }
        return v;
    }
}
