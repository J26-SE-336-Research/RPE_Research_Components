package org.rrpe.intelligence.adaptive;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

public final class AdaptiveModels {
    private AdaptiveModels() {}
    public enum Source { PROMETHEUS, TEMPO, MOCK }
    public enum Health { INSUFFICIENT_DATA, LEARNING_BASELINE, BASELINE_READY, PENDING_BREACH, HEALTHY, STRESSED, OVERLOADED }
    public enum Action { LEAKY_BUCKET, RETRY_BUDGET, CHAIN_DEADLINE }
    public enum Decision { AUTOMATIC, PENDING_APPROVAL, APPROVED, REJECTED }
    public enum Delivery { WAITING_FOR_COMPONENT_3, READY, SENT, APPLIED, FAILED, EXPIRED, SUPERSEDED }

    /** All counts are per-window original-request measurements; nullable fields are unavailable. */
    public record ServiceWindow(String service, Instant start, Instant end, Source source, boolean complete,
            Double originalRequests, Double p95Ms, Double errorCount, Double retryAttempts, Double successfulRetries,
            Double rejections, Double inFlight, Double successfulThroughput,
            Boolean retryableFailure, Boolean safeOperation, Double remainingDeadlineMs, Double retryDurationMs,
            List<String> problems) {
        public ServiceWindow { problems = problems == null ? List.of() : List.copyOf(problems); }
    }
    public record ChainWindow(String chain, List<String> services, Instant start, Instant end, Source source,
            boolean complete, Double requests, Double deadlineFailures, Double p99Ms, Health health, List<String> traceIds) {
        public ChainWindow {
            services = services == null ? List.of() : List.copyOf(services);
            traceIds = traceIds == null ? List.of() : List.copyOf(traceIds);
        }
    }
    /** Reference confirmed by Component 3; calculations never silently change it. */
    public record Policy(String target, String version, Instant validUntil, Double bucketRate, Double healthyBucketTarget,
            Double retryRatio, Double deadlineMs) {}
    public record Statistic(double median, double mad) {}
    public record Assessment(String target, Health health, Source source, Instant windowEnd, int baselineSamples,
            Map<String, Statistic> baseline, Map<String, Double> signals, List<String> evidence) {}
    public record Proposal(UUID id, String target, Action action, Source source, String baseVersion,
            double currentValue, double proposedValue, String unit, Instant generatedAt, Instant expiresAt,
            Map<String, Double> measurements, List<String> evidence) {}
    public record PolicyChange(Proposal proposal, Decision decision, String reviewer, Instant decidedAt,
            Delivery delivery, String detail, int attempts, Instant lastAttempt, String appliedVersion) {}
    public record Outcome(Assessment assessment, List<PolicyChange> changes) {}
    public record Review(String reviewer, boolean approve, String comment) {}
    public record Acknowledgement(String status, String policyVersion, Double appliedValue) {}

    // ---------- Deadline recommendation v2 (see DEADLINE_V2_MIGRATION.md) ----------

