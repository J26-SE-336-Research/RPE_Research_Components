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
 * Inbound admission, independent of the server framework (servlet filter, Spring interceptor, JDK HttpServer, Netty):
 * validates or creates the context, rejects expired or cancelled requests before application logic
 * (R-D02, R-T04) and reports what to forward to the application.
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
     * {@code X-RPE-Result: result.encode()} and {@code message}. {@code inbound} is null for pass-through.
     */
    public record Decision(boolean admitted, Inbound inbound, String contextHeader, String traceparent,
            int status, RpeResult result, String message) {
        static Decision pass() { return new Decision(true, null, null, null, 0, null, null); }

        static Decision reject(int status, RpeResult result, String message) {
            return new Decision(false, null, null, null, status, result, message);
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
            if (c.cancel() != CancelState.NONE) {
                return Decision.reject(504, RpeResult.of(RpeResult.STOPPED, RpeResult.CANCELLED, true, c.policyEpoch()), "request already cancelled");
            }
            if (c.ttlMillis() <= 0) {
                return Decision.reject(504, RpeResult.of(RpeResult.REJECTED, RpeResult.DEADLINE, true, c.policyEpoch()), "deadline expired on arrival");
            }
            String tp = Ids.traceIdFromTraceparent(traceparent).equals(c.rid()) ? traceparent : Ids.newTraceparent(c.rid());
            return new Decision(true, new Inbound(c, hop, received), c.encode(), tp, 0, null, null);
        } catch (ProtocolException e) {
            return Decision.reject(400, RpeResult.of(RpeResult.REJECTED, RpeResult.INVALID_CONTEXT, true, ""), e.getMessage());
        }
    }
}
