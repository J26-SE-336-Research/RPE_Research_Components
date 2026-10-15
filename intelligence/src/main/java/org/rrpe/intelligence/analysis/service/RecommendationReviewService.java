package org.rrpe.intelligence.analysis.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

import org.rrpe.intelligence.analysis.api.RecommendationReviewRequest;
import org.rrpe.intelligence.analysis.api.RecommendationReviewRequest.Decision;
import org.rrpe.intelligence.analysis.model.ChainDeadlineRecommendation;
import org.rrpe.intelligence.analysis.model.HealthStatus;
import org.rrpe.intelligence.analysis.model.RecommendationPlan;
import org.rrpe.intelligence.analysis.model.RecommendationReview;
import org.rrpe.intelligence.analysis.model.RecommendationReview.Status;
import org.rrpe.intelligence.analysis.store.RecommendationReviewStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Records automatic service decisions and human chain-deadline decisions against server-generated plans. Submission
 * methods are for internal generation paths, not client-supplied policy bodies.
 * Expiry is evaluated on lookup and review, including for approved plans.
 * This service does not reassess telemetry, authenticate reviewer labels,
 * distribute policies, or remove policies previously applied elsewhere.
 */
@Service
public class RecommendationReviewService {
    private static final int MAX_ATTEMPTS = 8;
    private final RecommendationReviewStore store;
    private final Clock clock;

    @Autowired
    public RecommendationReviewService(RecommendationReviewStore store) {
        this(store, Clock.systemUTC());
    }

    RecommendationReviewService(RecommendationReviewStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public RecommendationReview submitServicePlan(RecommendationPlan plan) {
        if (plan == null || !"1.0".equals(plan.schemaVersion()) || !"ADVISORY".equals(plan.mode())
                || plan.serviceName() == null || plan.serviceName().isBlank()
                || plan.status() == null
                || plan.recommendations().isEmpty()) {
            throw badRequest("An actionable advisory service plan is required");
        }
        for (var action : plan.recommendations()) {
            if (action.action() == null) throw badRequest("Unsupported automatic service action");
            Number value = action.parameters().get(switch (action.action()) {
                case RATE_LIMIT -> "maxRequestsPerSecond";
                case RETRY_BUDGET -> "retryBudgetRatio";
                case DISABLE_RETRIES -> "maxAttempts";
            });
            if (value == null || !Double.isFinite(value.doubleValue())
                    || (action.action() == RecommendationPlan.Action.RATE_LIMIT && value.doubleValue() <= 0)
                    || (action.action() == RecommendationPlan.Action.RETRY_BUDGET && (value.doubleValue() < 0 || value.doubleValue() > 0.10))
                    || (action.action() == RecommendationPlan.Action.DISABLE_RETRIES && value.doubleValue() != 1)) {
                throw badRequest("Invalid automatic service policy parameters");
            }
        }
        return submit(plan, null, plan.generatedAt(), plan.expiresAt());
    }

    public RecommendationReview submitDeadlinePlan(ChainDeadlineRecommendation plan) {
        if (plan == null || !"1.0".equals(plan.schemaVersion()) || !"ADVISORY".equals(plan.mode())
                || plan.chainId() == null || plan.chainId().isBlank() || !"READY".equals(plan.status())
                || !plan.ready() || !plan.requiresApproval() || !"CHANGE_CHAIN_DEADLINE".equals(plan.action())
                || plan.proposedDeadlineMs() == null || plan.proposedDeadlineMs() <= 0) {
            throw badRequest("An actionable advisory chain deadline plan is required");
        }
        return submit(null, plan, plan.generatedAt(), plan.expiresAt());
    }

    private RecommendationReview submit(RecommendationPlan servicePlan, ChainDeadlineRecommendation deadlinePlan,
                                        Instant generatedAt, Instant expiresAt) {
        Instant now = clock.instant();
        if (generatedAt == null || expiresAt == null || generatedAt.isAfter(now)
                || !expiresAt.isAfter(generatedAt) || !expiresAt.isAfter(now)) {
            throw badRequest("A current plan with a valid original expiry is required; generate a new recommendation");
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            var pending = new RecommendationReview(UUID.randomUUID(), servicePlan, deadlinePlan,
                    servicePlan != null ? Status.AUTO_APPROVED : Status.PENDING, now,
                    servicePlan != null ? now : null, servicePlan != null ? "automatic-policy-controller" : null,
                    servicePlan != null ? "Automatic service policy decision; distribution and enforcement are separate." : null);
            if (store.insert(pending)) {
                return find(pending.recommendationId());
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to allocate a review identifier");
    }

    /** Expired plans return an EXPIRED record rather than a waiting-state error. */
    public RecommendationReview find(UUID recommendationId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            var current = load(recommendationId);
            if (!needsExpiry(current, clock.instant())) {
                return current;
            }
            var expired = withState(current, Status.EXPIRED, current.reviewedAt(), current.reviewer(), current.comment());
            if (store.compareAndSet(current, expired)) {
                return expired;
            }
        }
        throw concurrentChange();
    }

    public List<RecommendationReview> findLatest(int limit) {
        if (limit < 1 || limit > 100) {
            throw badRequest("Review limit must be between 1 and 100");
        }
        return store.findLatest(limit).stream().map(review -> find(review.recommendationId())).toList();
    }

    public RecommendationReview review(RecommendationReviewRequest request) {
        if (request == null || request.recommendationId() == null || request.decision() == null
                || request.reviewer() == null || request.reviewer().isBlank()) {
            throw badRequest("Recommendation ID, decision, and reviewer are required");
        }
        String reviewer = request.reviewer().trim();
        Status desired = request.decision() == Decision.APPROVE ? Status.APPROVED : Status.REJECTED;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            var current = find(request.recommendationId());
            if (current.servicePlan() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Service policies are automatic; only chain deadlines require a human decision");
            }
            if (current.status() == Status.EXPIRED) {
                return current;
            }
            if (current.status() != Status.PENDING) {
                // An identical retry preserves the first decision's audit time.
                if (current.status() == desired && reviewer.equals(current.reviewer())
                        && Objects.equals(request.comment(), current.comment())) {
                    return current;
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This recommendation already has a review decision; reload its current state");
            }
            Instant now = clock.instant();
            if (needsExpiry(current, now)) {
                var expired = withState(current, Status.EXPIRED, null, null, null);
                if (store.compareAndSet(current, expired)) {
                    return expired;
                }
                continue;
            }
            var decided = withState(current, desired, now, reviewer, request.comment());
            if (store.compareAndSet(current, decided)) {
                // Recheck expiry before exposing an approval to a future consumer.
                return find(current.recommendationId());
            }
        }
        throw concurrentChange();
    }

    private RecommendationReview load(UUID recommendationId) {
        if (recommendationId == null) {
            throw badRequest("Recommendation ID is required");
        }
        return store.findById(recommendationId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Recommendation review not found"));
    }

    private static boolean needsExpiry(RecommendationReview review, Instant now) {
        return (review.status() == Status.PENDING || review.status() == Status.APPROVED || review.status() == Status.AUTO_APPROVED)
                && !review.expiresAt().isAfter(now);
    }

    private static RecommendationReview withState(RecommendationReview original, Status status,
                                                 Instant reviewedAt, String reviewer, String comment) {
        return new RecommendationReview(original.recommendationId(), original.servicePlan(), original.deadlinePlan(),
                status, original.submittedAt(), reviewedAt, reviewer, comment);
    }

    private static ResponseStatusException concurrentChange() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Review changed concurrently; reload its current state");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
