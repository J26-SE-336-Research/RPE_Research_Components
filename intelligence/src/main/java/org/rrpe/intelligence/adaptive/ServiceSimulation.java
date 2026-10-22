package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.*;
import java.util.*;

/**
 * Deterministic model of one service behind its leaky bucket, for the rate/retry simulations and the evaluation (the same
 * model was used for intelligence/evaluation/rate-retry-v1-baseline.md). The bucket admits {@code min(demand, rate)} req/s
 * and sheds the rest with 503 (queue full: utilisation 100%, depth 200). The app completes {@code min(admitted, capacity)};
 * above its capacity the app time grows and the excess fails. Measured p95 = app time + queue wait ({@code depth / rate}),
 * as the sidecar measures it. Retry attempts = retries per shed or failed request. ±4% jitter per cycle.
 */
public final class ServiceSimulation {
    private ServiceSimulation() {}

    public record Phase(String name, int cycles, double demand, double capacity, double appMs, double retryPerFailure, double retrySuccess, double burstDemand) {
        public Phase(String name, int cycles, double demand, double capacity, double appMs) { this(name, cycles, demand, capacity, appMs, 0, 0, 0); }
    }
    public record Scenario(String id, String title, double rate, double retryRatio, List<Phase> phases, String expectation) {}
    public record Window(double original, double p95, double errors, double retries, double retrySuccess, double rejections, double accepted,
            double queuePercent, double queueDepth, double appMs, double shedPerSecond) {}
    public record Step(int cycle, String phase, Action action, double from, double to, String reviewer, String evidence) {}
    public record Result(String service, double finalRate, double lowestRate, double finalRetry, double shedAtEnd, List<Step> steps,
            List<Outcome> outcomes, AdaptiveEngine.RateDecision lastDecision) {}

    public static Window model(Phase p, double rate, int cycle, double windowSeconds) {
        double jitter = 1 + .02 * ((cycle % 5) - 2);
        if (p.burstDemand() > 0) {
            // Repeating bursts inside every window: half the time at the burst demand, half at the base demand.
            var base = model(new Phase(p.name(), 1, p.demand(), p.capacity(), p.appMs(), p.retryPerFailure(), p.retrySuccess(), 0), rate, cycle, windowSeconds);
            var burst = model(new Phase(p.name(), 1, p.burstDemand(), p.capacity(), p.appMs(), p.retryPerFailure(), p.retrySuccess(), 0), rate, cycle, windowSeconds);
            return new Window((base.original() + burst.original()) / 2, Math.max(base.p95(), burst.p95()), (base.errors() + burst.errors()) / 2,
                    (base.retries() + burst.retries()) / 2, (base.retrySuccess() + burst.retrySuccess()) / 2, (base.rejections() + burst.rejections()) / 2,
                    (base.accepted() + burst.accepted()) / 2, Math.max(base.queuePercent(), burst.queuePercent()), Math.max(base.queueDepth(), burst.queueDepth()),
                    Math.max(base.appMs(), burst.appMs()), (base.shedPerSecond() + burst.shedPerSecond()) / 2);
        }
        double demand = p.demand();
        double admitted = Math.min(demand, rate), shed = Math.max(0, demand - rate);
        double accepted = Math.min(admitted, p.capacity()), failures = Math.max(0, admitted - p.capacity());
        double appMs = p.appMs() * (failures > 0 ? 1 + 14 * failures / p.capacity() : 1) * jitter;
        double depth = shed > 0 ? 200 : 0, pct = shed > 0 ? 100 : Math.min(45, 40 * admitted / Math.max(rate, 1));
        double p95 = appMs + (rate > 0 ? depth / rate * 1000 : 0);
        double retries = p.retryPerFailure() * (shed + failures);
        double w = windowSeconds;
        return new Window(demand * w, p95, failures * w, retries * w, retries * p.retrySuccess() * w, shed * w, accepted * jitter, pct, depth, appMs, shed);
    }

