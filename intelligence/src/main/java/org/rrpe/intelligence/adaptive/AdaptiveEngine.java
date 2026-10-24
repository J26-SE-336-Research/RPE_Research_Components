package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The decision engine of C4. It compares each service and chain with a baseline learned from its own healthy traffic,
 * waits until a problem lasts, and then recommends small, bounded changes. Recovery is gradual.
 */
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
    /** We evaluate a window this long after it ends, because Tempo needs a moment to store the traces. */
    @Autowired(required = false)
    void setEvaluationDelay(@org.springframework.beans.factory.annotation.Value("${rrpe.adaptive.evaluation-delay:0s}") Duration delay) {
        if (delay != null && !delay.isNegative()) evaluationDelay = delay;
    }
    /** Forgets everything learned from live data, for example after a new release. */
    public void resetBaselines() {
        services.keySet().removeIf(k -> !k.startsWith(Source.MOCK + ":"));
        chains.keySet().removeIf(k -> !k.startsWith(Source.MOCK + ":"));
        // Deadline state starts again: healthy time, window streaks and chain state.
        java.util.function.Predicate<String> live = k -> !k.startsWith(Source.MOCK + ":");
        healthy.resetIf(live); tracker.forgetIf(live); v2.keySet().removeIf(live);
        rates.keySet().removeIf(live); // rate state is learned again too
    }
    private Duration freshness() { return settings.cycle().multipliedBy(2).plus(evaluationDelay); }
    private String key(String target, Source source) { return source + ":" + target; }

    /** A service window without queue data. The rate decision is then UNKNOWN. */
    public Outcome service(ServiceWindow window) { return service(window, null); }

    /** One service window together with its queue data, used by the rate logic. */
    public Outcome service(ServiceWindow window, ServiceLoadSnapshot load) {
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
                // The rate logic does not wait for this baseline. It learns its own normal app time, so a rate
                // that is too low from the start can still be fixed.
                var learningEvidence = new ArrayList<String>(List.of("Learning a stable Healthy reference; current samples=" + state.samples.size()));
                var learningChanges = new ArrayList<PolicyChange>();
                Policy learningPolicy = workflow.current(window.service(), window.source());
                if (learningPolicy != null) bucket(window, load, learningPolicy, false, learningEvidence, learningChanges, null);
                return save(state, window.service(), window.source(), window.end(), phase, values, learningEvidence, learningChanges);
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
            // Stressed for two cycles already counts as lasting; a critical overload still waits two cycles.
            boolean protective = health == Health.STRESSED || health == Health.OVERLOADED;
            boolean persistentPolicy = protective && (persistent > 0 || state.degraded >= settings.persistence());
            evidence.add("Counts normalized by " + window.originalRequests() + " original requests; healthy cycles=" + state.healthy);
            List<PolicyChange> changes = new ArrayList<>();
            Policy policy = workflow.current(window.service(), window.source());
            // Errors above the baseline tell the rate logic that the app itself struggles. 503 rejections are not errors.
            Statistic errorBase = state.baseline.get("errorRate");
            boolean errorsHigh = errorBase != null
                    && (values.get("errorRate") - errorBase.median()) / variation(errorBase, "errorRate") >= settings.stressedMad();
            boolean retryProposed = false;
            if (policy == null) evidence.add("Waiting for current Component 3 policy reference");
            else {
                JointContext jc = joint.enabled() ? new JointContext() : null;
                var diagnosis = bucket(window, load, policy, errorsHigh, evidence, changes, jc);
                retryProposed = retry(window, state, policy, health, persistentPolicy, diagnosis, evidence, changes);
                if (jc != null) jointStep(jc, window, policy, health, diagnosis, retryProposed, evidence, changes);
            }
            // The rate logic withdraws its own proposals. A retry proposal that was not renewed follows the health state.
            if (!protective && health != Health.HEALTHY && !retryProposed) workflow.withdraw(window.service(), Action.RETRY_BUDGET, window.source(), "Condition is not yet persistent");
            if (health == Health.HEALTHY && state.healthy >= settings.recoveryWindows()) refreshBaseline(state, values);
            return save(state, window.service(), window.source(), window.end(), health, values, evidence, changes);
        }
    }

    // ---------- leaky-bucket rate ----------

    private AdaptiveConfig.RateSettings rate = AdaptiveConfig.RateSettings.defaults();
    private AdaptiveConfig.RetrySettings retrySettings = AdaptiveConfig.RetrySettings.defaults();
    private AdaptiveConfig.BaselineSettings baselineSettings = AdaptiveConfig.BaselineSettings.defaults();
    private AdaptiveConfig.AutoSettings autoSettings = AdaptiveConfig.AutoSettings.defaults();
    private final Map<String, RateState> rates = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public void configureRate(AdaptiveConfig.RateSettings rate, AdaptiveConfig.RetrySettings retry, AdaptiveConfig.BaselineSettings baseline,
            AdaptiveConfig.AutoSettings auto) {
        if (rate != null) this.rate = rate;
        if (retry != null) this.retrySettings = retry;
        if (baseline != null) this.baselineSettings = baseline;
        if (auto != null) this.autoSettings = auto;
    }
    RateState rateState(String service, Source source) { return rates.computeIfAbsent(key(service, source), ignored -> new RateState()); }

    private AdaptiveConfig.AutoApproval autoApproval = AdaptiveConfig.AutoApproval.none();
    /** The changes that may be approved automatically. Empty by default, so every recommendation waits for a reviewer. */
    @Autowired(required = false)
    public void configureAutoApproval(AdaptiveConfig.AutoApproval autoApproval) { if (autoApproval != null) this.autoApproval = autoApproval; }

    // ---------- joint recalibration of rate and deadline ----------

    private AdaptiveConfig.JointSettings joint = AdaptiveConfig.JointSettings.defaults();
    /** Joint recalibration is off by default. Then none of the joint steps do anything. */
    @Autowired(required = false)
    public void configureJoint(AdaptiveConfig.JointSettings joint) { if (joint != null) this.joint = joint; }
    private void recordCalibrationRates(ChainV2 v, Collection<String> services, Source chainSource) {
        Source serviceSource = chainSource == Source.MOCK ? Source.MOCK : Source.PROMETHEUS;
        var rates = new HashMap<String, Double>();
        for (String s : services) { Policy p = workflow.current(s, serviceSource); if (p != null && number(p.bucketRate()) && p.bucketRate() > 0) rates.put(s, p.bucketRate()); }
        v.calibrationRates.addLast(Map.copyOf(rates)); while (v.calibrationRates.size() > settings.baselineSamples()) v.calibrationRates.removeFirst();
    }
    /** The median rate of the service in the windows the chain deadline was learned from, or null when unknown. */
    static Double calibrationRate(Deque<Map<String, Double>> windows, String service) {
        double[] r = windows.stream().map(w -> w.get(service)).filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
        return r.length == 0 ? null : RobustStatistics.median(r);
    }
    /** The services each analysed chain passes through. */
    private record ChainPath(String chain, Source source, List<String> services) {}
    private final Map<String, ChainPath> chainPaths = new ConcurrentHashMap<>();

    /** The last deadline we saw for a chain, both from the policy and from the budget the gateway stamps. */
    private static final class BudgetWatch { Long policyMs, stampedMs; Instant policyChangedAt, stampedChangedAt; }
    private final Map<String, BudgetWatch> budgets = new ConcurrentHashMap<>();
    private void watchPolicyBudget(String chain, Source source, Instant now) {
        Policy p = workflow.current(chain, source);
        if (p == null || !number(p.deadlineMs())) return;
        var w = budgets.computeIfAbsent(key(chain, source), ignored -> new BudgetWatch());
        long ms = Math.round(p.deadlineMs());
        synchronized (w) { if (w.policyMs != null && w.policyMs != ms) w.policyChangedAt = now; w.policyMs = ms; }
    }
    private void watchStampedBudget(String chain, Source source, Long ms, Instant now) {
        var w = budgets.computeIfAbsent(key(chain, source), ignored -> new BudgetWatch());
        synchronized (w) { if (ms == null || w.stampedMs != null && !w.stampedMs.equals(ms)) w.stampedChangedAt = now; if (ms != null) w.stampedMs = ms; }
    }
    /** How long a deadline change needs before the chain is settled again: two windows plus the cooldown. */
    private Duration settlePeriod() { return settings.window().multipliedBy(2).plus(settings.cooldown()); }

    /**
     * Why a chain through this service has a deadline that is not settled yet, or null when all are settled. While a
     * deadline is unsettled we do not change rates automatically, so the two never move at the same time.
     * A deadline is unsettled when the chain is still learning, has a proposal or is settling, when a raise is building,
     * or when anyone changed the deadline within the settle period.
     */
    private String unsettledDeadline(String service, Source source) {
        Instant now = clock.instant(); Duration settle = settlePeriod();
        for (var path : chainPaths.values()) {
            if ((path.source() == Source.MOCK) != (source == Source.MOCK) || !path.services().contains(service)) continue;
            String chain = path.chain(), k = key(chain, path.source());
            ChainV2 v = v2.get(k);
            ChainDeadlineState state = v == null ? ChainDeadlineState.LEARNING : v.state;
            if (state == ChainDeadlineState.LEARNING || state == ChainDeadlineState.PROPOSED || state == ChainDeadlineState.SETTLING)
                return chain + " is " + state;
            if (workflow.pending(chain, Action.CHAIN_DEADLINE, path.source()).isPresent()) return chain + " has a pending deadline proposal";
            String recent = recentDeadlineChange(path, v, now, settle);
            if (recent != null) return recent;
        }
        return null;
    }
    /** A raise that is building, or a recent deadline change on this chain. Null when there is none. */
    private String recentDeadlineChange(ChainPath path, ChainV2 v, Instant now, Duration settle) {
        String chain = path.chain(), k = key(chain, path.source());
        if (v != null && v.raiseBuilding) return chain + ": healthy requests exceed the deadline (a raise is building)";
        watchPolicyBudget(chain, path.source(), now);
        var w = budgets.get(k);
        Instant applied = workflow.lastApplied(chain, Action.CHAIN_DEADLINE, path.source());
        for (Instant changed : new Instant[]{applied, w == null ? null : w.policyChangedAt, w == null ? null : w.stampedChangedAt}) {
            if (changed != null && Duration.between(changed, now).compareTo(settle) < 0)
                return chain + ": the deadline changed " + Duration.between(changed, now).toSeconds() + " s ago (settle period " + settle.toSeconds() + " s)";
        }
        return null;
    }

    /** Holds a possible joint change during one service cycle. It is decided after the retry rule. */
    private static final class JointContext { JointCandidate candidate; }
    /** A healthy rate change that may become a joint card. offerNormal makes the normal rate proposal instead. */
    private record JointCandidate(double current, double next, Map<String, Double> inputs, String rule, String trigger, Runnable offerNormal) {}

    /**
     * Decides about a joint card after the rate and retry rules of a service window. A normal recommendation always
     * comes first: a waiting joint card is withdrawn as soon as one is needed, and a joint card is only made when nothing
     * else is needed or pending. It starts from a healthy rate step that reaches its target, or from a changed healthy
     * target of the service.
     */
    private void jointStep(JointContext jc, ServiceWindow window, Policy policy, Health health, RateDiagnosis.Diagnosis diagnosis, boolean retryProposed,
            List<String> evidence, List<PolicyChange> changes) {
        String service = window.service(); Source source = window.source();
        RateState rs = rateState(service, source);
        Double target = policy.healthyBucketTarget();
        if (target != null && rs.seenHealthyTarget != null && !target.equals(rs.seenHealthyTarget)) rs.healthyTargetChange = target;
        if (target != null) rs.seenHealthyTarget = target;
        if (rs.healthyTargetChange != null && (!number(policy.bucketRate()) || Math.abs(policy.bucketRate() - rs.healthyTargetChange) < 1e-9)) rs.healthyTargetChange = null;
        var candidate = jc.candidate;
        String needed = retryProposed ? "a retry change" : health == Health.STRESSED || health == Health.OVERLOADED ? "the service is " + health
                : diagnosis == RateDiagnosis.Diagnosis.APP_OVERLOADED || diagnosis == RateDiagnosis.Diagnosis.RATE_TOO_LOW ? "rate v2 " + diagnosis : null;
        if (needed != null && workflow.activeJoint(service, source).isPresent()) {
            workflow.withdrawJoint(service, source, "A normal recommendation comes first: " + needed);
            evidence.add("Joint recommendation withdrawn: " + needed + " (the normal recommendation comes first)");
        }
        boolean normalRateNow = changes.stream().anyMatch(c -> c.proposal().target().equals(service) && c.proposal().action() == Action.LEAKY_BUCKET
                && !workflow.isJointPart(c.proposal().id()));
        if (candidate == null && rs.healthyTargetChange != null && !normalRateNow && number(policy.bucketRate()))
            candidate = new JointCandidate(policy.bucketRate(), rs.healthyTargetChange, Map.of(), "HEALTHY TARGET: the service's healthy target changed to "
                    + fmt(rs.healthyTargetChange) + " req/s", "the healthy target changed", () -> {});
        if (candidate == null) return;
        if (needed != null || health != Health.HEALTHY || diagnosis != RateDiagnosis.Diagnosis.HEALTHY) { candidate.offerNormal().run(); return; }
        if (!tryJoint(candidate, window, policy, evidence, changes)) candidate.offerNormal().run();
    }

    /** Makes or refreshes the joint card. Returns false when no joint card applies, so the rate goes alone. */
    private boolean tryJoint(JointCandidate c, ServiceWindow window, Policy policy, List<String> evidence, List<PolicyChange> changes) {
        String service = window.service(); Source source = window.source(); Instant now = clock.instant();
        for (Action a : List.of(Action.LEAKY_BUCKET, Action.RETRY_BUDGET)) {
            var p = workflow.pending(service, a, source);
            if (p.isPresent() && !workflow.isJointPart(p.get().proposal().id())) return noJoint(evidence, "a normal " + a + " proposal is pending for " + service);
        }
        Double q = rateState(service, source).healthyQueue(Math.max(1, Math.min(settings.baselineSamples(), 5)));
        if (q == null) return noJoint(evidence, "the healthy queue depth of " + service + " is not learned yet");
        var paths = chainPaths.values().stream().filter(p -> (p.source() == Source.MOCK) == (source == Source.MOCK) && p.services().contains(service))
                .sorted(Comparator.comparing(ChainPath::chain)).toList();
        if (paths.isEmpty()) return noJoint(evidence, "no analysed chain passes through " + service);
        UUID own = workflow.activeJoint(service, source).orElse(null);
        var plans = new ArrayList<HealthyRecalibration.Plan>(); var pathOf = new HashMap<String, ChainPath>();
        for (var path : paths) {
            ChainV2 v = v2.get(key(path.chain(), path.source()));
            if (v == null || v.evidence == null) return noJoint(evidence, path.chain() + " has no per-request deadline analysis yet");
            var pend = workflow.pending(path.chain(), Action.CHAIN_DEADLINE, path.source());
            boolean ownPart = pend.isPresent() && own != null && own.equals(workflow.jointOf(pend.get().proposal().id()));
            ChainDeadlineState state = ownPart && v.state == ChainDeadlineState.PROPOSED ? ChainDeadlineState.READY : v.state;
            Policy cp = workflow.current(path.chain(), path.source());
            Long d = cp == null || !number(cp.deadlineMs()) ? null : Math.round(cp.deadlineMs());
            var model = v.evidence.model();
            var view = new HealthyRecalibration.ChainView(path.chain(), state, v.evidence.decisionCase(), v.evidence.holdReason(), v.streaksBuilding,
                    v.evidence.oShare(), v.evidence.stressedShare(), v.evidence.lever(), pend.isPresent() && !ownPart,
                    recentDeadlineChange(path, v, now, settlePeriod()), model == null ? null : model.thMs(), model == null ? null : model.margin(), d);
            String why = HealthyRecalibration.notReady(view, deadline);
            if (why != null) return noJoint(evidence, why);
            Double rOld = calibrationRate(v.calibrationRates, service);
            plans.add(HealthyRecalibration.plan(view, new HealthyRecalibration.RateMove(service, q, rOld == null ? c.current() : rOld, c.current(), c.next()),
                    deadline, settings, joint));
            pathOf.put(path.chain(), path);
        }
        var passing = plans.stream().filter(HealthyRecalibration.Plan::passes).toList();
        if (passing.isEmpty()) { evidence.add(plans.get(0).text()); return false; }
        String head = String.format(Locale.ROOT, "Joint recommendation (one decision, %s): %s rate %s → %s req/s together with the deadline of %s. "
                        + "Approving or rejecting any card of it decides all of its cards; it is never approved automatically",
                c.trigger(), service, fmt(c.current()), fmt(c.next()), passing.stream().map(HealthyRecalibration.Plan::chain).collect(java.util.stream.Collectors.joining(", ")));
        var rateMeasurements = new HashMap<String, Double>(c.inputs()); rateMeasurements.putAll(passing.get(0).measurements());
        var rateEvidence = new ArrayList<String>(List.of(head, c.rule())); passing.forEach(p -> rateEvidence.add(p.text()));
        var parts = new ArrayList<Proposal>();
        parts.add(proposal(service, Action.LEAKY_BUCKET, source, policy, c.current(), c.next(), "requests/second", window.end(), rateMeasurements, rateEvidence));
        for (var p : passing) {
            var path = pathOf.get(p.chain());
            parts.add(proposal(p.chain(), Action.CHAIN_DEADLINE, path.source(), workflow.current(p.chain(), path.source()), p.currentMs(), p.proposedMs(),
                    "milliseconds", window.end(), p.measurements(), List.of(head, p.text())));
        }
        changes.addAll(workflow.offerJoint(parts));
        evidence.add(head);
        return true;
    }
    private static boolean noJoint(List<String> evidence, String reason) {
        evidence.add("Joint recommendation not made: " + reason + " (normal recommendations unchanged)");
        return false;
    }

    /** Automatic mode: some changes may be approved automatically and the data is live. Simulations are never automatic. */
    private boolean automaticMode(Source source) { return !autoApproval.kinds().isEmpty() && source != Source.MOCK; }

    /**
     * Offers a rate or retry change. The guard may approve it automatically (reviewer c4-auto). In automatic mode rates and
     * retries never wait for a person: a change the guard refuses is held, shown as the first evidence line, and tried
     * again next cycle. Returns null when held. Without automatic approval, and for simulations, a refused change waits
     * for a person.
     */
    private PolicyChange offerAuto(String service, Action action, Source source, Policy policy, double current, double proposed, String unit,
            Instant windowEnd, Map<String, Double> measurements, String rule, List<String> evidence, String kind, Double floor, Double ceiling,
            boolean persistent, boolean undo, boolean dataComplete) {
        RateState rs = rateState(service, source);
        Review auto = null; String guard = null;
        if (kind != null && !autoApproval.kinds().isEmpty()) {
            var autoKind = AdaptiveConfig.AutoKind.valueOf(kind);
            var check = AutoApprovalGuard.check(new AutoApprovalGuard.Request(autoKind, source, current, proposed, floor, ceiling, persistent, undo,
                    unsettledDeadline(service, source), dataComplete, rs.autoPaused(clock.instant())), autoApproval.kinds(), rate, retrySettings);
            guard = check.reason();
            if (check.approve()) auto = new Review("c4-auto", true, "Approved automatically by policy: " + check.reason());
            else if (!automaticMode(source) && autoApproval.kinds().contains(autoKind)) evidence.add("Automatic approval not used: " + check.reason() + "; a reviewer decides");
        }
        if (auto == null && automaticMode(source)) {
            String held = String.format(Locale.ROOT, "held: %s %s → %s (%s), trying again", action == Action.RETRY_BUDGET ? "retry ratio" : "rate",
                    action == Action.RETRY_BUDGET ? fmtRatio(current) : fmt(current) + " req/s", action == Action.RETRY_BUDGET ? fmtRatio(proposed) : fmt(proposed) + " req/s",
                    guard == null ? "no automatic kind" : guard);
            evidence.add(0, held);
            rs.held.add(held);
            workflow.withdrawPending(service, action, source, "Held for automatic approval: " + (guard == null ? "no automatic kind" : guard));
            return null;
        }
        var change = offer(service, action, source, policy, current, proposed, unit, windowEnd, measurements, rule, evidence, auto);
        if (auto != null && change.decision() == Decision.APPROVED && "c4-auto".equals(change.reviewer()) && !change.proposal().id().equals(rs.lastAutoAudited)) {
            rs.lastAutoAudited = change.proposal().id();
            if (action == Action.LEAKY_BUCKET) rs.autoProposalId = change.proposal().id();
            audit.rate(AuditLog.RATE_AUTO_APPROVED, change, Map.of("kind", kind, "guard", guard, "rule", rule, "inputs", measurements));
        }
        return change;
    }

    /**
     * Follows the latest automatic rate change. Once it is applied we note its direction and check it on the next few
     * windows. A raise passes when the app time stays normal. A cut passes when the app time gets clearly better or is
     * normal again. A change that fails is undone automatically (reviewer c4-auto-revert), and too many undos or flips
     * pause automatic approval. Returns true while the rate must stay as it is.
     */
    private boolean followAutomaticChange(ServiceWindow window, Policy policy, RateState rs, RateDiagnosis.Result result, Double shed, Double p0,
            double current, List<String> evidence, List<PolicyChange> changes) {
        String service = window.service(); Source source = window.source(); Instant now = clock.instant();
        if (rs.autoProposalId != null) {
            PolicyChange c;
            try { c = workflow.find(rs.autoProposalId); } catch (IllegalArgumentException gone) { c = null; }
            if (c == null || c.decision() != Decision.APPROVED || c.delivery() == Delivery.SUPERSEDED || c.delivery() == Delivery.EXPIRED) rs.autoProposalId = null;
            else if (c.delivery() == Delivery.FAILED) {
                // A failed delivery does not block the service. The bridge retries the same change; after 5 failed
                // attempts we drop it and decide again.
                if (c.attempts() >= 5) {
                    workflow.withdraw(service, Action.LEAKY_BUCKET, source, "Delivery to Component 3 failed " + c.attempts() + " times; C4 decides again");
                    rs.autoProposalId = null;
                }
                evidence.add("Rate v2: delivery of the automatic change " + fmt(c.proposal().currentValue()) + " → " + fmt(c.proposal().proposedValue())
                        + " failed " + c.attempts() + " time(s) (" + c.detail() + "); still deciding");
            } else if (c.delivery() != Delivery.APPLIED) {
                evidence.add("Rate v2: waiting for Component 3 to apply the automatic change " + fmt(c.proposal().currentValue()) + " → " + fmt(c.proposal().proposedValue()));
                return true;
            } else if (!c.proposal().id().equals(rs.trackedApplied)) {
                rs.trackedApplied = c.proposal().id(); rs.autoProposalId = null;
                double from = c.proposal().currentValue(), to = c.proposal().proposedValue();
                rs.recordDirection(Double.compare(to, from), now, autoSettings.period());
                if ("c4-auto-revert".equals(c.reviewer())) rs.recordUndo(now, autoSettings.period());
                else {
                    var m = c.proposal().measurements();
                    rs.verification = new RateState.Verification(c.proposal().id(), to > from ? AdaptiveConfig.AutoKind.BUCKET_RAISE.name() : AdaptiveConfig.AutoKind.BUCKET_CUT.name(),
                            from, to, m.getOrDefault("appMs", Double.NaN), m.getOrDefault("shedPerSecond", 0.0));
                    rs.verification.startAt = c.lastAttempt() == null ? now : c.lastAttempt();
                }
                pauseIfUnstable(service, source, current, rs, now, evidence);
            }
        }
        var v = rs.verification;
        if (v == null) return false;
        if (Math.abs(current - v.toRate) > 1e-9) { rs.verification = null; rs.lastVerification = "not checked: the rate changed again"; return false; }
        if (!window.start().isBefore(v.startAt) && (v.lastEnd == null || !window.start().isBefore(v.lastEnd))) {
            v.windows++; v.lastEnd = window.end(); v.shedSum += shed == null ? 0 : shed;
            if (result.appMs() != null && Double.isFinite(result.appMs())) { v.appSum += result.appMs(); v.appWindows++; }
        }
        if (v.windows < autoSettings.verifyWindows()) {
            evidence.add(String.format(Locale.ROOT, "Rate v2: checking the automatic change %s → %s (%d of %d windows)", fmt(v.fromRate), fmt(v.toRate), v.windows, autoSettings.verifyWindows()));
            return true;
        }
        rs.verification = null;
        double app = v.appAfter(), shedAfter = v.shedAfter();
        boolean raise = AdaptiveConfig.AutoKind.BUCKET_RAISE.name().equals(v.kind);
        if (v.appWindows == 0) {
            rs.lastVerification = "inconclusive: no app time measured"; evidence.add("Rate v2: check of the automatic change inconclusive (no app time); kept");
            rs.recordCheck(new RateState.Check(v.proposalId.toString(), v.kind, v.fromRate, v.toRate, "inconclusive", finite(v.appBefore), null, null,
                    finite(v.shedBefore), finite(shedAfter), now.toString()));
            return false;
        }
        double normal = rate.appTolerance() * (p0 != null ? p0 : v.appBefore);
        // A raise is only undone when it slowed the app. If rejections did not fall, demand grew, and lowering the rate
        // again would only reject more.
        boolean pass = raise ? app <= normal : app <= (1 - rate.minImprovement()) * v.appBefore || app <= normal || notBinding(window, shed, v.toRate);
        String detail = String.format(Locale.ROOT, "%s %s → %s: app time %.0f → %.0f ms (normal ≤ %.0f), shed %.1f → %.1f req/s",
                raise ? "raise" : "cut", fmt(v.fromRate), fmt(v.toRate), v.appBefore, app, normal, v.shedBefore, shedAfter);
        rs.recordCheck(new RateState.Check(v.proposalId.toString(), v.kind, v.fromRate, v.toRate, pass ? "kept" : "undone", finite(v.appBefore), finite(app),
                finite(normal), finite(v.shedBefore), finite(shedAfter), now.toString()));
        if (pass) {
            boolean shedFell = shedAfter < v.shedBefore || shedAfter == 0, idle = !raise && notBinding(window, shed, v.toRate);
            rs.lastVerification = "passed: " + detail + (raise && !shedFell ? " (shedding did not fall: demand grew)" : idle ? " (the cut has not bitten yet: fewer requests arrive than it lets through)" : "");
            evidence.add("Rate v2: automatic change checked and kept (" + detail + (raise && !shedFell ? "; shedding did not fall, a further raise may follow" : idle ? "; the cut has not bitten yet, the next step may follow" : "") + ")");
            return false;
        }
        rs.lastVerification = "failed: " + detail;
        if (raise) rs.rememberBrokeAt(v.toRate, now, rate.brokeAtMemory()); else { rs.lastCut = null; rs.antiRatchetHold = true; }
        var measurements = new HashMap<String, Double>();
        put(measurements, "appMs", app); put(measurements, "appBeforeMs", v.appBefore); put(measurements, "shedPerSecond", shedAfter);
        put(measurements, "shedBeforePerSecond", v.shedBefore); put(measurements, "p0Ms", p0);
        String rule = "Automatic undo: the automatic " + detail + " did not pass its check";
        var undo = offer(service, Action.LEAKY_BUCKET, source, policy, current, v.fromRate, "requests/second", window.end(), measurements, rule, evidence,
                new Review("c4-auto-revert", true, rule));
        changes.add(undo);
        rs.autoProposalId = undo.proposal().id(); rs.lastProposalId = undo.proposal().id();
        audit.rate(AuditLog.RATE_AUTO_REVERTED, undo, Map.of("check", detail, "inputs", measurements));
        return true;
    }
    /**
     * True when a cut has not taken effect yet: nothing is rejected and fewer requests arrive than the new rate lets
     * through. Such a cut cannot have helped, so we do not undo it and the next step can follow.
     */
    private boolean notBinding(ServiceWindow window, Double shed, double rateAfter) {
        Double arrivals = window.originalRequests();
        return (shed == null || shed == 0) && arrivals != null && Double.isFinite(arrivals) && arrivals / settings.window().toSeconds() < rateAfter;
    }
    /** Pauses automatic approval for a service after too many undos or direction changes in a short time. */
    private void pauseIfUnstable(String service, Source source, double current, RateState rs, Instant now, List<String> evidence) {
        rs.undos.removeIf(t -> Duration.between(t, now).compareTo(autoSettings.period()) >= 0);
        if (rs.autoPaused(now) || rs.undos.size() < autoSettings.maxReverts() && rs.flips.size() < autoSettings.maxFlips()) return;
        rs.autoPausedUntil = now.plus(autoSettings.pause());
        String reason = rs.undos.size() + " undos and " + rs.flips.size() + " direction changes in " + autoSettings.period().toMinutes() + " min";
        evidence.add("Rate v2: automatic approval paused until " + rs.autoPausedUntil + " (" + reason + "); a reviewer decides");
        audit.ratePaused(service, source, current, Map.of("reason", reason, "until", rs.autoPausedUntil.toString()));
    }

    /** The latest rate decision of a service, kept for the evidence. */
    record RateDecision(RateDiagnosis.Result result, Double rate, Double proposed, String kind, String rule, Map<String, Double> inputs) {}

    /**
     * The rate evidence of every live service for the status API: the latest diagnosis and its numbers, the rule used,
     * and the state of automatic approval.
     */
    public Map<String, Map<String, Object>> rateEvidence() {
        var result = new TreeMap<String, Map<String, Object>>(); Instant now = clock.instant();
        rates.forEach((k, rs) -> {
            if (k.startsWith(Source.MOCK + ":") || rs.lastDecision == null) return;
            result.put(k.substring(k.indexOf(':') + 1), rateEvidence(rs, now));
        });
        return result;
    }
    static Map<String, Object> rateEvidence(RateState rs, Instant now) {
        var d = rs.lastDecision; var e = new LinkedHashMap<String, Object>();
        e.put("diagnosis", d.result().diagnosis().name()); e.put("reason", d.result().reason());
        e.put("rate", d.rate()); e.put("proposed", d.proposed()); e.put("kind", d.kind()); e.put("rule", d.rule());
        e.put("persistentCycles", rs.diagnosisStreak); e.put("inputs", d.inputs());
        e.put("autoPausedUntil", rs.autoPaused(now) ? rs.autoPausedUntil.toString() : null);
        e.put("checking", rs.verification == null ? null : fmt(rs.verification.fromRate) + " → " + fmt(rs.verification.toRate));
        e.put("lastCheck", rs.lastVerification);
        e.put("held", List.copyOf(rs.held)); // changes held this cycle, tried again next cycle
        e.put("checks", List.copyOf(rs.checks)); // checks of the automatic rate changes, newest first
        return e;
    }

    /**
     * The leaky-bucket rate decision. We first separate time waiting in our own queue from time in the app. Then:
     * RATE_TOO_LOW raises toward the demand plus headroom, APP_OVERLOADED cuts (never below the floor, and a second cut
     * only if the first one helped), HEALTHY moves slowly toward the demand plus headroom, WATCH and UNKNOWN keep the rate.
     */
    private RateDiagnosis.Diagnosis bucket(ServiceWindow window, ServiceLoadSnapshot load, Policy policy, boolean errorsHigh, List<String> evidence, List<PolicyChange> changes,
            JointContext jc) {
        String service = window.service(); Source source = window.source(); Instant now = clock.instant();
        if (!number(policy.bucketRate()) || policy.bucketRate() <= 0) { evidence.add("Leaky Bucket waits for the current rate"); return null; }
        RateState rs = rateState(service, source);
        rs.held.clear(); // held changes are listed per cycle
        double current = policy.bucketRate();
        if (rs.healthyRate == null) rs.healthyRate = number(policy.healthyBucketTarget()) && policy.healthyBucketTarget() > 0 ? policy.healthyBucketTarget() : current;
        if (rs.goodRate == null) rs.goodRate = rs.healthyRate;
        double seconds = settings.window().toSeconds();
        Double queuePct = load == null ? null : load.queuePressurePercent(), depth = load == null ? null : load.queueDepth();
        Double rejected = load == null ? null : load.overloadRejections() != null || load.backpressureRejections() != null
                ? Math.max(load.overloadRejections() == null ? 0 : load.overloadRejections(), load.backpressureRejections() == null ? 0 : load.backpressureRejections())
                : window.rejections();
        Double shed = rejected == null ? null : rejected / seconds;
        double accepted = window.successfulThroughput();
        // While the queue is full or rejecting, the bucket limits the app, so a first rough P0 is good enough.
        // Calm windows wait for the full baseline; nothing is lost by waiting there.
        boolean limiting = queuePct != null && queuePct >= rate.queueHighPercent() || shed != null && shed > 0;
        Double p0 = rs.p0(settings.baselineSamples(), limiting ? Math.min(baselineSettings.provisionalWindows(), settings.baselineSamples()) : settings.baselineSamples(),
                baselineSettings);
        // Does the app time, without the queue wait, grow when the load grows?
        String growth = rs.loadGrowth(RateDiagnosis.appMs(window.p95Ms(), RateDiagnosis.queueWaitMs(depth, current)), accepted,
                rate.loadGrowthWindows(), rate.loadGrowthFactor(), rate.loadGrowthMinLoadRatio());
        var result = RateDiagnosis.diagnose(new RateDiagnosis.Inputs(current, queuePct, depth, window.p95Ms(), accepted, shed, p0, errorsHigh, growth), rate);
        // Learn after diagnosing, so this window is only compared with the past.
        boolean clean = queuePct != null && queuePct < rate.queueLowPercent() && shed != null && shed == 0;
        rs.learnApp(result.appMs(), accepted, clean, shed != null && shed > 0, !errorsHigh, baselineSettings.allowShedWindows(),
                queuePct != null && queuePct >= rate.queueHighPercent(), result.diagnosis() == RateDiagnosis.Diagnosis.APP_OVERLOADED, settings.baselineSamples(), Math.max(settings.baselineSamples(), baselineSettings.loadWindows()));
        int streak = rs.observe(result.diagnosis());
        if (result.diagnosis() != RateDiagnosis.Diagnosis.HEALTHY) { rs.relaxing = false; rs.relaxSince = null; } // lowering a generous rate only while healthy
        // Keep the healthy queue depth of this service for joint recalibration.
        if (joint.enabled() && result.diagnosis() == RateDiagnosis.Diagnosis.HEALTHY && depth != null && Double.isFinite(depth)) {
            rs.healthyDepth.addLast(depth); while (rs.healthyDepth.size() > settings.baselineSamples()) rs.healthyDepth.removeFirst();
        }
        double retryRatio = number(policy.retryRatio()) ? policy.retryRatio() : 0;
        double demand = (accepted + (shed == null ? 0 : shed)) * (1 + retryRatio);
        double maxDemand = rs.maxDemand(demand, 3);
        double floor = RateDiagnosis.floor(rs.goodRate, rate), ceiling = RateDiagnosis.ceiling(rs.brokeAt(now), rate);
        Instant lastApplied = workflow.lastApplied(service, Action.LEAKY_BUCKET, source);
        boolean stable = lastApplied == null || Duration.between(lastApplied, now).compareTo(rate.stabilisation()) >= 0;
        var inputs = new LinkedHashMap<String, Double>();
        put(inputs, "queuePercent", queuePct); put(inputs, "queueDepth", depth); put(inputs, "queueWaitMs", result.queueWaitMs());
        put(inputs, "appMs", result.appMs()); put(inputs, "p0Ms", p0); put(inputs, "p95Ms", window.p95Ms());
        put(inputs, "acceptedPerSecond", accepted); put(inputs, "shedPerSecond", shed); put(inputs, "demandPerSecond", demand);
        put(inputs, "maxDemandPerSecond", maxDemand); put(inputs, "floor", floor); put(inputs, "ceiling", ceiling);
        put(inputs, "goodRate", rs.goodRate); put(inputs, "healthyRate", rs.healthyRate); put(inputs, "brokeAt", rs.brokeAt);
        inputs.put("errorsHigh", errorsHigh ? 1.0 : 0.0); inputs.put("persistentCycles", (double) streak);
        evidence.add("Rate v2: " + result.diagnosis() + " (" + result.reason() + ")");
        if (p0 != null && rs.p0Note != null) evidence.add("Rate v2: P0 " + fmt(RateDiagnosis.round(p0)) + " ms " + rs.p0Note);

        Double proposed = null; String kind = null, rule = null; boolean undo = false, reachesTarget = false;
        boolean persistent = streak >= settings.persistence();
        boolean cooldownOk = cooldown(service, Action.LEAKY_BUCKET, source);
        boolean following = followAutomaticChange(window, policy, rs, result, shed, p0, current, evidence, changes);
        if (following) {
            // An automatic change is waiting, being checked or was just undone, so we decide nothing else now.
        } else if (!cooldownOk) {
            // Change nothing until the cooldown after the last rate change has passed.
            evidence.add("Rate v2: waiting for the cooldown after the last applied rate change");
        } else switch (result.diagnosis()) {
            case APP_OVERLOADED -> {
                if (!persistent) break;
                var last = rs.lastCut;
                if (last != null && Math.abs(current - last.toRate()) < 1e-9) {
                    // Only cut again if the last cut made the app clearly faster or normal again.
                    boolean helped = result.appMs() <= (1 - rate.minImprovement()) * last.appBefore() || p0 != null && result.appMs() <= rate.appTolerance() * p0
                            || notBinding(window, shed, last.toRate()); // a cut that has not bitten yet is followed by the next step
                    if (!helped) {
                        proposed = last.fromRate(); kind = AdaptiveConfig.AutoKind.BUCKET_RAISE.name(); undo = true;
                        rule = String.format(Locale.ROOT, "Anti-ratchet: the previous cut %s → %s did not help the app (%.0f → %.0f ms); undo it and hold, the rate was not the problem",
                                fmt(last.fromRate()), fmt(last.toRate()), last.appBefore(), result.appMs());
                        rs.lastCut = null; rs.antiRatchetHold = true;
                        break;
                    }
                }
                if (rs.antiRatchetHold) { evidence.add("Rate v2: holding after an anti-ratchet undo (the rate was not the problem)"); break; }
                // A cut only helps when it holds traffic back. If the service gets fewer requests than even the full
                // cut would let through, it is slow for another reason, like a slow dependency or a busy machine,
                // so we keep the rate.
                double counted = window.originalRequests() == null || !Double.isFinite(window.originalRequests()) ? 0 : window.originalRequests() / seconds;
                double arrivals = Math.max(counted, accepted + (shed == null ? 0 : shed)); // requests per second that reach the service
                double fullCut = RateDiagnosis.cut(current, accepted, floor, rate);
                if ((shed == null || shed == 0) && fullCut >= arrivals) {
                    evidence.add(String.format(Locale.ROOT, "Rate v2: the app is slow, but it gets %.1f req/s, less than even the cut to %s would let through: the rate is not the cause, no change",
                            arrivals, fmt(fullCut)));
                    break;
                }
                double cut = Math.max(RateDiagnosis.cut(current, accepted, floor, rate), RateDiagnosis.round(current * (1 - settings.maximumRateChange())));
                // In automatic mode a cut goes in small checked steps of at most 20 percent. A reviewer gets the full cut.
                double step = roundUp(current * rate.cutFactor()); // rounded up so the step stays within the guard's limit
                boolean stepwise = cut < step && automaticMode(source) && autoApproval.kinds().contains(AdaptiveConfig.AutoKind.BUCKET_CUT);
                if (cut < current) {
                    proposed = stepwise ? step : cut; kind = AdaptiveConfig.AutoKind.BUCKET_CUT.name();
                    rule = String.format(Locale.ROOT, "APP_OVERLOADED: cut max(floor %s, %.1f × min(rate %s, accepted %.1f req/s))", fmt(floor), rate.cutFactor(), fmt(current), accepted)
                            + (stepwise ? "; automatic mode: a checked step to " + fmt(step) + " (at most −" + Math.round(100 * (1 - rate.cutFactor())) + "%) toward " + fmt(cut) : "");
                }
            }
            case RATE_TOO_LOW -> {
                rs.antiRatchetHold = false;
                if (!persistent) break;
                double up = RateDiagnosis.raise(current, demand, rs.goodRate, ceiling, rate);
                // After the remembered limit expires, raise only in small steps until the service is healthy again.
                if (rs.probing) up = Math.min(up, RateDiagnosis.round(current * (1 + rate.stepFraction())));
                if (up > current) {
                    proposed = up; kind = AdaptiveConfig.AutoKind.BUCKET_RAISE.name();
                    rule = String.format(Locale.ROOT, "RATE_TOO_LOW: the bucket is the bottleneck; raise toward max(rate + %.1f, demand %.1f × %.2f), at most +%.0f%% per step, ceiling %s",
                            rate.stepFraction() * rs.goodRate, demand, 1 + rate.headroom(), 100 * (rs.probing ? rate.stepFraction() : rate.maxRaise()), fmt(ceiling))
                            + (rs.probing ? "; probing: the remembered limit expired, small steps until healthy" : "");
                }
            }
            case HEALTHY -> {
                rs.antiRatchetHold = false;
                if (rs.healthyStreak < settings.recoveryWindows()) break;
                // A rate that stays healthy counts as good, but only as far as it was used. A rate far above the
                // demand only shows that the demand plus headroom is fine. The floor follows the good rate.
                rs.goodRate = Math.min(current, RateDiagnosis.healthyTarget(maxDemand, rate.minAbsolute(), rate.maxAbsolute(), rate));
                rs.probing = false;
                floor = RateDiagnosis.floor(rs.goodRate, rate); inputs.put("floor", floor); inputs.put("goodRate", rs.goodRate);
                double target = RateDiagnosis.healthyTarget(maxDemand, floor, ceiling, rate);
                // A rate that stays above twice the demand target for a while is too generous. We then lower it in
                // small steps down to that target. We use the demand target here because the floor follows the
                // current rate and would otherwise keep a generous rate high.
                double demandTarget = RateDiagnosis.healthyTarget(maxDemand, rate.minAbsolute(), ceiling, rate);
                if (current > 2 * demandTarget) { if (rs.relaxSince == null) rs.relaxSince = now; } else if (!rs.relaxing) rs.relaxSince = null;
                if (rs.relaxSince != null && Duration.between(rs.relaxSince, now).compareTo(rate.generousWait()) >= 0) rs.relaxing = true;
                if (current <= demandTarget) { rs.relaxing = false; rs.relaxSince = null; }
                if (!stable) break;
                double next = rs.relaxing ? Math.max(demandTarget, roundUp(current * (1 - rate.relaxStep())))
                        : RateDiagnosis.healthyStep(current, target, rs.goodRate, false, rate);
                if (next != current) {
                    proposed = next;
                    kind = next > current ? AdaptiveConfig.AutoKind.BUCKET_RAISE.name() : AdaptiveConfig.AutoKind.BUCKET_RELAX.name();
                    reachesTarget = next == (rs.relaxing ? demandTarget : target);
                    rule = String.format(Locale.ROOT, "HEALTHY: move toward max demand of the last 3 windows %.1f × %.2f = %s (floor %s, ceiling %s)%s",
                            maxDemand, 1 + rate.headroom(), fmt(next < current ? demandTarget : target), fmt(floor), fmt(ceiling), next < current
                                    ? "; too generous: the rate stayed above 2 × target for " + rate.generousWait().toSeconds() + " s, lowering by at most "
                                    + Math.round(100 * rate.relaxStep()) + "% per step" : "");
                }
            }
            default -> { /* WATCH and UNKNOWN: hold */ }
        }
        if (proposed != null) {
            final double fProposed = proposed, fFloor = floor, fCeiling = ceiling; final String fKind = kind, fRule = rule; final boolean fUndo = undo;
            // HEALTHY only proposes after several healthy cycles, so it counts as lasting too.
            final boolean fPersistent = persistent || result.diagnosis() == RateDiagnosis.Diagnosis.HEALTHY, fData = result.diagnosis() != RateDiagnosis.Diagnosis.UNKNOWN;
            final var measurements = new HashMap<String, Double>(inputs);
            Runnable offerNormal = () -> {
                var change = offerAuto(service, Action.LEAKY_BUCKET, source, policy, current, fProposed, "requests/second", window.end(), measurements, fRule, evidence,
                        fKind, fFloor, fCeiling, fPersistent, fUndo, fData);
                if (change == null) return; // held, tried again next cycle
                changes.add(change);
                if (AdaptiveConfig.AutoKind.BUCKET_CUT.name().equals(fKind) && !change.proposal().id().equals(rs.lastProposalId)) {
                    // Remember what the app actually managed, not the configured rate.
                    rs.rememberBrokeAt(Math.min(current, accepted), now, rate.brokeAtMemory());
                    rs.lastCut = new RateState.Cut(current, fProposed, result.appMs() == null ? Double.NaN : result.appMs());
                }
                rs.lastProposalId = change.proposal().id(); rs.lastKind = fKind;
            };
            // A healthy step that reaches its target may become a joint card. That is decided after the retry rule,
            // because normal recommendations come first.
            if (jc != null && reachesTarget) jc.candidate = new JointCandidate(current, fProposed, Map.copyOf(measurements), fRule, "the healthy rate step reaches its target", offerNormal);
            else offerNormal.run();
        } else if (!following && (result.diagnosis() == RateDiagnosis.Diagnosis.HEALTHY || result.diagnosis() == RateDiagnosis.Diagnosis.WATCH)) {
            String reason = "Rate v2: " + result.diagnosis() + ", no rate change supported";
            // This clean-up never withdraws a waiting joint card.
            if (joint.enabled()) workflow.withdrawExceptJoint(service, Action.LEAKY_BUCKET, source, reason);
            else workflow.withdraw(service, Action.LEAKY_BUCKET, source, reason);
        }
        rs.lastDecision = new RateDecision(result, current, proposed, kind, rule, Map.copyOf(inputs));
        rs.lastAppMs = result.appMs(); rs.lastShed = shed;
        return result.diagnosis();
    }
    private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }
    /** The value rounded to 0.1, or null when it was not measured (NaN is not valid JSON). */
    private static Double finite(double v) { return Double.isFinite(v) ? Math.round(v * 10) / 10.0 : null; }
    /** Rounds up to 0.1 req/s, so a limited step never goes past its limit after rounding. */
    static double roundUp(double v) { return Math.ceil(v * 10 - 1e-6) / 10; }

    /**
     * The retry budget of a service. The rules, in order: when the app stays overloaded, stop extra retries. When the
     * bucket rejects requests, keep the budget, because retries into a full queue only become 503s. When retries rarely
     * succeed, step down and then stop. When stressed, keep the budget. When healthy for a while, step back up slowly.
     * Without retry data an increase is only reported. Checks per request belong to the sidecar, not to this budget.
     * Returns whether a change was proposed.
     */
    private boolean retry(ServiceWindow window, State state, Policy policy, Health health, boolean persistent, RateDiagnosis.Diagnosis rateDiagnosis,
                          List<String> evidence, List<PolicyChange> changes) {
        String service = window.service(); Source source = window.source();
        if (!number(policy.retryRatio()) || policy.retryRatio() > settings.maximumRetryRatio()) { evidence.add("Retry budget waits for valid current ratio"); return false; }
        double current = policy.retryRatio();
        RateState rs = rateState(service, source);
        boolean rateKnown = rateDiagnosis != null && rateDiagnosis != RateDiagnosis.Diagnosis.UNKNOWN;
        boolean appOverloaded = rateDiagnosis == RateDiagnosis.Diagnosis.APP_OVERLOADED && rs.diagnosisStreak >= settings.persistence();
        boolean healthOverloaded = !rateKnown && health == Health.OVERLOADED && persistent;
        // How often retries succeed, once there are enough attempts to decide.
        Double attempts = window.retryAttempts(), successes = window.successfulRetries();
        boolean counted = number(attempts) && number(successes) && successes <= attempts;
        boolean enough = counted && attempts >= retrySettings.minAttempts();
        Proportion success = enough ? RobustStatistics.wilsonOneSided((int) Math.round(successes), (int) Math.round(attempts), 1.645, 1) : null;
        boolean low = success != null && success.upper() < retrySettings.minSuccess();
        rs.retryLowStreak = low ? rs.retryLowStreak + 1 : 0;
        var inputs = new LinkedHashMap<String, Double>();
        put(inputs, "originalRequests", window.originalRequests()); put(inputs, "retryAttempts", attempts); put(inputs, "successfulRetries", successes);
        if (success != null) { inputs.put("retrySuccessRate", success.p()); inputs.put("retrySuccessUpperBound", success.upper()); }
        boolean cooldownOk = cooldown(service, Action.RETRY_BUDGET, source);

        if (appOverloaded || healthOverloaded) {
            if (current > 0 && cooldownOk) {
                inputs.put("allowedRetries", 0.0);
                var retryChange = offerAuto(service, Action.RETRY_BUDGET, source, policy, current, 0, "extra attempts/original request", window.end(), inputs,
                        appOverloaded ? "APP_OVERLOADED: disable additional retries; original requests remain allowed"
                                : "Overloaded: disable additional retries; original requests remain allowed", evidence,
                        AdaptiveConfig.AutoKind.RETRY_DECREASE.name(), null, null, true, false, rateKnown);
                if (retryChange != null) changes.add(retryChange);
                return true;
            }
            return false;
        }
        if (rateDiagnosis == RateDiagnosis.Diagnosis.RATE_TOO_LOW) {
            evidence.add("Retry v2: hold while the bucket sheds (retries into a full queue only become 503s)");
            workflow.withdraw(service, Action.RETRY_BUDGET, source, "Retry v2: hold while the bucket sheds");
            return false;
        }
        if (rs.retryLowStreak >= settings.persistence() && current > 0) {
            if (!cooldownOk) return false;
            double next = current > retrySettings.step() ? retrySettings.step() : 0;
            inputs.put("allowedRetries", Math.floor(window.originalRequests() * next));
            var retryChange = offerAuto(service, Action.RETRY_BUDGET, source, policy, current, next, "extra attempts/original request", window.end(), inputs,
                    String.format(Locale.ROOT, "Retries rarely succeed: %.0f of %.0f (upper bound %.2f < %.2f) for %d cycles; step down to %s, then 0",
                            successes, attempts, success.upper(), retrySettings.minSuccess(), rs.retryLowStreak, fmtRatio(retrySettings.step())), evidence,
                    AdaptiveConfig.AutoKind.RETRY_DECREASE.name(), null, null, true, false, rateKnown);
            if (retryChange != null) changes.add(retryChange);
            return true;
        }
        if (health == Health.STRESSED) { evidence.add("Retry v2: hold while STRESSED"); return false; }
        double target = Math.min(retrySettings.baseRatio(), settings.maximumRetryRatio());
        if (health != Health.HEALTHY || state.healthy < settings.recoveryWindows() || rateDiagnosis != RateDiagnosis.Diagnosis.HEALTHY
                || current >= target || !cooldownOk) return false;
        Instant lastApplied = workflow.lastApplied(service, Action.RETRY_BUDGET, source);
        if (lastApplied != null && Duration.between(lastApplied, clock.instant()).compareTo(rate.stabilisation()) < 0) return false;
        double next = Math.round(Math.min(target, current + retrySettings.step()) * 1000) / 1000.0;
        if (!enough) {
            evidence.add("Advisory: retry " + fmtRatio(current) + " → " + fmtRatio(next) + " not proposed ("
                    + (counted && attempts > 0 ? "too few retry attempts: " + Math.round(attempts) + " < " + retrySettings.minAttempts() : "retry-data-unavailable") + ")");
            return false;
        }
        if (low) return false;
        inputs.put("allowedRetries", Math.floor(window.originalRequests() * next));
        var retryChange = offerAuto(service, Action.RETRY_BUDGET, source, policy, current, next, "extra attempts/original request", window.end(), inputs,
                String.format(Locale.ROOT, "Healthy for %d cycles with a quiet queue and retries that succeed (%.0f of %.0f); +%s toward %s",
                        state.healthy, successes, attempts, fmtRatio(retrySettings.step()), fmtRatio(target)), evidence,
                AdaptiveConfig.AutoKind.RETRY_INCREASE.name(), null, null, true, false, rateKnown);
        if (retryChange != null) changes.add(retryChange);
        return true;
    }
    private static String fmtRatio(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    /** A chain window without per-request samples. It is checked and reported, but no deadline is decided on it. */
    public Outcome chain(ChainWindow window) {
        return chain(window, null, Map.of(), List.of());
    }
    public Outcome chain(ChainWindow window, Map<String, Double> supportingMeasurements, List<String> supportingEvidence) {
        return chain(window, null, supportingMeasurements, supportingEvidence);
    }

    /**
     * The deadline decision for one chain window. The window must be complete and fresh, and the decision uses the
     * per-request samples. Every proposal goes through the normal approval workflow.
     */
    public Outcome chain(ChainWindow window, ChainEvidence evidence, Map<String, Double> supportingMeasurements, List<String> supportingEvidence) {
        if (window == null || window.chain() == null || window.chain().isBlank() || window.source() == null) throw new IllegalArgumentException("Chain and source are required");
        State state = chains.computeIfAbsent(key(window.chain(), window.source()), ignored -> new State());
        if (window.services() != null && !window.services().isEmpty())
            chainPaths.put(key(window.chain(), window.source()), new ChainPath(window.chain(), window.source(), List.copyOf(window.services())));
        watchPolicyBudget(window.chain(), window.source(), clock.instant());
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

    // ---------- chain deadline ----------

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

    /** A counted window, kept so low traffic can be topped up with earlier windows. */
    private record CountedWindow(Instant end, long budget, List<LabelledSample> samples) {}
    /** What we remember about the deadline of one chain. */
    private static class ChainV2 {
        ChainDeadlineState state = ChainDeadlineState.LEARNING;
        final Deque<CountedWindow> recent = new ArrayDeque<>();
        final List<Instant> proposalTimes = new ArrayList<>();
        DeadlineEvidence evidence; List<String> lines = List.of();
        UUID lastProposalId; Long lastProposedMs; Map<String, Double> lastMeasurements = Map.of(); List<String> lastProposalEvidence = List.of(); String lastRule;
        DeadlineCase lastProposalCase;
        // Checking an applied change, pausing, and not auditing the same decision twice.
        UUID trackedApplied; Settling settling; String verification;
        String pausedReason; Instant pauseCauseLastSeen;
        final DeadlineSafety safety = new DeadlineSafety();
        Proportion lastH; String lastAuditedDecision; Instant conflictSince;
        /** Healthy requests keep going over the deadline, so a raise is building and the deadline is not settled. */
        boolean raiseBuilding;
        /** Some deadline streak is running, so a deadline change may be needed soon. */
        boolean streaksBuilding;
        /** The rate of each service on the path in the windows the healthy time was learned from. */
        final Deque<Map<String, Double>> calibrationRates = new ArrayDeque<>();
    }
    /** An applied change being checked: skip a few windows, then collect the check windows at the new deadline. */
    private static class Settling {
        final DeadlineCase applied; final long fromMs, toMs; final Proportion hBefore; int settleLeft, verified;
        Instant startedAt;
        /** The joint recommendation this change belongs to, or null. */
        UUID jointId;
        final List<LabelledSample> pool = new ArrayList<>();
        Settling(DeadlineCase applied, long fromMs, long toMs, Proportion hBefore, int settleLeft) {
            this.applied = applied; this.fromMs = fromMs; this.toMs = toMs; this.hBefore = hBefore; this.settleLeft = settleLeft;
        }
    }
    private AuditLog audit = AuditLog.NONE;
    @Autowired(required = false) public void setAudit(AuditLog audit) { this.audit = audit == null ? AuditLog.NONE : audit; }

    /**
     * The deadline the gateway stamps differs from the last approved policy. During a rollout this is normal for a short
     * time, so we only pause the chain when it lasts for several windows.
     */
    public void configurationConflict(String chain, Source source, String reason) {
        State state = chains.computeIfAbsent(key(chain, source), ignored -> new State());
        synchronized (state) {
            ChainV2 v = v2.computeIfAbsent(key(chain, source), ignored -> new ChainV2());
            Instant now = clock.instant();
            if (v.conflictSince == null) v.conflictSince = now;
            if (v.state == ChainDeadlineState.PAUSED) { if (reason.startsWith(String.valueOf(v.pausedReason).split(":")[0])) v.pauseCauseLastSeen = now; return; }
            if (Duration.between(v.conflictSince, now).compareTo(settings.window().multipliedBy(deadline.persistenceWindows())) >= 0) pauseChain(chain, source, reason);
        }
    }
    /** Pauses the deadline decisions of a chain now. */
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

    /** The latest deadline evidence of every live chain, for the API. */
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
        v.conflictSince = null; // this window had no configuration conflict
        var samples = input.samples().samples();

        // Classify the load of each service, then label every request by its own path.
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

        // Only separate windows count for learning, streaks and decisions.
        boolean counted = tracker.isDisjoint(key, end, settings.window());
        if (counted) tracker.markCounted(key, end);
        boolean eligible = window.complete() && !mixed && cutShare <= deadline.cutMaxShare();
        HealthyModel model = counted
                ? healthy.update(key, end, labelled, windowBudget, eligible, deadline, settings.baselineSamples(), settings.minimumDeadlineMs(), settings.maximumDeadlineMs())
                : healthy.model(key, deadline, settings.minimumDeadlineMs(), settings.maximumDeadlineMs());
        // Remember the rates of the services on the path while we learn the healthy time.
        if (joint.enabled() && counted && eligible) recordCalibrationRates(v, services, source);
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
        v.raiseBuilding = aStreak > 0; v.streaksBuilding = aStreak > 0 || oStreak > 0 || dStreak > 0;
        watchStampedBudget(chain, source, mixed ? null : windowBudget, clock.instant());

        var overloadedOnOPaths = new TreeSet<String>();
        for (var l : labelled) if (l.label() == RequestLabel.O) for (String s : l.sample().path()) if (loads.get(s) == ServiceLoad.OVERLOADED) overloadedOnOPaths.add(s);
        var leverResult = LeverDiagnosis.chain(overloadedOnOPaths, input.loads(), lever, load);
        var oSamples = labelled.stream().filter(l -> l.label() == RequestLabel.O && !l.sample().cut()).toList();
        Double predictedOAtTarget = target == null || oSamples.isEmpty() ? null
                : oSamples.stream().filter(l -> l.sample().durationMs() > target).count() / (double) oSamples.size();

        var pending = workflow.pending(chain, Action.CHAIN_DEADLINE, source);
        boolean pendingRate = services.stream().anyMatch(s -> workflow.pending(s, Action.LEAKY_BUCKET, source).isPresent());
        // A waiting joint card never holds the normal deadline decisions.
        if (joint.enabled()) {
            pending = pending.filter(p -> !workflow.isJointPart(p.proposal().id()));
            pendingRate = services.stream().anyMatch(s -> workflow.pending(s, Action.LEAKY_BUCKET, source).filter(p -> !workflow.isJointPart(p.proposal().id())).isPresent());
        }
        Instant now = clock.instant();
        v.proposalTimes.removeIf(t -> Duration.between(t, now).compareTo(Duration.ofHours(1)) >= 0);
        ChainDeadlineState stateBefore = v.state;

        // Pausing, noticing an applied change, and checking it.
        if (v.state == ChainDeadlineState.PAUSED && v.pauseCauseLastSeen != null && Duration.between(v.pauseCauseLastSeen, now).compareTo(deadline.pauseClear()) >= 0) {
            v.state = model.ready() ? ChainDeadlineState.READY : ChainDeadlineState.LEARNING; v.pausedReason = null;
        }
        if (counted && "REJECTING_MIDDLEWARE".equals(cutHint) && cutShare > deadline.cutMaxShare()) {
            audit.deadline(AuditLog.DEADLINE_CUT_DETECTED, chain, source, current == null ? windowBudget : current, null, cutHint,
                    String.format(Locale.ROOT, "%.1f%% of requests cut by another timer (%s); not used for learning", 100 * cutShare, cutHint));
            String reason = v.safety.rejectingMiddleware(now);
            if (reason != null) pause(v, reason, now, chain, source);
        }
        // An applied joint deadline part is handled like an applied deadline proposal, and the healthy time is
        // learned again from then on.
        if (joint.enabled()) {
            var part = workflow.appliedJointDeadline(chain, source);
            if (part != null && !part.proposal().id().equals(v.trackedApplied) && !part.proposal().id().equals(v.lastProposalId)) {
                v.lastProposalId = part.proposal().id(); v.lastProposalCase = DeadlineCase.RECALIBRATE;
                healthy.reset(key); v.calibrationRates.clear();
            }
        }
        if (v.lastProposalId != null && !v.lastProposalId.equals(v.trackedApplied) && v.state != ChainDeadlineState.PAUSED) {
            PolicyChange applied = null;
            try { applied = workflow.find(v.lastProposalId); } catch (IllegalArgumentException ignored) { /* trimmed history */ }
            if (applied != null && applied.delivery() == Delivery.APPLIED) {
                v.trackedApplied = applied.proposal().id();
                long from = Math.round(applied.proposal().currentValue()), to = Math.round(applied.proposal().proposedValue());
                DeadlineCase appliedCase = v.lastProposalCase != null ? v.lastProposalCase : to > from ? DeadlineCase.A_RAISE : DeadlineCase.D_TIGHTEN;
                v.settling = new Settling(appliedCase, from, to, v.lastH, deadline.settleWindows()); v.settling.startedAt = now;
                v.settling.jointId = workflow.jointOf(applied.proposal().id());
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
                    DeadlineCase checked = s.applied == DeadlineCase.RECALIBRATE ? (s.toMs > s.fromMs ? DeadlineCase.A_RAISE : DeadlineCase.D_TIGHTEN) : s.applied;
                    var result = DeadlineVerifier.verify(checked, s.hBefore, hAfter, oAfter, cleared, deadline, settings.minimumChainRequests());
                    // If a reviewer changed a rate or retry budget on this path meanwhile, the windows mix two changes,
                    // so the check proves nothing and never causes a revert.
                    Source serviceSource = source == Source.MOCK ? Source.MOCK : Source.PROMETHEUS;
                    String confounded = s.startedAt == null ? null
                            : services.stream().filter(svc -> workflow.reviewerApprovedSince(svc, serviceSource, s.startedAt, s.jointId)).findFirst().orElse(null);
                    if (confounded != null) result = new DeadlineVerifier.Verification(DeadlineVerifier.Result.INCONCLUSIVE,
                            "a reviewer approved a rate or retry change for " + confounded + " while the deadline was settling");
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

        // Give advice when the overload is really a rate or capacity problem.
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

        // A normal deadline recommendation comes first, so a waiting joint card is withdrawn when one is needed.
        if (joint.enabled() && counted && workflow.activeJoint(chain, source).isPresent()) {
            String needed = decision.proposes() || decision.decisionCase() == DeadlineCase.C_KEEP_FIRING ? "deadline case " + decision.decisionCase()
                    : oShare >= deadline.shareThreshold() || stressedShare >= deadline.shareThreshold() ? "overloaded or stressed requests on the chain"
                    : leverResult.lever() == Lever.RATE || leverResult.lever() == Lever.CAPACITY ? "lever " + leverResult.lever() : null;
            if (needed != null) workflow.withdrawJoint(chain, source, "A normal recommendation comes first: " + needed);
        }
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
        // When a counted window no longer proposes the same value, we stop refreshing, so the old proposal expires.
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
        // Write to the audit log only when something changed, to avoid noise.
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

    /** Hold reasons that mean the basis of a pending proposal is gone, so we withdraw it. */
    private static final Set<String> WITHDRAW_ON = Set.of("IN_BAND", "STRESS_GATE", "LEVER_RATE", "LEVER_CAPACITY", "DEADLINE_NOT_THE_LEVER",
            "PREVIEW_FAILED", "INCONSISTENT", "CENSORED_OTHER", "UNKNOWN_SHARE", "AT_LIMIT");

    /** How many requests of a label go over the deadline. With little traffic we add earlier windows at the same deadline. */
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
            case RECALIBRATE -> String.format(Locale.ROOT, "Recalibrate the deadline %d → %d ms with the healthy rate change (joint recommendation)", now, d.proposedMs());
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
     * The unit we measure a change in: the baseline MAD, but never below a small floor. Very steady traffic has an almost
     * zero MAD, which would otherwise turn tiny noise into a breach.
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
        return offer(target, action, source, policy, current, proposed, unit, windowEnd, measurements, rule, evidence, null);
    }
    private PolicyChange offer(String target, Action action, Source source, Policy policy, double current, double proposed,
            String unit, Instant windowEnd, Map<String, Double> measurements, String rule, List<String> evidence, Review auto) {
        Instant now = clock.instant(); Instant expiry = now.plus(settings.ttl()); if (policy.validUntil().isBefore(expiry)) expiry = policy.validUntil();
        var data = new HashMap<>(measurements); data.put("windowEndEpochSeconds", (double) windowEnd.getEpochSecond());
        var reasons = new ArrayList<>(evidence); reasons.add(rule);
        return workflow.offer(new Proposal(UUID.randomUUID(), target, action, source, policy.version(), current, proposed, unit, now, expiry, Map.copyOf(data), List.copyOf(reasons)), auto);
    }
    /** A proposal with the normal lifetime, used for the parts of a joint recommendation. */
    private Proposal proposal(String target, Action action, Source source, Policy policy, double current, double proposed,
            String unit, Instant windowEnd, Map<String, Double> measurements, List<String> evidence) {
        Instant now = clock.instant(); Instant expiry = now.plus(settings.ttl()); if (policy.validUntil().isBefore(expiry)) expiry = policy.validUntil();
        var data = new HashMap<>(measurements); data.put("windowEndEpochSeconds", (double) windowEnd.getEpochSecond());
        return new Proposal(UUID.randomUUID(), target, action, source, policy.version(), current, proposed, unit, now, expiry, Map.copyOf(data), List.copyOf(evidence));
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
