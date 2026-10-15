package org.rrpe.intelligence.analysis.model;

import java.time.Instant;
import java.util.List;

/** A separate chain-scoped suggestion; it does not infer chain p99 from service p95. */
public record ChainDeadlineRecommendation(
        String schemaVersion, String mode, String chainId, String status,
        boolean ready, boolean requiresApproval, String action, Long proposedDeadlineMs,
        String message, Request evidence, double safetyMargin,
        Instant generatedAt, Instant expiresAt
) {
    /**
     * Caller-supplied end-to-end chain telemetry for a recent analysis window.
     * services are in path order; p99LatencyMs is the chain percentile, not a
     * sum of per-service percentiles. chainHealth is caller-supplied until the
     * trace and metric integration computes it. currentDeadlineMs is the
     * current chain policy, not a remaining per-request deadline budget.
     */
    public record Request(String chainId, List<String> services, HealthStatus chainHealth,
                          Instant windowStart, Instant windowEnd, long requestCount,
                          long deadlineFailureCount, double p99LatencyMs, long currentDeadlineMs) {
        public Request {
            if (services != null) {
                services = List.copyOf(services);
            }
        }
    }
}
