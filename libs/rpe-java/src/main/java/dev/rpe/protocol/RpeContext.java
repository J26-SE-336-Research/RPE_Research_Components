package dev.rpe.protocol;

import static dev.rpe.protocol.RpeHeaders.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code X-RPE-Context} header. It can't be changed once built. The deadline is sent as time left
 * ({@code ttl}), so the machines' clocks don't have to agree.
 *
 * @param version      protocol version ({@code v})
 * @param rid          request id, 32 lowercase hex, equal to the W3C trace-id
 * @param iid          id of this call or attempt, 16 lowercase hex
 * @param pid          id of the parent call, {@link RpeHeaders#ROOT_PARENT_ID} at the gateway
 * @param ttlMillis    time left when the message was sent
 * @param budgetMillis the full budget from the gateway, never changes
 * @param retryBudget  retries left for this part of the chain
 * @param cancel       cancel state
 * @param policyEpoch  policy epoch the request sticks to, "" if none
 * @param route        route key like "GET /api/orders"
 * @param unknown      members we don't understand, passed on as they are
 */
public record RpeContext(long version, String rid, String iid, String pid, long ttlMillis, long budgetMillis,
        long retryBudget, CancelState cancel, String policyEpoch, String route, Map<String, String> unknown) {

    public RpeContext {
        cancel = cancel == null ? CancelState.NONE : cancel;
        policyEpoch = policyEpoch == null ? "" : policyEpoch;
        unknown = unknown == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(unknown));
    }

    /** Parses and checks an {@code X-RPE-Context} value. */
    public static RpeContext decode(String raw) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_HEADER_VALUE_BYTES) {
            throw new ProtocolException(ErrorCode.CONTEXT_TOO_LARGE, CONTEXT, "value exceeds 256 bytes");
        }
        long v = 0, ttl = 0, bud = 0, rb = 0;
        String rid = "", iid = "", pid = "", pe = "", rt = "";
        CancelState cs = CancelState.NONE;
        Map<String, String> unknown = new LinkedHashMap<>();
        for (Sfv.Member m : Sfv.parseDictionary(CONTEXT, raw)) {
            switch (m.key()) {
                case "v" -> v = Sfv.wantInt(m, 1, 1L << 31);
                case "rid" -> rid = Sfv.wantId(m, REQUEST_ID_LENGTH, false);
                case "iid" -> iid = Sfv.wantId(m, INVOCATION_ID_LENGTH, false);
                case "pid" -> pid = Sfv.wantId(m, INVOCATION_ID_LENGTH, true);
                case "ttl" -> ttl = Sfv.wantInt(m, -MAX_DEADLINE_MILLIS, MAX_DEADLINE_MILLIS);
                case "bud" -> bud = Sfv.wantInt(m, MIN_DEADLINE_MILLIS, MAX_DEADLINE_MILLIS);
                case "rb" -> rb = Sfv.wantInt(m, MIN_RETRY_BUDGET, MAX_RETRY_BUDGET);
                case "cs" -> {
                    if (m.item().kind() != Sfv.Kind.TOKEN) {
                        throw new ProtocolException(ErrorCode.INVALID_SYNTAX, CONTEXT, "cs must be a token");
                    }
                    cs = CancelState.fromWire(m.item().string());
                }
                case "pe" -> pe = Sfv.wantString(m, 1, MAX_POLICY_VERSION_BYTES);
                case "rt" -> rt = Sfv.wantString(m, MIN_ROUTE_BYTES, MAX_ROUTE_BYTES);
                default -> unknown.put(m.key(), m.item().raw());
            }
        }
        RpeContext c = new RpeContext(v, rid, iid, pid, ttl, bud, rb, cs, pe, rt, unknown);
        c.validate();
        return c;
    }

    /** Checks the required members, the version, and that ttl is not bigger than the budget. */
    public void validate() {
        if (version == 0 || rid.isEmpty() || iid.isEmpty() || pid.isEmpty() || budgetMillis == 0 || route.isEmpty()) {
            throw new ProtocolException(ErrorCode.MISSING_HEADER, CONTEXT, "v, rid, iid, pid, bud and rt are required");
        }
        if (version != SUPPORTED_VERSION) {
            throw new ProtocolException("R-V02", ErrorCode.UNSUPPORTED_VERSION, CONTEXT, "unsupported protocol version");
        }
        if (ttlMillis > budgetMillis) {
            throw new ProtocolException("R-T02", ErrorCode.INVALID_TRANSITION, CONTEXT, "ttl exceeds the original budget");
        }
    }

    /** Writes the header value: known members in a fixed order, then the unknown ones sorted by key. */
    public String encode() {
        Sfv.Writer w = new Sfv.Writer()
                .integer("v", version).string("rid", rid).string("iid", iid).string("pid", pid)
                .integer("ttl", ttlMillis).integer("bud", budgetMillis).integer("rb", retryBudget)
                .token("cs", cancel.wire());
        if (!policyEpoch.isEmpty()) {
            w.string("pe", policyEpoch);
        }
        return w.string("rt", route).unknown(unknown).toString();
    }

    /**
     * Creates the first context at the gateway. {@code traceId} can be an existing W3C trace-id to use as rid, if it
     * is empty a new one is made.
     */
    public static RpeContext newRoot(String route, long budgetMillis, long retryBudget, String epoch, String traceId) {
        String rid = traceId == null || traceId.isEmpty() ? Ids.random(16) : traceId;
        RpeContext c = new RpeContext(SUPPORTED_VERSION, rid, Ids.random(8), ROOT_PARENT_ID, budgetMillis, budgetMillis,
                retryBudget, CancelState.NONE, epoch, route, Map.of());
        c.validate();
        return c;
    }

    /**
     * Makes the context for an outbound call: a new iid, pid set to our iid, ttl minus the time spent here and the
     * reserve, and the retry budget given to this child.
     */
    public RpeContext child(long elapsedMillis, long reserveMillis, long grant) {
        if (grant < 0 || grant > retryBudget) {
            throw new ProtocolException("R-T03", ErrorCode.INVALID_TRANSITION, CONTEXT, "child grant exceeds parent retry budget");
        }
        return new RpeContext(version, rid, Ids.random(8), iid, ttlMillis - elapsedMillis - reserveMillis, budgetMillis,
                grant, cancel, policyEpoch, route, unknown);
    }

    public RpeContext withTtl(long ttl) {
        return new RpeContext(version, rid, iid, pid, ttl, budgetMillis, retryBudget, cancel, policyEpoch, route, unknown);
    }

    public RpeContext withCancel(CancelState cs) {
        return new RpeContext(version, rid, iid, pid, ttlMillis, budgetMillis, retryBudget, cs, policyEpoch, route, unknown);
    }
}
