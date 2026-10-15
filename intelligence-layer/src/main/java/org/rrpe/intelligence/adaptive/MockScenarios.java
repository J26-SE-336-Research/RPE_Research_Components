package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Isolated virtual-time demonstrations. Neither production baselines nor the delivery outbox are shared. */
@Component
public class MockScenarios {
    private final AdaptiveConfig.Settings settings;
    private final AdaptiveConfig.Connections connections;
    public MockScenarios(AdaptiveConfig.Settings settings, AdaptiveConfig.Connections connections) { this.settings = settings; this.connections = connections; }
    private static class SimulationClock extends Clock {
        Instant now;
        SimulationClock(Instant now) { this.now = now; }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    public Map<String, Object> run(String scenario) {
        if (scenario.equals("multiple-chains")) return multipleChains();
        if (!List.of("healthy", "stressed", "overloaded", "retry-stressed", "recovery", "deadline-increase", "deadline-recovery").contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
        var clock = new SimulationClock(Instant.now().minusSeconds(settings.baselineSamples() * settings.cycle().toSeconds() + 900));
        var workflow = new PolicyWorkflow(connections, clock); var engine = new AdaptiveEngine(settings, workflow, clock);
        String service = "mock-order-service"; String chain = "POST /api/orders | mock-api-gateway > mock-order-service";
        boolean recovery = scenario.equals("recovery");
        workflow.putPolicy(new Policy(service, "mock-v1", clock.now.plusSeconds(7200), recovery ? 60.0 : 120.0, 120.0, recovery ? .02 : .05, null), Source.MOCK);
        workflow.putPolicy(new Policy(chain, "mock-v1", clock.now.plusSeconds(7200), null, null, null, scenario.equals("deadline-recovery") ? 1440.0 : 1000.0), Source.MOCK);
        double[] latency = {80,90,100,110,120}; double[] capacity = {90,105,120,135,150};
        for (int i = 0; i < settings.baselineSamples(); i++) {
            engine.service(service(service, clock.now, latency[i % 5], capacity[i % 5], recovery ? 100 : 0, recovery ? 40 : 0));
            engine.chain(chain(chain, clock.now, 1000, 0, Health.HEALTHY)); clock.now = clock.now.plus(settings.cycle());
        }
        clock.now = clock.now.plus(settings.window());
        var outcomes = new ArrayList<Outcome>();
        for (int cycle = 0; cycle < settings.recoveryWindows(); cycle++) {
            if (scenario.startsWith("deadline")) outcomes.add(engine.chain(chain(chain, clock.now,
                    scenario.equals("deadline-increase") ? 1200 : 1000, scenario.equals("deadline-increase") ? 20 : 0,
                    scenario.equals("deadline-increase") ? Health.STRESSED : Health.HEALTHY)));
            else outcomes.add(engine.service(service(service, clock.now, scenario.equals("overloaded") ? 150 : scenario.equals("stressed") ? 125 : 100,
                    scenario.equals("overloaded") ? 80 : scenario.equals("stressed") ? 100 : 120,
                    recovery || scenario.equals("retry-stressed") ? 100 : 0, recovery || scenario.equals("retry-stressed") ? 40 : 0)));
            clock.now = clock.now.plus(settings.cycle());
        }
        return Map.of("scenario", scenario, "source", "MOCK", "virtualTime", true,
                "message", "Isolated synthetic simulation. No live baseline or Component 3 delivery is modified.", "outcomes", outcomes, "changes", workflow.list());
    }
    private Map<String, Object> multipleChains() {
        var clock = new SimulationClock(Instant.now().minusSeconds(settings.baselineSamples() * settings.cycle().toSeconds() + 900));
        var workflow = new PolicyWorkflow(connections, clock);
        var engine = new AdaptiveEngine(settings, workflow, clock);
        String[] targets = {
                "POST /api/orders | mock-api-gateway > mock-order-service > mock-inventory-service > mock-payment-service",
                "GET /api/inventory | mock-api-gateway > mock-inventory-service",
                "POST /api/notifications | mock-api-gateway > mock-notification-service"};
        double[] current = {1000, 600, 1200}, baseline = {1000, 400, 800}, recent = {1200, 550, 800}, failures = {20, 35, 0};
        for (int i = 0; i < targets.length; i++) workflow.putPolicy(new Policy(targets[i], "mock-v1", clock.now.plusSeconds(7200),
                null, null, null, current[i]), Source.MOCK);
        for (int cycle = 0; cycle < settings.baselineSamples(); cycle++) {
            for (int i = 0; i < targets.length; i++) engine.chain(chain(targets[i], clock.now, baseline[i], 0, Health.HEALTHY));
            clock.now = clock.now.plus(settings.cycle());
        }
        clock.now = clock.now.plus(settings.window());
        var outcomes = new ArrayList<Outcome>();
        var measurements = new ArrayList<TempoWindows.Collection>();
        for (int cycle = 0; cycle < Math.max(settings.persistence(), settings.recoveryWindows()); cycle++) {
            measurements.clear();
            for (int i = 0; i < targets.length; i++) {
                var window = chain(targets[i], clock.now, recent[i], failures[i], failures[i] > 0 ? Health.STRESSED : Health.HEALTHY);
                outcomes.add(engine.chain(window));
                measurements.add(new TempoWindows.Collection(window, current[i], List.of()));
            }
            clock.now = clock.now.plus(settings.cycle());
        }
        return Map.of("scenario", "multiple-chains", "source", "MOCK", "virtualTime", true,
                "message", "Isolated synthetic request chains. No live policies are modified.",
                "outcomes", outcomes, "changes", workflow.list(), "chains", List.copyOf(measurements));
    }
    private ServiceWindow service(String target, Instant end, double latency, double throughput, double retries, double successes) {
        return new ServiceWindow(target, end.minus(settings.window()), end, Source.MOCK, true, 1000.0, latency, 0.0, retries, successes,
                0.0, 10.0, throughput, true, true, 500.0, 100.0, List.of());
    }
    private ChainWindow chain(String target, Instant end, double p99, double failures, Health health) {
        return new ChainWindow(target, List.of(target.split(" \\| ", 2)[1].split(" > ")), end.minus(settings.window()), end,
                Source.MOCK, true, 1000.0, failures, p99, health, List.of("synthetic-trace"));
    }
}
