package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Corrected PDF: normalized rates, robust baselines, persistence, bounded policies and gradual recovery. */
@Service
public class AdaptiveEngine {
    private final AdaptiveConfig.Settings settings;
    private final PolicyWorkflow workflow;
    private final Clock clock;
    private final Map<String, State> services = new ConcurrentHashMap<>();
    private final Map<String, State> chains = new ConcurrentHashMap<>();
    @Autowired public AdaptiveEngine(AdaptiveConfig.Settings settings, PolicyWorkflow workflow) { this(settings, workflow, Clock.systemUTC()); }
    public AdaptiveEngine(AdaptiveConfig.Settings settings, PolicyWorkflow workflow, Clock clock) {
        this.settings = settings; this.workflow = workflow; this.clock = clock;
    }
    private Duration evaluationDelay = Duration.ZERO;
    /** Windows are evaluated this long after they end (Tempo ingestion); freshness limits allow for it. */
    @Autowired(required = false)
    void setEvaluationDelay(@org.springframework.beans.factory.annotation.Value("${rrpe.adaptive.evaluation-delay:0s}") Duration delay) {
        if (delay != null && !delay.isNegative()) evaluationDelay = delay;
    }
    /** Forgets every learned baseline (live sources); used after an intentional change such as a new release. */
    public void resetBaselines() {
        services.keySet().removeIf(k -> !k.startsWith(Source.MOCK + ":"));
        chains.keySet().removeIf(k -> !k.startsWith(Source.MOCK + ":"));
    }
    private Duration freshness() { return settings.cycle().multipliedBy(2).plus(evaluationDelay); }
    private String key(String target, Source source) { return source + ":" + target; }

