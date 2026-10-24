package dev.rpe.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared conformance cases in {@code conformance-kit/vectors} against rpe-java. The Go side runs the same
 * files, so together they show both libraries agree on every case.
 */
class ConformanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIR = Path.of(System.getProperty("rpe.conformance.dir", "../../conformance-kit/vectors"));

    private static Stream<JsonNode> cases(String file) throws IOException {
        JsonNode suite = JSON.readTree(Files.readString(DIR.resolve(file)));
        List<JsonNode> out = new ArrayList<>();
        suite.get("cases").forEach(out::add);
        assertFalse(out.isEmpty(), file + " has no cases");
        return out.stream();
    }

    private static RpeContext context(JsonNode f) {
        Map<String, String> unknown = new LinkedHashMap<>();
        if (f.has("unknown")) f.get("unknown").fields().forEachRemaining(e -> unknown.put(e.getKey(), e.getValue().asText()));
        CancelState cs = switch (f.get("cs").asText()) {
            case "cancel" -> CancelState.CANCEL;
            case "expired" -> CancelState.EXPIRED;
            default -> CancelState.NONE;
        };
        return new RpeContext(f.get("v").asLong(), f.get("rid").asText(), f.get("iid").asText(), f.get("pid").asText(),
                f.get("ttl").asLong(), f.get("bud").asLong(), f.get("rb").asLong(), cs, f.get("pe").asText(), f.get("rt").asText(), unknown);
    }

    private static void assertFailure(JsonNode expected, ProtocolException e, String name) {
        assertEquals(expected.get("code").asText(), e.code().name(), name + ": code (" + e.getMessage() + ")");
        assertEquals(expected.has("rule") ? expected.get("rule").asText() : null, e.rule(), name + ": rule");
    }

    @TestFactory
    Stream<DynamicTest> contextDecode() throws IOException {
        return cases("context-decode.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            String name = c.get("name").asText();
            if (c.has("error")) {
                ProtocolException e = assertThrows(ProtocolException.class, () -> RpeContext.decode(c.get("input").asText()), name);
                assertFailure(c.get("error"), e, name);
                return;
            }
            RpeContext got = RpeContext.decode(c.get("input").asText());
            assertEquals(context(c.get("expect")), got, name);
            assertEquals(c.get("canonical").asText(), got.encode(), name + ": canonical encoding");
        }));
    }

    @TestFactory
    Stream<DynamicTest> contextEncode() throws IOException {
        return cases("context-encode.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(),
                () -> assertEquals(c.get("expect").asText(), context(c.get("fields")).encode())));
    }

    @TestFactory
    Stream<DynamicTest> contextTransition() throws IOException {
        return cases("context-transition.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            List<String> got = ContextGuard.validateTransition(context(c.get("parent")), context(c.get("child")), c.get("elapsedMs").asLong())
                    .stream().map(ProtocolException::rule).toList();
            List<String> want = new ArrayList<>();
            c.get("violations").forEach(v -> want.add(v.asText()));
            assertEquals(want, got);
        }));
    }

    @TestFactory
    Stream<DynamicTest> contextChild() throws IOException {
        return cases("context-child.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            RpeContext parent = context(c.get("parent"));
            if (c.has("error")) {
                ProtocolException e = assertThrows(ProtocolException.class,
                        () -> parent.child(c.get("elapsedMs").asLong(), c.get("reserveMs").asLong(), c.get("grant").asLong()));
                assertFailure(c.get("error"), e, c.get("name").asText());
                return;
            }
            RpeContext child = parent.child(c.get("elapsedMs").asLong(), c.get("reserveMs").asLong(), c.get("grant").asLong());
            assertEquals(c.get("expectTtl").asLong(), child.ttlMillis());
            assertEquals(c.get("expectRb").asLong(), child.retryBudget());
            assertEquals(parent.iid(), child.pid());
            assertNotEquals(parent.iid(), child.iid());
            assertTrue(ContextGuard.validateTransition(parent, child, c.get("elapsedMs").asLong()).isEmpty(), "a derived child passes the guard");
        }));
    }

    @TestFactory
    Stream<DynamicTest> budgetSplit() throws IOException {
        return cases("budget-split.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            BudgetSplit.Mode mode = c.get("mode").asText().equals("sequential") ? BudgetSplit.Mode.SEQUENTIAL : BudgetSplit.Mode.PARALLEL;
            long[] got = BudgetSplit.split(mode, c.get("rb").asLong(), c.get("reserve").asLong(), c.get("n").asInt());
            long[] want = new long[c.get("expect").size()];
            for (int i = 0; i < want.length; i++) want[i] = c.get("expect").get(i).asLong();
            assertArrayEquals(want, got);
        }));
    }

    @TestFactory
    Stream<DynamicTest> result() throws IOException {
        return cases("result.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            String name = c.get("name").asText();
            if (c.has("error")) {
                assertFailure(c.get("error"), assertThrows(ProtocolException.class, () -> RpeResult.decode(c.get("input").asText())), name);
                return;
            }
            RpeResult r = RpeResult.decode(c.get("input").asText());
            JsonNode e = c.get("expect");
            assertEquals(new RpeResult(e.get("v").asLong(), e.get("out").asText(), e.get("rsn").asText(), e.get("term").asBoolean(),
                    e.get("bp").asLong(), e.get("bpu").asLong(), e.get("rbu").asLong(), e.get("pv").asText(), e.get("late").asBoolean(),
                    e.get("kids").asText()), r, name);
            assertEquals(c.get("canonical").asText(), r.encode(), name + ": canonical encoding");
        }));
    }

    @TestFactory
    Stream<DynamicTest> hop() throws IOException {
        return cases("hop.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            String name = c.get("name").asText();
            if (c.has("error")) {
                assertFailure(c.get("error"), assertThrows(ProtocolException.class, () -> Hop.decode(c.get("input").asText())), name);
                return;
            }
            Hop h = Hop.decode(c.get("input").asText());
            assertEquals(c.get("expectCb").asText(), h.callerToken());
            assertEquals(c.get("expectAtt").asLong(), h.attempt());
            assertEquals(c.get("canonical").asText(), h.encode());
        }));
    }

    @TestFactory
    Stream<DynamicTest> traceparent() throws IOException {
        return cases("traceparent.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(),
                () -> assertEquals(c.get("expect").asText(), Ids.traceIdFromTraceparent(c.get("input").asText()))));
    }

    @TestFactory
    Stream<DynamicTest> retryGate() throws IOException {
        return cases("retry-gate.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            JsonNode i = c.get("input");
            CancelState cs = switch (i.get("cs").asText()) {
                case "cancel" -> CancelState.CANCEL;
                case "expired" -> CancelState.EXPIRED;
                default -> CancelState.NONE;
            };
            RetryGate.Verdict v = RetryGate.decide(new RetryGate.Check(i.get("attempt").asLong(), i.get("maxAttempts").asLong(), cs,
                    i.get("terminal").asBoolean(), i.get("replaySafe").asBoolean(), i.get("remainingMs").asLong(), i.get("backoffMs").asLong(),
                    i.get("attemptCostMs").asLong(), i.get("rb").asLong()));
            JsonNode e = c.get("expect");
            assertEquals(new RetryGate.Verdict(e.get("retry").asBoolean(), e.get("reason").asText(), e.get("rule").asText(),
                    e.get("nextAttempt").asLong(), e.get("budgetLeft").asLong()), v);
        }));
    }

    @TestFactory
    Stream<DynamicTest> trail() throws IOException {
        return cases("trail.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> {
            String name = c.get("name").asText();
            if (c.has("error")) {
                assertFailure(c.get("error"), assertThrows(ProtocolException.class, () -> Trail.decode(c.get("input").asText())), name);
                return;
            }
            Trail t = Trail.decode(c.get("input").asText());
            JsonNode e = c.get("expect");
            List<Trail.Entry> entries = new ArrayList<>();
            e.get("entries").forEach(x -> entries.add(new Trail.Entry(x.get("svc").asLong(), x.get("parent").asInt(),
                    x.get("elapsedMicros").asLong(), x.get("decision").asText().charAt(0), x.get("consumedMs").asLong())));
            assertEquals(new Trail(e.get("rv").asLong(), e.get("inc").asBoolean(), entries), t, name);
            assertEquals(c.get("canonical").asText(), t.encode(), name + ": canonical encoding");
        }));
    }

    @TestFactory
    Stream<DynamicTest> trailMerge() throws IOException {
        return cases("trail-merge.json").map(c -> DynamicTest.dynamicTest(c.get("name").asText(), () -> assertEquals(c.get("expect").asText(),
                Trail.decode(c.get("base").asText()).append(Trail.decode(c.get("sub").asText()), c.get("parent").asInt()).encode())));
    }
}
