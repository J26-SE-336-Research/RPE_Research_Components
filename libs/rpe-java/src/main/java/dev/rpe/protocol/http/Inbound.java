package dev.rpe.protocol.http;

import dev.rpe.protocol.Hop;
import dev.rpe.protocol.RpeContext;

/**
 * The checked context of the request being served, plus the time we got it. Later deadline checks only use the
 * local clock.
 */
public record Inbound(RpeContext context, Hop hop, long receivedNanos) {
    /** Milliseconds left for this request at {@code nowNanos}, a System.nanoTime() value. */
    public long remainingMillis(long nowNanos) {
        return context.ttlMillis() - (nowNanos - receivedNanos) / 1_000_000;
    }

    public long remainingMillis() {
        return remainingMillis(System.nanoTime());
    }

    public long elapsedMillis(long nowNanos) {
        return (nowNanos - receivedNanos) / 1_000_000;
    }
}