    /**
     * One complete gateway request of a chain, as v2 uses it. {@code expired}: the chain passed its deadline (span event
     * {@code deadline.exceeded}, or inferred from {@code duration >= budget}). {@code cut}: ended early by a timer that is
     * not RPE (a 504 near the deadline, an outer timeout, a client abort); such requests are censored by something else.
     */
    public record DeadlineSample(String traceId, String method, String route, Instant end, double durationMs, int status,
            long budgetMs, SortedSet<String> path, boolean expired, boolean expiredInferred, boolean cut, String cutHint,
            Double queueWaitMs) {
        public DeadlineSample { path = path == null ? new TreeSet<>() : Collections.unmodifiableSortedSet(new TreeSet<>(path)); }
        public String stratum() { return String.join(">", path); }
    }
    /** Requests of one chain window that v2 may use, plus the count of those it must exclude, by reason. */
    public record ChainSamples(List<DeadlineSample> samples, Map<String, Integer> excluded) {
        public ChainSamples {
            samples = samples == null ? List.of() : List.copyOf(samples);
            excluded = excluded == null ? Map.of() : Map.copyOf(excluded);
        }
    }
    /** Per-service load signals for one window. A null value means the series is unavailable. */
    public record ServiceLoadSnapshot(String service, Double queuePressurePercent, Double overloadRejections,
            Double backpressureRejections, Double breakerOpenPeers, Double cpuUtilisation, Double cpuThrottling,
            Double memoryUtilisation, Double queueWaitShare, List<String> problems, Double queueDepth) {
        public ServiceLoadSnapshot { problems = problems == null ? List.of() : List.copyOf(problems); }
        /** Without queue depth (deadline v2 callers). */
        public ServiceLoadSnapshot(String service, Double queuePressurePercent, Double overloadRejections, Double backpressureRejections,
                Double breakerOpenPeers, Double cpuUtilisation, Double cpuThrottling, Double memoryUtilisation, Double queueWaitShare, List<String> problems) {
            this(service, queuePressurePercent, overloadRejections, backpressureRejections, breakerOpenPeers, cpuUtilisation, cpuThrottling,
                    memoryUtilisation, queueWaitShare, problems, null);
        }
        public static ServiceLoadSnapshot unavailable(String service) {
            return new ServiceLoadSnapshot(service, null, null, null, null, null, null, null, null, List.of());
        }
    }
    /** Per-request evidence of a chain window for v2: samples, per-service load signals, deadline-guard health, hops without a sidecar. */
    public record ChainEvidence(ChainSamples samples, Map<String, ServiceLoadSnapshot> loads, Map<String, Health> guards, java.util.Set<String> unmonitored) {
        public ChainEvidence {
            loads = loads == null ? Map.of() : Map.copyOf(loads);
            guards = guards == null ? Map.of() : Map.copyOf(guards);
            unmonitored = unmonitored == null ? java.util.Set.of() : java.util.Set.copyOf(unmonitored);
        }
    }
    public enum ServiceLoad { HEALTHY, STRESSED, OVERLOADED, UNKNOWN }
    public enum RequestLabel { H, STRESSED, O, UNKNOWN }
    public record LabelledSample(DeadlineSample sample, RequestLabel label, String stratum) {}
    /** x events out of n with one-sided Wilson bounds. */
    public record Proportion(int events, int n, double p, double lower, double upper) {}
    public enum Lever { DEADLINE, RATE, CAPACITY, NONE }
    public enum DeadlineCase { A_RAISE, B_LOWER_OVERLOAD, C_KEEP_FIRING, D_TIGHTEN, HOLD,
        /** The deadline part of an applied joint healthy recalibration (C3 J5); verified like A_RAISE or D_TIGHTEN by direction. */
        RECALIBRATE }
    public enum ChainDeadlineState { LEARNING, READY, PROPOSED, SETTLING, PAUSED }
    /**
     * Learned healthy time of a chain. {@code status}: IDENTIFIED (T_h known), CENSORED (only "p99 ≥ D" is known; T_h may
     * be null) or LEARNING. Null numbers mean "not known yet".
     */
    public record HealthyModel(String status, boolean ready, Double thMs, Double sigmaMs, Double margin, Long targetMs,
            int windows, int requests, double censoredShare, boolean coverageGap, Map<String, Double> strataShares) {}
    /** Everything behind one v2 deadline decision; exposed additively in the API and the audit trail. */
    public record DeadlineEvidence(DeadlineCase decisionCase, String holdReason, long currentMs, Long proposedMs,
            HealthyModel model, Proportion hExceed, Proportion oExceed, double oShare, double stressedShare,
            double unknownShare, double cutShare, String cutHint, Double predictedHExceed, Double predictedOExceed,
            Lever lever, boolean leverInferred, Map<String, String> serviceLoadReasons, ChainDeadlineState state,
            String verification, List<String> exampleTraceIds, String advisory) {}
}
