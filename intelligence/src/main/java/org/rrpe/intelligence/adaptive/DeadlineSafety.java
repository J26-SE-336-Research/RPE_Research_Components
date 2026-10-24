package org.rrpe.intelligence.adaptive;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * When to pause a chain, all within one hour: two reversals (applied changes in opposite directions), two
 * failed verifications, or requests cut by a rejecting middleware in two counted windows. Each method returns the pause
 * reason, or null.
 */
final class DeadlineSafety {
    private static final Duration HOUR = Duration.ofHours(1);
    private final List<Instant> reversals = new ArrayList<>(), failures = new ArrayList<>(), middleware = new ArrayList<>();
    private int lastDirection; private Instant lastAppliedAt;

    String applied(long fromMs, long toMs, Instant now) {
        int direction = Long.compare(toMs, fromMs);
        prune(reversals, now);
        if (lastDirection != 0 && direction != 0 && direction != lastDirection && lastAppliedAt != null
                && Duration.between(lastAppliedAt, now).compareTo(HOUR) < 0) reversals.add(now);
        lastDirection = direction; lastAppliedAt = now;
        return reversals.size() >= 2 ? "TWO_REVERSALS: the deadline changed direction twice within an hour" : null;
    }
    String verificationFailed(Instant now) {
        failures.add(now); prune(failures, now);
        return failures.size() >= 2 ? "TWO_VERIFICATION_FAILURES: two applied changes failed verification within an hour" : null;
    }
    String rejectingMiddleware(Instant now) {
        middleware.add(now); prune(middleware, now);
        return middleware.size() >= 2 ? "REJECTING_MIDDLEWARE: requests are cut with 504 near the deadline in " + middleware.size()
                + " counted windows within an hour (older rejecting rpe-go Middleware?)" : null;
    }
    private static void prune(List<Instant> list, Instant now) { list.removeIf(t -> Duration.between(t, now).compareTo(HOUR) >= 0); }
}
