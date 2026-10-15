package dev.rpe.protocol.http;

import dev.rpe.protocol.CancelState;
import dev.rpe.protocol.Ids;
import dev.rpe.protocol.RpeContext;
import dev.rpe.protocol.RpeHeaders;
import dev.rpe.protocol.RpeResult;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Context propagation for {@link java.net.http.HttpClient}: derives the child context (fresh iid, pid, ttl reduced by
 * elapsed time and reserve), bounds the call by the remaining deadline, and refuses to send once the deadline has
 * passed or the request is cancelled.
 */
public final class RpeHttpClient {
    /** The call was not sent, or did not finish, within the propagated deadline. Carries the terminal result. */
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

    /** Sends {@code request} as a child of {@code inbound} with the given retry-budget grant (use BudgetSplit for fan-out). */
    public <T> HttpResponse<T> send(HttpRequest.Builder request, Inbound inbound, long grant, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        RpeContext in = inbound.context();
        long now = System.nanoTime();
        long remaining = inbound.remainingMillis(now);
        if (in.cancel() != CancelState.NONE || remaining <= 0) {
            throw new DeadlineExceededException("deadline expired before send",
                    RpeResult.of(RpeResult.REJECTED, RpeResult.DEADLINE, true, in.policyEpoch()));
        }
        RpeContext child = in.child(inbound.elapsedMillis(now), reserveMillis, grant);
        request.setHeader(RpeHeaders.CONTEXT, child.encode())
                .setHeader(RpeHeaders.TRACEPARENT, Ids.newTraceparent(child.rid()))
                .timeout(Duration.ofMillis(Math.max(1, remaining)));
        try {
            return http.send(request.build(), handler);
        } catch (HttpTimeoutException e) {
            // the call outlived the propagated deadline: a terminal deadline result (R-D04)
            throw new DeadlineExceededException("deadline expired in flight",
                    RpeResult.of(RpeResult.REJECTED, RpeResult.DEADLINE, true, in.policyEpoch()));
        }
    }
}
