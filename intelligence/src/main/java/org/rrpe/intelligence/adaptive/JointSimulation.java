package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Deterministic test bed for the joint healthy recalibration (C3 request): one chain {@code gateway > payment} analysed per
 * request, and payment's service window with its leaky-bucket queue, one window of each per cycle, in an isolated engine on a
 * virtual clock. Used by the joint demo scenarios, the joint experiment and the tests.
 */
public final class JointSimulation {
    private JointSimulation() {}
    public static final String GATEWAY = "mock-api-gateway", SERVICE = "mock-payment-service";
    public static final String CHAIN = "POST /api/orders | " + GATEWAY + " > " + SERVICE;

    /**
     * C3's worked examples: payment at {@code rate} serving {@code demand} req/s with a healthy queue of {@code depth} requests,
     * the chain's healthy requests following {@code healthy} at the deadline {@code deadlineMs}; then the healthy target changes
     * to {@code newTarget}.
     */
    public record Example(String id, String title, double rate, double newTarget, double demand, double depth, long deadlineMs,
                          ChainSimulation.Profile healthy, long expectedDeadlineMs, String expectation) {}
    /** Example 1: healthy time 700 ms, Q = 8, payment rate from 50 to 100 req/s, deadline from 875 to 780 ms. */
    public static Example recovery() {
        return new Example("joint-recovery", "Joint recovery: payment's healthy target 50 → 100 req/s", 50, 100, 40, 8, 875,
                ChainSimulation.profile(300, 640, 660, 700, 740), 780, "one joint card: payment 50 → 100 req/s with the deadline 875 → 780 ms");
    }
    /** Example 2: healthy time 620 ms, Q = 8, payment rate from 100 to 60 req/s, deadline from 780 to 850 ms. */
    public static Example capacityDrop() {
        return new Example("joint-capacity-drop", "Joint capacity drop: payment's healthy target 100 → 60 req/s", 100, 60, 48, 8, 780,
                ChainSimulation.profile(300, 560, 585, 620, 655), 850, "one joint card: payment 100 → 60 req/s with the deadline 780 → 850 ms");
    }
    public static List<Example> examples() { return List.of(recovery(), capacityDrop()); }
    /** Only for the joint experiment: the capacity drop with bursty healthy traffic (healthy queue Q = 24), where the old deadline flags healthy requests. */
    public static Example capacityDropBursty() {
        var e = capacityDrop();
        return new Example("joint-capacity-drop-q24", "Joint capacity drop with a larger healthy queue (Q = 24)", e.rate(), e.newTarget(), e.demand(), 24, e.deadlineMs(),
                e.healthy(), 980, "joint card: payment 100 → 60 req/s with the deadline 780 → 980 ms");
    }

    /** One isolated engine with payment and the chain. */
    public static final class Bed {
        public final AdaptiveConfig.Settings settings;
        public final ChainSimulation.VirtualClock clock;
        public final PolicyWorkflow workflow;
        public final AdaptiveEngine engine;
        public final Source source, chainSource;
        private int cycle, versions;
        /** The latest outcome of payment's service window (its evidence explains joint decisions). */
        public Outcome lastService;
        /** Share of the last chain window's requests at or above the deadline (signalled with cs=expired). */
        public double lastExpiredShare;

