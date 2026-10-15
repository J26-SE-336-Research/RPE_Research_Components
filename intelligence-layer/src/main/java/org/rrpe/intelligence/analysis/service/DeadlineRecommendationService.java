package org.rrpe.intelligence.analysis.service;

import java.time.Duration;
import java.time.Instant;

import org.rrpe.intelligence.analysis.config.BaselineLearningConfig;
import org.rrpe.intelligence.analysis.config.RecommendationPolicyConfig;
import org.rrpe.intelligence.analysis.model.ChainDeadlineRecommendation;
import org.rrpe.intelligence.analysis.model.ChainDeadlineRecommendation.Request;
import org.rrpe.intelligence.analysis.model.HealthStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Generates bounded advisory increases; recovery-based reductions are separate. */
@Service
public class DeadlineRecommendationService {
    private final RecommendationPolicyConfig.Settings policy;
    private final BaselineLearningConfig.Settings windows;

    public DeadlineRecommendationService(RecommendationPolicyConfig.Settings policy,
                                         BaselineLearningConfig.Settings windows) {
        this.policy = policy;
        this.windows = windows;
    }

    public ChainDeadlineRecommendation recommend(Request request) {
        Instant now = Instant.now();
        validate(request, now);
        Duration span = Duration.between(request.windowStart(), request.windowEnd());
        if (request.requestCount() < policy.minimumChainRequests()
                || span.compareTo(windows.recentWindow()) < 0
                || span.compareTo(windows.recentWindow().plus(windows.sampleInterval())) > 0) {
            return response(request, "INSUFFICIENT_DATA", null, "Waiting for enough requests in a complete recent window.", now);
        }
        if (Duration.between(request.windowEnd(), now).compareTo(windows.analysisInterval().multipliedBy(2)) > 0) {
            return response(request, "WAITING_FOR_FRESH_DATA", null, "Waiting for fresh chain telemetry.", now);
        }
        if (request.chainHealth() == HealthStatus.OVERLOADED) {
            return response(request, "BLOCKED_BY_OVERLOAD", null,
                    "Address overload and retry traffic before considering a longer deadline.", now);
        }
        double proposed = Math.ceil(request.p99LatencyMs() * policy.deadlineSafetyMargin());
        if (request.deadlineFailureCount() == 0 || proposed <= request.currentDeadlineMs()) {
            return response(request, "NO_CHANGE", null, "Current evidence does not support increasing the chain deadline.", now);
        }
        if (!Double.isFinite(proposed) || proposed > policy.maximumChainDeadlineMs()
                || proposed / request.currentDeadlineMs() > policy.maximumDeadlineIncreaseFactor()) {
            return response(request, "REVIEW_REQUIRED", null,
                    "The calculated deadline exceeds policy safety limits; investigate the chain instead of applying an automatic increase.", now);
        }
        return response(request, "READY", (long) proposed,
                "Deadline failures were observed. Proposed deadline is chain p99 latency multiplied by the configured safety margin.", now);
    }

    private ChainDeadlineRecommendation response(Request request, String status, Long proposed, String message, Instant now) {
        boolean suggested = proposed != null;
        return new ChainDeadlineRecommendation("1.0", "ADVISORY", request.chainId(), status,
                suggested || status.equals("NO_CHANGE"), suggested,
                suggested ? "CHANGE_CHAIN_DEADLINE" : null, proposed, message, request,
                policy.deadlineSafetyMargin(), suggested ? now : null,
                suggested ? now.plus(policy.recommendationTtl()) : null);
    }

    private static void validate(Request request, Instant now) {
        if (request == null || request.chainId() == null || request.chainId().isBlank()
                || request.services() == null || request.services().isEmpty()
                || request.services().stream().anyMatch(name -> name == null || name.isBlank())
                || request.chainHealth() == null || request.windowStart() == null || request.windowEnd() == null) {
            throw badRequest("Chain identity, services, health, and both window timestamps are required");
        }
        if (!request.windowEnd().isAfter(request.windowStart()) || request.windowEnd().isAfter(now)) {
            throw badRequest("Window must have positive duration and must not end in the future");
        }
        if (request.requestCount() < 0 || request.deadlineFailureCount() < 0
                || request.deadlineFailureCount() > request.requestCount()
                || !Double.isFinite(request.p99LatencyMs()) || request.p99LatencyMs() < 0
                || (request.requestCount() > 0 && request.p99LatencyMs() == 0) || request.currentDeadlineMs() <= 0) {
            throw badRequest("Chain measurements and current deadline must have valid numeric ranges");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
