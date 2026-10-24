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
 * Sends the RPE context with every {@link java.net.http.HttpClient} call. Each call gets a child context with a new
 * iid and pid and a smaller ttl. A first attempt always goes out, even when the chain is late (then it carries
 * {@code cs=expired}), and slow calls are never cut off. Only a retry of a cancelled or expired chain is held back.
 * Use {@link dev.rpe.protocol.RetryGate} to decide if a retry is worth it at all.
 */
public final class RpeHttpClient {
    /** Thrown when a retry of a cancelled or expired chain was not sent. Holds the result to send back. */
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
     * @param reserveMillis time kept back for our own work after the call, taken off the child ttl
     */
    public RpeHttpClient(HttpClient http, long reserveMillis) {
        this.http = http;
        this.reserveMillis = reserveMillis;
    }

    /** Sends {@code request} as the first attempt of a child call, giving it {@code grant} retry budget. */
    public <T> HttpResponse<T> send(HttpRequest.Builder request, Inbound inbound, long grant, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return send(request, inbound, grant, 1, handler);
    }

    /**
     * Sends attempt number {@code attempt} of a child call. From the second attempt on it adds
     * {@code X-RPE-Hop: att=attempt}. A retry of a cancelled or expired chain is not sent and throws
     * {@link DeadlineExceededException}.
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
            child = child.withCancel(CancelState.EXPIRED); // no sidecar here, so we mark it ourselves
        }
        request.setHeader(RpeHeaders.CONTEXT, child.encode()).setHeader(RpeHeaders.TRACEPARENT, Ids.newTraceparent(child.rid()));
        if (attempt >= 2) {
            request.setHeader(RpeHeaders.HOP, new Hop("", attempt).encode());
        }
        return http.send(request.build(), handler);
    }
}