        public Bed(AdaptiveConfig.Settings settings, AdaptiveConfig.DeadlineSettings deadline, AdaptiveConfig.JointSettings joint, Source source, Instant start) {
            this.settings = settings; this.source = source; this.chainSource = source == Source.MOCK ? Source.MOCK : Source.TEMPO;
            this.clock = new ChainSimulation.VirtualClock(start);
            this.workflow = new PolicyWorkflow(new AdaptiveConfig.Connections(false, "http://localhost:9090", "http://localhost:3200", "rpe-demo",
                    List.of(SERVICE), GATEWAY, Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofSeconds(30), 5000, "", "", Map.of()), settings, clock);
            this.engine = new AdaptiveEngine(settings, workflow, clock);
            engine.configureDeadline(deadline, null, null);
            engine.configureJoint(joint);
        }
        /** Payment's policy (rate, healthy target, retry ratio) and the chain's route deadline, as a new version. */
        public void policies(double rate, double healthyTarget, double retry, long deadlineMs) {
            String version = "sim-v" + (++versions);
            workflow.putPolicy(new Policy(SERVICE, version, clock.now.plus(Duration.ofDays(30)), rate, healthyTarget, retry, null), source);
            workflow.putPolicy(new Policy(CHAIN, version, clock.now.plus(Duration.ofDays(30)), null, null, null, (double) deadlineMs), chainSource);
        }
        /** The operator sets a new healthy target for payment (C3: "the healthyBucketTarget itself changes"). */
        public void healthyTarget(double target) {
            var p = workflow.current(SERVICE, source);
            workflow.putPolicy(new Policy(SERVICE, "sim-v" + (++versions), p.validUntil(), p.bucketRate(), target, p.retryRatio(), null), source);
        }
        /** The route deadline changes (by C4 or anyone else). */
        public void deadline(long ms) {
            var p = workflow.current(CHAIN, chainSource);
            workflow.putPolicy(new Policy(CHAIN, "sim-v" + (++versions), p.validUntil(), null, null, null, (double) ms), chainSource);
        }
        public double rate() { return workflow.current(SERVICE, source).bucketRate(); }
        public long deadlineMs() { return Math.round(workflow.current(CHAIN, chainSource).deadlineMs()); }

        /** Traffic of one cycle: payment demand, its healthy queue depth, app time, retries; the chain's request profile. */
        public record Traffic(ChainSimulation.Profile chain, double demand, double depth, double appMs, double retries, double retrySuccesses, boolean overloaded) {
            public Traffic(ChainSimulation.Profile chain, double demand, double depth) { this(chain, demand, depth, 40, 0, 0, false); }
            public Traffic(ChainSimulation.Profile chain, double demand, double depth, double appMs, double retries, double retrySuccesses) {
                this(chain, demand, depth, appMs, retries, retrySuccesses, false);
            }
        }
        /** One cycle: the chain window (stamped with the current deadline), then payment's service window. Returns the new changes. */
        public List<PolicyChange> cycle(Traffic t) {
            long budget = deadlineMs();
            var phase = new ChainSimulation.Phase("p", 1, List.of(new ChainSimulation.PathTraffic(1, t.chain(), GATEWAY, SERVICE)));
            var set = ChainSimulation.samples(phase, cycle, budget, 300, clock.now, "POST", "/api/orders", "j");
            double failures = set.samples().stream().filter(DeadlineSample::expired).count();
            lastExpiredShare = failures / Math.max(1, set.samples().size());
            var w = new ChainWindow(CHAIN, List.of(GATEWAY, SERVICE), clock.now.minus(settings.window()), clock.now, chainSource, true, 300.0, failures,
                    TempoWindows.percentile99(set.samples().stream().mapToDouble(DeadlineSample::durationMs).toArray()), t.overloaded() ? Health.OVERLOADED : Health.HEALTHY, List.of("j"));
            var changes = new ArrayList<PolicyChange>(engine.chain(w, new ChainEvidence(set, Map.of(), Map.of(GATEWAY, Health.HEALTHY, SERVICE, t.overloaded() ? Health.OVERLOADED : Health.HEALTHY), Set.of()),
                    Map.of(), List.of()).changes());
            double rate = rate(), seconds = settings.window().toSeconds();
            double accepted = Math.min(t.demand(), rate), shed = Math.max(0, t.demand() - rate);
            double depth = shed > 0 ? 200 : t.depth(), pct = shed > 0 ? 100 : 20;
            double p95 = t.appMs() + depth / rate * 1000;
            var window = new ServiceWindow(SERVICE, clock.now.minus(settings.window()), clock.now, source, true, t.demand() * seconds, p95, 0.0,
                    t.retries() * seconds, t.retrySuccesses() * seconds, shed * seconds, 10.0, accepted, null, null, null, null, List.of());
            var load = new ServiceLoadSnapshot(SERVICE, pct, shed * seconds, 0.0, 0.0, null, null, null, null, List.of(), depth);
            lastService = engine.service(window, load);
            changes.addAll(lastService.changes());
            clock.now = clock.now.plus(settings.cycle()); cycle++;
            return changes;
        }
        /** Applies an approved change as Component 3 would after a two-phase rollout. */
        public void apply(PolicyChange change) {
            workflow.deliveryResult(change.proposal().id(), new Acknowledgement("APPLIED", "sim-v" + (++versions), change.proposal().proposedValue()), null);
        }
    }

