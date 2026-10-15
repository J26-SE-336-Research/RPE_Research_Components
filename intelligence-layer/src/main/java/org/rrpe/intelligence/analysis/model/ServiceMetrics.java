package org.rrpe.intelligence.analysis.model;

import java.time.Instant;

public record ServiceMetrics(
        String serviceName,
        Instant collectedAt,
        double requestRate,
        double p95LatencyMs,
        double errorRate,
        long retryCount,
        long rejectedRequestCount,
        long deadlineFailureCount,
        long cancellationCount,
        long inFlightRequests,
        double successfulThroughput
) {
}
