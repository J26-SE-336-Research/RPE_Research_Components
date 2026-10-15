package org.rrpe.intelligence.adaptive;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
}
