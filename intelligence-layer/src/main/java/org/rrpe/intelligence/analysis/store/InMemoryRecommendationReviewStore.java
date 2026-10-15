package org.rrpe.intelligence.analysis.store;

import java.util.Objects;
import java.util.List;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.rrpe.intelligence.analysis.model.RecommendationReview;
import org.springframework.stereotype.Repository;

/**
 * Local PoC review storage. Records are lost on restart and are not shared
 * across application replicas. Expired records remain available for history;
 * retention management and a shared backend are separate implementation steps.
 */
@Repository
public class InMemoryRecommendationReviewStore implements RecommendationReviewStore {
    private final ConcurrentMap<UUID, RecommendationReview> reviews = new ConcurrentHashMap<>();

    @Override
    public boolean insert(RecommendationReview review) {
        requireReview(review);
        return reviews.putIfAbsent(review.recommendationId(), review) == null;
    }

    @Override
    public Optional<RecommendationReview> findById(UUID recommendationId) {
        if (recommendationId == null) {
            throw new IllegalArgumentException("recommendationId is required");
        }
        return Optional.ofNullable(reviews.get(recommendationId));
    }

    @Override
    public List<RecommendationReview> findLatest(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Review limit must be between 1 and 100");
        }
        return reviews.values().stream()
                .sorted(Comparator.comparing(RecommendationReview::submittedAt).reversed()
                        .thenComparing(RecommendationReview::recommendationId))
                .limit(limit).toList();
    }

    @Override
    public boolean compareAndSet(RecommendationReview expected, RecommendationReview replacement) {
        requireReview(expected);
        requireReview(replacement);
        if (!expected.recommendationId().equals(replacement.recommendationId())
                || !Objects.equals(expected.servicePlan(), replacement.servicePlan())
                || !Objects.equals(expected.deadlinePlan(), replacement.deadlinePlan())
                || !expected.submittedAt().equals(replacement.submittedAt())) {
            throw new IllegalArgumentException("An update must preserve the original identity, plan, and submission time");
        }
        return reviews.replace(expected.recommendationId(), expected, replacement);
    }

    private static void requireReview(RecommendationReview review) {
        if (review == null) {
            throw new IllegalArgumentException("review is required");
        }
    }
}
