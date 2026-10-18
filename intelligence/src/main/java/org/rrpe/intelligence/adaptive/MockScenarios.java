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
    public MockScenarios(AdaptiveConfig.Settings settings, AdaptiveConfig.Connections connections) { this.settings = settings; this.connections = connections; }
    @Autowired(required = false)
    public void configureDeadline(AdaptiveConfig.DeadlineSettings deadline, AdaptiveConfig.LoadSettings load, AdaptiveConfig.LeverSettings lever) {
        if (deadline != null) this.deadline = deadline;
        if (load != null) this.load = load;
        if (lever != null) this.lever = lever;
    }
    private static class SimulationClock extends Clock {
        Instant now;
        SimulationClock(Instant now) { this.now = now; }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    static final List<String> SERVICE_SCENARIOS = List.of("healthy", "stressed", "overloaded", "retry-stressed", "recovery");
    /** Guide §7 worked examples (deadline v2), run with a simulated reviewer that approves every proposal. */
    static final List<String> EXAMPLE_SCENARIOS = DeadlineExamples.all().stream().map(DeadlineExamples.Example::scenario).toList();
    public Map<String, Object> run(String scenario) {
        if (scenario.equals("multiple-chains")) return multipleChains();
        if (List.of("deadline-increase", "deadline-recovery").contains(scenario)) return deadlineScenario(scenario);
        if (EXAMPLE_SCENARIOS.contains(scenario)) return example(scenario);
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
    /** The deadline side of the service scenarios: overloaded → Case B (or C), stressed and retry-stressed → no increase. */
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
        String service = "mock-order-service";
        boolean recovery = scenario.equals("recovery");
        workflow.putPolicy(new Policy(service, "mock-v1", clock.now.plusSeconds(7200), recovery ? 60.0 : 120.0, 120.0, recovery ? .02 : .05, null), Source.MOCK);
        double[] latency = {80,90,100,110,120}; double[] capacity = {90,105,120,135,150};
        for (int i = 0; i < settings.baselineSamples(); i++) {
            engine.service(service(service, clock.now, latency[i % 5], capacity[i % 5], recovery ? 100 : 0, recovery ? 40 : 0));
            clock.now = clock.now.plus(settings.cycle());
        }
        clock.now = clock.now.plus(settings.window());
        var outcomes = new ArrayList<Outcome>();
        for (int cycle = 0; cycle < settings.recoveryWindows(); cycle++) {
            outcomes.add(engine.service(service(service, clock.now, scenario.equals("overloaded") ? 150 : scenario.equals("stressed") ? 125 : 100,
                    scenario.equals("overloaded") ? 80 : scenario.equals("stressed") ? 100 : 120,
                    recovery || scenario.equals("retry-stressed") ? 100 : 0, recovery || scenario.equals("retry-stressed") ? 40 : 0)));
            clock.now = clock.now.plus(settings.cycle());
        }
        return Map.of("scenario", scenario, "source", "MOCK", "virtualTime", true,
                "message", "Isolated synthetic simulation. No live baseline or Component 3 delivery is modified.", "outcomes", outcomes, "changes", workflow.list());
    }

    // ---------- deadline v2 simulations (per-request synthetic traffic) ----------

    static final String ORDERS = "POST /api/orders | mock-api-gateway > mock-order-service";
    /** Healthy time ≈ 400 ms (guide example 1): body 150–350, top 3% cycling 380 / 400 / 420. */
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
