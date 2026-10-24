package dev.rpe.protocol.http;

import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.Hop;
import dev.rpe.protocol.Ids;
import dev.rpe.protocol.ProtocolException;
import dev.rpe.protocol.RpeContext;
import dev.rpe.protocol.RpeHeaders;
import dev.rpe.protocol.RpeResult;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Inbound check for services that run without the sidecar. It works with any server framework. It checks or creates
 * the context and tells the caller what to pass on to the app. A request that arrives late is still let in, with
 * {@code cs=expired} in its context, and the app decides what to stop. Only a retry of a cancelled or expired chain
 * gets a 504.
 *
 * <p>Note: nothing stops a late original request unless the app checks {@code inbound().context().cancel()}.
 */
public final class Admission {
    /** Settings for the admission step. */
    public record Config(boolean gateway, RouteBudget routeBudget, long retryBudget, Supplier<String> epoch, boolean required) {
        public Config {
            routeBudget = routeBudget == null ? (method, path) -> new RouteBudget.Budget(method + " " + path, 5_000) : routeBudget;
            epoch = epoch == null ? () -> "" : epoch;
        }

        public static Config gateway(RouteBudget budgets, long retryBudget, Supplier<String> epoch) {
            return new Config(true, budgets, retryBudget, epoch, false);
        }

        public static Config service() {
            return new Config(false, null, 0, null, false);
        }
    }

    /** Route key and deadline budget for a gateway request. */
    @FunctionalInterface
    public interface RouteBudget {
        record Budget(String route, long budgetMillis) {}

        Budget resolve(String method, String path);
    }

    /**
     * What admission decided. If {@code admitted}, pass {@code contextHeader} and {@code traceparent} to the app
     * instead of the received ones and drop {@code X-RPE-Hop}. If not, answer with {@code status},
     * {@code X-RPE-Result: resultHeader()} and {@code message}. {@code inbound} is null when the request just passes
     * through.
     *
     * @param cancel the cancel state sent back with a refusal, NONE otherwise
     */
    public record Decision(boolean admitted, Inbound inbound, String contextHeader, String traceparent,
            int status, RpeResult result, String message, CancelState cancel) {
        static Decision pass() { return new Decision(true, null, null, null, 0, null, null, CancelState.NONE); }

        static Decision reject(int status, RpeResult result, String message, CancelState cancel) {
            return new Decision(false, null, null, null, status, result, message, cancel);
        }

        /** The {@code X-RPE-Result} value for a refusal, with {@code cs} added when the chain is cancelled. */
        public String resultHeader() {
            if (result == null) return null;
            return cancel == null || cancel == CancelState.NONE ? result.encode() : result.encode() + ", cs=" + cancel.wire();
        }
    }

    private Admission() {}

    public static Decision admit(Config cfg, String method, String path, Map<String, List<String>> headers) {
        long received = System.nanoTime();
        RpeContext c;
        Hop hop;
        try {
            String hopRaw = Headers.single(headers, RpeHeaders.HOP);
            hop = hopRaw.isEmpty() ? new Hop("", 0) : Hop.decode(hopRaw);
            String traceparent = Headers.single(headers, RpeHeaders.TRACEPARENT);
            if (cfg.gateway()) {
                // at the gateway we ignore whatever context the client sent and start a new one
                RouteBudget.Budget b = cfg.routeBudget().resolve(method, path);
                c = RpeContext.newRoot(b.route(), b.budgetMillis(), cfg.retryBudget(), cfg.epoch().get(), Ids.traceIdFromTraceparent(traceparent));
            } else {
                String raw = Headers.single(headers, RpeHeaders.CONTEXT);
                if (raw.isEmpty()) {
                    if (!cfg.required()) return Decision.pass();
                    throw new ProtocolException(dev.rpe.protocol.ErrorCode.MISSING_HEADER, RpeHeaders.CONTEXT, "header absent");
                }
                c = RpeContext.decode(raw);
            }
            if (c.cancel() == CancelState.NONE && c.ttlMillis() <= 0) {
                c = c.withCancel(CancelState.EXPIRED); // mark it, the request still goes through
            }
            if (hop.attempt() >= 2 && (c.cancel() != CancelState.NONE || c.ttlMillis() <= 0)) {
                // refuse a retry of a cancelled or expired chain, but never the original request
                boolean cancelled = c.cancel() == CancelState.CANCEL;
                return Decision.reject(504, RpeResult.of(RpeResult.REJECTED, cancelled ? RpeResult.CANCELLED : RpeResult.DEADLINE, true,
                        c.policyEpoch()), "retry refused (R-R02): the request chain is " + c.cancel().wire(), c.cancel());
            }
            String tp = Ids.traceIdFromTraceparent(traceparent).equals(c.rid()) ? traceparent : Ids.newTraceparent(c.rid());
            return new Decision(true, new Inbound(c, hop, received), c.encode(), tp, 0, null, null, CancelState.NONE);
        } catch (ProtocolException e) {
            return Decision.reject(400, RpeResult.of(RpeResult.REJECTED, RpeResult.INVALID_CONTEXT, true, ""), e.getMessage(), CancelState.NONE);
        }
    }
}
