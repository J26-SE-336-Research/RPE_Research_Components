package dev.rpe.protocol;

import static dev.rpe.protocol.RpeHeaders.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The end-to-end {@code X-RPE-Context} (spec §2.1). Immutable; deadlines are relative ({@code ttl}) so that clocks
 * never need to be synchronised (R-D01).
 *
 * @param version      protocol version ({@code v})
 * @param rid          request id, 32 lowercase hex, equal to the W3C trace-id
 * @param iid          invocation id of this attempt, 16 lowercase hex
 * @param pid          parent invocation id ({@link RpeHeaders#ROOT_PARENT_ID} at the gateway)
 * @param ttlMillis    remaining deadline when the message was sent
 * @param budgetMillis original end-to-end budget (immutable)
 * @param retryBudget  retry budget remaining for this subtree
 * @param cancel       cancellation state
 * @param policyEpoch  policy epoch the request is pinned to ("" when none; immutable)
 * @param route        route key "METHOD /template" (immutable)
 * @param unknown      members this implementation does not understand, forwarded unchanged (R-V01)
 */
public record RpeContext(long version, String rid, String iid, String pid, long ttlMillis, long budgetMillis,
        long retryBudget, CancelState cancel, String policyEpoch, String route, Map<String, String> unknown) {

    public RpeContext {
        cancel = cancel == null ? CancelState.NONE : cancel;
        policyEpoch = policyEpoch == null ? "" : policyEpoch;
        unknown = unknown == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(unknown));
    }

    /** Parses and validates a serialized {@code X-RPE-Context} value. */
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

    /** Checks required members, version support (R-V02) and that ttl never exceeds the budget (R-T02). */
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

    /** Canonical serialization: known members in a fixed order, then unknown members sorted by key. */
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
     * Creates the root context at the gateway trust boundary (R-S01). {@code traceId} binds rid to an existing W3C
     * trace-id; empty creates a fresh one.
     */
    public static RpeContext newRoot(String route, long budgetMillis, long retryBudget, String epoch, String traceId) {
        String rid = traceId == null || traceId.isEmpty() ? Ids.random(16) : traceId;
        RpeContext c = new RpeContext(SUPPORTED_VERSION, rid, Ids.random(8), ROOT_PARENT_ID, budgetMillis, budgetMillis,
                retryBudget, CancelState.NONE, epoch, route, Map.of());
        c.validate();
        return c;
    }

    /**
     * Derives the context of an outbound call: a fresh iid, pid set to this iid, ttl reduced by the locally measured
     * elapsed time and the post-processing reserve (R-T02, R-D07), and the retry-budget grant (R-T03).
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
