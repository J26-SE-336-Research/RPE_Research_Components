package org.rrpe.intelligence.analysis.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.rrpe.intelligence.analysis.api.CancellationAnalysisRequest;
import org.rrpe.intelligence.analysis.config.BaselineLearningConfig.Settings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Detects post-cancellation activity from caller-supplied correlated evidence.
 * Findings require investigation; they do not identify a root cause or change
 * sidecar policies. The two affected-request counts can overlap and are never
 * added together to claim a total number of affected requests.
 */
@Service
public class CancellationAnalysisService {
    private final Settings windows;

    public CancellationAnalysisService(Settings windows) {
        this.windows = windows;
    }

    public record Finding(String code, long affectedRequestCount, String message, String suggestedCheck) {
    }

    public record AnalysisResult(String schemaVersion, String mode, String chainId, String status,
                                 boolean ready, boolean requiresReview, String message,
                                 CancellationAnalysisRequest evidence, List<Finding> findings,
                                 Instant analysedAt) {
        public AnalysisResult {
            findings = List.copyOf(findings);
        }
    }

    public AnalysisResult analyse(CancellationAnalysisRequest request) {
        Instant now = Instant.now();
        validate(request, now);
        Duration span = Duration.between(request.windowStart(), request.windowEnd());
        if (!request.telemetryComplete() || span.compareTo(windows.recentWindow()) < 0
                || span.compareTo(windows.recentWindow().plus(windows.sampleInterval())) > 0) {
            return result(request, "INSUFFICIENT_DATA", false,
                    "Waiting for complete correlated cancellation evidence covering the recent window.", List.of(), now);
        }
        if (Duration.between(request.windowEnd(), now).compareTo(windows.analysisInterval().multipliedBy(2)) > 0) {
            return result(request, "WAITING_FOR_FRESH_DATA", false,
                    "Waiting for fresh cancellation evidence.", List.of(), now);
        }
        if (request.cancellationCount() == 0) {
            return result(request, "NO_CANCELLATIONS_OBSERVED", true,
                    "No cancelled requests were observed; this window cannot validate cancellation handling.", List.of(), now);
        }

        var findings = new ArrayList<Finding>();
        if (request.continuedProcessingCount() > 0) {
            findings.add(new Finding("PROCESSING_AFTER_CANCELLATION", request.continuedProcessingCount(),
                    "Processing remained active after cancellation and the observation grace period.",
                    "Inspect cancellation propagation and whether downstream work stops when the request is cancelled."));
        }
        if (request.downstreamActivityCount() > 0) {
            findings.add(new Finding("DOWNSTREAM_CALL_AFTER_CANCELLATION", request.downstreamActivityCount(),
                    "New downstream calls started after cancellation and the observation grace period.",
                    "Inspect cancellation checks before starting downstream calls, retries, and fan-out work."));
        }
        if (findings.isEmpty()) {
            return result(request, "NO_PROBLEM_OBSERVED", true,
                    "No post-cancellation activity was observed beyond the supplied grace period in this window.", findings, now);
        }
        return result(request, "PROBLEM_DETECTED", true,
                "Post-cancellation activity was observed. Review the findings; no policy is automatically changed.", findings, now);
    }

    private static AnalysisResult result(CancellationAnalysisRequest request, String status, boolean ready,
                                         String message, List<Finding> findings, Instant now) {
        return new AnalysisResult("1.0", "ADVISORY", request.chainId(), status, ready,
                !findings.isEmpty(), message, request, findings, now);
    }

    private static void validate(CancellationAnalysisRequest request, Instant now) {
        if (request == null || request.chainId() == null || request.chainId().isBlank()
                || request.services() == null || request.services().isEmpty()
                || request.services().stream().anyMatch(name -> name == null || name.isBlank())
                || request.windowStart() == null || request.windowEnd() == null) {
            throw badRequest("Chain identity, services, and both window timestamps are required");
        }
        if (!request.windowEnd().isAfter(request.windowStart()) || request.windowEnd().isAfter(now)) {
            throw badRequest("Window must have positive duration and must not end in the future");
        }
        if (request.cancellationCount() < 0 || request.continuedProcessingCount() < 0
                || request.downstreamActivityCount() < 0 || request.observationGraceMs() < 0
                || request.continuedProcessingCount() > request.cancellationCount()
                || request.downstreamActivityCount() > request.cancellationCount()) {
            throw badRequest("Counts and grace period must be nonnegative; each affected count must not exceed cancellationCount");
        }
        if (Duration.ofMillis(request.observationGraceMs()).compareTo(Duration.between(request.windowStart(), request.windowEnd())) > 0) {
            throw badRequest("Observation grace period must not exceed the measurement window");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
