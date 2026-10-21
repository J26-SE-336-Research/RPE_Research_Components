package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Deterministic test bed for the joint healthy recalibration (C3 request): one chain {@code gateway > payment} analysed per
 * request, and payment's service window with its leaky-bucket queue, one window of each per cycle, in an isolated engine on a
 * virtual clock. Used by the joint demo scenarios, the E8 simulation and the tests.
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
    /** C3 example 1: T_h = 700 ms (slow requests 660 / 700 / 740 ms by window: m ≈ 0.25), Q = 8, payment 50 → 100/s, 875 → 780 ms. */
    public static Example recovery() {
        return new Example("joint-recovery", "Joint recovery: payment's healthy target 50 → 100 req/s", 50, 100, 40, 8, 875,
                ChainSimulation.profile(300, 640, 660, 700, 740), 780, "one joint card: payment 50 → 100 req/s with the deadline 875 → 780 ms");
    }
    /** C3 example 2: T_h = 620 ms (585 / 620 / 655 ms: m ≈ 0.25), Q = 8, payment 100 → 60/s, 780 → 850 ms. */
    public static Example capacityDrop() {
        return new Example("joint-capacity-drop", "Joint capacity drop: payment's healthy target 100 → 60 req/s", 100, 60, 48, 8, 780,
                ChainSimulation.profile(300, 560, 585, 620, 655), 850, "one joint card: payment 100 → 60 req/s with the deadline 780 → 850 ms");
    }
    public static List<Example> examples() { return List.of(recovery(), capacityDrop()); }

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
        public record Traffic(ChainSimulation.Profile chain, double demand, double depth, double appMs, double retries, double retrySuccesses) {
            public Traffic(ChainSimulation.Profile chain, double demand, double depth) { this(chain, demand, depth, 40, 0, 0); }
        }
        /** One cycle: the chain window (stamped with the current deadline), then payment's service window. Returns the new changes. */
        public List<PolicyChange> cycle(Traffic t) {
            long budget = deadlineMs();
            var phase = new ChainSimulation.Phase("p", 1, List.of(new ChainSimulation.PathTraffic(1, t.chain(), GATEWAY, SERVICE)));
            var set = ChainSimulation.samples(phase, cycle, budget, 300, clock.now, "POST", "/api/orders", "j");
            double failures = set.samples().stream().filter(DeadlineSample::expired).count();
            var w = new ChainWindow(CHAIN, List.of(GATEWAY, SERVICE), clock.now.minus(settings.window()), clock.now, chainSource, true, 300.0, failures,
                    TempoWindows.percentile99(set.samples().stream().mapToDouble(DeadlineSample::durationMs).toArray()), Health.HEALTHY, List.of("j"));
            var changes = new ArrayList<PolicyChange>(engine.chain(w, new ChainEvidence(set, Map.of(), Map.of(GATEWAY, Health.HEALTHY, SERVICE, Health.HEALTHY), Set.of()),
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