    /**
     * Runs a scenario for {@code service}: one window per cycle. With {@code humanApproves} a simulated reviewer approves
     * every pending proposal; automatic approvals (reviewer {@code c4-auto…}) are applied as well. Approved changes are applied
     * on the next cycle (as Component 3 would after a two-phase rollout).
     */
    public static Result run(AdaptiveEngine engine, PolicyWorkflow workflow, ChainSimulation.VirtualClock clock, AdaptiveConfig.Settings settings,
            String service, Source source, Scenario s, boolean humanApproves) {
        double rate = s.rate(), ratio = s.retryRatio(), lowest = rate; int version = 1;
        workflow.putPolicy(new Policy(service, "sim-v1", clock.now.plus(Duration.ofDays(30)), rate, rate, ratio, null), source);
        var seen = new HashSet<UUID>(); var steps = new ArrayList<Step>(); var outcomes = new ArrayList<Outcome>();
        var toApply = new ArrayList<UUID>(); int cycle = 0; Window last = null; double w = settings.window().toSeconds();
        for (var phase : s.phases()) for (int i = 0; i < phase.cycles(); i++, cycle++) {
            for (UUID id : toApply) {
                var c = workflow.find(id);
                if (c.decision() == Decision.APPROVED && List.of(Delivery.WAITING_FOR_COMPONENT_3, Delivery.READY, Delivery.SENT, Delivery.FAILED).contains(c.delivery())) {
                    workflow.deliveryResult(id, new Acknowledgement("APPLIED", "sim-v" + (++version), c.proposal().proposedValue()), null);
                    if (c.proposal().action() == Action.LEAKY_BUCKET) { rate = c.proposal().proposedValue(); lowest = Math.min(lowest, rate); }
                    else ratio = c.proposal().proposedValue();
                }
            }
            toApply.clear();
            var m = model(phase, rate, cycle, w); last = m;
            var window = new ServiceWindow(service, clock.now.minus(settings.window()), clock.now, source, true, m.original(), m.p95(), m.errors(),
                    m.retries(), m.retrySuccess(), m.rejections(), 10.0, m.accepted(), null, null, null, null, List.of());
            var load = new ServiceLoadSnapshot(service, m.queuePercent(), m.rejections(), 0.0, 0.0, null, null, null, null, List.of(), m.queueDepth());
            var outcome = engine.service(window, load); outcomes.add(outcome);
            for (var c : outcome.changes()) {
                if (!seen.add(c.proposal().id())) continue;
                boolean auto = c.decision() == Decision.APPROVED;
                if (c.decision() == Decision.PENDING_APPROVAL && humanApproves) c = workflow.decide(c.proposal().id(), new Review("simulated-reviewer", true, "simulation"));
                if (c.decision() == Decision.APPROVED) toApply.add(c.proposal().id());
                steps.add(new Step(cycle, phase.name(), c.proposal().action(), c.proposal().currentValue(), c.proposal().proposedValue(),
                        auto ? c.reviewer() : c.decision() == Decision.APPROVED ? "simulated-reviewer" : "pending", c.proposal().evidence().isEmpty() ? "" : c.proposal().evidence().get(c.proposal().evidence().size() - 1)));
            }
            clock.now = clock.now.plus(settings.cycle());
        }
        var rs = engine.rateState(service, source);
        return new Result(service, rate, lowest, ratio, last == null ? 0 : last.shedPerSecond(), steps, outcomes, rs.lastDecision);
    }

    /** The rate/retry scenarios of the guide (§4.4) plus a retry storm, identical to the old-logic baseline. */
    public static List<Scenario> scenarios() {
        return List.of(
            new Scenario("rate-too-low", "Example 1: rate too low (demand grows to 150 above the rate 100)", 100, .05, List.of(
                new Phase("healthy (learn), demand 80", 16, 80, 1000, 40),
                new Phase("demand 150 > rate 100, app fine", 60, 150, 1000, 40)),
                "raise the rate (about 150, then 187.5); never cut"),
            new Scenario("rate-too-low-day-one", "Example 1b: rate too low from day one (the bucket sheds from the start)", 100, .05, List.of(
                new Phase("demand 150 > rate 100 from the start, app fine", 76, 150, 1000, 40)),
                "learn the app time despite shedding, then raise"),
            new Scenario("rate-real-overload", "Example 2: real overload (app capacity drops to 60)", 100, .05, List.of(
                new Phase("healthy (learn), demand 90", 16, 90, 200, 40),
                new Phase("app capacity 60, demand 90", 60, 90, 60, 40),
                new Phase("app recovered (capacity 200)", 40, 90, 200, 40)),
                "cut once to the floor (50), not below; retry 0 while the app is overloaded"),
            new Scenario("rate-recovery", "Recovery after an earlier cut (rate 60, the app is fine again, demand 90)", 60, .02, List.of(
                new Phase("healthy (learn), demand 50", 16, 50, 200, 40),
                new Phase("demand 90 > rate 60, app recovered", 40, 90, 200, 40)),
                "raise back in steps of at most +50% to demand × 1.25; never cut"),
            new Scenario("rate-short-burst", "Example 3: one short burst (2x demand for one cycle)", 120, .05, List.of(
                new Phase("healthy (learn), demand 80", 16, 80, 1000, 40),
                new Phase("burst 160 for one cycle", 1, 160, 1000, 40),
                new Phase("back to 80", 30, 80, 1000, 40)),
                "no change"),
            new Scenario("rate-bursts", "Example 4: repeating bursts (80 / 160, inside every window)", 120, .05, List.of(
                new Phase("healthy (learn), demand 80", 16, 80, 1000, 40),
                new Phase("bursts 80 / 160", 60, 80, 1000, 40, 0, 0, 160)),
                "raise to cover the bursts (about 160 × 1.25 = 200); never cut"),
            new Scenario("retry-storm-rate", "Retry storm from a too-low rate (shed 503s are retried, 10% succeed)", 100, .05, List.of(
                new Phase("healthy (learn), demand 80", 16, 80, 1000, 40),
                new Phase("demand 130 > rate 100, every shed request retried once", 60, 130, 1000, 40, 1, .1, 0)),
                "raise the rate; hold the retry budget (do not punish retries for our own shedding)"));
    }
    public static Scenario byId(String id) { return scenarios().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(); }
}
