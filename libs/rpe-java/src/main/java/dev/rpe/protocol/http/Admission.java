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
 * Inbound admission, independent of the server framework (servlet filter, Spring interceptor, JDK HttpServer, Netty),
 * for services that run without the RPE sidecar: validates or creates the context and reports what to forward to the
 * application. Signal mode (R-D02): an original request that arrives with no time left is admitted with
 * {@code cs=expired} in the context it forwards, never rejected; the application decides what to stop. Only a retry
 * ({@code X-RPE-Hop att >= 2}) of a cancelled or expired chain is refused with a terminal 504 (R-R02).
 *
 * <p>Warning for application developers: nothing stops an expired original request unless the application reads the
 * signal ({@code inbound().context().cancel()}).
 */
public final class Admission {
    /** Configuration of the admission step. */
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

    /** Route key and deadline budget of a gateway request. */
    @FunctionalInterface
    public interface RouteBudget {
        record Budget(String route, long budgetMillis) {}

        Budget resolve(String method, String path);
    }

    /**
     * The admission decision. When {@code admitted}, forward {@code contextHeader} and {@code traceparent} to the
     * application instead of the received values and drop {@code X-RPE-Hop}. Otherwise reply with {@code status},
     * {@code X-RPE-Result: resultHeader()} and {@code message}. {@code inbound} is null for pass-through.
     *
     * @param cancel the chain's cancellation state reported with a refusal ({@code cs} member), NONE otherwise
     */
    public record Decision(boolean admitted, Inbound inbound, String contextHeader, String traceparent,
            int status, RpeResult result, String message, CancelState cancel) {
        static Decision pass() { return new Decision(true, null, null, null, 0, null, null, CancelState.NONE); }

        static Decision reject(int status, RpeResult result, String message, CancelState cancel) {
            return new Decision(false, null, null, null, status, result, message, cancel);
        }

        /** The {@code X-RPE-Result} value of a refusal, with the {@code cs} member when the chain is cancelled. */
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
                // trust boundary (R-S01): any client-supplied context is ignored and a fresh root is created
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
                c = c.withCancel(CancelState.EXPIRED); // signal it (R-D02); the request is still admitted
            }
            if (hop.attempt() >= 2 && (c.cancel() != CancelState.NONE || c.ttlMillis() <= 0)) {
                // R-R02: a retry of a cancelled or expired chain is refused; an original request never is
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