    public Outcome service(ServiceWindow window) {
        if (window == null || window.service() == null || window.service().isBlank() || window.source() == null) throw new IllegalArgumentException("Service and source are required");
        State state = services.computeIfAbsent(key(window.service(), window.source()), ignored -> new State());
        synchronized (state) {
            var errors = new ArrayList<String>(window.problems());
            if (!validWindow(window.start(), window.end()) || !window.complete()) errors.add("Incomplete, stale or invalid measurement window");
            Double[] required = {window.originalRequests(), window.p95Ms(), window.errorCount(), window.retryAttempts(), window.rejections(), window.inFlight(), window.successfulThroughput()};
            if (Arrays.stream(required).anyMatch(value -> !number(value))) errors.add("Required request, latency, error, retry, rejection, in-flight or throughput evidence is unavailable");
            if (errors.isEmpty() && (window.originalRequests() < settings.minimumServiceRequests()
                    || window.errorCount() > window.originalRequests() || window.rejections() > window.originalRequests())) errors.add("Insufficient original requests or inconsistent counts");
            if (window.end() != null && window.end().isAfter(clock.instant())) {
                reset(state); withdrawService(window, "Future-dated evidence");
                return save(state, window.service(), window.source(), window.end(), Health.INSUFFICIENT_DATA, Map.of(), errors, List.of());
            }
            if (!acceptCycle(state, window.end())) return previousOrWaiting(state, window.service(), window.source(), window.end());
            if (!errors.isEmpty()) {
                reset(state); withdrawService(window, "Insufficient or stale evidence");
                return save(state, window.service(), window.source(), window.end(), Health.INSUFFICIENT_DATA, Map.of(), errors, List.of());
            }
            Map<String, Double> values = Map.of("latency", window.p95Ms(), "errorRate", window.errorCount() / window.originalRequests(),
                    "retryRate", window.retryAttempts() / window.originalRequests(), "rejectionRate", window.rejections() / window.originalRequests(),
                    "inFlight", window.inFlight(), "throughput", window.successfulThroughput());
            if (state.baseline.isEmpty()) {
                boolean clean = window.errorCount() == 0 && window.retryAttempts() / window.originalRequests() <= settings.maximumRetryRatio()
                        && (window.retryAttempts() == 0 || number(window.successfulRetries()) && window.successfulRetries() > 0
                            && window.successfulRetries() <= window.retryAttempts() && window.successfulRetries() <= window.originalRequests())
                        && window.rejections() == 0
                        && window.p95Ms() > 0 && window.successfulThroughput() > 0;
                Health phase = learn(state, values, window.end(), clean, List.of("latency", "throughput"));
                return save(state, window.service(), window.source(), window.end(), phase, values,
                        List.of("Learning a stable Healthy reference; current samples=" + state.samples.size()), List.of());
            }
            if (!window.start().isAfter(state.baselineEnd)) return save(state, window.service(), window.source(), window.end(),
                    Health.BASELINE_READY, values, List.of("Waiting for a recent window separate from baseline observations"), List.of());
            int persistent = 0; boolean critical = false; boolean breached = false;
            var evidence = new ArrayList<String>();
            for (var entry : values.entrySet()) {
                Statistic reference = state.baseline.get(entry.getKey());
                double variation = variation(reference, entry.getKey());
                double distance = (entry.getKey().equals("throughput") ? reference.median() - entry.getValue() : entry.getValue() - reference.median()) / variation;
                if (distance >= settings.stressedMad()) {
                    breached = true;
                    int cycles = state.signalCycles.merge(entry.getKey(), 1, Integer::sum);
                    if (cycles >= settings.persistence()) persistent++;
                    evidence.add(entry.getKey() + "=" + entry.getValue() + ", baseline median=" + reference.median()
                            + ", MAD=" + reference.mad() + ", effective variation=" + variation + ", consecutive breaches=" + cycles);
                } else state.signalCycles.remove(entry.getKey());
                if (distance >= settings.overloadedMad() && List.of("latency", "errorRate").contains(entry.getKey())) critical = true;
            }
            Health health = critical ? Health.OVERLOADED : persistent >= 2 ? Health.OVERLOADED : persistent == 1 ? Health.STRESSED
                    : breached ? Health.PENDING_BREACH : Health.HEALTHY;
            state.healthy = health == Health.HEALTHY ? state.healthy + 1 : 0;
            state.degraded = health == Health.STRESSED || health == Health.OVERLOADED ? state.degraded + 1 : 0;
            // A two-cycle stressed condition is already persistent; critical overload still waits two policy cycles.
            boolean protective = health == Health.STRESSED || health == Health.OVERLOADED;
            boolean persistentPolicy = protective && (persistent > 0 || state.degraded >= settings.persistence());
            evidence.add("Counts normalized by " + window.originalRequests() + " original requests; healthy cycles=" + state.healthy);
            List<PolicyChange> changes = new ArrayList<>();
            Policy policy = workflow.current(window.service(), window.source());
            if (policy == null) evidence.add("Waiting for current Component 3 policy reference");
            else {
                bucket(window, state, policy, health, persistentPolicy, evidence, changes);
                retry(window, state, policy, health, persistentPolicy, evidence, changes);
            }
            if (!protective && health != Health.HEALTHY) withdrawService(window, "Condition is not yet persistent");
            if (health == Health.HEALTHY && state.healthy >= settings.recoveryWindows()) refreshBaseline(state, values);
            return save(state, window.service(), window.source(), window.end(), health, values, evidence, changes);
        }
    }

    private void bucket(ServiceWindow window, State state, Policy policy, Health health, boolean persistent,
                        List<String> evidence, List<PolicyChange> changes) {
        if (!number(policy.bucketRate()) || policy.bucketRate() <= 0 || !number(policy.healthyBucketTarget())) {
            evidence.add("Leaky Bucket waits for current rate and Healthy target"); return;
        }
        double baseline = state.baseline.get("throughput").median();
        double candidate = policy.bucketRate();
        String rule = null;
        if (persistent) {
            candidate = Math.min(policy.bucketRate(), Math.min(baseline, window.successfulThroughput()) * (health == Health.OVERLOADED ? .75 : .90));
            rule = "capacity=min(baseline throughput,recent throughput); factor=" + (health == Health.OVERLOADED ? .75 : .90);
        } else if (health == Health.HEALTHY && state.healthy >= settings.recoveryWindows()) {
            candidate = Math.min(Math.min(policy.healthyBucketTarget(), baseline), policy.bucketRate() + baseline * .05);
            rule = "Healthy recovery: add 5% of baseline throughput, capped at Healthy target and baseline";
        }
        if (rule != null && candidate != policy.bucketRate() && cooldown(window.service(), Action.LEAKY_BUCKET, window.source())) {
            if (candidate <= 0 || Math.abs(candidate / policy.bucketRate() - 1) > settings.maximumRateChange()) {
                evidence.add("Leaky Bucket candidate blocked by configured change/positive-rate limits"); return;
            }
            changes.add(offer(window.service(), Action.LEAKY_BUCKET, window.source(), policy, policy.bucketRate(), candidate,
                    "requests/second", window.end(), Map.of("baselineThroughput", baseline, "recentThroughput", window.successfulThroughput()), rule, evidence));
        } else if (rule == null && health == Health.HEALTHY) workflow.withdraw(window.service(), Action.LEAKY_BUCKET, window.source(), "Waiting for stable Healthy recovery");
    }

