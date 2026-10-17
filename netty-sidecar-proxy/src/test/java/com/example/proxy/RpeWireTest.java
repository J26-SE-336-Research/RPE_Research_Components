package com.example.proxy;

import static org.junit.jupiter.api.Assertions.*;

import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.RpeContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RpeWireTest {
    private static final String RID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String CONTEXT = "v=1, rid=\"" + RID + "\", iid=\"00f067aa0ba902b7\", pid=\"0000000000000000\", "
            + "ttl=800, bud=1000, rb=3, cs=none, pe=\"e1\", rt=\"POST /api/orders\", x=7";

    @Test
    void readRequestTranslatesTheContextIntoTheInternalHeaders() {
        HttpHeaders headers = new DefaultHttpHeaders().set(RpeWire.CONTEXT, CONTEXT);
        RpeWire.Identity identity = RpeWire.readRequest(headers, 10_000);
        assertNotNull(identity);
        assertNull(headers.get(RpeWire.CONTEXT));
        assertEquals(RID, headers.get(RpeWire.REQUEST_ID));
        assertEquals("10800", headers.get(RpeWire.DEADLINE));
        assertEquals("false", headers.get(RpeWire.EXCEEDED));
        assertEquals("false", headers.get(RpeWire.CANCELLATION));
        assertEquals("POST", headers.get(RpeWire.CHAIN_METHOD));
        assertEquals("/api/orders", headers.get(RpeWire.ROUTE));
        assertEquals("1000", headers.get(RpeWire.BUDGET));
        assertEquals(10_800, identity.requestedDeadlineMillis());
    }

    @Test
    void cancellationStatesMapToTheFlags() {
        HttpHeaders expired = new DefaultHttpHeaders().set(RpeWire.CONTEXT, CONTEXT.replace("cs=none", "cs=expired"));
        RpeWire.readRequest(expired, 0);
        assertEquals("true", expired.get(RpeWire.EXCEEDED));
        assertEquals("true", expired.get(RpeWire.CANCELLATION));
        HttpHeaders cancel = new DefaultHttpHeaders().set(RpeWire.CONTEXT, CONTEXT.replace("cs=none", "cs=cancel"));
        RpeWire.readRequest(cancel, 0);
        assertEquals("false", cancel.get(RpeWire.EXCEEDED));
        assertEquals("true", cancel.get(RpeWire.CANCELLATION));
    }

    @Test
    void invalidOrDuplicatedContextsAreLeftUntouched() {
        HttpHeaders invalid = new DefaultHttpHeaders().set(RpeWire.CONTEXT, "v=1, rid=nonsense");
        assertNull(RpeWire.readRequest(invalid, 0));
        assertEquals("v=1, rid=nonsense", invalid.get(RpeWire.CONTEXT));
        assertNull(invalid.get(RpeWire.REQUEST_ID));
        HttpHeaders duplicated = new DefaultHttpHeaders().add(RpeWire.CONTEXT, CONTEXT).add(RpeWire.CONTEXT, CONTEXT);
        assertNull(RpeWire.readRequest(duplicated, 0));
        assertNull(duplicated.get(RpeWire.REQUEST_ID));
    }

    @Test
    void writeRequestEncodesTheTimeLeftAndKeepsTheIdentity() {
        HttpHeaders headers = new DefaultHttpHeaders().set(RpeWire.CONTEXT, CONTEXT);
        RpeWire.Identity identity = RpeWire.readRequest(headers, 10_000);
        headers.set(RpeWire.STORED_CONTEXT, RpeWire.store(identity));
        RpeWire.writeRequest(headers, identity, false, 10_300);
        RpeContext out = RpeContext.decode(headers.get(RpeWire.CONTEXT));
        assertEquals(500, out.ttlMillis());
        assertEquals("00f067aa0ba902b7", out.iid());
        assertEquals("e1", out.policyEpoch());
        assertEquals(Map.of("x", "7"), out.unknown(), "unknown members are forwarded (R-V01)");
        for (String internal : new String[]{RpeWire.REQUEST_ID, RpeWire.DEADLINE, RpeWire.EXCEEDED, RpeWire.CANCELLATION,
                RpeWire.ROUTE, RpeWire.BUDGET, RpeWire.CHAIN_METHOD, RpeWire.STORED_CONTEXT}) {
            assertNull(headers.get(internal), internal);
        }
    }

    @Test
    void writeRequestUsesTheEarlierDeadlineAndNeverResetsCancellation() {
        HttpHeaders headers = new DefaultHttpHeaders().set(RpeWire.CONTEXT, CONTEXT.replace("cs=none", "cs=cancel"));
        RpeWire.Identity identity = RpeWire.readRequest(headers, 10_000); // requested deadline 10800
        headers.set(RpeWire.DEADLINE, "20000"); // a later stored deadline must not extend the time left
        headers.set(RpeWire.EXCEEDED, "true");
        RpeWire.writeRequest(headers, identity, true, 10_000);
        RpeContext out = RpeContext.decode(headers.get(RpeWire.CONTEXT));
        assertEquals(800, out.ttlMillis());
        assertEquals(CancelState.CANCEL, out.cancel(), "cs never changes once set (R-T04)");
        assertEquals(RID, headers.get(RpeWire.REQUEST_ID), "Request_id is kept for the local application");
    }

    @Test
    void expiredDeadlineIsEncodedAsExpiredWithNoTimeLeft() {
        RpeWire.Identity root = RpeWire.root(RID, "GET", "/api/orders", 1000, 3, "");
        HttpHeaders headers = new DefaultHttpHeaders().set(RpeWire.REQUEST_ID, RID).set(RpeWire.DEADLINE, "5000")
                .set(RpeWire.EXCEEDED, "true").set(RpeWire.CANCELLATION, "true");
        RpeWire.writeRequest(headers, root, false, 9000);
        RpeContext out = RpeContext.decode(headers.get(RpeWire.CONTEXT));
        assertEquals(0, out.ttlMillis());
        assertEquals(CancelState.EXPIRED, out.cancel());
        assertEquals("0000000000000000", out.pid());
        assertEquals("GET /api/orders", out.route());
    }

    @Test
    void childOfAStoredContextHasAFreshInvocation() {
        RpeWire.Identity parent = RpeWire.restore(RpeContext.decode(CONTEXT).encode());
        RpeWire.Identity child = RpeWire.child(parent);
        assertEquals("00f067aa0ba902b7", child.context().pid());
        assertNotEquals("00f067aa0ba902b7", child.context().iid());
        assertEquals(RID, child.context().rid());
    }

    @Test
    void nonRpeRequestIdsKeepTheInternalHeaders() {
        String uuid = "8e1a3f5e-0c1b-4d5f-9a8b-1c2d3e4f5a6b";
        assertNull(RpeWire.root(uuid, "GET", "/x", 1000, 3, ""));
        HttpHeaders headers = new DefaultHttpHeaders().set(RpeWire.REQUEST_ID, uuid).set(RpeWire.DEADLINE, "1");
        RpeWire.writeRequest(headers, null, false, 0);
        assertNull(headers.get(RpeWire.CONTEXT));
        assertEquals(uuid, headers.get(RpeWire.REQUEST_ID));
        assertEquals("1", headers.get(RpeWire.DEADLINE));
    }

    @Test
    void responseStateUsesTheCsMemberOfTheResult() {
        HttpHeaders response = new DefaultHttpHeaders().set(RpeWire.RESULT, "v=1, out=ok, rsn=none, kids=\"a:ok:1,b\", cs=cancel");
        boolean[] state = RpeWire.readResponseState(response);
        assertFalse(state[0]);
        assertTrue(state[1]);
        RpeWire.writeResponseState(response, true, true);
        assertEquals("v=1, out=ok, rsn=none, kids=\"a:ok:1,b\", cs=expired", response.get(RpeWire.RESULT));

        HttpHeaders none = new DefaultHttpHeaders();
        RpeWire.writeResponseState(none, false, false);
        assertNull(none.get(RpeWire.RESULT), "no result is invented while the chain is not cancelled");
        RpeWire.writeResponseState(none, false, true);
        assertEquals("v=1, cs=cancel", none.get(RpeWire.RESULT));
    }

    @Test
    void rejectionResultNamesTheReason() {
        assertEquals("v=1, out=rejected, rsn=overload, term=?0, bp=0, bpu=100, rbu=0, late=?0, cs=none",
                RpeWire.rejectionResult("overload", 100, false, false));
        assertTrue(RpeWire.rejectionResult("circuit-open", null, true, true).endsWith("cs=expired"));
    }

    @Test
    void traceparentIsBoundToTheRequestId() {
        HttpHeaders headers = new DefaultHttpHeaders().set(RpeWire.TRACEPARENT, "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        RpeWire.bindTraceparent(headers, RID);
        assertTrue(headers.get(RpeWire.TRACEPARENT).startsWith("00-" + RID + "-"));
        String bound = headers.get(RpeWire.TRACEPARENT);
        RpeWire.bindTraceparent(headers, RID);
        assertEquals(bound, headers.get(RpeWire.TRACEPARENT), "a matching traceparent is kept");
    }
}
