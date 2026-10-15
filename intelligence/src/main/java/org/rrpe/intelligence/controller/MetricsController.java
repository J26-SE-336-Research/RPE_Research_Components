package org.rrpe.intelligence.controller;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.rrpe.intelligence.analysis.api.ClassificationRequest;
import org.rrpe.intelligence.analysis.model.RecommendationPlan;
import org.rrpe.intelligence.analysis.model.ServiceMetrics;
import org.rrpe.intelligence.analysis.model.HealthStatus;
import org.rrpe.intelligence.analysis.model.RecommendationReview;
import org.rrpe.intelligence.analysis.service.RecommendationReviewService;
import org.rrpe.intelligence.analysis.service.BaselineLearningService;
import org.rrpe.intelligence.analysis.service.BaselineLearningService.LearningStatus;
import org.rrpe.intelligence.analysis.service.RecommendationService;
import org.rrpe.intelligence.analysis.service.RecentWindowService;
import org.rrpe.intelligence.analysis.service.RecentWindowService.WindowStatus;
import org.rrpe.intelligence.analysis.service.ServiceMetricsStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Accepts snapshots; it does not collect telemetry or apply resilience policies. */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {
    private final ServiceMetricsStore store;
    private final RecommendationService recommendations;
    private final BaselineLearningService baselineLearning;
    private final RecentWindowService recentWindows;
    private final RecommendationReviewService reviews;
    private final ConcurrentMap<String, Object> observationLocks = new ConcurrentHashMap<>();

    public MetricsController(ServiceMetricsStore store, RecommendationService recommendations,
                             BaselineLearningService baselineLearning, RecentWindowService recentWindows,
                             RecommendationReviewService reviews) {
        this.store = store;
        this.recommendations = recommendations;
        this.baselineLearning = baselineLearning;
        this.recentWindows = recentWindows;
        this.reviews = reviews;
    }

    /** Read-only dashboard summary of up to 100 services with submitted observations. */
    @GetMapping({"", "/"})
    public List<RecommendationResponse> listServices() {
        return baselineLearning.findServiceNames().stream().map(this::getRecommendations).toList();
    }

    /** Submit one comparable metric snapshot, without a manually supplied baseline. */
    @PostMapping("/observations")
    public LearningStatus observe(@RequestBody ServiceMetrics observation) {
        if (observation == null) {
            throw new IllegalArgumentException("Observation is required");
        }
        synchronized (observationLock(observation.serviceName())) {
            // Check cadence and validate before updating the learner. These
            // services receive this observation stream through this endpoint.
            recentWindows.observe(observation);
            return baselineLearning.observe(observation);
        }
    }

    /** Reports startup exclusion, learning progress, or the frozen learned baseline. */
    @GetMapping("/{serviceName}/baseline-learning")
    public LearningStatus getBaselineLearning(@PathVariable String serviceName) {
        return baselineLearning.findStatus(serviceName).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No observations have been received for this service"));
    }

    @GetMapping("/{serviceName}/recent-window")
    public WindowStatus getRecentWindow(@PathVariable String serviceName) {
        return recentWindows.findWindow(serviceName).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No observations have been received for this service"));
    }

    @PostMapping("/baseline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveBaseline(@RequestBody ServiceMetrics baseline) {
        store.saveBaseline(baseline);
    }

    @PostMapping("/recent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveRecent(@RequestBody ServiceMetrics recent) {
        store.saveRecent(recent);
    }

    @GetMapping("/{serviceName}")
    public ClassificationRequest getComparison(@PathVariable String serviceName) {
        return store.findComparison(serviceName).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Both baseline and recent metrics are required for this service"));
    }

    @GetMapping("/{serviceName}/recommendations")
    public RecommendationResponse getRecommendations(@PathVariable String serviceName) {
        synchronized (observationLock(serviceName)) {
            LearningStatus learning = baselineLearning.findStatus(serviceName).orElse(null);
            WindowStatus recent = recentWindows.findWindow(serviceName).orElse(null);
            if (learning == null) {
                return waiting(serviceName, "WAITING_FOR_OBSERVATIONS", "Waiting for service metrics to arrive.", null, recent);
            }
            if (learning.phase() != BaselineLearningService.Phase.READY) {
                return waiting(serviceName, "LEARNING_BASELINE", learning.reason(), learning, recent);
            }
            if (recent == null || recent.phase() != RecentWindowService.Phase.READY) {
                return waiting(serviceName, "WAITING_FOR_RECENT_DATA",
                        recent == null ? "Waiting for recent service metrics." : recent.reason(), learning, recent);
            }
            if (!recent.windowStart().isAfter(learning.baseline().collectedAt())) {
                return waiting(serviceName, "WAITING_FOR_RECENT_DATA",
                        "Baseline is ready. Collecting a separate recent window for comparison.", learning, recent);
            }
            RecommendationPlan plan = recommendations.recommend(new ClassificationRequest(recent.summary(), learning.baseline()));
            var evidence = new ArrayList<>(plan.evidence());
            evidence.add("Recent latency uses MAX_OBSERVED_P95; baseline latency is median observed p95, not pooled window percentiles");
            return new RecommendationResponse(plan.schemaVersion(), plan.mode(), plan.serviceName(),
                    plan.status().name(), true, "Analysis is ready.", plan.sourceCollectedAt(),
                    plan.generatedAt(), plan.expiresAt(), evidence, plan.recommendations(), learning, recent);
        }
    }

    /** Waiting is normal operation: no health classification or policies are fabricated. */
    private RecommendationResponse waiting(String serviceName, String status, String message,
                                           LearningStatus learning, WindowStatus recent) {
        return new RecommendationResponse("1.0", "ADVISORY", serviceName, status, false, message,
                null, null, null, List.of(), List.of(), learning, recent);
    }

    /** Explicitly queue a plan from the learned baseline and current recent window. */
    @PostMapping("/{serviceName}/reviews")
    public ReviewSubmission queueReview(@PathVariable String serviceName) {
        synchronized (observationLock(serviceName)) {
            var analysis = getRecommendations(serviceName);
            if (!analysis.ready() || analysis.recommendations().isEmpty()) {
                return new ReviewSubmission(analysis.ready(),
                        analysis.ready() ? "No service policy changes are suggested." : analysis.message(), null, analysis);
            }
            var plan = new RecommendationPlan(analysis.schemaVersion(), analysis.mode(), analysis.serviceName(),
                    HealthStatus.valueOf(analysis.status()), analysis.sourceCollectedAt(), analysis.generatedAt(),
                    analysis.expiresAt(), analysis.evidence(), analysis.recommendations());
            return new ReviewSubmission(true, "Service policy decision recorded automatically. Distribution is pending Component 3 integration.", reviews.submitServicePlan(plan), analysis);
        }
    }

    public record ReviewSubmission(boolean ready, String message, RecommendationReview review,
                                   RecommendationResponse analysis) {
    }

    public record RecommendationResponse(String schemaVersion, String mode, String serviceName,
            String status, boolean ready, String message, Instant sourceCollectedAt,
            Instant generatedAt, Instant expiresAt, List<String> evidence,
            List<RecommendationPlan.Recommendation> recommendations,
            LearningStatus baselineLearning, WindowStatus recentWindow) {
        public RecommendationResponse {
            evidence = List.copyOf(evidence);
            recommendations = List.copyOf(recommendations);
        }
    }

    private Object observationLock(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName is required");
        }
        return observationLocks.computeIfAbsent(serviceName, name -> new Object());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidMetrics(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail baselineRequired(IllegalStateException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
