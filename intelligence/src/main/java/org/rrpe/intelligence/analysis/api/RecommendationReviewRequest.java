package org.rrpe.intelligence.analysis.api;

import java.util.UUID;

/**
 * A human decision about an existing, server-stored recommendation.
 * The review service must validate required fields, look up the original plan,
 * and check its expiry and review state before accepting this decision.
 * This request cannot replace the original actions, parameters, or expiry.
 *
 * @param recommendationId server-issued identifier for the exact plan being reviewed
 * @param decision approve or reject the complete plan
 * @param reviewer reviewer label for the PoC audit record; caller-supplied and
 *        not an authenticated identity
 * @param comment optional explanation of the decision
 */
public record RecommendationReviewRequest(
        UUID recommendationId,
        Decision decision,
        String reviewer,
        String comment
) {
    public enum Decision {
        APPROVE, REJECT
    }
}