    // ---------- joint experiment simulation ----------

    /**
     * The result of one run of one example. falseSignalRate: healthy requests that reach the deadline after the change.
     * firstSignalMinutes: minutes from the overload until enough requests get the signal (null if never).
     * minutesOutsideBand: healthy minutes with the deadline outside the right band for the new healthy time.
     * proposals: how many decisions a reviewer made (a joint card is one). paymentCompletion: payment's completed share.
     */
    public record E8Result(String example, String arm, double falseSignalRate, Integer firstSignalMinutes, int minutesOutsideBand, int proposals,
                           double paymentCompletion, long finalDeadlineMs, double finalRate) {}

    /** Every duration of a profile shifted by {@code ms} (the change of the healthy queue wait). */
    public static ChainSimulation.Profile shifted(ChainSimulation.Profile p, double ms) {
        return new ChainSimulation.Profile(p.body().stream().map(s -> new ChainSimulation.Segment(s.fraction(), s.lo() + ms, s.hi() + ms)).toList(),
                Arrays.stream(p.tops()).map(t -> t + ms).toArray());
    }

    /**
     * Runs one example: at t0 the healthy rate of payment changes for good. With joint=false only the rate changes and
     * the deadline is left to the normal deadline logic. With joint=true the joint card is approved and applied at once. Afterwards the chain's healthy requests are faster or slower by the change of the queue wait; a simulated
     * reviewer approves every further recommendation. With {@code overloadAt} the payment service is overloaded from that minute
     * on (requests {@code overloadMs} slower).
     */
    public static E8Result e8(Example e, boolean joint, AdaptiveConfig.Settings settings, AdaptiveConfig.DeadlineSettings deadline, int minutes,
            Integer overloadAt, double overloadMs) {
        var bed = learned(e, settings, deadline, joint ? AdaptiveConfig.JointSettings.on() : AdaptiveConfig.JointSettings.defaults(), Source.MOCK,
                Instant.parse("2026-10-21T00:00:00Z"));
        var model = bed.engine.deadlineEvidence(CHAIN, Source.MOCK).model();
        double dw = 1000 * e.depth() / e.newTarget() - 1000 * e.depth() / e.rate();
        double thNew = model.thMs() + dw, m = model.margin();
        long bandLow = DeadlineMath.target(thNew, m, settings.minimumDeadlineMs(), settings.maximumDeadlineMs());
        double bandHigh = (1 + deadline.deadbandHealthy()) * bandLow;
        var after = shifted(e.healthy(), dw);
        var decided = new HashSet<UUID>(); int decisions = 0;
        bed.healthyTarget(e.newTarget());
        var first = bed.cycle(new Bed.Traffic(e.healthy(), e.demand(), e.depth()));
        if (joint) {
            var parts = first.stream().filter(c -> bed.workflow.isJointPart(c.proposal().id())).toList();
            if (parts.isEmpty()) throw new IllegalStateException("no joint card for " + e.id());
            bed.workflow.decide(parts.get(0).proposal().id(), new Review("e8-reviewer", true, "E8"));
            for (var p : parts) { bed.apply(bed.workflow.find(p.proposal().id())); decided.add(p.proposal().id()); }
            decisions++;
        } else {
            // today: the rate recommendation alone is approved and applied
            var p = bed.workflow.current(SERVICE, Source.MOCK);
            var rate = bed.workflow.offer(new Proposal(UUID.randomUUID(), SERVICE, Action.LEAKY_BUCKET, Source.MOCK, p.version(), p.bucketRate(), e.newTarget(),
                    "requests/second", bed.clock.now, bed.clock.now.plus(settings.ttl()), Map.of("windowEndEpochSeconds", (double) bed.clock.now.getEpochSecond()),
                    List.of("E8 arm (a): the healthy rate change alone")));
            bed.workflow.decide(rate.proposal().id(), new Review("e8-reviewer", true, "E8")); bed.apply(bed.workflow.find(rate.proposal().id()));
            decided.add(rate.proposal().id()); decisions++;
        }
        double healthy = 0, falseSignals = 0, completed = 0, offered = 0; Integer firstSignal = null; int outside = 0;
        for (int minute = 1; minute <= minutes; minute++) {
            boolean overload = overloadAt != null && minute >= overloadAt;
            var traffic = overload ? new Bed.Traffic(shifted(after, overloadMs), e.demand(), e.depth(), 400, 0, 0, true) : new Bed.Traffic(after, e.demand(), e.depth());
            var changes = bed.cycle(traffic);
            double rate = bed.rate();
            completed += Math.min(e.demand(), rate); offered += e.demand();
            if (!overload) {
                healthy += 300; falseSignals += bed.lastExpiredShare * 300;
                long d = bed.deadlineMs(); if (d < bandLow || d > bandHigh) outside++;
            } else if (firstSignal == null && bed.lastExpiredShare >= deadline.shareThreshold()) firstSignal = minute - overloadAt;
            for (var c : changes) {
                if (c.decision() != Decision.PENDING_APPROVAL || !decided.add(c.proposal().id())) continue;
                bed.workflow.decide(c.proposal().id(), new Review("e8-reviewer", true, "E8")); decisions++;
                var parts = bed.workflow.isJointPart(c.proposal().id()) ? bed.workflow.jointParts(bed.workflow.jointOf(c.proposal().id())) : List.of(bed.workflow.find(c.proposal().id()));
                for (var p : parts) { decided.add(p.proposal().id()); if (p.delivery() != Delivery.APPLIED) bed.apply(bed.workflow.find(p.proposal().id())); }
            }
        }
        return new E8Result(e.id(), joint ? "(b) joint" : "(a) rate alone", healthy == 0 ? 0 : falseSignals / healthy, firstSignal, outside, decisions,
                offered == 0 ? 1 : completed / offered, bed.deadlineMs(), bed.rate());
    }

    /** A bed for one example, learned until a healthy target change can be judged. */
    public static Bed learned(Example e, AdaptiveConfig.Settings settings, AdaptiveConfig.DeadlineSettings deadline, AdaptiveConfig.JointSettings joint,
            Source source, Instant start) {
        var bed = new Bed(settings, deadline, joint, source, start);
        bed.policies(e.rate(), e.rate(), 0, e.deadlineMs());
        for (int i = 0; i < learningCycles(settings, deadline); i++) bed.cycle(new Bed.Traffic(e.healthy(), e.demand(), e.depth()));
        return bed;
    }
    /** Enough cycles for the service baselines (health, P0, Q) and for the chain to be READY and settled. */
    public static int learningCycles(AdaptiveConfig.Settings settings, AdaptiveConfig.DeadlineSettings deadline) {
        int perWindow = (int) Math.max(1, settings.window().toSeconds() / settings.cycle().toSeconds());
        long settle = (settings.window().multipliedBy(2).plus(settings.cooldown())).toSeconds() / Math.max(1, settings.cycle().toSeconds());
        return Math.max(settings.baselineSamples() * 2 + 10, (deadline.minLearningWindows() + deadline.persistenceWindows() + 2) * perWindow + (int) settle);
    }
}
