package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import static org.rrpe.intelligence.adaptive.ChainSimulation.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Isolated virtual-time demonstrations. Neither production baselines nor the delivery outbox are shared. */
@Component
public class MockScenarios {
    private final AdaptiveConfig.Settings settings;
    private final AdaptiveConfig.Connections connections;
    private AdaptiveConfig.DeadlineSettings deadline = AdaptiveConfig.DeadlineSettings.defaults();
    private AdaptiveConfig.LoadSettings load = AdaptiveConfig.LoadSettings.defaults();
    private AdaptiveConfig.LeverSettings lever = AdaptiveConfig.LeverSettings.defaults();
    private AdaptiveConfig.RateSettings rate; private AdaptiveConfig.RetrySettings retry;
    private AdaptiveConfig.BaselineSettings baseline; private AdaptiveConfig.AutoSettings auto;
    public MockScenarios(AdaptiveConfig.Settings settings, AdaptiveConfig.Connections connections) { this.settings = settings; this.connections = connections; }
    @Autowired(required = false)
    public void configureDeadline(AdaptiveConfig.DeadlineSettings deadline, AdaptiveConfig.LoadSettings load, AdaptiveConfig.LeverSettings lever) {
        if (deadline != null) this.deadline = deadline;
        if (load != null) this.load = load;
        if (lever != null) this.lever = lever;
    }
    @Autowired(required = false)
    public void configureRate(AdaptiveConfig.RateSettings rate, AdaptiveConfig.RetrySettings retry, AdaptiveConfig.BaselineSettings baseline,
            AdaptiveConfig.AutoSettings auto) {
        this.rate = rate; this.retry = retry; this.baseline = baseline; this.auto = auto;
    }
    private static class SimulationClock extends Clock {
        Instant now;
        SimulationClock(Instant now) { this.now = now; }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    static final List<String> SERVICE_SCENARIOS = List.of("healthy", "stressed", "overloaded", "retry-stressed", "recovery");
    /** The deadline examples, run with a simulated reviewer that approves every proposal. */
    static final List<String> EXAMPLE_SCENARIOS = DeadlineExamples.all().stream().map(DeadlineExamples.Example::scenario).toList();
    /** The rate and retry examples on the service model, run with a simulated reviewer that approves every proposal. */
    static final List<String> RATE_SCENARIOS = ServiceSimulation.scenarios().stream().map(ServiceSimulation.Scenario::id).toList();
    /** Joint healthy recalibration (C3 request, examples 1 and 2): one joint card with C3's numbers. */
    static final List<String> JOINT_SCENARIOS = JointSimulation.examples().stream().map(JointSimulation.Example::id).toList();
    public Map<String, Object> run(String scenario) { return run(scenario, false); }
    /** The automatic-approval list of the demo cluster (deploy/k8s/platform/intelligence.yaml), used by {@code ?mode=auto}. */
    static final List<String> AUTO_ACTIONS = List.of("BUCKET_RAISE", "BUCKET_CUT", "RETRY_DECREASE");
    /**
     * With {@code auto} (POST /demo/{scenario}?mode=auto) the rate and retry scenarios run in an isolated engine with automatic
     * approval on: changes the guard allows are approved by {@code c4-auto}, the others wait for a person. The isolated workflow
     * has no Component 3 URL, so nothing is ever delivered. Other scenarios run as without it (deadline and joint cards are never
     * automatic). Without {@code auto} every scenario runs exactly as before.
     */
    public Map<String, Object> run(String scenario, boolean auto) {
        if (auto && !RATE_SCENARIOS.contains(scenario)) {
            var out = new LinkedHashMap<String, Object>(run(scenario, false));
            out.put("mode", "auto"); out.put("automaticApproval", "not used: only the rate and retry scenarios have automatic changes");
            return out;
        }
        if (auto) return rateScenario(scenario, true);
        if (scenario.equals("multiple-chains")) return multipleChains();
        if (List.of("deadline-increase", "deadline-recovery").contains(scenario)) return deadlineScenario(scenario);
        if (EXAMPLE_SCENARIOS.contains(scenario)) return example(scenario);
        if (RATE_SCENARIOS.contains(scenario)) return rateScenario(scenario, false);
        if (JOINT_SCENARIOS.contains(scenario)) return jointScenario(scenario);
        if (!SERVICE_SCENARIOS.contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
        var response = new LinkedHashMap<>(serviceScenario(scenario));
        var chainPart = chainPartOf(scenario);
        if (chainPart != null) {
            @SuppressWarnings("unchecked") var changes = new ArrayList<>((List<PolicyChange>) response.get("changes"));
            @SuppressWarnings("unchecked") var chainChanges = (List<PolicyChange>) chainPart.get("changes");
            changes.addAll(chainChanges);
            response.put("changes", changes);
            response.put("chains", chainPart.get("chains")); response.put("deadlineEvidence", chainPart.get("deadlineEvidence"));
        }
        return response;
    }
    /** The deadline side of the service scenarios: overloaded gives Case B or C, stressed and retry-stressed give no increase. */
    private Map<String, Object> chainPartOf(String scenario) {
        int learn = learningCycles() + perWindow(), decide = decisionCycles() + 10;
        String chain = "POST /api/orders | mock-api-gateway > mock-order-service";
        var learnPhase = new Phase("healthy (learning)", learn, List.of(new PathTraffic(1, HEALTHY_400, "mock-api-gateway", "mock-order-service")));
        return switch (scenario) {
            case "overloaded" -> simulate(scenario, List.of(new Script(chain, 600, List.of(learnPhase,
                    new Phase("order overloaded, not signalled", decide, List.of(new PathTraffic(1, profile(520, 580, 585, 590, 595), "mock-api-gateway", "mock-order-service")),
                            Map.of("mock-order-service", Health.OVERLOADED), Map.of())))), false, "");
            case "stressed", "retry-stressed" -> simulate(scenario, List.of(new Script(chain, 600, List.of(learnPhase,
                    new Phase("order stressed, slower", decide, List.of(new PathTraffic(1, profile(300, 560, 600, 620, 640), "mock-api-gateway", "mock-order-service")),
                            Map.of("mock-order-service", Health.STRESSED), Map.of())))), false, "");
            default -> null;
        };
    }
    private Map<String, Object> example(String scenario) {
        var e = DeadlineExamples.byScenario(scenario, Math.max(DeadlineExamples.LEARN, learningCycles() + perWindow()));
        var response = simulate(scenario, List.of(e.script()), true,
                "Isolated synthetic simulation of " + e.title() + ". A simulated reviewer approves every proposal; nothing is sent to Component 3. Expected: " + e.expectation());
        response.put("simulatedApprovals", true);
        return response;
    }
    private Map<String, Object> serviceScenario(String scenario) {
        var clock = new SimulationClock(Instant.now().minusSeconds(settings.baselineSamples() * settings.cycle().toSeconds() + 900));
        var workflow = new PolicyWorkflow(connections, clock); var engine = new AdaptiveEngine(settings, workflow, clock);
        engine.configureRate(rate, retry, baseline, auto);
        String service = "mock-order-service";
        boolean recovery = scenario.equals("recovery"), overloaded = scenario.equals("overloaded");
        workflow.putPolicy(new Policy(service, "mock-v1", clock.now.plusSeconds(7200), recovery ? 60.0 : 120.0, 120.0, recovery ? .02 : .05, null), Source.MOCK);
        double[] latency = {80,90,100,110,120}; double[] capacity = {90,105,120,135,150};
        // The rate logic needs the queue data. The queue is quiet while learning. The overloaded app is slow, 200 ms
        // against a 100 ms baseline, with only 2 requests waiting, so the app itself is clearly slow.
        for (int i = 0; i < settings.baselineSamples(); i++) {
            engine.service(service(service, clock.now, latency[i % 5], capacity[i % 5], recovery ? 100 : 0, recovery ? 40 : 0), queue(service, 20, 0));
            clock.now = clock.now.plus(settings.cycle());
        }
        clock.now = clock.now.plus(settings.window());
        var outcomes = new ArrayList<Outcome>();
        for (int cycle = 0; cycle < settings.recoveryWindows(); cycle++) {
            // retry-stressed: only 10 of 100 retries succeed, so the budget steps down
            outcomes.add(engine.service(service(service, clock.now, overloaded ? 200 : scenario.equals("stressed") ? 125 : 100,
                    overloaded ? 80 : scenario.equals("stressed") ? 100 : 120,
                    recovery || scenario.equals("retry-stressed") ? 100 : 0, recovery ? 40 : scenario.equals("retry-stressed") ? 10 : 0),
                    overloaded ? queue(service, 60, 2) : queue(service, 20, 0)));
            clock.now = clock.now.plus(settings.cycle());
        }
        return Map.of("scenario", scenario, "source", "MOCK", "virtualTime", true,
                "message", "Isolated synthetic simulation. No live baseline or Component 3 delivery is modified.", "outcomes", outcomes, "changes", workflow.list());
    }
    private static ServiceLoadSnapshot queue(String service, double percent, double depth) {
        return new ServiceLoadSnapshot(service, percent, 0.0, 0.0, 0.0, null, null, null, null, List.of(), depth);
    }
    /**
     * A joint healthy recalibration (C3 request, examples 1 and 2) in an isolated engine with joint recalibration on: payment and
     * the chain are learned, the operator changes payment's healthy target, and C4 offers ONE joint card (rate + deadline),
     * waiting for a reviewer. Never sent to Component 3.
     */
    private Map<String, Object> jointScenario(String id) {
        var e = JointSimulation.examples().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        int cycles = JointSimulation.learningCycles(settings, deadline) + 2;
        var bed = JointSimulation.learned(e, settings, deadline, AdaptiveConfig.JointSettings.on(), Source.MOCK, Instant.now().minus(settings.cycle().multipliedBy(cycles + 10L)));
        bed.healthyTarget(e.newTarget());
        var parts = bed.cycle(new JointSimulation.Bed.Traffic(e.healthy(), e.demand(), e.depth())).stream().filter(c -> bed.workflow.isJointPart(c.proposal().id())).toList();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("service", JointSimulation.SERVICE); evidence.put("chain", JointSimulation.CHAIN); evidence.put("title", e.title()); evidence.put("expectation", e.expectation());
        for (var p : parts) {
            if (p.proposal().action() == Action.LEAKY_BUCKET) { evidence.put("rateFrom", p.proposal().currentValue()); evidence.put("rateTo", p.proposal().proposedValue()); }
            else { evidence.put("deadlineFromMs", p.proposal().currentValue()); evidence.put("deadlineToMs", p.proposal().proposedValue()); evidence.put("calculation", p.proposal().measurements()); }
        }
        if (!parts.isEmpty()) evidence.put("jointId", String.valueOf(bed.workflow.jointOf(parts.get(0).proposal().id())));
        var response = new LinkedHashMap<String, Object>();
        response.put("scenario", id); response.put("source", "MOCK"); response.put("virtualTime", true); response.put("simulatedApprovals", false);
        response.put("message", "Isolated synthetic simulation of " + e.title() + ". The joint card waits for a reviewer; nothing is sent to Component 3. Expected: " + e.expectation());
        response.put("outcomes", List.of(bed.lastService)); response.put("changes", bed.workflow.list()); response.put("joints", bed.workflow.joints());
        response.put("jointEvidence", List.of(evidence));
        return response;
    }
    /** A rate or retry example on the service model, with a simulated reviewer. Nothing is sent to Component 3. */
    private Map<String, Object> rateScenario(String id, boolean auto) {
        var s = ServiceSimulation.byId(id);
        // The learning phase lasts long enough to learn P0 with the configured baseline-samples (5 in the demo, 30 by default).
        var phases = new ArrayList<>(s.phases()); var learn = phases.get(0);
        if (learn.name().startsWith("healthy (learn)") && learn.cycles() < settings.baselineSamples() + 6) {
            phases.set(0, new ServiceSimulation.Phase(learn.name(), settings.baselineSamples() + 6, learn.demand(), learn.capacity(), learn.appMs(),
                    learn.retryPerFailure(), learn.retrySuccess(), learn.burstDemand()));
            s = new ServiceSimulation.Scenario(s.id(), s.title(), s.rate(), s.retryRatio(), List.copyOf(phases), s.expectation());
        }
        int cycles = s.phases().stream().mapToInt(ServiceSimulation.Phase::cycles).sum();
        var clock = new VirtualClock(Instant.now().minus(settings.cycle().multipliedBy(cycles + 10L)));
        var workflow = new PolicyWorkflow(auto ? withoutDelivery(connections) : connections, settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock); engine.configureRate(rate, retry, baseline, this.auto);
        // ?mode=auto: a live-labelled source inside the isolated engine, because synthetic (MOCK) changes are never automatic
        Source source = auto ? Source.PROMETHEUS : Source.MOCK;
        if (auto) engine.configureAutoApproval(new AdaptiveConfig.AutoApproval(AUTO_ACTIONS));
        String service = "mock-inventory-service";
        var r = ServiceSimulation.run(engine, workflow, clock, settings, service, source, s, !auto);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("service", service); evidence.put("title", s.title()); evidence.put("expectation", s.expectation());
        evidence.put("initialRate", s.rate()); evidence.put("finalRate", r.finalRate()); evidence.put("lowestRate", r.lowestRate());
        evidence.put("initialRetry", s.retryRatio()); evidence.put("finalRetry", r.finalRetry()); evidence.put("shedAtEnd", r.shedAtEnd());
        evidence.put("steps", r.steps());
        var rs = engine.rateState(service, source);
        if (rs.lastDecision != null) evidence.put("evidence", AdaptiveEngine.rateEvidence(rs, clock.now));
        var response = new LinkedHashMap<String, Object>();
        response.put("scenario", id); response.put("source", "MOCK"); response.put("virtualTime", true); response.put("simulatedApprovals", true);
        response.put("message", "Isolated synthetic simulation of " + s.title() + ". A simulated reviewer approves every proposal; nothing is sent to Component 3. Expected: " + s.expectation());
        response.put("outcomes", r.outcomes().isEmpty() ? List.of() : List.of(r.outcomes().get(r.outcomes().size() - 1)));
        response.put("changes", workflow.list()); response.put("rateEvidence", List.of(evidence));
        if (auto) {
            response.put("simulatedApprovals", false); response.put("mode", "auto"); response.put("automaticApproval", AUTO_ACTIONS);
            response.put("message", "Isolated simulation of " + s.title() + " with automatic approval ON (" + String.join(", ", AUTO_ACTIONS)
                    + "): changes the guard allows are approved by c4-auto, the others wait for a person; nothing is sent to Component 3. Expected: " + s.expectation());
        }
        return response;
    }

    /** The connections without a Component 3 URL: an isolated automatic-mode simulation can never deliver. */
    private static AdaptiveConfig.Connections withoutDelivery(AdaptiveConfig.Connections c) {
        return new AdaptiveConfig.Connections(false, c.prometheusUrl(), c.tempoUrl(), c.namespace(), c.services(), c.gatewayService(), c.timeout(),
                c.scrapeInterval(), c.maximumScrapeAge(), c.traceLimit(), "", "", c.metricNames());
    }

    // ---------- deadline simulations with made-up requests ----------

    static final String ORDERS = "POST /api/orders | mock-api-gateway > mock-order-service";
    /** Healthy time of about 400 ms: most requests take 150 to 350 ms, the slowest 3 percent take 380, 400 or 420 ms. */
    static final Profile HEALTHY_400 = profile(150, 350, 380, 400, 420);
    /** True healthy p99 ≈ 1200 ms: about 25% of requests take longer than 1000 ms. */
    static final Profile HEALTHY_1200 = profile(500, 1150, 1180, 1200, 1220);
    /** Healthy time ≈ 1000 ms. */
    static final Profile HEALTHY_1000 = profile(500, 950, 980, 1000, 1020);

    private int perWindow() { return (int) Math.max(1, settings.window().toSeconds() / settings.cycle().toSeconds()); }
    private int learningCycles() { return deadline.minLearningWindows() * perWindow(); }
    private int decisionCycles() {
        long perWindow = Math.max(1, settings.window().toSeconds() / settings.cycle().toSeconds());
        return (int) ((deadline.persistenceWindows() + 2) * perWindow);
    }
    private Map<String, Object> simulate(String scenario, List<Script> scripts, boolean approve, String message) {
        var clock = new VirtualClock(Instant.now().minus(settings.cycle().multipliedBy(scripts.stream().mapToInt(Script::cycles).max().orElse(0) + 10L)));
        var workflow = new PolicyWorkflow(connections, settings, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock); engine.configureDeadline(deadline, load, lever);
        var result = ChainSimulation.run(engine, workflow, clock, settings, scripts, approve, 300);
        var chains = new ArrayList<TempoWindows.Collection>(); var evidence = new ArrayList<Map<String, Object>>();
        var outcomes = new ArrayList<Outcome>();
        for (var c : result.chains()) {
            chains.add(c.lastWindow());
            var entry = new LinkedHashMap<String, Object>();
            entry.put("chain", c.chain()); entry.put("initialDeadlineMs", c.phaseStartMs().isEmpty() ? null : c.phaseStartMs().get(0));
            entry.put("finalDeadlineMs", c.finalMs()); entry.put("steps", c.steps());
            if (c.lastEvidence() != null) entry.put("evidence", c.lastEvidence());
            evidence.add(entry);
        }
        // the last outcome of each chain (a simulation runs hundreds of cycles)
        var last = new LinkedHashMap<String, Outcome>();
        for (var o : result.outcomes()) last.put(o.assessment().target(), o);
        outcomes.addAll(last.values());
        var response = new LinkedHashMap<String, Object>();
        response.put("scenario", scenario); response.put("source", "MOCK"); response.put("virtualTime", true);
        response.put("message", message); response.put("outcomes", outcomes); response.put("changes", workflow.list());
        response.put("chains", chains); response.put("deadlineEvidence", evidence);
        return response;
    }
    private Map<String, Object> deadlineScenario(String scenario) {
        if (scenario.equals("deadline-increase")) {
            // Case A: the healthy chain is over-signalled (about 25% of healthy requests exceed 1000 ms).
            return simulate(scenario, List.of(new Script(ORDERS, 1000, List.of(new Phase("healthy, deadline too short", decisionCycles() + 10,
                    List.of(new PathTraffic(1, HEALTHY_1200, "mock-api-gateway", "mock-order-service")))))),
                    false, "Isolated synthetic simulation: healthy requests are over-signalled, so v2 proposes a raise (Case A).");
        }
        // Case D: healthy again (healthy time ≈ 400 ms, D* = 490 ms) after an earlier increase to 1440 ms.
        return simulate(scenario, List.of(new Script(ORDERS, 1440, List.of(new Phase("healthy, deadline too long", learningCycles() + 15,
                List.of(new PathTraffic(1, HEALTHY_400, "mock-api-gateway", "mock-order-service")))))),
                false, "Isolated synthetic simulation: the chain is healthy and the deadline is too long, so v2 proposes tightening (Case D).");
    }
    private Map<String, Object> multipleChains() {
        int learn = learningCycles(), decide = decisionCycles() + 10;
        var overloadedInventory = Map.of("mock-inventory-service", Health.OVERLOADED);
        var scripts = List.of(
                new Script("POST /api/orders | mock-api-gateway > mock-order-service > mock-inventory-service > mock-payment-service", 1000, List.of(
                        new Phase("healthy, deadline too short", learn + decide, List.of(new PathTraffic(1, HEALTHY_1200,
                                "mock-api-gateway", "mock-order-service", "mock-inventory-service", "mock-payment-service"))))),
                new Script("GET /api/inventory | mock-api-gateway > mock-inventory-service", 600, List.of(
                        new Phase("healthy (learning)", learn, List.of(new PathTraffic(1, HEALTHY_400, "mock-api-gateway", "mock-inventory-service"))),
                        new Phase("inventory overloaded, not signalled", decide, List.of(new PathTraffic(1, profile(520, 580, 585, 590, 595),
                                "mock-api-gateway", "mock-inventory-service")), overloadedInventory, Map.of()))),
                new Script("POST /api/notifications | mock-api-gateway > mock-notification-service", 1200, List.of(
                        new Phase("healthy, deadline too long", learn + decide, List.of(new PathTraffic(1, HEALTHY_400, "mock-api-gateway", "mock-notification-service"))))));
        return simulate("multiple-chains", scripts, false, "Isolated synthetic request chains (raise, lower under overload, tighten). No live policies are modified.");
    }

    private ServiceWindow service(String target, Instant end, double latency, double throughput, double retries, double successes) {
        return new ServiceWindow(target, end.minus(settings.window()), end, Source.MOCK, true, 1000.0, latency, 0.0, retries, successes,
                0.0, 10.0, throughput, true, true, 500.0, 100.0, List.of());
    }
}
