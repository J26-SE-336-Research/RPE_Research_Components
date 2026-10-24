package dev.rpe.protocolservice;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rpe.protocol.BudgetSplit;
import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.ContextGuard;
import dev.rpe.protocol.Hop;
import dev.rpe.protocol.Ids;
import dev.rpe.protocol.ProtocolException;
import dev.rpe.protocol.RetryGate;
import dev.rpe.protocol.RpeContext;
import dev.rpe.protocol.RpeResult;
import dev.rpe.protocol.Trail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates the shared conformance vectors with rpe-java at run time (the same checks as the library's
 * {@code ConformanceTest}), so the admin panel can show the Go and Java verdicts of every case side by side.
 */
final class ConformanceRunner {
    record CaseResult(String name, boolean pass, String detail) {}

    record FileResult(String file, String description, List<CaseResult> cases, String error) {}

    @FunctionalInterface
    interface Check {
        /** Returns null when the case passes, otherwise what rpe-java produced. */
        String run(JsonNode c) throws Exception;
    }

    static final Map<String, Check> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("context-decode.json", c -> {
            if (c.has("error")) return failure(c.get("error"), () -> RpeContext.decode(c.get("input").asText()));
            RpeContext got = RpeContext.decode(c.get("input").asText());
            boolean ok = got.equals(context(c.get("expect"))) && got.encode().equals(c.get("canonical").asText());
            return ok ? null : got.encode();
        });
        CHECKS.put("context-encode.json", c -> {
            String got = context(c.get("fields")).encode();
            return got.equals(c.get("expect").asText()) ? null : got;
        });
        CHECKS.put("context-transition.json", c -> {
            List<String> got = ContextGuard.validateTransition(context(c.get("parent")), context(c.get("child")), c.get("elapsedMs").asLong())
                    .stream().map(ProtocolException::rule).toList();
            List<String> want = new ArrayList<>();
            c.get("violations").forEach(v -> want.add(v.asText()));
            return got.equals(want) ? null : got.toString();
        });
        CHECKS.put("context-child.json", c -> {
            RpeContext parent = context(c.get("parent"));
            long elapsed = c.get("elapsedMs").asLong(), reserve = c.get("reserveMs").asLong(), grant = c.get("grant").asLong();
            if (c.has("error")) return failure(c.get("error"), () -> parent.child(elapsed, reserve, grant));
            RpeContext child = parent.child(elapsed, reserve, grant);
            boolean ok = child.ttlMillis() == c.get("expectTtl").asLong() && child.retryBudget() == c.get("expectRb").asLong()
                    && child.pid().equals(parent.iid()) && !child.iid().equals(parent.iid())
                    && ContextGuard.validateTransition(parent, child, elapsed).isEmpty();
            return ok ? null : "ttl=" + child.ttlMillis() + " rb=" + child.retryBudget();
        });
        CHECKS.put("budget-split.json", c -> {
            BudgetSplit.Mode mode = c.get("mode").asText().equals("sequential") ? BudgetSplit.Mode.SEQUENTIAL : BudgetSplit.Mode.PARALLEL;
            long[] got = BudgetSplit.split(mode, c.get("rb").asLong(), c.get("reserve").asLong(), c.get("n").asInt());
            long[] want = new long[c.get("expect").size()];
            for (int i = 0; i < want.length; i++) want[i] = c.get("expect").get(i).asLong();
            return Arrays.equals(got, want) ? null : Arrays.toString(got);
        });
        CHECKS.put("result.json", c -> {
            if (c.has("error")) return failure(c.get("error"), () -> RpeResult.decode(c.get("input").asText()));
            RpeResult r = RpeResult.decode(c.get("input").asText());
            JsonNode e = c.get("expect");
            RpeResult want = new RpeResult(e.get("v").asLong(), e.get("out").asText(), e.get("rsn").asText(), e.get("term").asBoolean(),
                    e.get("bp").asLong(), e.get("bpu").asLong(), e.get("rbu").asLong(), e.get("pv").asText(), e.get("late").asBoolean(), e.get("kids").asText());
            return r.equals(want) && r.encode().equals(c.get("canonical").asText()) ? null : r.encode();
        });
        CHECKS.put("hop.json", c -> {
            if (c.has("error")) return failure(c.get("error"), () -> Hop.decode(c.get("input").asText()));
            Hop h = Hop.decode(c.get("input").asText());
            boolean ok = h.callerToken().equals(c.get("expectCb").asText()) && h.attempt() == c.get("expectAtt").asLong()
                    && h.encode().equals(c.get("canonical").asText());
            return ok ? null : h.encode();
        });
        CHECKS.put("traceparent.json", c -> {
            String got = Ids.traceIdFromTraceparent(c.get("input").asText());
            return got.equals(c.get("expect").asText()) ? null : got;
        });
        CHECKS.put("retry-gate.json", c -> {
            JsonNode i = c.get("input");
            RetryGate.Verdict v = RetryGate.decide(new RetryGate.Check(i.get("attempt").asLong(), i.get("maxAttempts").asLong(),
                    cancel(i.get("cs").asText()), i.get("terminal").asBoolean(), i.get("replaySafe").asBoolean(), i.get("remainingMs").asLong(),
                    i.get("backoffMs").asLong(), i.get("attemptCostMs").asLong(), i.get("rb").asLong()));
            JsonNode e = c.get("expect");
            RetryGate.Verdict want = new RetryGate.Verdict(e.get("retry").asBoolean(), e.get("reason").asText(), e.get("rule").asText(),
                    e.get("nextAttempt").asLong(), e.get("budgetLeft").asLong());
            return v.equals(want) ? null : v.toString();
        });
        CHECKS.put("trail.json", c -> {
            if (c.has("error")) return failure(c.get("error"), () -> Trail.decode(c.get("input").asText()));
            Trail t = Trail.decode(c.get("input").asText());
            JsonNode e = c.get("expect");
            List<Trail.Entry> entries = new ArrayList<>();
            e.get("entries").forEach(x -> entries.add(new Trail.Entry(x.get("svc").asLong(), x.get("parent").asInt(),
                    x.get("elapsedMicros").asLong(), x.get("decision").asText().charAt(0), x.get("consumedMs").asLong())));
            boolean ok = t.equals(new Trail(e.get("rv").asLong(), e.get("inc").asBoolean(), entries)) && t.encode().equals(c.get("canonical").asText());
            return ok ? null : t.encode();
        });
        CHECKS.put("trail-merge.json", c -> {
            String got = Trail.decode(c.get("base").asText()).append(Trail.decode(c.get("sub").asText()), c.get("parent").asInt()).encode();
            return got.equals(c.get("expect").asText()) ? null : got;
        });
    }

    private ConformanceRunner() {}

    static List<FileResult> runAll(Path dir) {
        List<FileResult> out = new ArrayList<>();
        CHECKS.forEach((file, check) -> {
            try {
                JsonNode suite = Main.JSON.readTree(Files.readString(dir.resolve(file)));
                List<CaseResult> cases = new ArrayList<>();
                for (JsonNode c : suite.get("cases")) {
                    String detail;
                    try {
                        detail = check.run(c);
                    } catch (Exception e) {
                        detail = "unexpected " + e;
                    }
                    cases.add(new CaseResult(c.get("name").asText(), detail == null, detail));
                }
                out.add(new FileResult(file, suite.path("description").asText(), cases, null));
            } catch (Exception e) {
                out.add(new FileResult(file, "", List.of(), e.toString()));
            }
        });
        return out;
    }

    interface Action {
        void run();
    }

    /** Null when the action fails with the expected code and rule. */
    private static String failure(JsonNode expected, Action action) {
        try {
            action.run();
            return "no error";
        } catch (ProtocolException e) {
            String rule = expected.has("rule") ? expected.get("rule").asText() : null;
            boolean ok = expected.get("code").asText().equals(e.code().name()) && Objects.equals(rule, e.rule());
            return ok ? null : e.code().name() + (e.rule() == null ? "" : " " + e.rule());
        }
    }

    private static CancelState cancel(String s) {
        return switch (s) {
            case "cancel" -> CancelState.CANCEL;
            case "expired" -> CancelState.EXPIRED;
            default -> CancelState.NONE;
        };
    }

    private static RpeContext context(JsonNode f) {
        Map<String, String> unknown = new LinkedHashMap<>();
        if (f.has("unknown")) f.get("unknown").fields().forEachRemaining(e -> unknown.put(e.getKey(), e.getValue().asText()));
        return new RpeContext(f.get("v").asLong(), f.get("rid").asText(), f.get("iid").asText(), f.get("pid").asText(), f.get("ttl").asLong(),
                f.get("bud").asLong(), f.get("rb").asLong(), cancel(f.get("cs").asText()), f.get("pe").asText(), f.get("rt").asText(), unknown);
    }
}
