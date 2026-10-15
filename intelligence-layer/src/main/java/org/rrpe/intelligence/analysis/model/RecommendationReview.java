package org.rrpe.intelligence.analysis.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable review record for one exact, server-generated recommendation.
 * Exactly one of servicePlan and deadlinePlan is populated. Keeping the typed
 * original plan preserves its actions, evidence, and original expiry during
 * review; approval must not restart its validity period.
 *
 * This model does not persist records, enforce expiry, authenticate reviewers,
 * or deliver policies. Those responsibilities belong to later workflow layers.
 * APPROVED means human approval; AUTO_APPROVED means an automatic service-policy decision.
 * Neither status confirms successful policy application.
 * An EXPIRED record does not remove a policy that was already applied.
 */
public record RecommendationReview(
        UUID recommendationId,
        RecommendationPlan servicePlan,
        ChainDeadlineRecommendation deadlinePlan,
        Status status,
        Instant submittedAt,
        Instant reviewedAt,
        String reviewer,
        String comment
) {
    public RecommendationReview {
        if (recommendationId == null || status == null || submittedAt == null) {
            throw new IllegalArgumentException("Review identity, status, and submission time are required");
        }
        if ((servicePlan == null) == (deadlinePlan == null)) {
            throw new IllegalArgumentException("A review must contain exactly one original recommendation plan");
        }
        Instant generated = servicePlan != null ? servicePlan.generatedAt() : deadlinePlan.generatedAt();
        Instant expiry = servicePlan != null ? servicePlan.expiresAt() : deadlinePlan.expiresAt();
        if (generated == null || expiry == null || !expiry.isAfter(generated)) {
            throw new IllegalArgumentException("The original plan must have a positive validity period");
        }
    }

    /** Returns the original plan's expiry without extending it for review. */
    public Instant expiresAt() {
        return servicePlan != null ? servicePlan.expiresAt() : deadlinePlan.expiresAt();
    }

    public enum Status {
        PENDING, APPROVED, AUTO_APPROVED, REJECTED, EXPIRED
    }
}
