package dev.rpe.protocol.http;

import dev.rpe.protocol.Hop;
import dev.rpe.protocol.RpeContext;

/**
 * The validated context of the request being served, with its local receive time. Later deadline checks use the
 * local monotonic clock only (R-D01).
 */
public record Inbound(RpeContext context, Hop hop, long receivedNanos) {
    /** Milliseconds left for this request at {@code nowNanos} (System.nanoTime()). */
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
