package org.rrpe.intelligence.analysis.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RecommendationPlan(
        String schemaVersion,
        String mode,
        String serviceName,
        HealthStatus status,
        Instant sourceCollectedAt,
        Instant generatedAt,
        Instant expiresAt,
        List<String> evidence,
        List<Recommendation> recommendations
) {
    public RecommendationPlan {
        evidence = List.copyOf(evidence);
        recommendations = List.copyOf(recommendations);
    }

    public enum Action {
        RATE_LIMIT,
        DISABLE_RETRIES,
        /**
         * Uses the parameter {@code retryBudgetRatio}: additional retry attempts
         * allowed per original, non-retry request in the consumer's accounting
         * window. A ratio of 0.05 allows at most floor(0.05 * original requests)
         * extra attempts across the named service's aggregate scope. It is not
         * a per-request maxAttempts value. The consumer must support and enforce
         * this budget before accepting the action.
         */
        RETRY_BUDGET
    }

    public record Recommendation(Action action, Map<String, Number> parameters, String reason) {
        public Recommendation {
            parameters = Map.copyOf(parameters);
        }
    }
}
