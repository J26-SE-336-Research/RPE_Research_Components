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
        // Deadline v2: healthy time, disjoint-window streaks and chain state start again (state LEARNING).
        java.util.function.Predicate<String> live = k -> !k.startsWith(Source.MOCK + ":");
        healthy.resetIf(live); tracker.forgetIf(live); v2.keySet().removeIf(live);
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

    /** Aggregate-only chain window (no per-request samples): checked and reported, but v2 cannot decide on it. */
    public Outcome chain(ChainWindow window) {
        return chain(window, null, Map.of(), List.of());
    }
    public Outcome chain(ChainWindow window, Map<String, Double> supportingMeasurements, List<String> supportingEvidence) {
        return chain(window, null, supportingMeasurements, supportingEvidence);
    }

    /**
     * Deadline recommendation v2 for one chain window (guide §6). The window must be complete and fresh, as before; the
     * decision itself uses the per-request {@code evidence}. Every proposal goes through the existing approval workflow.
     */
    public Outcome chain(ChainWindow window, ChainEvidence evidence, Map<String, Double> supportingMeasurements, List<String> supportingEvidence) {
        if (window == null || window.chain() == null || window.chain().isBlank() || window.source() == null) throw new IllegalArgumentException("Chain and source are required");
        State state = chains.computeIfAbsent(key(window.chain(), window.source()), ignored -> new State());
        synchronized (state) {
            if (window.end() != null && window.end().isAfter(clock.instant())) {
                reset(state); workflow.withdraw(window.chain(), Action.CHAIN_DEADLINE, window.source(), "Future-dated chain evidence");
                return save(state, window.chain(), window.source(), window.end(), Health.INSUFFICIENT_DATA, Map.of(), List.of("Future-dated window"), List.of());
            }
            if (!acceptCycle(state, window.end())) return previousOrWaiting(state, window.chain(), window.source(), window.end());
            if (!validWindow(window.start(), window.end()) || !window.complete() || window.services().isEmpty()
                    || !number(window.requests()) || window.requests() <= 0
                    || !number(window.deadlineFailures()) || window.deadlineFailures() > window.requests()
                    || !number(window.p99Ms()) || window.p99Ms() <= 0) {
                reset(state); workflow.withdraw(window.chain(), Action.CHAIN_DEADLINE, window.source(), "Incomplete chain evidence");
                return save(state, window.chain(), window.source(), window.end(), Health.INSUFFICIENT_DATA, Map.of(),
                        List.of("Complete chain p99, request/failure counts and an ordered path are required"), List.of());
            }
            var signals = new HashMap<String, Double>(Map.of("p99", window.p99Ms(), "deadlineFailureRate", window.deadlineFailures() / window.requests()));
            Health health = window.health() == null ? Health.INSUFFICIENT_DATA : window.health();
            if (evidence == null || evidence.samples().samples().isEmpty()) {
                return save(state, window.chain(), window.source(), window.end(), health, signals,
                        List.of("Deadline v2 needs per-request samples (gateway traces); an aggregate-only window is reported but not analysed"), List.of());
            }
            return deadlineV2(state, window, health, evidence, signals, supportingMeasurements, supportingEvidence);
        }
    }

    // ---------------------------------------------------------------- deadline v2 ----------------------------------------------------------------

    private AdaptiveConfig.DeadlineSettings deadline = AdaptiveConfig.DeadlineSettings.defaults();
    private AdaptiveConfig.LoadSettings load = AdaptiveConfig.LoadSettings.defaults();
    private AdaptiveConfig.LeverSettings lever = AdaptiveConfig.LeverSettings.defaults();
    private final HealthyLatencyModel healthy = new HealthyLatencyModel();
    private final DisjointWindowTracker tracker = new DisjointWindowTracker();
    private final Map<String, ChainV2> v2 = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public void configureDeadline(AdaptiveConfig.DeadlineSettings deadline, AdaptiveConfig.LoadSettings load, AdaptiveConfig.LeverSettings lever) {
        if (deadline != null) this.deadline = deadline;
        if (load != null) this.load = load;
        if (lever != null) this.lever = lever;
    }
    public AdaptiveConfig.DeadlineSettings deadlineSettings() { return deadline; }

    /** One counted window kept for low-traffic widening. */
    private record CountedWindow(Instant end, long budget, List<LabelledSample> samples) {}
    /** Per-chain v2 state. */
    private static class ChainV2 {
        ChainDeadlineState state = ChainDeadlineState.LEARNING;
        final Deque<CountedWindow> recent = new ArrayDeque<>();
        final List<Instant> proposalTimes = new ArrayList<>();
        DeadlineEvidence evidence; List<String> lines = List.of();
        UUID lastProposalId; Long lastProposedMs; Map<String, Double> lastMeasurements = Map.of(); List<String> lastProposalEvidence = List.of(); String lastRule;
        DeadlineCase lastProposalCase;
        // Phase 4: verification after APPLIED, pause and audit de-duplication.
        UUID trackedApplied; Settling settling; String verification;
        String pausedReason; Instant pauseCauseLastSeen;
        final DeadlineSafety safety = new DeadlineSafety();
        Proportion lastH; String lastAuditedDecision;
    }
    /** An applied change being verified: skip {@code settleLeft} counted windows, then collect {@code verify-windows} at the new budget. */
    private static class Settling {
        final DeadlineCase applied; final long fromMs, toMs; final Proportion hBefore; int settleLeft, verified;
        final List<LabelledSample> pool = new ArrayList<>();
        Settling(DeadlineCase applied, long fromMs, long toMs, Proportion hBefore, int settleLeft) {
            this.applied = applied; this.fromMs = fromMs; this.toMs = toMs; this.hBefore = hBefore; this.settleLeft = settleLeft;
        }
    }
    private AuditLog audit = AuditLog.NONE;
    @Autowired(required = false) public void setAudit(AuditLog audit) { this.audit = audit == null ? AuditLog.NONE : audit; }

    /** Configuration conflict (the stamped budget differs from the last approved policy): the chain is paused. */
    public void pauseChain(String chain, Source source, String reason) {
        State state = chains.computeIfAbsent(key(chain, source), ignored -> new State());
        synchronized (state) {
            ChainV2 v = v2.computeIfAbsent(key(chain, source), ignored -> new ChainV2());
            var before = v.state;
            Policy policy = workflow.current(chain, source);
            pause(v, reason, clock.instant(), chain, source);
            if (v.evidence != null) v.evidence = withState(v.evidence, v.state, "PAUSED");
            if (before != v.state) audit.deadline(AuditLog.DEADLINE_STATE_CHANGED, chain, source, policy == null || policy.deadlineMs() == null ? 0 : policy.deadlineMs(),
                    null, v.state.name(), before + " → " + v.state + ": " + reason);
        }
    }
    private void pause(ChainV2 v, String reason, Instant now, String chain, Source source) {
        if (v.state != ChainDeadlineState.PAUSED) workflow.withdraw(chain, Action.CHAIN_DEADLINE, source, "Chain paused: " + reason);
        v.state = ChainDeadlineState.PAUSED; v.pausedReason = reason; v.pauseCauseLastSeen = now; v.settling = null; v.lastProposedMs = null;
    }

    /** Latest v2 evidence per live chain (MOCK chains excluded), for the API. */
    public Map<String, DeadlineEvidence> deadlineEvidence() {
        var result = new TreeMap<String, DeadlineEvidence>();
        v2.forEach((k, v) -> { if (!k.startsWith(Source.MOCK + ":") && v.evidence != null) result.put(k.substring(k.indexOf(':') + 1), v.evidence); });
        return result;
    }
    public DeadlineEvidence deadlineEvidence(String chain, Source source) { var v = v2.get(key(chain, source)); return v == null ? null : v.evidence; }

    private Outcome deadlineV2(State state, ChainWindow window, Health health, ChainEvidence input, Map<String, Double> signals,
            Map<String, Double> supportingMeasurements, List<String> supportingEvidence) {
        String chain = window.chain(); Source source = window.source(); Instant end = window.end();
        String key = key(chain, source);
        ChainV2 v = v2.computeIfAbsent(key, ignored -> new ChainV2());
        var samples = input.samples().samples();

        // Service load and per-request labels (by each request's own path).
        var services = new TreeSet<String>(); samples.forEach(s -> services.addAll(s.path())); services.removeAll(input.unmonitored());
        var loadResults = ServiceLoadClassifier.classify(services, input.loads(), input.guards(), load);
        var loads = new HashMap<String, ServiceLoad>(); var reasons = new TreeMap<String, String>();
        loadResults.forEach((s, r) -> { loads.put(s, r.load()); reasons.put(s, r.load() + ": " + r.reason()); });
        var labelled = RequestLabeler.label(samples, loads, input.unmonitored());
        int n = labelled.size();
        double oShare = share(labelled, l -> l.label() == RequestLabel.O), stressedShare = share(labelled, l -> l.label() == RequestLabel.STRESSED);
        double unknownShare = share(labelled, l -> l.label() == RequestLabel.UNKNOWN), cutShare = share(labelled, l -> l.sample().cut());
        String cutHint = labelled.stream().filter(l -> l.sample().cut()).collect(java.util.stream.Collectors.groupingBy(l -> l.sample().cutHint(), java.util.stream.Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        var budgets = new TreeSet<Long>(); samples.forEach(s -> budgets.add(s.budgetMs()));
        boolean mixed = budgets.size() > 1; long windowBudget = budgets.first();

        Policy policy = workflow.current(chain, source);
        Long current = policy == null || !number(policy.deadlineMs()) || policy.deadlineMs() <= 0 ? null : Math.round(policy.deadlineMs());

        // Disjoint windows: only these count for learning, persistence and decisions.
        boolean counted = tracker.isDisjoint(key, end, settings.window());
        if (counted) tracker.markCounted(key, end);
        boolean eligible = window.complete() && !mixed && cutShare <= deadline.cutMaxShare();
        HealthyModel model = counted
                ? healthy.update(key, end, labelled, windowBudget, eligible, deadline, settings.baselineSamples(), settings.minimumDeadlineMs(), settings.maximumDeadlineMs())
                : healthy.model(key, deadline, settings.minimumDeadlineMs(), settings.maximumDeadlineMs());
        if (counted && !mixed) {
            v.recent.addLast(new CountedWindow(end, windowBudget, labelled));
            long keep = Math.max(1, deadline.maxWiden().toSeconds() / Math.max(1, settings.window().toSeconds())) + 1;
            while (v.recent.size() > keep) v.recent.removeFirst();
        }
        Proportion hExceed = proportion(v, labelled, RequestLabel.H, windowBudget, end);
        Proportion oExceed = proportion(v, labelled, RequestLabel.O, windowBudget, end);
        boolean hSufficient = hExceed.n() >= settings.minimumChainRequests(), oSufficient = oExceed.n() >= settings.minimumChainRequests();
        Long target = model.targetMs();
        int aStreak, oStreak, dStreak;
        if (counted) {
            aStreak = tracker.streak(key, "A", hSufficient && hExceed.lower() > deadline.eTarget(), end, settings.window());
            oStreak = tracker.streak(key, "O", oShare >= deadline.shareThreshold(), end, settings.window());
            dStreak = tracker.streak(key, "D", target != null && current != null && current > (1 + deadline.deadbandHealthy()) * target, end, settings.window());
        } else { aStreak = tracker.current(key, "A"); oStreak = tracker.current(key, "O"); dStreak = tracker.current(key, "D"); }

        var overloadedOnOPaths = new TreeSet<String>();
        for (var l : labelled) if (l.label() == RequestLabel.O) for (String s : l.sample().path()) if (loads.get(s) == ServiceLoad.OVERLOADED) overloadedOnOPaths.add(s);
        var leverResult = LeverDiagnosis.chain(overloadedOnOPaths, input.loads(), lever, load);
        var oSamples = labelled.stream().filter(l -> l.label() == RequestLabel.O && !l.sample().cut()).toList();
        Double predictedOAtTarget = target == null || oSamples.isEmpty() ? null
                : oSamples.stream().filter(l -> l.sample().durationMs() > target).count() / (double) oSamples.size();

        var pending = workflow.pending(chain, Action.CHAIN_DEADLINE, source);
        boolean pendingRate = services.stream().anyMatch(s -> workflow.pending(s, Action.LEAKY_BUCKET, source).isPresent());
        Instant now = clock.instant();
        v.proposalTimes.removeIf(t -> Duration.between(t, now).compareTo(Duration.ofHours(1)) >= 0);
        ChainDeadlineState stateBefore = v.state;

        // ---- Phase 4: pause, applied-change detection and verification (guide §6.6, §6.7) ----
        if (v.state == ChainDeadlineState.PAUSED && v.pauseCauseLastSeen != null && Duration.between(v.pauseCauseLastSeen, now).compareTo(deadline.pauseClear()) >= 0) {
            v.state = model.ready() ? ChainDeadlineState.READY : ChainDeadlineState.LEARNING; v.pausedReason = null;
        }
        if (counted && "REJECTING_MIDDLEWARE".equals(cutHint) && cutShare > deadline.cutMaxShare()) {
            audit.deadline(AuditLog.DEADLINE_CUT_DETECTED, chain, source, current == null ? windowBudget : current, null, cutHint,
                    String.format(Locale.ROOT, "%.1f%% of requests cut by another timer (%s); not used for learning", 100 * cutShare, cutHint));
            String reason = v.safety.rejectingMiddleware(now);
            if (reason != null) pause(v, reason, now, chain, source);
        }
        if (v.lastProposalId != null && !v.lastProposalId.equals(v.trackedApplied) && v.state != ChainDeadlineState.PAUSED) {
            PolicyChange applied = null;
            try { applied = workflow.find(v.lastProposalId); } catch (IllegalArgumentException ignored) { /* trimmed history */ }
            if (applied != null && applied.delivery() == Delivery.APPLIED) {
                v.trackedApplied = applied.proposal().id();
                long from = Math.round(applied.proposal().currentValue()), to = Math.round(applied.proposal().proposedValue());
                DeadlineCase appliedCase = v.lastProposalCase != null ? v.lastProposalCase : to > from ? DeadlineCase.A_RAISE : DeadlineCase.D_TIGHTEN;
                v.settling = new Settling(appliedCase, from, to, v.lastH, deadline.settleWindows());
                v.state = ChainDeadlineState.SETTLING; v.verification = null;
                String reason = v.safety.applied(from, to, now);
                if (reason != null) pause(v, reason, now, chain, source);
            }
        }
        boolean revert = false; long revertTo = 0;
        if (counted && v.state == ChainDeadlineState.SETTLING && v.settling != null && !mixed && windowBudget == v.settling.toMs) {
            var s = v.settling;
            if (s.settleLeft > 0) s.settleLeft--;
            else {
                for (var l : labelled) if (!l.sample().cut()) s.pool.add(l);
                if (++s.verified >= deadline.verifyWindows()) {
                    var hAfter = proportionOf(s.pool, RequestLabel.H); var oAfter = proportionOf(s.pool, RequestLabel.O);
                    boolean cleared = share(s.pool, l -> l.label() == RequestLabel.O) < deadline.shareThreshold();
                    var result = DeadlineVerifier.verify(s.applied, s.hBefore, hAfter, oAfter, cleared, deadline, settings.minimumChainRequests());
                    v.verification = result.result() + " after " + s.applied + " " + s.fromMs + " → " + s.toMs + " ms: " + result.detail();
                    audit.deadline(AuditLog.DEADLINE_VERIFICATION, chain, source, s.toMs, null, result.result().name(), v.verification);
                    v.settling = null;
                    if (result.result() == DeadlineVerifier.Result.FAIL) {
                        String reason = v.safety.verificationFailed(now);
                        if (reason != null) pause(v, reason, now, chain, source);
                        else { revert = true; revertTo = s.fromMs; v.state = ChainDeadlineState.READY; }
                    } else v.state = model.ready() ? ChainDeadlineState.READY : ChainDeadlineState.LEARNING;
                }
            }
        }

        var changes = new ArrayList<PolicyChange>();
        DeadlinePolicy.DeadlineDecision decision;
        if (current == null) decision = DeadlinePolicy.hold("WAITING_FOR_POLICY", "waiting for the current route deadline");
        else if (counted && v.state == ChainDeadlineState.PAUSED) decision = DeadlinePolicy.hold("PAUSED", v.pausedReason);
        else if (counted && v.state == ChainDeadlineState.SETTLING) decision = DeadlinePolicy.hold("SETTLING", "verifying the applied change before deciding again");
        else if (counted && revert) {
            final long back = revertTo;
            decision = new DeadlinePolicy.DeadlineDecision(back > current ? DeadlineCase.A_RAISE : DeadlineCase.D_TIGHTEN, back, "VERIFICATION_FAILED", false,
                    healthy.predictedExceed(key, back), null, "VERIFICATION_FAILED: revert to " + back + " ms (" + v.verification + ")");
        }
        else if (!counted) {
            decision = DeadlinePolicy.hold("NOT_COUNTED", "overlaps the last counted window");
            // Keep a pending proposal fresh between counted windows while its basis still holds.
            if (pending.isPresent() && pending.get().proposal().id().equals(v.lastProposalId) && v.lastProposedMs != null
                    && pending.get().decision() == Decision.PENDING_APPROVAL && eligible && windowBudget == current) {
                changes.add(offer(chain, Action.CHAIN_DEADLINE, source, policy, current, v.lastProposedMs, "milliseconds", end,
                        v.lastMeasurements, v.lastRule, v.lastProposalEvidence));
            }
        } else {
            var ctx = new DeadlinePolicy.ChainContext(false, null, true, window.complete(), mixed, unknownShare, cutShare, cutHint, pendingRate,
                    !cooldown(chain, Action.CHAIN_DEADLINE, source), v.proposalTimes.size(), model, current, oShare, stressedShare,
                    hExceed, oExceed, hSufficient, oSufficient, aStreak, oStreak, dStreak, leverResult.lever(), predictedOAtTarget,
                    candidate -> healthy.predictedExceed(key, candidate),
                    pending.filter(p -> Math.round(p.proposal().currentValue()) == current).map(p -> Math.round(p.proposal().proposedValue())).orElse(null));
            decision = DeadlinePolicy.decide(ctx, deadline, settings.maximumDeadlineMs());
        }

        // Advisory whenever the overload is a rate or capacity problem (also when the deadline signal already fires).
        String advisory = oShare >= deadline.shareThreshold() && (leverResult.lever() == Lever.RATE || leverResult.lever() == Lever.CAPACITY)
                ? "Consider the " + (leverResult.lever() == Lever.RATE ? "inbound rate" : "capacity") + " at " + String.join(", ", overloadedOnOPaths) + " (" + leverResult.reason() + ")" : null;
        String headline = headline(decision, current, model, hExceed, oExceed, windowBudget);
        var lines = new ArrayList<String>();
        lines.add(headline);
        lines.add(modelLine(model));
        lines.add(String.format(Locale.ROOT, "Requests %d: overloaded %.1f%%, stressed %.1f%%, unknown %.1f%%, cut %.1f%%%s; healthy exceed %s, overloaded exceed %s",
                n, 100 * oShare, 100 * stressedShare, 100 * unknownShare, 100 * cutShare, cutHint == null ? "" : " (" + cutHint + ")", proportionText(hExceed), proportionText(oExceed)));
        if (leverResult.lever() != Lever.NONE) lines.add("Lever: " + leverResult.lever() + (leverResult.inferred() ? " (inferred)" : "") + "; " + leverResult.reason());
        if (advisory != null) lines.add("Advisory: " + advisory);
        if (counted) lines.add("Counted disjoint window; streaks: over-signalled " + aStreak + ", overloaded " + oStreak + ", too long " + dStreak
                + " (need " + deadline.persistenceWindows() + ")");
        lines.add("Every deadline change requires human approval; the chain p99 is pooled from complete request durations");

        if (counted && current != null) {
            if (decision.proposes()) {
                var measurements = new HashMap<String, Double>(supportingMeasurements);
                measurements.put("requestCount", window.requests()); measurements.put("deadlineFailureCount", window.deadlineFailures());
                measurements.put("recentP99Ms", window.p99Ms());
                put(measurements, "baselineP99Ms", model.thMs()); put(measurements, "healthyTimeMs", model.thMs());
                put(measurements, "safetyMargin", model.margin()); put(measurements, "targetMs", target == null ? null : target.doubleValue());
                measurements.put("healthyExceedRate", hExceed.p()); measurements.put("healthyExceedLower", hExceed.lower()); measurements.put("healthyExceedUpper", hExceed.upper());
                measurements.put("healthySamples", (double) hExceed.n());
                measurements.put("overloadExceedRate", oExceed.p()); measurements.put("overloadExceedLower", oExceed.lower()); measurements.put("overloadExceedUpper", oExceed.upper());
                measurements.put("overloadSamples", (double) oExceed.n());
                measurements.put("overloadShare", oShare); measurements.put("stressedShare", stressedShare); measurements.put("unknownShare", unknownShare); measurements.put("cutShare", cutShare);
                put(measurements, "predictedHealthyExceed", decision.predictedH()); put(measurements, "predictedOverloadExceed", decision.predictedO());
                var proposalEvidence = new ArrayList<String>();
                proposalEvidence.add(headline);
                proposalEvidence.add(String.format(Locale.ROOT, "Impact preview at %d ms: about %s of healthy requests would exceed it%s", decision.proposedMs(),
                        decision.predictedH() == null ? "n/a" : pctText(decision.predictedH()),
                        decision.predictedO() == null ? "" : ", and at least " + pctText(decision.predictedO()) + " of overloaded requests would be signalled"));
                proposalEvidence.addAll(lines.subList(1, lines.size()));
                proposalEvidence.addAll(supportingEvidence);
                var change = offer(chain, Action.CHAIN_DEADLINE, source, policy, current, decision.proposedMs(), "milliseconds", end,
                        Map.copyOf(measurements), decision.rule(), proposalEvidence);
                changes.add(change);
                if (!change.proposal().id().equals(v.lastProposalId) || pending.isEmpty()) v.proposalTimes.add(now);
                v.lastProposalId = change.proposal().id(); v.lastProposedMs = decision.proposedMs(); v.lastProposalCase = decision.decisionCase();
                v.lastMeasurements = Map.copyOf(measurements); v.lastProposalEvidence = List.copyOf(proposalEvidence); v.lastRule = decision.rule();
            } else if (decision.decisionCase() == DeadlineCase.C_KEEP_FIRING) {
                workflow.withdrawPendingIncrease(chain, source, current, "Case C: the overload signal fires; the pending increase is withdrawn");
            } else if (pending.isPresent() && WITHDRAW_ON.contains(decision.holdReason())) {
                workflow.withdraw(chain, Action.CHAIN_DEADLINE, source, "Conditions no longer support the change (" + decision.holdReason() + ")");
            }
        }
        // A counted window that no longer proposes the same value stops the refresh: an outdated proposal then goes stale.
        if (counted && !decision.proposes()) v.lastProposedMs = null;
        boolean stillPending = workflow.pending(chain, Action.CHAIN_DEADLINE, source).isPresent();
        if (v.state != ChainDeadlineState.PAUSED && v.state != ChainDeadlineState.SETTLING) {
            v.state = stillPending ? ChainDeadlineState.PROPOSED : model.ready() ? ChainDeadlineState.READY : ChainDeadlineState.LEARNING;
        }
        if (counted && v.state != ChainDeadlineState.SETTLING) v.lastH = hExceed; // the "before" of the next applied change
        if (!counted && v.evidence != null && v.evidence.state() != v.state) v.evidence = withState(v.evidence, v.state, v.evidence.holdReason());
        if (counted || v.evidence == null) {
            v.evidence = new DeadlineEvidence(decision.decisionCase(), decision.holdReason(), current == null ? windowBudget : current,
                    decision.proposedMs(), model, hExceed, oExceed, oShare, stressedShare, unknownShare, cutShare, cutHint,
                    decision.predictedH(), decision.predictedO(), leverResult.lever(), leverResult.inferred(), reasons, v.state, v.verification,
                    samples.stream().map(DeadlineSample::traceId).limit(5).toList(), advisory);
            v.lines = List.copyOf(lines);
        }
        // ---- audit (append-only; only on change to avoid noise) ----
        double auditCurrent = current == null ? windowBudget : current;
        if (stateBefore != v.state) audit.deadline(AuditLog.DEADLINE_STATE_CHANGED, chain, source, auditCurrent, null, v.state.name(),
                stateBefore + " → " + v.state + (v.pausedReason == null || v.state != ChainDeadlineState.PAUSED ? "" : ": " + v.pausedReason));
        if (counted) {
            String decisionKey = decision.decisionCase() + ":" + decision.holdReason() + ":" + decision.proposedMs();
            if (!decisionKey.equals(v.lastAuditedDecision)) {
                v.lastAuditedDecision = decisionKey;
                audit.deadline(AuditLog.DEADLINE_DECISION, chain, source, auditCurrent, decision.proposedMs() == null ? null : decision.proposedMs().doubleValue(),
                        decision.decisionCase() + (decision.holdReason() == null ? "" : ":" + decision.holdReason()), v.evidence);
            }
            if (healthy.consumeRelearned(key)) audit.deadline(AuditLog.DEADLINE_HEALTHY_RELEARNED, chain, source, auditCurrent, null, model.status(),
                    "Healthy time re-learned after drift: " + modelLine(model));
        }
        signals.put("healthyExceedRate", hExceed.p()); signals.put("overloadShare", oShare); signals.put("stressedShare", stressedShare);
        var evidenceLines = new ArrayList<>(v.lines); evidenceLines.addAll(supportingEvidence);
        Map<String, Statistic> baseline = model.thMs() == null ? Map.of() : Map.of("p99", new Statistic(model.thMs(), model.sigmaMs() == null ? 0 : model.sigmaMs()));
        state.outcome = new Outcome(new Assessment(chain, health, source, end, model.windows(), baseline, Map.copyOf(signals), List.copyOf(evidenceLines)), List.copyOf(changes));
        return state.outcome;
    }

    /** Holds that mean "the conditions for the pending proposal are gone": the pending proposal is withdrawn. */
    private static final Set<String> WITHDRAW_ON = Set.of("IN_BAND", "STRESS_GATE", "LEVER_RATE", "LEVER_CAPACITY", "DEADLINE_NOT_THE_LEVER",
            "PREVIEW_FAILED", "INCONSISTENT", "CENSORED_OTHER", "UNKNOWN_SHARE", "AT_LIMIT");

    /** Exceed rate of a label at the current budget; widened with earlier counted windows (same budget) when traffic is low. */
    private Proportion proportion(ChainV2 v, List<LabelledSample> window, RequestLabel label, long budget, Instant end) {
        var pool = new ArrayList<LabelledSample>();
        for (var l : window) if (l.label() == label && !l.sample().cut()) pool.add(l);
        if (pool.size() < settings.minimumChainRequests()) {
            var earlier = new ArrayList<>(v.recent); java.util.Collections.reverse(earlier);
            for (var w : earlier) {
                if (w.end().equals(end) || w.budget() != budget || Duration.between(w.end(), end).compareTo(deadline.maxWiden()) > 0) continue;
                for (var l : w.samples()) if (l.label() == label && !l.sample().cut()) pool.add(l);
                if (pool.size() >= settings.minimumChainRequests()) break;
            }
        }
        int events = (int) pool.stream().filter(l -> l.sample().expired()).count();
        return RobustStatistics.wilsonOneSided(events, pool.size(), deadline.z(), deadline.designEffect());
    }
    private static DeadlineEvidence withState(DeadlineEvidence e, ChainDeadlineState state, String holdReason) {
        boolean paused = state == ChainDeadlineState.PAUSED;
        return new DeadlineEvidence(paused ? DeadlineCase.HOLD : e.decisionCase(), holdReason, e.currentMs(), paused ? null : e.proposedMs(), e.model(),
                e.hExceed(), e.oExceed(), e.oShare(), e.stressedShare(), e.unknownShare(), e.cutShare(), e.cutHint(), e.predictedHExceed(),
                e.predictedOExceed(), e.lever(), e.leverInferred(), e.serviceLoadReasons(), state, e.verification(), e.exampleTraceIds(), e.advisory());
    }
    private Proportion proportionOf(List<LabelledSample> pool, RequestLabel label) {
        int n = 0, x = 0;
        for (var l : pool) if (l.label() == label) { n++; if (l.sample().expired()) x++; }
        return RobustStatistics.wilsonOneSided(x, n, deadline.z(), deadline.designEffect());
    }
    private static double share(List<LabelledSample> all, java.util.function.Predicate<LabelledSample> p) {
        return all.isEmpty() ? 0 : all.stream().filter(p).count() / (double) all.size();
    }
    private static void put(Map<String, Double> map, String key, Double value) { if (value != null && Double.isFinite(value)) map.put(key, value); }
    private static String pctText(double v) { return String.format(Locale.ROOT, "%.1f%%", 100 * v); }
    private static String proportionText(Proportion p) {
        return p.n() == 0 ? "n/a (0 requests)" : String.format(Locale.ROOT, "%s of %d [%s, %s]", pctText(p.p()), p.n(), pctText(p.lower()), pctText(p.upper()));
    }
    private static String modelLine(HealthyModel m) {
        String th = m.thMs() == null ? "unknown" : Math.round(m.thMs()) + " ms";
        return "Healthy time T_h " + th + " (" + m.status() + (m.ready() ? ", ready" : ", learning") + "; " + m.windows() + " windows, " + m.requests()
                + " healthy requests" + (m.coverageGap() ? ", coverage gap: T_h frozen" : "") + ")"
                + (m.targetMs() == null ? "" : String.format(Locale.ROOT, "; target D* %d ms (margin %.0f%%)", m.targetMs(), 100 * m.margin()));
    }
    private static String headline(DeadlinePolicy.DeadlineDecision d, Long current, HealthyModel m, Proportion h, Proportion o, long budget) {
        long now = current == null ? budget : current;
        return switch (d.decisionCase()) {
            case A_RAISE -> "CENSORED".equals(m.status())
                    ? String.format(Locale.ROOT, "Raise the deadline %d → %d ms: %s of healthy requests exceed %d ms, so the healthy time is censored at the deadline (its p99 is at least the deadline)",
                            now, d.proposedMs(), pctText(h.p()), now)
                    : String.format(Locale.ROOT, "Raise the deadline %d → %d ms: healthy requests are over-signalled (%s exceed it)", now, d.proposedMs(), pctText(h.p()));
            case B_LOWER_OVERLOAD -> String.format(Locale.ROOT, "Lower the deadline %d → %d ms: services are overloaded but only %s of their requests are signalled",
                    now, d.proposedMs(), pctText(o.p()));
            case D_TIGHTEN -> String.format(Locale.ROOT, "Tighten the deadline %d → %d ms: the chain is healthy and the deadline is longer than 1.25 × D*", now, d.proposedMs());
            case C_KEEP_FIRING -> String.format(Locale.ROOT, "Keep the deadline %d ms: the overload signal fires (%s of overloaded requests are signalled)", now, pctText(o.p()));
            case HOLD -> "Hold the deadline at " + now + " ms (" + d.holdReason() + "): " + d.rule().substring(d.rule().indexOf(':') + 2);
        };
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
