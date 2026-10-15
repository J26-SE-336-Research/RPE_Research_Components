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
 * Learns a fixed reference from a contiguous period of stable, successful traffic.
 * Observations must represent comparable windows and scopes; counters must be
 * interval counts, not cumulative Prometheus counters. Startup exclusion starts
 * at the first observation, since service startup timestamps are not supplied.
 * The learned values are medians of observations, including observed p95 values;
 * they are not a pooled latency percentile or a proof that an SLA was satisfied.
 * State is local to this application instance and is lost on restart.
 */
@Service
public class BaselineLearningService {
    private final Settings settings;
    private final ConcurrentMap<String, ServiceState> services = new ConcurrentHashMap<>();

    public BaselineLearningService(Settings settings) {
        this.settings = settings;
    }

    public enum Phase { STARTUP, LEARNING, READY }

    public record LearningStatus(Phase phase, int sampleCount, Instant windowStart,
                                 Instant windowEnd, String reason, ServiceMetrics baseline) {
    }

    /** Records an observation and returns the current learning progress. */
    public LearningStatus observe(ServiceMetrics metrics) {
        validate(metrics);
        ServiceState state = services.computeIfAbsent(metrics.serviceName(), name -> new ServiceState());
        synchronized (state) {
            if (metrics.equals(state.latest)) {
                return state.status;
            }
            if (state.latest != null && !metrics.collectedAt().isAfter(state.latest.collectedAt())) {
                throw new IllegalArgumentException("Observation must be newer than the previous observation");
            }
            if (state.startedAt == null) {
                state.startedAt = metrics.collectedAt();
            }
            state.latest = metrics;
            if (state.baseline != null) {
                return state.status;
            }
            if (metrics.collectedAt().isBefore(state.startedAt.plus(settings.startupExclusion()))) {
                return progress(state, Phase.STARTUP, "Excluding startup observations");
            }
            if (!eligible(metrics)) {
                state.samples.clear();
                return progress(state, Phase.LEARNING, "Waiting for active, failure-free traffic with comparable successful throughput");
            }

            if (!state.samples.isEmpty()) {
                ServiceMetrics last = state.samples.get(state.samples.size() - 1);
                Duration gap = Duration.between(last.collectedAt(), metrics.collectedAt());
                if (gap.compareTo(settings.maxSampleGap()) > 0) {
                    state.samples.clear();
                } else if (!stable(state.samples.get(0), metrics)) {
                    state.samples.clear();
                } else if (gap.compareTo(settings.sampleInterval()) < 0) {
                    // Fast submissions cannot inflate the minimum sample count.
                    return state.status;
                }
            }
            state.samples.add(metrics);
            Instant cutoff = metrics.collectedAt().minus(settings.baselineWindow());
            state.samples.removeIf(sample -> sample.collectedAt().isBefore(cutoff));

            // Allow at most one expected scrape interval of boundary slack.
            Duration span = Duration.between(state.samples.get(0).collectedAt(), metrics.collectedAt());
            Duration requiredSpan = settings.baselineWindow().minus(settings.sampleInterval());
            if (state.samples.size() < settings.minimumBaselineSamples()
                    || span.compareTo(requiredSpan) < 0) {
                return progress(state, Phase.LEARNING, "Waiting for enough samples across the baseline window");
            }
            state.baseline = aggregate(metrics.serviceName(), metrics.collectedAt(), state.samples);
            LearningStatus ready = progress(state, Phase.READY, "Baseline learned and frozen");
            state.samples.clear();
            return ready;
        }
    }

    /** Bounded service discovery from observations already received by this instance. */
    public List<String> findServiceNames() {
        return services.keySet().stream().sorted().limit(100).toList();
    }

    public Optional<LearningStatus> findStatus(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName is required");
        }
        ServiceState state = services.get(serviceName);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            return Optional.ofNullable(state.status);
        }
    }

    public Optional<ServiceMetrics> findBaseline(String serviceName) {
        return findStatus(serviceName).map(LearningStatus::baseline);
    }

    private LearningStatus progress(ServiceState state, Phase phase, String reason) {
        Instant start = state.samples.isEmpty() ? null : state.samples.get(0).collectedAt();
        Instant end = state.samples.isEmpty() ? null : state.samples.get(state.samples.size() - 1).collectedAt();
        state.status = new LearningStatus(phase, state.samples.size(), start, end, reason, state.baseline);
        return state.status;
    }

    private boolean eligible(ServiceMetrics metrics) {
        return metrics.requestRate() > 0 && metrics.p95LatencyMs() > 0 && metrics.successfulThroughput() > 0
                && metrics.errorRate() == 0 && metrics.retryCount() == 0 && metrics.rejectedRequestCount() == 0
                && metrics.deadlineFailureCount() == 0 && metrics.cancellationCount() == 0
                && close(metrics.requestRate(), metrics.successfulThroughput());
    }

    private boolean stable(ServiceMetrics reference, ServiceMetrics current) {
        return close(reference.requestRate(), current.requestRate())
                && close(reference.p95LatencyMs(), current.p95LatencyMs())
                && close(reference.successfulThroughput(), current.successfulThroughput())
                && close(reference.inFlightRequests(), current.inFlightRequests());
    }

    private boolean close(double reference, double value) {
        if (reference == 0) {
            return value == 0;
        }
        return Math.abs(value / reference - 1) <= settings.stabilityTolerance();
    }

    private static ServiceMetrics aggregate(String name, Instant timestamp, List<ServiceMetrics> samples) {
        return new ServiceMetrics(name, timestamp, median(samples, ServiceMetrics::requestRate),
                median(samples, ServiceMetrics::p95LatencyMs), 0, 0, 0, 0, 0,
                Math.round(median(samples, metrics -> metrics.inFlightRequests())),
                median(samples, ServiceMetrics::successfulThroughput));
    }

    private static double median(List<ServiceMetrics> samples, ToDoubleFunction<ServiceMetrics> value) {
        double[] sorted = samples.stream().mapToDouble(value).sorted().toArray();
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : sorted[middle - 1] / 2 + sorted[middle] / 2;
    }

    private static void validate(ServiceMetrics metrics) {
        if (metrics == null || metrics.serviceName() == null || metrics.serviceName().isBlank()
                || metrics.collectedAt() == null) {
            throw new IllegalArgumentException("Observation requires serviceName and collectedAt");
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

    private static final class ServiceState {
        private Instant startedAt;
        private ServiceMetrics latest;
        private ServiceMetrics baseline;
        private LearningStatus status;
        private final List<ServiceMetrics> samples = new ArrayList<>();
    }
}
