package com.example.proxy;

import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.Ids;
import dev.rpe.protocol.RpeContext;
import dev.rpe.protocol.RpeHeaders;
import dev.rpe.protocol.RpeResult;
import io.netty.handler.codec.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wire format of the request-chain state (Component 1 header contract): one {@code X-RPE-Context} on requests and
 * the {@code cs} member of {@code X-RPE-Result} on responses.
 *
 * <p>The deadline, cancellation and backpressure logic of the proxy is unchanged and keeps working on its internal
 * chain headers ({@code Request_id}, {@code deadlinevalue}, {@code deadlineExceded}, {@code cancellation_Triggered},
 * ...). This class only translates at the edges: {@link #readRequest} turns a received {@code X-RPE-Context} into
 * those internal headers, and {@link #writeRequest} turns them back into {@code X-RPE-Context} just before a request
 * leaves the proxy. The deadline travels as relative time left ({@code ttl}), so pod clocks never have to agree:
 * each proxy converts it to a local absolute deadline on arrival.
 */
final class RpeWire {
    private static final Logger LOGGER = Logger.getLogger(RpeWire.class.getName());

    static final String CONTEXT = RpeHeaders.CONTEXT;
    static final String RESULT = RpeHeaders.RESULT;
    static final String TRACEPARENT = RpeHeaders.TRACEPARENT;
    /** Proxy-internal copy of the context identity stored with a chain; never sent. */
    static final String STORED_CONTEXT = "X-RPE-Stored-Context";

    // the proxy's internal chain headers (names kept as used by ProxyHandler)
    static final String REQUEST_ID = "Request_id";
    static final String DEADLINE = "deadlinevalue";
    static final String EXCEEDED = "deadlineExceded";
    static final String CANCELLATION = "cancellation_Triggered";
    static final String ROUTE = "Request_route";
    static final String BUDGET = "Request_deadline_millis";
    static final String CHAIN_METHOD = "Request_method";
    private static final String[] INTERNAL_REQUEST_HEADERS = {DEADLINE, EXCEEDED, CANCELLATION, ROUTE, BUDGET,
            CHAIN_METHOD, STORED_CONTEXT};
    private static final String[] INTERNAL_RESPONSE_HEADERS = {REQUEST_ID, DEADLINE, EXCEEDED, CANCELLATION};

    private RpeWire() {}

    /**
     * The identity of one forwarded request: the context fields the proxy does not own (rid, iid, pid, budget,
     * retry budget, epoch, route, incoming cancellation state, unknown members) and the deadline the sender asked
     * for. The proxy owns the remaining time and the deadline / cancellation flags.
     */
    record Identity(RpeContext context, long requestedDeadlineMillis) {}

    /** A fresh request id: 32 lowercase hex characters, usable as a W3C trace-id. */
    static String newRequestId() {
        return Ids.random(16);
    }

    static boolean isRequestId(String value) {
        if (value == null || value.length() != RpeHeaders.REQUEST_ID_LENGTH) return false;
        boolean nonZero = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            if (c != '0') nonZero = true;
        }
        return nonZero;
    }

    /**
     * Ingress: when the request carries exactly one valid {@code X-RPE-Context}, removes it and writes the internal
     * chain headers the proxy logic reads. A missing, duplicated or invalid context is left untouched (the request
     * then has no context for the proxy) and null is returned.
     */
    static Identity readRequest(HttpHeaders headers, long nowMillis) {
        List<String> values = headers.getAll(CONTEXT);
        if (values.size() != 1) return null;
        RpeContext context;
        try {
            context = RpeContext.decode(values.get(0));
        } catch (RuntimeException invalid) {
            return null;
        }
        if (!isRequestId(context.rid())) return null;
        headers.remove(CONTEXT);
        long deadline = saturatingAdd(nowMillis, context.ttlMillis());
        headers.set(REQUEST_ID, context.rid());
        headers.set(DEADLINE, Long.toString(deadline));
        headers.set(EXCEEDED, Boolean.toString(context.cancel() == CancelState.EXPIRED));
        headers.set(CANCELLATION, Boolean.toString(context.cancel() != CancelState.NONE));
        String route = context.route();
        int space = route.indexOf(' ');
        if (space > 0) {
            headers.set(CHAIN_METHOD, route.substring(0, space));
            headers.set(ROUTE, route.substring(space + 1));
        } else {
            headers.set(ROUTE, route);
        }
        headers.set(BUDGET, Long.toString(context.budgetMillis()));
        return new Identity(context, deadline);
    }

    /** Identity of a request chain created by this proxy (the gateway trust boundary), or null for a non-RPE id. */
    static Identity root(String rid, String method, String route, long budgetMillis, long retryBudget, String epoch) {
        if (!isRequestId(rid)) return null;
        try {
            RpeContext context = RpeContext.newRoot(routeKey(method, route), clampBudget(budgetMillis), retryBudget,
                    validEpoch(epoch), rid);
            return new Identity(context, Long.MAX_VALUE);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * The same identity with the policy epoch replaced. {@code pe} is immutable along a chain (A3.1) and the gateway
     * proxy is its authority: the epoch it pinned when the request entered replaces whatever the co-located
     * application stamped on its outbound call (an application learns the active epoch by polling, so it can lag).
     */
    static Identity withEpoch(Identity identity, String epoch) {
        RpeContext c = identity.context();
        String pe = validEpoch(epoch);
        if (pe.equals(c.policyEpoch())) return identity;
        return new Identity(new RpeContext(c.version(), c.rid(), c.iid(), c.pid(), c.ttlMillis(), c.budgetMillis(),
                c.retryBudget(), c.cancel(), pe, c.route(), c.unknown()), identity.requestedDeadlineMillis());
    }

    /** Identity of an outbound call that carried only {@code Request_id}: a child of the stored inbound context. */
    static Identity child(Identity parent) {
        RpeContext context = parent.context();
        return new Identity(context.child(0, 0, context.retryBudget()), Long.MAX_VALUE);
    }

    static String store(Identity identity) {
        return identity == null ? null : identity.context().encode();
    }

    static Identity restore(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            return new Identity(RpeContext.decode(stored), Long.MAX_VALUE);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * Egress: replaces the internal chain headers with {@code X-RPE-Context}. {@code Request_id} is kept only on
     * the hop to the co-located application, which propagates it on its outbound calls. If no valid context can be
     * built (for example a chain id that is not an RPE request id), the internal headers are left as they are.
     */
    static void writeRequest(HttpHeaders headers, Identity identity, boolean toLocalApplication, long nowMillis) {
        headers.remove(CONTEXT);
        String rid = headers.get(REQUEST_ID);
        if (identity == null || !isRequestId(rid)) {
            headers.remove(STORED_CONTEXT);
            return;
        }
        try {
            RpeContext in = identity.context();
            long deadline = Math.min(parseLong(headers.get(DEADLINE), Long.MAX_VALUE), identity.requestedDeadlineMillis());
            long ttl = deadline == Long.MAX_VALUE ? in.budgetMillis()
                    : Math.max(0, Math.min(in.budgetMillis(), deadline - nowMillis));
            CancelState cancel = in.cancel() != CancelState.NONE ? in.cancel() // never changes once set (R-T04)
                    : Boolean.parseBoolean(headers.get(EXCEEDED)) ? CancelState.EXPIRED
                    : Boolean.parseBoolean(headers.get(CANCELLATION)) ? CancelState.CANCEL
                    : CancelState.NONE;
            RpeContext out = new RpeContext(in.version(), rid, in.iid(), in.pid(), ttl, in.budgetMillis(),
                    in.retryBudget(), cancel, in.policyEpoch(), in.route(), in.unknown());
            out.validate();
            String encoded = out.encode();
            if (encoded.getBytes(StandardCharsets.UTF_8).length > RpeHeaders.MAX_HEADER_VALUE_BYTES) {
                throw new IllegalArgumentException("X-RPE-Context exceeds 256 bytes");
            }
            headers.set(CONTEXT, encoded);
            for (String internal : INTERNAL_REQUEST_HEADERS) headers.remove(internal);
            if (!toLocalApplication) headers.remove(REQUEST_ID);
        } catch (RuntimeException invalid) {
            headers.remove(STORED_CONTEXT);
            LOGGER.log(Level.WARNING, "Could not encode X-RPE-Context for Request_id=" + rid
                    + "; forwarding the internal chain headers", invalid);
        }
    }

    /** Binds the W3C trace-id to the request id (trace binding), keeping a traceparent that already matches. */
    static void bindTraceparent(HttpHeaders headers, String rid) {
        if (!isRequestId(rid)) return;
        if (!rid.equals(Ids.traceIdFromTraceparent(headers.get(TRACEPARENT)))) {
            headers.set(TRACEPARENT, Ids.newTraceparent(rid));
        }
    }

    /** Chain state reported by a downstream response: {expired, cancelled}. */
    static boolean[] readResponseState(HttpHeaders headers) {
        String cs = member(headers.get(RESULT), "cs");
        boolean expired = "expired".equals(cs) || Boolean.parseBoolean(headers.get(EXCEEDED));
        boolean cancelled = expired || "cancel".equals(cs) || Boolean.parseBoolean(headers.get(CANCELLATION));
        return new boolean[]{expired, cancelled};
    }

    /**
     * Writes the chain state on a response going upstream as the {@code cs} member of {@code X-RPE-Result}. The
     * application's own members are kept; a response without a result only gets one when the chain is cancelled.
     */
    static void writeResponseState(HttpHeaders headers, boolean expired, boolean cancelled) {
        for (String internal : INTERNAL_RESPONSE_HEADERS) headers.remove(internal);
        String cs = cancelState(expired, cancelled).wire();
        String result = headers.get(RESULT);
        if (result != null && !result.isBlank()) {
            headers.set(RESULT, withMember(result, "cs", cs));
        } else if (!cs.equals("none")) {
            headers.set(RESULT, "v=1, cs=" + cs);
        }
    }

    /** {@code X-RPE-Result} of a request the proxy rejected because of backpressure (reason overload or circuit-open). */
    static String rejectionResult(String reason, Integer queueUtilizationPercent, boolean expired, boolean cancelled) {
        int utilization = queueUtilizationPercent == null ? 0 : Math.max(0, Math.min(100, queueUtilizationPercent));
        String result = new RpeResult(1, RpeResult.REJECTED, reason, false, 0, utilization, 0, "", false, "").encode();
        return withMember(result, "cs", cancelState(expired, cancelled).wire());
    }

    static CancelState cancelState(boolean expired, boolean cancelled) {
        return expired ? CancelState.EXPIRED : cancelled ? CancelState.CANCEL : CancelState.NONE;
    }

    /** Raw value of a top-level member of an RFC 8941 dictionary, or null. */
    static String member(String dictionary, String key) {
        if (dictionary == null) return null;
        for (String part : splitMembers(dictionary)) {
            String trimmed = part.trim();
            String name = keyOf(trimmed);
            if (name.equals(key)) {
                int equals = trimmed.indexOf('=');
                return equals < 0 ? "?1" : trimmed.substring(equals + 1).trim();
            }
        }
        return null;
    }

    /** Sets (or replaces) a top-level dictionary member, keeping the other members in order. */
    static String withMember(String dictionary, String key, String value) {
        List<String> out = new ArrayList<>();
        boolean replaced = false;
        for (String part : splitMembers(dictionary)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (keyOf(trimmed).equals(key)) {
                if (!replaced) out.add(key + "=" + value);
                replaced = true;
            } else {
                out.add(trimmed);
            }
        }
        if (!replaced) out.add(key + "=" + value);
        return String.join(", ", out);
    }

    private static List<String> splitMembers(String dictionary) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < dictionary.length(); i++) {
            char c = dictionary.charAt(i);
            if (quoted && c == '\\' && i + 1 < dictionary.length()) {
                current.append(c).append(dictionary.charAt(++i));
                continue;
            }
            if (c == '"') quoted = !quoted;
            if (c == ',' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String keyOf(String member) {
        int end = 0;
        while (end < member.length() && member.charAt(end) != '=' && member.charAt(end) != ';') end++;
        return member.substring(0, end).trim();
    }

    private static String routeKey(String method, String route) {
        String key = (method == null || method.isBlank() ? "GET" : method) + " "
                + (route == null || route.isBlank() ? "/" : route);
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= RpeHeaders.MAX_ROUTE_BYTES) return key;
        return new String(bytes, 0, RpeHeaders.MAX_ROUTE_BYTES, StandardCharsets.UTF_8).replace("�", "");
    }

    private static long clampBudget(long budgetMillis) {
        return Math.max(RpeHeaders.MIN_DEADLINE_MILLIS, Math.min(RpeHeaders.MAX_DEADLINE_MILLIS, budgetMillis));
    }

    private static String validEpoch(String epoch) {
        if (epoch == null || epoch.isEmpty() || epoch.length() > RpeHeaders.MAX_POLICY_VERSION_BYTES) return "";
        for (int i = 0; i < epoch.length(); i++) {
            char c = epoch.charAt(i);
            if (c < 0x20 || c > 0x7e) return "";
        }
        return epoch;
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value.trim()); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    private static long saturatingAdd(long left, long right) {
        long sum = left + right;
        if (((left ^ sum) & (right ^ sum)) < 0) return right > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        return sum;
    }
}
