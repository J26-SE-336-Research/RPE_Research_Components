package dev.rpe.protocolservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rpe.protocol.Hop;
import dev.rpe.protocol.ProtocolException;
import dev.rpe.protocol.RpeContext;
import dev.rpe.protocol.RpeResult;
import dev.rpe.protocol.Trail;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The Java part of the C1 protocol service. It runs next to the Go service and answers with the same JSON, so the
 * admin panel can show header checks and conformance results from both libraries side by side.
 *
 * <pre>
 * POST /validate     {"kind": "context|result|hop|trail", "value": "..."}  -> verdict
 * GET  /conformance  every case file, run with rpe-java                       -> [{file, description, cases}]
 * GET  /healthz
 * </pre>
 */
public final class Main {
    static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(env("JAVA_PORT", "8096"));
        Path vectors = Path.of(env("VECTORS_DIR", "../../conformance-kit/vectors"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/healthz", ex -> reply(ex, 200, JSON.createObjectNode().put("status", "ok")));
        server.createContext("/validate", ex -> {
            if (!"POST".equals(ex.getRequestMethod())) {
                reply(ex, 405, JSON.createObjectNode().put("error", "POST required"));
                return;
            }
            JsonNode in;
            try (InputStream body = ex.getRequestBody()) {
                in = JSON.readTree(body.readNBytes(1 << 16));
            } catch (IOException e) {
                reply(ex, 400, JSON.createObjectNode().put("error", e.getMessage()));
                return;
            }
            reply(ex, 200, validate(in.path("kind").asText("context"), in.path("value").asText("").strip()));
        });
        server.createContext("/conformance", ex -> reply(ex, 200, JSON.valueToTree(ConformanceRunner.runAll(vectors))));
        server.start();
        System.out.println("rpe-protocol-java listening on :" + port);
    }

    /** Decodes a value with rpe-java, formatting the fields the same way the Go service does. */
    static ObjectNode validate(String kind, String value) {
        ObjectNode out = JSON.createObjectNode().put("impl", "java");
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            String canonical;
            switch (kind) {
                case "context" -> {
                    RpeContext c = RpeContext.decode(value);
                    fields.put("v", Long.toString(c.version()));
                    fields.put("rid", c.rid());
                    fields.put("iid", c.iid());
                    fields.put("pid", c.pid());
                    fields.put("ttl", Long.toString(c.ttlMillis()));
                    fields.put("bud", Long.toString(c.budgetMillis()));
                    fields.put("rb", Long.toString(c.retryBudget()));
                    fields.put("cs", c.cancel().wire());
                    fields.put("pe", c.policyEpoch());
                    fields.put("rt", c.route());
                    c.unknown().forEach((k, raw) -> fields.put("+" + k, raw));
                    canonical = c.encode();
                }
                case "result" -> {
                    RpeResult r = RpeResult.decode(value);
                    fields.put("v", Long.toString(r.version()));
                    fields.put("out", r.outcome());
                    fields.put("rsn", r.reason());
                    fields.put("term", Boolean.toString(r.terminal()));
                    fields.put("bp", Long.toString(r.bpLevel()));
                    fields.put("bpu", Long.toString(r.bpUtil()));
                    fields.put("rbu", Long.toString(r.budgetUsed()));
                    fields.put("pv", r.policyEpoch());
                    fields.put("late", Boolean.toString(r.late()));
                    fields.put("kids", r.children());
                    canonical = r.encode();
                }
                case "hop" -> {
                    Hop h = Hop.decode(value);
                    fields.put("cb", h.callerToken());
                    fields.put("att", Long.toString(h.attempt()));
                    canonical = h.encode();
                }
                case "trail" -> {
                    Trail t = Trail.decode(value);
                    fields.put("rv", Long.toString(t.registryVersion()));
                    fields.put("inc", Boolean.toString(t.incomplete()));
                    fields.put("entries", Integer.toString(t.entries().size()));
                    for (int i = 0; i < t.entries().size(); i++) {
                        Trail.Entry e = t.entries().get(i);
                        fields.put(String.format("#%02d", i + 1), String.format("svc %d, parent %d, %d µs, %c, consumed %d ms",
                                e.service(), e.parent(), e.elapsedMicros(), e.decision(), e.consumedMs()));
                    }
                    canonical = t.encode();
                }
                default -> throw new IllegalArgumentException("kind must be context, result, hop or trail");
            }
            out.put("valid", true);
            out.set("fields", JSON.valueToTree(fields));
            out.put("canonical", canonical);
        } catch (ProtocolException e) {
            out.put("valid", false);
            ObjectNode err = out.putObject("error").put("code", e.code().name());
            if (e.rule() != null) err.put("rule", e.rule());
            err.put("detail", e.detail());
        } catch (IllegalArgumentException e) {
            out.put("valid", false);
            out.putObject("error").put("code", "ERROR").put("detail", e.getMessage());
        }
        return out;
    }

    static void reply(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? def : v;
    }
}
