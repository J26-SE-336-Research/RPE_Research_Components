package org.rrpe.intelligence.analysis.store;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.rrpe.intelligence.analysis.model.RecommendationReview;

/**
 * Storage contract for immutable recommendation review records.
 * Implementations may use memory, Redis, or another backend. All operations
 * must be atomic within the backend's scope; review decisions and expiry checks
 * are the review service's responsibility, not this interface's.
 *
 * Expiry is recorded in the original plan. It must not automatically delete
 * the review record: expired plans can still be needed for review history.
 */
public interface RecommendationReviewStore {

    /**
     * Inserts a new record only when its recommendation ID is not already stored.
     * An existing record must never be overwritten, even for an identical replay.
     *
     * @return true when inserted; false when the ID already exists
     * @throws IllegalArgumentException when the record is null
     */
    boolean insert(RecommendationReview review);

    /**
     * Returns an immutable snapshot without changing its status or expiry.
     *
     * @return the stored record, or empty when its ID is unknown
     * @throws IllegalArgumentException when recommendationId is null
     */
    Optional<RecommendationReview> findById(UUID recommendationId);

    /** Returns the latest records, newest submission first, with a limit from 1 to 100. */
    List<RecommendationReview> findLatest(int limit);

    /**
     * Atomically replaces a record only if the complete stored value equals
     * expected. A caller that receives false must reload the latest record
     * before deciding whether to retry. This prevents concurrent approvals,
     * rejections, and expiry updates from silently overwriting each other.
     *
     * Replacement must preserve the ID, original service/deadline plan, and
     * submission time. Only review status and review metadata may change.
     * Business rules for permitted status transitions are enforced separately.
     *
     * @return true when replaced; false when missing or changed concurrently
     * @throws IllegalArgumentException when either record is null or the
     *         replacement changes the original identity, plan, or submission time
     */
    boolean compareAndSet(RecommendationReview expected, RecommendationReview replacement);
}