    private void retry(ServiceWindow window, State state, Policy policy, Health health, boolean persistent,
                       List<String> evidence, List<PolicyChange> changes) {
        if (!number(policy.retryRatio()) || policy.retryRatio() > settings.maximumRetryRatio()) { evidence.add("Retry budget waits for valid current ratio"); return; }
        // Overload permits a protective zero budget without assuming absent effectiveness or retry eligibility.
        if (health == Health.OVERLOADED && persistent && cooldown(window.service(), Action.RETRY_BUDGET, window.source())) {
            if (policy.retryRatio() > 0) changes.add(offer(window.service(), Action.RETRY_BUDGET, window.source(), policy,
                    policy.retryRatio(), 0, "extra attempts/original request", window.end(), Map.of("originalRequests", window.originalRequests(), "allowedRetries", 0.0),
                    "Overloaded: disable additional retries; original requests remain allowed", evidence));
            return;
        }
        if (!number(window.successfulRetries()) || window.successfulRetries() > window.retryAttempts()
                || window.successfulRetries() > window.originalRequests() || window.retryAttempts() == 0) {
            evidence.add("Retry effectiveness unavailable: need retry attempts and successful retries; no division by zero");
            workflow.withdraw(window.service(), Action.RETRY_BUDGET, window.source(), "Retry-effectiveness evidence unavailable"); return;
        }
        double effectiveness = window.successfulRetries() / window.retryAttempts();
        double healthyTarget = settings.maximumRetryRatio() * effectiveness;
        double candidate = health == Health.STRESSED ? healthyTarget * .5 : healthyTarget;
        String rule = "retry effectiveness=" + effectiveness + "; maximum ratio=" + settings.maximumRetryRatio();
        boolean ready = health == Health.STRESSED && persistent || health == Health.HEALTHY && state.healthy >= settings.recoveryWindows();
        if (!ready || !cooldown(window.service(), Action.RETRY_BUDGET, window.source())) return;
        if (health == Health.HEALTHY && candidate > policy.retryRatio()) candidate = Math.min(candidate, policy.retryRatio() + .01);
        // Never increase retries during stress, and never enable attempts without operation/deadline evidence.
        if (health == Health.STRESSED) candidate = Math.min(candidate, policy.retryRatio());
        if (candidate > 0 && (!Boolean.TRUE.equals(window.retryableFailure()) || !Boolean.TRUE.equals(window.safeOperation())
                || !number(window.remainingDeadlineMs()) || !number(window.retryDurationMs())
                || window.remainingDeadlineMs() <= window.retryDurationMs() || window.retryDurationMs() == 0)) {
            evidence.add("Nonzero retry budget blocked: safe retryable operation and sufficient remaining deadline required");
            workflow.withdraw(window.service(), Action.RETRY_BUDGET, window.source(), "Retry safety evidence unavailable"); return;
        }
        if (candidate != policy.retryRatio()) {
            changes.add(offer(window.service(), Action.RETRY_BUDGET, window.source(), policy, policy.retryRatio(), candidate,
                    "extra attempts/original request", window.end(), Map.of("effectiveness", effectiveness, "originalRequests", window.originalRequests(),
                            "allowedRetries", Math.floor(window.originalRequests() * candidate)), rule + "; retry executor must enforce aggregate budget, exponential backoff and jitter", evidence));
        }
    }

    public Outcome chain(ChainWindow window) {
        return chain(window, Map.of(), List.of());
    }

