package dev.rpe.protocol.http;

import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.Hop;
import dev.rpe.protocol.Ids;
import dev.rpe.protocol.RpeContext;
import dev.rpe.protocol.RpeHeaders;
import dev.rpe.protocol.RpeResult;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Context propagation for {@link java.net.http.HttpClient}: derives the child context (fresh iid, pid, ttl reduced by
 * elapsed time and reserve) for every call. Signal mode: a first attempt is always sent, also when the chain is late
 * (its context then carries {@code cs=expired}), and a slow call is never cut off by the library. Only a retry
 * (attempt 2 or later) of a cancelled or expired chain is not sent (R-R02); use {@link dev.rpe.protocol.RetryGate}
 * to decide whether a retry is worth trying at all.
 */
public final class RpeHttpClient {
    /** A retry of a cancelled or expired chain was not sent (R-R02). Carries the terminal result. */
    public static final class DeadlineExceededException extends IOException {
        private final RpeResult result;

        DeadlineExceededException(String message, RpeResult result) {
            super(message);
            this.result = result;
        }

        public RpeResult result() { return result; }
    }

    private final HttpClient http;
    private final long reserveMillis;

    /**
     * @param reserveMillis parent post-processing allowance subtracted from the child ttl (R-D07)
     */
    public RpeHttpClient(HttpClient http, long reserveMillis) {
        this.http = http;
        this.reserveMillis = reserveMillis;
    }

    /** Sends {@code request} as the first attempt of a child call of {@code inbound} with the given retry-budget grant. */
    public <T> HttpResponse<T> send(HttpRequest.Builder request, Inbound inbound, long grant, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return send(request, inbound, grant, 1, handler);
    }

    /**
     * Sends attempt {@code attempt} of a child call ({@code X-RPE-Hop: att=attempt} from the second attempt on). A
     * retry of a cancelled or expired chain is refused with {@link DeadlineExceededException} without being sent.
     */
    public <T> HttpResponse<T> send(HttpRequest.Builder request, Inbound inbound, long grant, long attempt,
            HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        RpeContext in = inbound.context();
        long now = System.nanoTime();
        if (attempt >= 2 && (in.cancel() != CancelState.NONE || inbound.remainingMillis(now) <= 0)) {
            boolean cancelled = in.cancel() == CancelState.CANCEL;
            throw new DeadlineExceededException("retry refused (R-R02): the request chain is " + (cancelled ? "cancel" : "expired"),
                    RpeResult.of(RpeResult.REJECTED, cancelled ? RpeResult.CANCELLED : RpeResult.DEADLINE, true, in.policyEpoch()));
        }
        RpeContext child = in.child(inbound.elapsedMillis(now), reserveMillis, grant);
        if (child.cancel() == CancelState.NONE && child.ttlMillis() <= 0) {
            child = child.withCancel(CancelState.EXPIRED); // without a sidecar the library signals expiry (R-D04)
        }
        request.setHeader(RpeHeaders.CONTEXT, child.encode()).setHeader(RpeHeaders.TRACEPARENT, Ids.newTraceparent(child.rid()));
        if (attempt >= 2) {
            request.setHeader(RpeHeaders.HOP, new Hop("", attempt).encode());
        }
        return http.send(request.build(), handler);
    }
}
