package org.rrpe.intelligence.analysis.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import org.rrpe.intelligence.analysis.api.ClassificationRequest;
import org.rrpe.intelligence.analysis.config.RecommendationPolicyConfig.Settings;
import org.rrpe.intelligence.analysis.model.HealthStatus;
import org.rrpe.intelligence.analysis.model.RecommendationPlan;
import org.rrpe.intelligence.analysis.model.RecommendationPlan.Action;
import org.rrpe.intelligence.analysis.model.RecommendationPlan.Recommendation;
import org.rrpe.intelligence.analysis.model.ServiceMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationService {
    private final ServiceHealthClassifier classifier;
    private final Settings settings;

    public RecommendationService(ServiceHealthClassifier classifier, Settings settings) {
        this.classifier = classifier;
        this.settings = settings;
    }

    public RecommendationPlan recommend(ClassificationRequest request) {
        if (request == null) {
            throw badRequest("recent and baseline are required");
        }
        validate(request.recent());
        validate(request.baseline());
        var recent = request.recent();
        var baseline = request.baseline();
        if (!recent.serviceName().equals(baseline.serviceName())) {
            throw badRequest("recent and baseline must refer to the same service");
        }
        if (!recent.collectedAt().isAfter(baseline.collectedAt())) {
            throw badRequest("recent.collectedAt must be after baseline.collectedAt");
        }

        var assessment = classifier.classify(recent, baseline);
        var recommendations = new ArrayList<Recommendation>();
        if (assessment.status() != HealthStatus.HEALTHY) {
            double factor = assessment.status() == HealthStatus.OVERLOADED
                    ? settings.overloadedRateMultiplier() : settings.stressedRateMultiplier();
            if (baseline.requestRate() > 0 && recent.requestRate() > 0) {
                recommendations.add(new Recommendation(Action.RATE_LIMIT,
                        Map.of("maxRequestsPerSecond", Math.min(recent.requestRate(), baseline.requestRate() * factor)),
                        "Reduce admitted load while latency or errors are elevated"));
            }
            if (recent.retryCount() > baseline.retryCount()) {
                double retryBudget = assessment.status() == HealthStatus.OVERLOADED
                        ? settings.overloadedRetryBudgetRatio() : settings.stressedRetryBudgetRatio();
                if (retryBudget == 0) {
                    recommendations.add(new Recommendation(Action.DISABLE_RETRIES,
                            Map.of("maxAttempts", 1),
                            "Retries have increased during degradation; avoid adding retry traffic"));
                } else {
                    // Snapshots do not contain original request counts or an
                    // active retry policy. Suggest the configured ratio without
                    // claiming a measured budget breach or a policy reduction.
                    recommendations.add(new Recommendation(Action.RETRY_BUDGET,
                            Map.of("retryBudgetRatio", retryBudget),
                            "Retries have increased during degradation; cap extra attempts with a service retry budget"));
                }
            }
        }
        Instant now = Instant.now();
        return new RecommendationPlan("1.0", "ADVISORY", recent.serviceName(), assessment.status(),
                recent.collectedAt(), now, now.plus(settings.recommendationTtl()), assessment.evidence(), recommendations);
    }

    private static void validate(ServiceMetrics metrics) {
        if (metrics == null || metrics.serviceName() == null || metrics.serviceName().isBlank()
                || metrics.collectedAt() == null) {
            throw badRequest("Each snapshot requires serviceName and collectedAt");
        }
        for (double value : new double[]{metrics.requestRate(), metrics.p95LatencyMs(),
                metrics.errorRate(), metrics.successfulThroughput()}) {
            if (!Double.isFinite(value) || value < 0) {
                throw badRequest("Metric values must be finite and nonnegative");
            }
        }
        if (metrics.errorRate() > 1 || metrics.retryCount() < 0 || metrics.rejectedRequestCount() < 0
                || metrics.deadlineFailureCount() < 0 || metrics.cancellationCount() < 0
                || metrics.inFlightRequests() < 0) {
            throw badRequest("errorRate must be between 0 and 1 and counters must be nonnegative");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
