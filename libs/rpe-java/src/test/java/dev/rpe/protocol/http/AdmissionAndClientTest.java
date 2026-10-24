package dev.rpe.protocol.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.ContextGuard;
import dev.rpe.protocol.RpeContext;
import dev.rpe.protocol.RpeHeaders;
import dev.rpe.protocol.RpeResult;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdmissionAndClientTest {
    private static final String CTX = "v=1, rid=\"4bf92f3577b34da6a3ce929d0e0e4736\", iid=\"00f067aa0ba902b7\", pid=\"0000000000000000\", "
            + "ttl=287, bud=300, rb=3, cs=none, pe=\"e42\", rt=\"POST /api/orders\"";

    @Test
    void gatewayIgnoresClientContextAndCreatesRoot() { // R-S01
        var cfg = Admission.Config.gateway((m, p) -> new Admission.RouteBudget.Budget(m + " " + p, 1000), 3, () -> "e7");
        var d = Admission.admit(cfg, "GET", "/api/orders",
                Map.of(RpeHeaders.CONTEXT, List.of(CTX.replace("ttl=287, bud=300", "ttl=99999, bud=99999")),
                        "traceparent", List.of("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")));
        assertTrue(d.admitted());
        RpeContext c = d.inbound().context();
        assertEquals(1000, c.budgetMillis());
        assertEquals("GET /api/orders", c.route());
        assertEquals("e7", c.policyEpoch());
        assertEquals("0af7651916cd43dd8448eb211c80319c", c.rid(), "rid is bound to the incoming trace");
        assertEquals(RpeHeaders.ROOT_PARENT_ID, c.pid());
    }

    @Test
    void expiredOriginalsAreSignalledAndAdmitted() { // R-D02 (signal mode), R-T04
        var svc = Admission.Config.service();
        var expired = Admission.admit(svc, "GET", "/x", Map.of("x-rpe-context", List.of(CTX.replace("ttl=287", "ttl=0"))));
        assertTrue(expired.admitted(), "an expired original request is never rejected");
        assertEquals(CancelState.EXPIRED, expired.inbound().context().cancel());
        assertTrue(expired.contextHeader().contains("cs=expired"), "the application sees the signal");
        var cancelled = Admission.admit(svc, "GET", "/x", Map.of(RpeHeaders.CONTEXT, List.of(CTX.replace("cs=none", "cs=cancel"))));
        assertTrue(cancelled.admitted());
        assertEquals(CancelState.CANCEL, cancelled.inbound().context().cancel());
        var invalid = Admission.admit(svc, "GET", "/x", Map.of(RpeHeaders.CONTEXT, List.of(CTX + ",")));
        assertEquals(400, invalid.status());
        var duplicate = Admission.admit(svc, "GET", "/x", Map.of(RpeHeaders.CONTEXT, List.of(CTX, CTX)));
        assertEquals(400, duplicate.status());
        assertTrue(Admission.admit(svc, "GET", "/x", Map.of()).admitted(), "requests without a context pass through when not required");
    }

    @Test
    void expiredRetriesAreRefused() { // R-R02
        var svc = Admission.Config.service();
        for (String ctx : List.of(CTX.replace("ttl=287", "ttl=0"), CTX.replace("cs=none", "cs=expired"), CTX.replace("cs=none", "cs=cancel"))) {
            var d = Admission.admit(svc, "GET", "/x", Map.of(RpeHeaders.CONTEXT, List.of(ctx), RpeHeaders.HOP, List.of("att=2")));
            assertFalse(d.admitted(), ctx);
            assertEquals(504, d.status());
            assertTrue(d.result().terminal());
            assertTrue(d.resultHeader().contains(", cs="), "the refusal carries the chain state");
        }
        var retry = Admission.admit(svc, "GET", "/x", Map.of(RpeHeaders.CONTEXT, List.of(CTX), RpeHeaders.HOP, List.of("att=2")));
        assertTrue(retry.admitted(), "a retry with time left is admitted");
    }

    @Test
    void clientPropagatesAValidChildAndNeverCutsACallOff() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> seenHop = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            seen.set(ex.getRequestHeaders().getFirst(RpeHeaders.CONTEXT));
            seenHop.set(ex.getRequestHeaders().getFirst(RpeHeaders.HOP));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            var parent = Admission.admit(Admission.Config.service(), "POST", "/api/orders", Map.of(RpeHeaders.CONTEXT, List.of(CTX))).inbound();
            var client = new RpeHttpClient(HttpClient.newHttpClient(), 5);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var resp = client.send(HttpRequest.newBuilder(URI.create(base + "/ok")), parent, 1, HttpResponse.BodyHandlers.discarding());
            assertEquals(204, resp.statusCode());
            RpeContext child = RpeContext.decode(seen.get());
            assertTrue(ContextGuard.validateTransition(parent.context(), child, 0).isEmpty(), "propagated child passes the guard");
            assertEquals(1, child.retryBudget());
            assertTrue(child.ttlMillis() <= 287 - 5);
            assertNull(seenHop.get(), "a first attempt carries no attempt number");

            // a call slower than the remaining deadline completes: the library never cuts it off
            assertEquals(204, client.send(HttpRequest.newBuilder(URI.create(base + "/slow")), parent, 0, HttpResponse.BodyHandlers.discarding()).statusCode());

            var expired = new Inbound(parent.context(), parent.hop(), System.nanoTime() - 1_000_000_000L);
            assertEquals(204, client.send(HttpRequest.newBuilder(URI.create(base + "/ok")), expired, 0, HttpResponse.BodyHandlers.discarding()).statusCode(),
                    "a late first attempt is still sent");
            assertEquals(CancelState.EXPIRED, RpeContext.decode(seen.get()).cancel(), "and carries cs=expired");
            var e = assertThrows(RpeHttpClient.DeadlineExceededException.class,
                    () -> client.send(HttpRequest.newBuilder(URI.create(base + "/ok")), expired, 0, 2, HttpResponse.BodyHandlers.discarding()),
                    "a retry of an expired chain is not sent");
            assertTrue(e.result().terminal());
            var fresh = Admission.admit(Admission.Config.service(), "POST", "/api/orders", Map.of(RpeHeaders.CONTEXT, List.of(CTX))).inbound();
            client.send(HttpRequest.newBuilder(URI.create(base + "/ok")), fresh, 0, 2, HttpResponse.BodyHandlers.discarding());
            assertEquals("att=2", seenHop.get(), "a retry is marked with its attempt number");
        } finally {
            server.stop(0);
        }
    }
}