    public Outcome chain(ChainWindow window, Map<String, Double> supportingMeasurements, List<String> supportingEvidence) {
        if (window == null || window.chain() == null || window.chain().isBlank() || window.source() == null) throw new IllegalArgumentException("Chain and source are required");
        State state = chains.computeIfAbsent(key(window.chain(), window.source()), ignored -> new State());
        synchronized (state) {
            if (window.end() != null && window.end().isAfter(clock.instant())) {
                reset(state); workflow.withdraw(window.chain(), Action.CHAIN_DEADLINE, window.source(), "Future-dated chain evidence");
                return save(state, window.chain(), window.source(), window.end(), Health.INSUFFICIENT_DATA, Map.of(), List.of("Future-dated window"), List.of());
            }
            if (!acceptCycle(state, window.end())) return previousOrWaiting(state, window.chain(), window.source(), window.end());
            if (!validWindow(window.start(), window.end()) || !window.complete() || window.services().isEmpty()
                    || !number(window.requests()) || window.requests() < settings.minimumChainRequests()
                    || !number(window.deadlineFailures()) || window.deadlineFailures() > window.requests()
                    || !number(window.p99Ms()) || window.p99Ms() <= 0 || window.health() == null
                    || !List.of(Health.HEALTHY, Health.STRESSED, Health.OVERLOADED).contains(window.health())) {
                reset(state); workflow.withdraw(window.chain(), Action.CHAIN_DEADLINE, window.source(), "Incomplete chain evidence");
                return save(state, window.chain(), window.source(), window.end(), Health.INSUFFICIENT_DATA, Map.of(),
                        List.of("Complete chain p99, request/failure counts, ordered path and known health are required"), List.of());
            }
            Map<String, Double> signals = Map.of("p99", window.p99Ms(), "deadlineFailureRate", window.deadlineFailures() / window.requests());
            if (state.baseline.isEmpty()) {
                Health phase = learn(state, signals, window.end(), window.health() == Health.HEALTHY && window.deadlineFailures() == 0, List.of("p99"));
                String why = window.health() != Health.HEALTHY ? "; restarted: path health is " + window.health()
                        : window.deadlineFailures() > 0 ? "; restarted: " + Math.round(window.deadlineFailures()) + " deadline failures in the window"
                        : state.samples.size() == 1 ? "; restarted or first sample (p99 must stay within " + Math.round(settings.stabilityTolerance() * 100) + "% of the median)" : "";
                return save(state, window.chain(), window.source(), window.end(), phase, signals,
                        List.of("Learning stable Healthy chain p99; samples=" + state.samples.size() + "/" + settings.baselineSamples() + why), List.of());
            }
            if (!window.start().isAfter(state.baselineEnd)) return save(state, window.chain(), window.source(), window.end(), Health.BASELINE_READY,
                    signals, List.of("Waiting for a recent window separate from chain baseline"), List.of());
            var evidence = new ArrayList<String>(supportingEvidence); var changes = new ArrayList<PolicyChange>();
            double baseline = state.baseline.get("p99").median();
            double failureRate = signals.get("deadlineFailureRate");
            boolean abnormalFailures = failureRate > state.baseline.get("deadlineFailureRate").median() + settings.ratioFloor();
            state.degraded = abnormalFailures ? state.degraded + 1 : 0;
            state.healthy = window.health() == Health.HEALTHY && !abnormalFailures ? state.healthy + 1 : 0;
            Policy policy = workflow.current(window.chain(), window.source());
            if (policy == null || !number(policy.deadlineMs()) || policy.deadlineMs() <= 0) evidence.add("Waiting for approved Component 3 chain deadline");
            else if (window.health() == Health.OVERLOADED) {
                evidence.add("Deadline increase blocked during overload"); workflow.withdraw(window.chain(), Action.CHAIN_DEADLINE, window.source(), "Chain is overloaded");
            } else if (cooldown(window.chain(), Action.CHAIN_DEADLINE, window.source())) {
                double candidate = policy.deadlineMs(); double margin = 0; String rule = null;
                if (state.degraded >= settings.persistence()) {
                    var calculated = DeadlineMath.increaseCensored(window.p99Ms(), baseline, policy.deadlineMs());
                    margin = calculated.safetyMargin(); candidate = calculated.milliseconds();
                    if (candidate > policy.deadlineMs()) {
                        rule = "Persistent deadline failures; latency change=" + Math.round(calculated.latencyChangeRatio() * 100) + "%; margin=" + margin;
                        if (window.p99Ms() >= policy.deadlineMs() * .95) evidence.add("Observed p99 is cut at the current deadline (censored); the uncensored baseline p99 "
                                + Math.round(baseline) + " ms is used as a lower bound");
                        double bounded = DeadlineMath.clamp(candidate, policy.deadlineMs(), settings.maximumDeadlineChange());
                        if (bounded < candidate) {
                            evidence.add("Limited to the maximum step of " + Math.round(settings.maximumDeadlineChange() * 100) + "% (wanted " + Math.round(candidate) + " ms)");
                            candidate = bounded;
                        }
                    }
                } else if (state.healthy >= settings.recoveryWindows()) {
                    candidate = DeadlineMath.restore(baseline, policy.deadlineMs());
                    if (candidate < policy.deadlineMs()) rule = "Healthy deadline restoration: max(baseline p99*1.10,current deadline*0.90)";
                }
                if (rule != null) {
                    if (candidate < settings.minimumDeadlineMs() || candidate > settings.maximumDeadlineMs()
                            || Math.abs(candidate / policy.deadlineMs() - 1) > settings.maximumDeadlineChange()) evidence.add("Deadline candidate exceeds configured limits");
                    else {
                        var measurements = new HashMap<String, Double>(supportingMeasurements);
                        measurements.putAll(Map.of("baselineP99Ms", baseline, "recentP99Ms", window.p99Ms(), "failureRate", failureRate, "safetyMargin", margin,
                                    "requestCount", window.requests(), "deadlineFailureCount", window.deadlineFailures(),
                                    "failureCycles", (double) state.degraded, "healthyCycles", (double) state.healthy));
                        changes.add(offer(window.chain(), Action.CHAIN_DEADLINE, window.source(), policy, policy.deadlineMs(), candidate, "milliseconds", window.end(),
                                Map.copyOf(measurements), rule, evidence));
                    }
                } else workflow.withdraw(window.chain(), Action.CHAIN_DEADLINE, window.source(), "No persistent deadline change supported");
            }
            evidence.add("Deadline changes and restoration require human approval; chain p99 is never a sum of service percentiles");
            if (state.healthy >= settings.recoveryWindows()) refreshBaseline(state, signals);
            return save(state, window.chain(), window.source(), window.end(), window.health(), signals, evidence, changes);
        }
    }

