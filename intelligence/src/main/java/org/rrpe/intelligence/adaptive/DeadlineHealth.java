package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deadline-only overload guard using measured proxy attempts, latency, errors, throughput and
 * active work.
 */
@Component
public class DeadlineHealth {
    private final AdaptiveConfig.Settings settings;
    private final AdaptiveConfig.Connections connections;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Autowired
    public DeadlineHealth(
            AdaptiveConfig.Settings settings, AdaptiveConfig.Connections connections) {
        this(settings, connections, Clock.systemUTC());
    }

    private Duration evaluationDelay = Duration.ZERO;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvaluationDelay(
            @org.springframework.beans.factory.annotation.Value(
                            "${rrpe.adaptive.evaluation-delay:0s}")
                    Duration delay) {
        if (delay != null && !delay.isNegative()) evaluationDelay = delay;
    }

    public DeadlineHealth(
            AdaptiveConfig.Settings settings, AdaptiveConfig.Connections connections, Clock clock) {
        this.settings = settings;
        this.connections = connections;
        this.clock = clock;
    }

    /** Forgets the learned deadline-guard baselines. */
    public void reset() {
        states.clear();
    }

    public Assessment observe(PrometheusWindows.Collection data) {
        var window = data.window();
        var values = data.available();
        State state = states.computeIfAbsent(window.service(), ignored -> new State());
        synchronized (state) {
            var problems = new ArrayList<String>();
            if (window.start() == null
                    || window.end() == null
                    || !Duration.between(window.start(), window.end()).equals(settings.window())
                    || window.end().isAfter(clock.instant())
                    || Duration.between(window.end(), clock.instant())
                                    .compareTo(
                                            settings.cycle().multipliedBy(2).plus(evaluationDelay))
                            > 0) {
                state.streaks.clear();
                state.healthy = 0;
                if (state.baseline.isEmpty()) state.samples.clear();
                return save(
                        state,
                        window.service(),
                        window.end(),
                        Health.INSUFFICIENT_DATA,
                        Map.of(),
                        List.of("Deadline guard window is stale, future-dated or incomplete"));
            }
            for (String metric :
                    List.of(
                            "observedRequests",
                            "p95Ms",
                            "errorCount",
                            "inFlight",
                            "successfulThroughput",
                            "scrapeAge",
                            "coverage",
                            "scrapeSamples")) {
                Double value = values.get(metric);
                if (value == null || !Double.isFinite(value) || value < 0)
                    problems.add("Missing deadline-guard measurement: " + metric);
            }
            if (state.last != null) {
                Duration gap = Duration.between(state.last, window.end());
                if (gap.compareTo(settings.cycle()) < 0) return state.assessment;
                if (gap.compareTo(settings.cycle().multipliedBy(2)) > 0) {
                    state.streaks.clear();
                    state.healthy = 0;
                    if (state.baseline.isEmpty()) state.samples.clear();
                }
            }
            state.last = window.end();
            if (problems.isEmpty()
                    && (values.get("observedRequests") < settings.minimumServiceRequests()
                            || values.get("errorCount") > values.get("observedRequests")
                            || values.get("scrapeAge") > connections.maximumScrapeAge().toSeconds()
                            || values.get("coverage") < 1
                            || values.get("scrapeSamples")
                                    < settings.window().toSeconds()
                                            / (double) connections.scrapeInterval().toSeconds()
                                            * .8)) {
                problems.add(
                        "Too few requests, inconsistent counts, stale data or incomplete scrape"
                            + " coverage");
            }
            if (!problems.isEmpty()) {
                state.streaks.clear();
                state.healthy = 0;
                if (state.baseline.isEmpty()) state.samples.clear();
                return save(
                        state,
                        window.service(),
                        window.end(),
                        Health.INSUFFICIENT_DATA,
                        Map.of(),
                        problems);
            }
            Map<String, Double> signals =
                    Map.of(
                            "latency",
                            values.get("p95Ms"),
                            "errorRate",
                            values.get("errorCount") / values.get("observedRequests"),
                            "inFlight",
                            values.get("inFlight"),
                            "throughput",
                            values.get("successfulThroughput"));
            if (state.baseline.isEmpty()) {
                if (signals.get("errorRate") != 0
                        || signals.get("latency") <= 0
                        || signals.get("throughput") <= 0) state.samples.clear();
                else {
                    var candidate = new ArrayList<>(state.samples);
                    candidate.add(signals);
                    for (String metric : List.of("latency", "throughput")) {
                        double median =
                                RobustStatistics.median(
                                        candidate.stream()
                                                .mapToDouble(sample -> sample.get(metric))
                                                .toArray());
                        if (candidate.stream()
                                .anyMatch(
                                        sample ->
                                                Math.abs(sample.get(metric) / median - 1)
                                                        > settings.stabilityTolerance())) {
                            state.samples.clear();
                            break;
                        }
                    }
                    state.samples.add(signals);
                }
                if (state.samples.size() < settings.baselineSamples())
                    return save(
                            state,
                            window.service(),
                            window.end(),
                            Health.LEARNING_BASELINE,
                            signals,
                            List.of(
                                    "Learning deadline-only performance baseline; retry/rejection"
                                        + " metrics are not required"));
                state.baseline = statistics(state.samples);
                return save(
                        state,
                        window.service(),
                        window.end(),
                        Health.HEALTHY,
                        signals,
                        List.of("Deadline-only performance baseline ready"));
            }
            int breaches = 0;
            int persistent = 0;
            boolean critical = false;
            var evidence = new ArrayList<String>();
            for (var signal : signals.entrySet()) {
                Statistic reference = state.baseline.get(signal.getKey());
                // never below the practical floor: very steady traffic gives a near-zero MAD (see
                // AdaptiveEngine.variation)
                double variation =
                        Math.max(
                                reference.mad(),
                                Math.max(
                                        signal.getKey().equals("errorRate")
                                                ? settings.ratioFloor()
                                                : 1,
                                        reference.median() * settings.zeroMadRelativeFloor()));
                double distance =
                        (signal.getKey().equals("throughput")
                                        ? reference.median() - signal.getValue()
                                        : signal.getValue() - reference.median())
                                / variation;
                if (distance >= settings.stressedMad()) {
                    breaches++;
                    if (state.streaks.merge(signal.getKey(), 1, Integer::sum)
                            >= settings.persistence()) persistent++;
                    evidence.add(
                            signal.getKey()
                                    + " breached deadline guard; value="
                                    + signal.getValue()
                                    + ", median="
                                    + reference.median()
                                    + ", MAD="
                                    + reference.mad());
                } else state.streaks.remove(signal.getKey());
                if (distance >= settings.overloadedMad()
                        && List.of("latency", "errorRate").contains(signal.getKey()))
                    critical = true;
            }
            // STRESSED means a measured non-critical breach; deadline failures themselves must
            // still persist two cycles.
            Health health =
                    critical || persistent >= 2
                            ? Health.OVERLOADED
                            : breaches > 0 ? Health.STRESSED : Health.HEALTHY;
            state.healthy = health == Health.HEALTHY ? state.healthy + 1 : 0;
            evidence.add(
                    "Deadline-only overload guard; error denominator is observed inbound attempts,"
                        + " not fabricated original requests");
            if (state.healthy >= settings.recoveryWindows()) {
                state.samples.remove(0);
                state.samples.add(signals);
                state.baseline = statistics(state.samples);
            }
            return save(state, window.service(), window.end(), health, signals, evidence);
        }
    }

    private Map<String, Statistic> statistics(List<Map<String, Double>> samples) {
        var result = new HashMap<String, Statistic>();
        for (String metric : samples.get(0).keySet())
            result.put(
                    metric,
                    RobustStatistics.summarize(
                            samples.stream().mapToDouble(sample -> sample.get(metric)).toArray()));
        return Map.copyOf(result);
    }

    private Assessment save(
            State state,
            String service,
            Instant end,
            Health health,
            Map<String, Double> signals,
            List<String> evidence) {
        state.assessment =
                new Assessment(
                        service,
                        health,
                        Source.PROMETHEUS,
                        end,
                        state.samples.size(),
                        state.baseline,
                        signals,
                        List.copyOf(evidence));
        return state.assessment;
    }

    private static class State {
        Instant last;
        int healthy;
        Assessment assessment;
        List<Map<String, Double>> samples = new ArrayList<>();
        Map<String, Statistic> baseline = Map.of();
        Map<String, Integer> streaks = new HashMap<>();
    }
}
