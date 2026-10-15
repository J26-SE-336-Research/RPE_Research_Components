package org.rrpe.intelligence.analysis.service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.rrpe.intelligence.analysis.api.ClassificationRequest;
import org.rrpe.intelligence.analysis.model.ServiceMetrics;
import org.springframework.stereotype.Service;

/**
 * Stores an explicitly selected baseline and the latest observation per service.
 * Data is local to this application instance and is lost on restart.
 * Callers must submit metrics measured over comparable windows and scopes.
 */
@Service
public class ServiceMetricsStore {
    private final ConcurrentMap<String, Snapshots> snapshots = new ConcurrentHashMap<>();

    /** Sets or replaces the reference baseline without promoting recent metrics. */
    public void saveBaseline(ServiceMetrics baseline) {
        validate(baseline);
        snapshots.compute(baseline.serviceName(), (serviceName, existing) -> {
            ServiceMetrics recent = existing == null ? null : existing.recent();
            if (recent != null && !recent.collectedAt().isAfter(baseline.collectedAt())) {
                throw new IllegalArgumentException("Baseline must be earlier than the stored recent snapshot");
            }
            return new Snapshots(baseline, recent);
        });
    }

    /** Saves a newer observation; an identical replay leaves the store unchanged. */
    public void saveRecent(ServiceMetrics recent) {
        validate(recent);
        snapshots.compute(recent.serviceName(), (serviceName, existing) -> {
            if (existing == null) {
                throw new IllegalStateException("Save a baseline for this service before recent metrics");
            }
            if (!recent.collectedAt().isAfter(existing.baseline().collectedAt())) {
                throw new IllegalArgumentException("Recent snapshot must be later than the baseline");
            }
            if (recent.equals(existing.recent())) {
                return existing;
            }
            if (existing.recent() != null && !recent.collectedAt().isAfter(existing.recent().collectedAt())) {
                throw new IllegalArgumentException("Recent snapshot must be newer than the stored observation");
            }
            return new Snapshots(existing.baseline(), recent);
        });
    }

    /** Returns a comparison only after both snapshots have been received. */
    public Optional<ClassificationRequest> findComparison(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName is required");
        }
        Snapshots stored = snapshots.get(serviceName);
        if (stored == null || stored.recent() == null) {
            return Optional.empty();
        }
        return Optional.of(new ClassificationRequest(stored.recent(), stored.baseline()));
    }

    private static void validate(ServiceMetrics metrics) {
        if (metrics == null || metrics.serviceName() == null || metrics.serviceName().isBlank()
                || metrics.collectedAt() == null) {
            throw new IllegalArgumentException("Metrics require serviceName and collectedAt");
        }
        for (double value : new double[]{metrics.requestRate(), metrics.p95LatencyMs(),
                metrics.errorRate(), metrics.successfulThroughput()}) {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("Metric values must be finite and nonnegative");
            }
        }
        if (metrics.errorRate() > 1 || metrics.retryCount() < 0 || metrics.rejectedRequestCount() < 0
                || metrics.deadlineFailureCount() < 0 || metrics.cancellationCount() < 0
                || metrics.inFlightRequests() < 0) {
            throw new IllegalArgumentException("errorRate must be between 0 and 1 and counters must be nonnegative");
        }
    }

    private record Snapshots(ServiceMetrics baseline, ServiceMetrics recent) {
    }
}
