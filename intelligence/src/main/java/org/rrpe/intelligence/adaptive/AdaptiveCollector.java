package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdaptiveCollector {
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveEngine engine;
    private final PrometheusWindows prometheus;
    private final TempoWindows tempo;
    private final DeadlineHealth deadlineHealth;
    private final PolicyWorkflow workflow;
    private final Clock clock;
    private final Map<String, PrometheusWindows.Collection> latest = new ConcurrentHashMap<>();
    private volatile List<String> problems = List.of();
    private volatile List<Assessment> guards = List.of();
    private volatile List<TempoWindows.Collection> chainMeasurements = List.of();
    // Windows end this far in the past so that traces still being ingested by Tempo are not counted
    // as missing.
    private java.time.Duration evaluationDelay = java.time.Duration.ZERO;

    @Autowired(required = false)
    void setEvaluationDelay(
            @org.springframework.beans.factory.annotation.Value(
                            "${rrpe.adaptive.evaluation-delay:0s}")
                    java.time.Duration delay) {
        if (delay != null && !delay.isNegative()) evaluationDelay = delay;
    }

    @Autowired
    public AdaptiveCollector(
            AdaptiveConfig.Connections connections,
            AdaptiveEngine engine,
            PrometheusWindows prometheus,
            TempoWindows tempo,
            DeadlineHealth deadlineHealth,
            PolicyWorkflow workflow) {
        this(connections, engine, prometheus, tempo, deadlineHealth, workflow, Clock.systemUTC());
    }

    public AdaptiveCollector(
            AdaptiveConfig.Connections connections,
            AdaptiveEngine engine,
            PrometheusWindows prometheus,
            TempoWindows tempo,
            DeadlineHealth deadlineHealth,
            PolicyWorkflow workflow,
            Clock clock) {
        this.connections = connections;
        this.engine = engine;
        this.prometheus = prometheus;
        this.tempo = tempo;
        this.deadlineHealth = deadlineHealth;
        this.workflow = workflow;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rrpe.adaptive.cycle:1m}")
    public synchronized void collect() {
        if (!connections.collect()) return;
        Instant end =
                clock.instant()
                        .minus(evaluationDelay)
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Map<String, Health> health = new HashMap<>();
        var errors = new ArrayList<String>();
        var guardResults = new ArrayList<Assessment>();
        for (String service : connections.services()) {
            var data = prometheus.collect(service, end);
            latest.put(service, data);
            engine.service(
                    data
                            .window()); // Full service analysis can wait for retries/rejections
                                        // independently.
            var guard = deadlineHealth.observe(data);
            guardResults.add(guard);
            health.put(service, guard.health());
            if (Thread.currentThread().isInterrupted()) return;
        }
        guards = List.copyOf(guardResults);
        try {
            chainMeasurements = tempo.collect(end, health);
            for (var measurement : chainMeasurements) {
                var chain = measurement.window();
                if (chain.complete()
                        && measurement.currentDeadlineMs() != null
                        && !workflow.observeConfiguredDeadline(
                                chain.chain(), measurement.currentDeadlineMs(), end)) {
                    errors.add(
                            chain.chain()
                                    + ": observed configuration conflicts with a confirmed applied"
                                    + " policy");
                    workflow.withdraw(
                            chain.chain(),
                            Action.CHAIN_DEADLINE,
                            Source.TEMPO,
                            "Current deadline configuration conflict");
                    continue;
                }
                var evidence = new ArrayList<String>();
                var measurements = new HashMap<String, Double>();
                var coverage = measurement.coverage();
                if (coverage != null) {
                    measurements.put("completeTraceCount", (double) coverage.traceCount());
                    if (coverage.prometheusCount() != null)
                        measurements.put("prometheusRequestCount", coverage.prometheusCount());
                    if (coverage.tolerance() != null)
                        measurements.put("traceCountTolerance", coverage.tolerance());
                    measurements.put("coverageAccepted", coverage.accepted() ? 1.0 : 0.0);
                }
                measurements.put("observedPathCount", (double) measurement.paths().size());
                evidence.add(
                        "One deadline policy for method + route; p99 pools complete gateway"
                            + " durations across observed paths");
                for (var path : measurement.paths())
                    evidence.add(
                            "Observed path ("
                                    + path.traceCount()
                                    + " complete traces): "
                                    + String.join(" > ", path.services()));
                engine.chain(chain, measurements, evidence);
            }
        } catch (RuntimeException exception) {
            errors.add("Tempo/chain collection: " + exception.getMessage());
            for (var measurement : chainMeasurements)
                workflow.withdraw(
                        measurement.window().chain(),
                        Action.CHAIN_DEADLINE,
                        Source.TEMPO,
                        "Live collection failed");
            chainMeasurements = List.of();
        }
        problems = List.copyOf(errors);
    }

    /**
     * Re-learn: forget live baselines so the next windows build new ones (after an intentional
     * change).
     */
    public synchronized void resetBaselines() {
        engine.resetBaselines();
        deadlineHealth.reset();
        guards = List.of();
        chainMeasurements = List.of();
    }

    public Map<String, Object> status() {
        return Map.of(
                "enabled",
                connections.collect(),
                "deliveryConfigured",
                !connections.component3Url().isBlank(),
                "configuredServices",
                connections.services(),
                "measurements",
                latest.values().stream()
                        .sorted(Comparator.comparing(c -> c.window().service()))
                        .toList(),
                "deadlineGuards",
                guards,
                "chains",
                chainMeasurements,
                "problems",
                problems);
    }
}
