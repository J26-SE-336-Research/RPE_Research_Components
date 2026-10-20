package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.*;
import java.util.*;

/**
 * Deterministic synthetic request chains on a virtual clock, for the isolated simulations (MockScenarios) and the
 * evaluation. Nothing here touches live baselines or Component 3: the source is always MOCK.
 */
public final class ChainSimulation {
    private ChainSimulation() {}

    public static final class VirtualClock extends Clock {
        public Instant now;
        public VirtualClock(Instant now) { this.now = now; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    /** Durations: each body segment spreads its share of requests evenly between lo and hi; the rest (the top) cycles through {@code tops} per window. */
    public record Segment(double fraction, double lo, double hi) {}
    public record Profile(List<Segment> body, double[] tops) {
        public double[] durations(int n, int window) {
            var out = new ArrayList<Double>();
            for (var s : body) {
                int c = (int) Math.round(s.fraction() * n);
                for (int i = 0; i < c && out.size() < n; i++) out.add(c == 1 ? s.lo() : s.lo() + (s.hi() - s.lo()) * i / (c - 1));
            }
            double top = tops[Math.floorMod(window, tops.length)];
            while (out.size() < n) out.add(top);
            return out.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        }
    }
    public static Profile profile(double lo, double hi, double... tops) { return new Profile(List.of(new Segment(.97, lo, hi)), tops); }

    /** One path of the chain: services, share of requests, durations, status; {@code cutNearDeadline}: ended with the status at 0.99·D. */
    public record PathTraffic(List<String> services, double share, Profile profile, int status, boolean cutNearDeadline) {
        public PathTraffic(double share, Profile profile, String... services) { this(List.of(services), share, profile, 200, false); }
    }
    /** A stretch of time with fixed traffic, deadline-guard health and load signals per service. */
    public record Phase(String name, int cycles, List<PathTraffic> paths, Map<String, Health> guards, Map<String, ServiceLoadSnapshot> loads) {
        public Phase(String name, int cycles, List<PathTraffic> paths) { this(name, cycles, paths, Map.of(), Map.of()); }
    }
    public record Script(String chain, long initialMs, List<Phase> phases) {
        int cycles() { return phases.stream().mapToInt(Phase::cycles).sum(); }
    }
    public record Step(String chain, int cycle, String phase, long fromMs, long toMs, DeadlineCase decisionCase, String headline, String outcome) {}
    public record ChainResult(String chain, long finalMs, List<Long> phaseStartMs, List<Long> phaseEndMs, List<Boolean> phaseIncreased,
            List<Step> steps, DeadlineEvidence lastEvidence, TempoWindows.Collection lastWindow, List<DeadlineEvidence> evidence) {}
    public record Result(List<Outcome> outcomes, List<ChainResult> chains) {}

    /** The requests of one window of a phase at deadline {@code deadlineMs}, as TempoWindows would deliver them. */
    public static ChainSamples samples(Phase phase, int window, long deadlineMs, int n, Instant end, String method, String route, String idPrefix) {
        var traces = new ArrayList<TempoWindows.TraceSample>(); int i = 0;
        long endNanos = end.getEpochSecond() * 1_000_000_000L;
        for (var path : phase.paths()) {
            int count = (int) Math.round(n * path.share());
            for (double d : path.profile().durations(count, window)) {
                double ms = path.cutNearDeadline() ? deadlineMs * .99 : d;
                boolean expired = ms >= deadlineMs && !path.cutNearDeadline();
                traces.add(new TempoWindows.TraceSample(idPrefix + "-" + window + "-" + (i++), route, method, path.services(), ms,
                        endNanos - (long) (ms * 1_000_000), endNanos, (double) deadlineMs, 0, path.status(), expired));
            }
        }
        return TempoWindows.deadlineSamples(traces);
    }

    /** Runs the scripts side by side, one window per cycle; with {@code approve} a simulated reviewer approves every proposal and it is applied on the next cycle. */
    public static Result run(AdaptiveEngine engine, PolicyWorkflow workflow, VirtualClock clock, AdaptiveConfig.Settings settings,
            List<Script> scripts, boolean approve, int requestsPerWindow) {
        var outcomes = new ArrayList<Outcome>();
        var deadline = new HashMap<String, Long>(); var version = new HashMap<String, Integer>(); var toApply = new HashMap<String, UUID>();
        var seen = new HashSet<UUID>(); var steps = new HashMap<String, List<Step>>(); var evidence = new HashMap<String, List<DeadlineEvidence>>();
        var phaseStart = new HashMap<String, List<Long>>(); var phaseEnd = new HashMap<String, List<Long>>(); var increased = new HashMap<String, List<Boolean>>();
        var lastWindow = new HashMap<String, TempoWindows.Collection>();
        for (var s : scripts) {
            workflow.putPolicy(new Policy(s.chain(), "sim-v1", clock.now.plus(Duration.ofDays(30)), null, null, null, (double) s.initialMs()), Source.MOCK);
            deadline.put(s.chain(), s.initialMs()); version.put(s.chain(), 1);
            steps.put(s.chain(), new ArrayList<>()); evidence.put(s.chain(), new ArrayList<>());
            phaseStart.put(s.chain(), new ArrayList<>()); phaseEnd.put(s.chain(), new ArrayList<>()); increased.put(s.chain(), new ArrayList<>());
        }
        int total = scripts.stream().mapToInt(Script::cycles).max().orElse(0);
        for (int cycle = 0; cycle < total; cycle++) {
            for (var s : scripts) {
                Phase phase = phaseAt(s, cycle); if (phase == null) continue;
                String chain = s.chain();
                if (isPhaseStart(s, cycle)) { phaseStart.get(chain).add(deadline.get(chain)); increased.get(chain).add(false); }
                UUID pending = toApply.remove(chain);
                if (pending != null) {
                    var change = workflow.find(pending);
                    if (change.decision() == Decision.APPROVED && List.of(Delivery.WAITING_FOR_COMPONENT_3, Delivery.READY, Delivery.SENT, Delivery.FAILED).contains(change.delivery())) {
                        int v = version.merge(chain, 1, Integer::sum);
                        workflow.deliveryResult(pending, new Acknowledgement("APPLIED", "sim-v" + v, change.proposal().proposedValue()), null);
                        long applied = Math.round(change.proposal().proposedValue());
                        if (applied > deadline.get(chain)) { var list = increased.get(chain); list.set(list.size() - 1, true); }
                        deadline.put(chain, applied);
                    }
                }
                String[] identity = chain.split(" \\| ", 2)[0].split(" ", 2);
                var set = samples(phase, cycle, deadline.get(chain), requestsPerWindow, clock.now, identity[0], identity[1], "sim");
                var included = set.samples();
                var services = new LinkedHashSet<String>(); phase.paths().forEach(p -> services.addAll(p.services()));
                Health health = Health.HEALTHY;
                for (String svc : services) {
                    Health h = phase.guards().getOrDefault(svc, Health.HEALTHY);
                    if (h == Health.OVERLOADED) health = h; else if (h == Health.STRESSED && health == Health.HEALTHY) health = h;
                }
                var guards = new HashMap<String, Health>(); for (String svc : services) guards.put(svc, phase.guards().getOrDefault(svc, Health.HEALTHY));
                int requests = included.size() + set.excluded().values().stream().mapToInt(Integer::intValue).sum();
                double failures = included.stream().filter(DeadlineSample::expired).count();
                double p99 = included.isEmpty() ? 1 : TempoWindows.percentile99(included.stream().mapToDouble(DeadlineSample::durationMs).toArray());
                var window = new ChainWindow(chain, List.copyOf(services), clock.now.minus(settings.window()), clock.now, Source.MOCK, true,
                        (double) requests, failures, p99, health, included.stream().map(DeadlineSample::traceId).limit(5).toList());
                lastWindow.put(chain, new TempoWindows.Collection(window, (double) deadline.get(chain), List.of(), List.of(), null, set));
                var outcome = engine.chain(window, new ChainEvidence(set, phase.loads(), guards, Set.of()), Map.of(), List.of());
                outcomes.add(outcome);
                var ev = engine.deadlineEvidence(chain, Source.MOCK); if (ev != null) evidence.get(chain).add(ev);
                for (var change : outcome.changes()) {
                    if (change.decision() != Decision.PENDING_APPROVAL || !seen.add(change.proposal().id())) continue;
                    steps.get(chain).add(new Step(chain, cycle, phase.name(), Math.round(change.proposal().currentValue()), Math.round(change.proposal().proposedValue()),
                            ev == null ? null : ev.decisionCase(), change.proposal().evidence().get(0), approve ? "approved and applied" : "pending approval"));
                    if (approve) { workflow.decide(change.proposal().id(), new Review("simulated-reviewer", true, "simulation")); toApply.put(chain, change.proposal().id()); }
                }
                if (isPhaseEnd(s, cycle)) phaseEnd.get(chain).add(deadline.get(chain));
            }
            clock.now = clock.now.plus(settings.cycle());
        }
        var results = new ArrayList<ChainResult>();
        for (var s : scripts) {
            var ev = evidence.get(s.chain());
            results.add(new ChainResult(s.chain(), deadline.get(s.chain()), phaseStart.get(s.chain()), phaseEnd.get(s.chain()), increased.get(s.chain()),
                    steps.get(s.chain()), ev.isEmpty() ? null : ev.get(ev.size() - 1), lastWindow.get(s.chain()), ev));
        }
        return new Result(outcomes, results);
    }
    private static Phase phaseAt(Script s, int cycle) {
        int c = 0; for (var p : s.phases()) { if (cycle < c + p.cycles()) return p; c += p.cycles(); } return null;
    }
    private static boolean isPhaseStart(Script s, int cycle) { int c = 0; for (var p : s.phases()) { if (cycle == c) return true; c += p.cycles(); } return false; }
    private static boolean isPhaseEnd(Script s, int cycle) { int c = 0; for (var p : s.phases()) { c += p.cycles(); if (cycle == c - 1) return true; } return false; }
}
