package org.rrpe.intelligence.analysis.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.ToDoubleFunction;

import org.rrpe.intelligence.analysis.config.BaselineLearningConfig.Settings;
import org.rrpe.intelligence.analysis.model.ServiceMetrics;
import org.springframework.stereotype.Service;

/**
 * Maintains recent per-service observations, including degraded traffic.
 * Inputs must describe comparable, non-overlapping sampling intervals with
 * interval counts rather than cumulative counters. Rates are averaged and errors
 * are weighted by request rate. The latency summary is MAX_OBSERVED_P95, not
 * the true percentile of the combined requests; histogram telemetry is needed
 * for that. State is local to this instance and is lost on restart.
 */
@Service
public class RecentWindowService {
    private final Settings settings;
    private final ConcurrentMap<String, Observations> services = new ConcurrentHashMap<>();

    public RecentWindowService(Settings settings) {
        this.settings = settings;
    }

    public enum Phase { INSUFFICIENT_DATA, READY }

    public record WindowStatus(Phase phase, int sampleCount, Instant windowStart, Instant windowEnd,
                               String reason, String latencyAggregation, ServiceMetrics summary) {
    }

    public void observe(ServiceMetrics metrics) {
        validate(metrics);
        Observations state = services.computeIfAbsent(metrics.serviceName(), name -> new Observations());
        synchronized (state) {
            if (metrics.equals(state.latest)) {
                return;
            }
            if (state.latest != null) {
                Duration gap = Duration.between(state.latest.collectedAt(), metrics.collectedAt());
                if (gap.compareTo(settings.sampleInterval()) < 0) {
                    throw new IllegalArgumentException("Observation must be newer by at least sample-interval");
                }
            }
            state.latest = metrics;
            state.samples.add(metrics);
            Instant cutoff = metrics.collectedAt().minus(settings.recentWindow());
            state.samples.removeIf(sample -> sample.collectedAt().isBefore(cutoff));
        }
    }

    public Optional<WindowStatus> findWindow(String serviceName) {
        return findWindow(serviceName, Instant.now());
    }

    /** Explicit analysis time supports deterministic tests and scheduled analysis. */
    public Optional<WindowStatus> findWindow(String serviceName, Instant asOf) {
        if (serviceName == null || serviceName.isBlank() || asOf == null) {
            throw new IllegalArgumentException("serviceName and analysis time are required");
        }
        Observations state = services.get(serviceName);
        if (state == null) {
            return Optional.empty();
        }
        Instant cutoff = asOf.minus(settings.recentWindow());
        List<ServiceMetrics> samples;
        synchronized (state) {
            samples = state.samples.stream()
                    .filter(sample -> !sample.collectedAt().isBefore(cutoff) && !sample.collectedAt().isAfter(asOf))
                    .toList();
        }
        if (samples.size() < settings.minimumRecentSamples()) {
            return Optional.of(status(Phase.INSUFFICIENT_DATA, samples, "Not enough recent samples", null));
        }
        Instant first = samples.get(0).collectedAt();
        Instant last = samples.get(samples.size() - 1).collectedAt();
        if (Duration.between(last, asOf).compareTo(settings.maxSampleGap()) > 0) {
            return Optional.of(status(Phase.INSUFFICIENT_DATA, samples, "Latest observation is stale", null));
        }
        if (first.isAfter(cutoff.plus(settings.sampleInterval()))
                || Duration.between(first, last).compareTo(settings.recentWindow().minus(settings.sampleInterval())) < 0) {
            return Optional.of(status(Phase.INSUFFICIENT_DATA, samples, "Observations do not cover the recent window", null));
        }
        for (int i = 1; i < samples.size(); i++) {
            Duration gap = Duration.between(samples.get(i - 1).collectedAt(), samples.get(i).collectedAt());
            if (gap.compareTo(settings.maxSampleGap()) > 0) {
                return Optional.of(status(Phase.INSUFFICIENT_DATA, samples, "Recent window contains a data gap", null));
            }
        }
        return Optional.of(status(Phase.READY, samples, "Recent window is available",
                aggregate(serviceName, last, samples)));
    }

    private static WindowStatus status(Phase phase, List<ServiceMetrics> samples, String reason, ServiceMetrics summary) {
        return new WindowStatus(phase, samples.size(), samples.isEmpty() ? null : samples.get(0).collectedAt(),
                samples.isEmpty() ? null : samples.get(samples.size() - 1).collectedAt(),
                reason, "MAX_OBSERVED_P95", summary);
    }

    private static ServiceMetrics aggregate(String name, Instant timestamp, List<ServiceMetrics> samples) {
        double maximumRate = samples.stream().mapToDouble(ServiceMetrics::requestRate).max().orElse(0);
        double errorRate = 0;
        if (maximumRate > 0) {
            double weight = samples.stream().mapToDouble(sample -> sample.requestRate() / maximumRate).sum();
            errorRate = samples.stream().mapToDouble(sample -> sample.errorRate() * (sample.requestRate() / maximumRate)).sum() / weight;
        }
        return new ServiceMetrics(name, timestamp, mean(samples, ServiceMetrics::requestRate),
                samples.stream().mapToDouble(ServiceMetrics::p95LatencyMs).max().orElseThrow(), errorRate,
                count(samples, ServiceMetrics::retryCount), count(samples, ServiceMetrics::rejectedRequestCount),
                count(samples, ServiceMetrics::deadlineFailureCount), count(samples, ServiceMetrics::cancellationCount),
                Math.round(mean(samples, sample -> sample.inFlightRequests())),
                mean(samples, ServiceMetrics::successfulThroughput));
    }

    private static double mean(List<ServiceMetrics> samples, ToDoubleFunction<ServiceMetrics> value) {
        return samples.stream().mapToDouble(sample -> value.applyAsDouble(sample) / samples.size()).sum();
    }

    private static long count(List<ServiceMetrics> samples, java.util.function.ToLongFunction<ServiceMetrics> value) {
        long sum = 0;
        try {
            for (ServiceMetrics sample : samples) {
                sum = Math.addExact(sum, value.applyAsLong(sample));
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Window counter total exceeds supported range", exception);
        }
        return sum;
    }

    private static void validate(ServiceMetrics metrics) {
        if (metrics == null || metrics.serviceName() == null || metrics.serviceName().isBlank() || metrics.collectedAt() == null) {
            throw new IllegalArgumentException("Observation requires serviceName and collectedAt");
        }
        for (double value : new double[]{metrics.requestRate(), metrics.p95LatencyMs(), metrics.errorRate(), metrics.successfulThroughput()}) {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("Metric values must be finite and nonnegative");
            }
        }
        if (metrics.errorRate() > 1 || metrics.retryCount() < 0 || metrics.rejectedRequestCount() < 0
                || metrics.deadlineFailureCount() < 0 || metrics.cancellationCount() < 0 || metrics.inFlightRequests() < 0) {
            throw new IllegalArgumentException("errorRate must be between 0 and 1 and counters must be nonnegative");
        }
    }

    private static final class Observations {
        private ServiceMetrics latest;
        private final List<ServiceMetrics> samples = new ArrayList<>();
    }
}