    public List<Assessment> assessments() {
        var result = new ArrayList<Assessment>();
        for (State state : combined()) synchronized (state) {
            if (state.outcome != null) {
                var a = state.outcome.assessment();
                if (a.windowEnd() == null || Duration.between(a.windowEnd(), clock.instant()).compareTo(freshness()) > 0)
                    result.add(new Assessment(a.target(), Health.INSUFFICIENT_DATA, a.source(), a.windowEnd(), a.baselineSamples(), a.baseline(), Map.of(), List.of("Evidence is stale")));
                else result.add(a);
            }
        }
        return result.stream().sorted(Comparator.comparing(Assessment::target)).toList();
    }
    private List<State> combined() { var states = new ArrayList<>(services.values()); states.addAll(chains.values()); return states; }
    private Health learn(State state, Map<String, Double> signals, Instant end, boolean clean, List<String> stableMetrics) {
        if (!clean) { state.samples.clear(); return Health.LEARNING_BASELINE; }
        var candidate = new ArrayList<>(state.samples); candidate.add(signals);
        for (String metric : stableMetrics) {
            double median = RobustStatistics.median(candidate.stream().mapToDouble(item -> item.get(metric)).toArray());
            if (median <= 0 || candidate.stream().anyMatch(item -> Math.abs(item.get(metric) / median - 1) > settings.stabilityTolerance())) {
                state.samples.clear(); break;
            }
        }
        state.samples.add(signals);
        if (state.samples.size() < settings.baselineSamples()) return Health.LEARNING_BASELINE;
        state.baseline = statistics(state.samples); state.baselineEnd = end; return Health.BASELINE_READY;
    }
    private Map<String, Statistic> statistics(List<Map<String, Double>> samples) {
        var result = new HashMap<String, Statistic>();
        for (String metric : samples.get(0).keySet()) result.put(metric, RobustStatistics.summarize(samples.stream().mapToDouble(item -> item.get(metric)).toArray()));
        return Map.copyOf(result);
    }
    private void refreshBaseline(State state, Map<String, Double> values) { state.samples.remove(0); state.samples.add(values); state.baseline = statistics(state.samples); }
    /**
     * The variation a deviation is measured in: the baseline MAD, but never below a practical floor (an absolute floor,
     * or the relative floor of the median). Very steady traffic gives a near-zero MAD, which would otherwise turn
     * noise of a fraction of a request per second into a "breach".
     */
    private double variation(Statistic reference, String metric) {
        double floor = Math.max(metric.endsWith("Rate") ? settings.ratioFloor() : 1, Math.abs(reference.median()) * settings.zeroMadRelativeFloor());
        return Math.max(reference.mad(), floor);
    }
    private boolean validWindow(Instant start, Instant end) {
        return start != null && end != null && !end.isAfter(clock.instant()) && Duration.between(start, end).equals(settings.window())
                && Duration.between(end, clock.instant()).compareTo(freshness()) <= 0;
    }
    private boolean number(Double value) { return value != null && Double.isFinite(value) && value >= 0; }
    private boolean acceptCycle(State state, Instant end) {
        if (end == null) return true;
        if (state.last != null) {
            Duration gap = Duration.between(state.last, end);
            if (gap.compareTo(settings.cycle()) < 0) return false;
            if (gap.compareTo(settings.cycle().multipliedBy(2)) > 0) reset(state);
        }
        state.last = end; return true;
    }
    private void reset(State state) { state.signalCycles.clear(); state.degraded = 0; state.healthy = 0; if (state.baseline.isEmpty()) state.samples.clear(); }
    private boolean cooldown(String target, Action action, Source source) {
        Instant last = workflow.lastApplied(target, action, source);
        return last == null || Duration.between(last, clock.instant()).compareTo(settings.cooldown()) >= 0;
    }
    private void withdrawService(ServiceWindow window, String reason) {
        workflow.withdraw(window.service(), Action.LEAKY_BUCKET, window.source(), reason); workflow.withdraw(window.service(), Action.RETRY_BUDGET, window.source(), reason);
    }
    private PolicyChange offer(String target, Action action, Source source, Policy policy, double current, double proposed,
            String unit, Instant windowEnd, Map<String, Double> measurements, String rule, List<String> evidence) {
        Instant now = clock.instant(); Instant expiry = now.plus(settings.ttl()); if (policy.validUntil().isBefore(expiry)) expiry = policy.validUntil();
        var data = new HashMap<>(measurements); data.put("windowEndEpochSeconds", (double) windowEnd.getEpochSecond());
        var reasons = new ArrayList<>(evidence); reasons.add(rule);
        return workflow.offer(new Proposal(UUID.randomUUID(), target, action, source, policy.version(), current, proposed, unit, now, expiry, Map.copyOf(data), List.copyOf(reasons)));
    }
    private Outcome previousOrWaiting(State state, String target, Source source, Instant end) {
        return state.outcome == null ? new Outcome(new Assessment(target, Health.INSUFFICIENT_DATA, source, end, 0, Map.of(), Map.of(), List.of("Waiting for a distinct analysis cycle")), List.of()) : state.outcome;
    }
    private Outcome save(State state, String target, Source source, Instant end, Health health, Map<String, Double> signals, List<String> evidence, List<PolicyChange> changes) {
        state.outcome = new Outcome(new Assessment(target, health, source, end, state.samples.size(), state.baseline, Map.copyOf(signals), List.copyOf(evidence)), List.copyOf(changes)); return state.outcome;
    }
    private static class State {
        Instant last; Instant baselineEnd; int degraded; int healthy; Outcome outcome;
        List<Map<String, Double>> samples = new ArrayList<>(); Map<String, Statistic> baseline = Map.of(); Map<String, Integer> signalCycles = new HashMap<>();
    }
}
