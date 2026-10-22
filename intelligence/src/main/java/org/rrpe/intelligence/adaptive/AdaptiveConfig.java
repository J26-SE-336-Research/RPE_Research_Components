package org.rrpe.intelligence.adaptive;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AdaptiveConfig.Settings.class, AdaptiveConfig.Connections.class, AdaptiveConfig.DeadlineSettings.class,
        AdaptiveConfig.LoadSettings.class, AdaptiveConfig.LeverSettings.class, AdaptiveConfig.RateSettings.class, AdaptiveConfig.RetrySettings.class,
        AdaptiveConfig.BaselineSettings.class, AdaptiveConfig.AutoSettings.class, AdaptiveConfig.AutoApproval.class, AdaptiveConfig.JointSettings.class})
public class AdaptiveConfig {
    @ConfigurationProperties("rrpe.adaptive")
    public record Settings(@DefaultValue("5m") Duration window, @DefaultValue("1m") Duration cycle,
            @DefaultValue("30") int baselineSamples, @DefaultValue("20") int minimumServiceRequests,
            @DefaultValue("100") int minimumChainRequests, @DefaultValue("2") int persistence,
            @DefaultValue("3") int recoveryWindows, @DefaultValue("3m") Duration cooldown,
            @DefaultValue("5m") Duration ttl, @DefaultValue("0.25") double stabilityTolerance,
            @DefaultValue("2") double stressedMad, @DefaultValue("3") double overloadedMad,
            @DefaultValue("0.05") double zeroMadRelativeFloor, @DefaultValue("0.001") double ratioFloor,
            @DefaultValue("0.5") double maximumRateChange, @DefaultValue("0.10") double maximumRetryRatio,
            @DefaultValue("100") long minimumDeadlineMs, @DefaultValue("10000") long maximumDeadlineMs,
            @DefaultValue("0.50") double maximumDeadlineChange) {
        public Settings {
            for (Duration duration : List.of(window, cycle, cooldown, ttl)) {
                if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Durations must be positive");
            }
            if (cycle.compareTo(window) > 0 || baselineSamples < 3 || minimumServiceRequests < 1 || minimumChainRequests < 100
                    || persistence < 2 || recoveryWindows < 3 || !Double.isFinite(stressedMad) || !Double.isFinite(overloadedMad)
                    || stressedMad <= 0 || overloadedMad <= stressedMad || minimumDeadlineMs <= 0 || maximumDeadlineMs < minimumDeadlineMs) {
                throw new IllegalArgumentException("Invalid adaptive thresholds or sample limits");
            }
            for (double value : new double[]{stabilityTolerance, zeroMadRelativeFloor, ratioFloor, maximumRateChange,
                    maximumRetryRatio, maximumDeadlineChange}) {
                if (!Double.isFinite(value) || value <= 0 || value > 1) throw new IllegalArgumentException("Invalid adaptive fraction");
            }
            if (maximumRetryRatio > 0.10) throw new IllegalArgumentException("Retry cap cannot exceed 10% of original traffic");
        }
        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(5), Duration.ofMinutes(1), 30, 20, 100, 2, 3,
                    Duration.ofMinutes(3), Duration.ofMinutes(5), .25, 2, 3, .05, .001, .50, .10, 100, 10000, .50);
        }
    }

    /**
     * Deadline recommendation v2 (guide §8.3), env {@code RRPE_ADAPTIVE_DEADLINE_<NAME>}. Values an existing property
     * already covers are reused from {@link Settings}: N_learn = baseline-samples, minimum requests per window =
     * minimum-chain-requests, min/max deadline, cooldown.
     */
    @ConfigurationProperties("rrpe.adaptive.deadline")
    public record DeadlineSettings(@DefaultValue("10") int minLearningWindows, @DefaultValue("1000") int minLearningRequests,
            @DefaultValue("0.99") double quantile, @DefaultValue("3") double marginK, @DefaultValue("0.10") double marginMin,
            @DefaultValue("0.30") double marginMax, @DefaultValue("0.01") double eTarget, @DefaultValue("0.05") double eFire,
            @DefaultValue("1.645") double z, @DefaultValue("1.0") double designEffect, @DefaultValue("30m") Duration maxWiden,
            @DefaultValue("3") int persistenceWindows, @DefaultValue("0.05") double shareThreshold,
            @DefaultValue("0.10") double unknownMaxShare, @DefaultValue("0.005") double cutMaxShare,
            @DefaultValue("1.5") double stepUp, @DefaultValue("0.75") double stepDownOverload, @DefaultValue("0.80") double stepDownHealthy,
            @DefaultValue("0.10") double deadbandOverload, @DefaultValue("0.25") double deadbandHealthy,
            @DefaultValue("1") int settleWindows, @DefaultValue("2") int verifyWindows, @DefaultValue("4") int maxProposalsPerHour,
            @DefaultValue("0.25") double driftThreshold, @DefaultValue("3") int driftWindows,
            @DefaultValue("0.05") double coverageMinShare, @DefaultValue("20") int coverageMinSamples,
            @DefaultValue("30m") Duration pauseClear) {
        public DeadlineSettings {
            if (minLearningWindows < 1 || minLearningRequests < 1 || persistenceWindows < 1 || settleWindows < 0 || verifyWindows < 1
                    || maxProposalsPerHour < 1 || driftWindows < 1 || coverageMinSamples < 1 || maxWiden == null || maxWiden.isNegative()
                    || pauseClear == null || pauseClear.isNegative() || !(quantile > .5 && quantile < 1) || marginMin <= 0 || marginMax < marginMin
                    || marginK <= 0 || z <= 0 || designEffect < 1 || stepUp <= 1 || !(stepDownOverload > 0 && stepDownOverload < 1)
                    || !(stepDownHealthy > 0 && stepDownHealthy < 1) || !(eTarget > 0 && eTarget < eFire && eFire < 1)) {
                throw new IllegalArgumentException("Invalid deadline v2 settings");
            }
            for (double v : new double[]{shareThreshold, unknownMaxShare, cutMaxShare, deadbandOverload, deadbandHealthy, driftThreshold, coverageMinShare}) {
                if (!(v >= 0 && v < 1)) throw new IllegalArgumentException("Invalid deadline v2 fraction");
            }
        }
        public static DeadlineSettings defaults() {
            return new DeadlineSettings(10, 1000, .99, 3, .10, .30, .01, .05, 1.645, 1.0, Duration.ofMinutes(30), 3, .05, .10, .005,
                    1.5, .75, .80, .10, .25, 1, 2, 4, .25, 3, .05, 20, Duration.ofMinutes(30));
        }
        /** The demo-cluster values of guide §8.3 (min-window-requests and cooldown come from {@link Settings}). */
        public static DeadlineSettings demo() {
            return new DeadlineSettings(3, 150, .99, 3, .10, .30, .01, .05, 1.645, 1.0, Duration.ofMinutes(5), 2, .05, .10, .005,
                    1.5, .75, .80, .10, .25, 1, 2, 6, .25, 2, .05, 10, Duration.ofMinutes(30));
        }
    }
    /** Service load thresholds for deadline v2 (guide §6.2). Queue pressure is in percent (0–100), as the sidecar exports it. */
    @ConfigurationProperties("rrpe.adaptive.load")
    public record LoadSettings(@DefaultValue("50") double pressureStressed, @DefaultValue("80") double pressureOverloaded,
            @DefaultValue("0.70") double cpuStressed, @DefaultValue("0.85") double cpuOverloaded,
            @DefaultValue("0.25") double throttleOverloaded, @DefaultValue("0.90") double memoryOverloaded) {
        public LoadSettings {
            if (!(pressureStressed > 0 && pressureStressed < pressureOverloaded && pressureOverloaded <= 100) || !(cpuStressed > 0 && cpuStressed < cpuOverloaded)
                    || throttleOverloaded <= 0 || memoryOverloaded <= 0) throw new IllegalArgumentException("Invalid load thresholds");
        }
        public static LoadSettings defaults() { return new LoadSettings(50, 80, .70, .85, .25, .90); }
    }
    /** Lever diagnosis thresholds (guide §6.4). */
    @ConfigurationProperties("rrpe.adaptive.lever")
    public record LeverSettings(@DefaultValue("0.5") double queueShare, @DefaultValue("0.70") double cpuLow) {
        public LeverSettings { if (!(queueShare > 0 && queueShare <= 1) || cpuLow <= 0) throw new IllegalArgumentException("Invalid lever thresholds"); }
        public static LeverSettings defaults() { return new LeverSettings(.5, .70); }
    }

    /**
     * Leaky-bucket rate v2 (rate/retry guide §4, §7), env {@code RRPE_ADAPTIVE_RATE_<NAME>}. Reused from {@link Settings}:
     * maximum-rate-change (largest cut per step), persistence, recovery-windows, cooldown, baseline-samples.
     */
    @ConfigurationProperties("rrpe.adaptive.rate")
    public record RateSettings(@DefaultValue("0.25") double headroom, @DefaultValue("80") double queueHighPercent,
            @DefaultValue("50") double queueLowPercent, @DefaultValue("1.5") double appTolerance, @DefaultValue("0.8") double cutFactor,
            @DefaultValue("0.10") double stepFraction, @DefaultValue("5") double minAbsolute, @DefaultValue("1000") double maxAbsolute,
            @DefaultValue("0.5") double floorFraction, @DefaultValue("30m") Duration brokeAtMemory, @DefaultValue("5m") Duration stabilisation,
            @DefaultValue("30m") Duration relaxAfter, @DefaultValue("0.10") double relaxStep, @DefaultValue("0.5") double maxRaise,
            @DefaultValue("0.20") double minImprovement) {
        public RateSettings {
            if (!(headroom >= 0) || !(queueLowPercent > 0 && queueLowPercent < queueHighPercent && queueHighPercent <= 100) || !(appTolerance > 1)
                    || !(cutFactor > 0 && cutFactor < 1) || !(stepFraction > 0) || !(minAbsolute > 0 && maxAbsolute > minAbsolute)
                    || !(floorFraction > 0 && floorFraction < 1) || brokeAtMemory == null || stabilisation == null || relaxAfter == null
                    || !(relaxStep > 0 && relaxStep < 1) || !(maxRaise > 0) || !(minImprovement > 0 && minImprovement < 1)) {
                throw new IllegalArgumentException("Invalid rate v2 settings");
            }
        }
        public static RateSettings defaults() {
            return new RateSettings(.25, 80, 50, 1.5, .8, .10, 5, 1000, .5, Duration.ofMinutes(30), Duration.ofMinutes(5), Duration.ofMinutes(30), .10, .5, .20);
        }
    }
    /**
     * Retry budget v2 (guide §5), env {@code RRPE_ADAPTIVE_RETRY_<NAME>}. The maximum ratio is the existing
     * {@link Settings#maximumRetryRatio()} (at most 0.10, also enforced by the workflow), so the guide's max-ratio 0.20 is not used.
     */
    @ConfigurationProperties("rrpe.adaptive.retry")
    public record RetrySettings(@DefaultValue("0.10") double baseRatio, @DefaultValue("0.20") double minSuccess,
            @DefaultValue("30") int minAttempts, @DefaultValue("0.02") double step) {
        public RetrySettings {
            if (!(baseRatio > 0 && baseRatio <= .10) || !(minSuccess > 0 && minSuccess < 1) || minAttempts < 1 || !(step > 0 && step <= .10)) {
                throw new IllegalArgumentException("Invalid retry v2 settings");
            }
        }
        public static RetrySettings defaults() { return new RetrySettings(.10, .20, 30, .02); }
    }
    /** {@code allow-shed-windows}: windows with rejections but normal errors may feed the app-time baseline P0 (guide §4.5). */
    @ConfigurationProperties("rrpe.adaptive.baseline")
    public record BaselineSettings(@DefaultValue("false") boolean allowShedWindows) {
        public static BaselineSettings defaults() { return new BaselineSettings(false); }
    }
    /** Automatic approval safety (guide §6): verification windows, undo and flip limits within {@code period}, pause length. */
    @ConfigurationProperties("rrpe.adaptive.auto")
    public record AutoSettings(@DefaultValue("2") int verifyWindows, @DefaultValue("2") int maxReverts, @DefaultValue("3") int maxFlips,
            @DefaultValue("60m") Duration pause, @DefaultValue("30m") Duration period) {
        public AutoSettings {
            if (verifyWindows < 1 || maxReverts < 1 || maxFlips < 1 || pause == null || pause.isNegative() || period == null || period.isNegative()) {
                throw new IllegalArgumentException("Invalid auto-approval settings");
            }
        }
        public static AutoSettings defaults() { return new AutoSettings(2, 2, 3, Duration.ofMinutes(60), Duration.ofMinutes(30)); }
    }
    /**
     * Joint healthy recalibration (C3 request J1–J6): when a healthy rate step reaches its target, the matching chain
     * deadlines are recommended in the same decision. Off by default ({@code rrpe.adaptive.joint.enabled}); a joint is made
     * only when the deadline change is at least {@code max(min-change-fraction × D, min-change-ms)}.
     */
    @ConfigurationProperties("rrpe.adaptive.joint")
    public record JointSettings(@DefaultValue("false") boolean enabled, @DefaultValue("0.05") double minChangeFraction,
            @DefaultValue("10") long minChangeMs) {
        public JointSettings {
            if (!(minChangeFraction >= 0 && minChangeFraction < 1) || minChangeMs < 0) throw new IllegalArgumentException("Invalid joint settings");
        }
        public static JointSettings defaults() { return new JointSettings(false, .05, 10); }
        public static JointSettings on() { return new JointSettings(true, .05, 10); }
    }
    /** The kinds of change that may be approved automatically. A chain deadline is never one of them. */
    public enum AutoKind { BUCKET_RAISE, BUCKET_CUT, RETRY_DECREASE, RETRY_INCREASE }
    /**
     * {@code rrpe.adaptive.auto-approve-actions} (env {@code RRPE_ADAPTIVE_AUTOAPPROVEACTIONS}); empty by default, so every
     * recommendation waits for a human exactly as before. {@code CHAIN_DEADLINE} is rejected.
     */
    @ConfigurationProperties("rrpe.adaptive")
    public record AutoApproval(@DefaultValue List<String> autoApproveActions) {
        public AutoApproval {
            autoApproveActions = autoApproveActions == null ? List.of() : autoApproveActions.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            for (String action : autoApproveActions) {
                if (action.equalsIgnoreCase("CHAIN_DEADLINE")) throw new IllegalArgumentException("Chain deadlines are never approved automatically");
                AutoKind.valueOf(action);
            }
        }
        public java.util.Set<AutoKind> kinds() {
            var set = java.util.EnumSet.noneOf(AutoKind.class); autoApproveActions.forEach(a -> set.add(AutoKind.valueOf(a))); return set;
        }
        public static AutoApproval none() { return new AutoApproval(List.of()); }
    }

    @ConfigurationProperties("rrpe.connections")
    public record Connections(@DefaultValue("false") boolean collect,
            @DefaultValue("http://localhost:9090") String prometheusUrl,
            @DefaultValue("http://localhost:3200") String tempoUrl,
            @DefaultValue("default") String namespace,
            @DefaultValue({"apigateway", "order", "inventory", "payment", "notification"}) List<String> services,
            @DefaultValue("apigateway") String gatewayService,
            @DefaultValue("5s") Duration timeout, @DefaultValue("15s") Duration scrapeInterval,
            @DefaultValue("30s") Duration maximumScrapeAge, @DefaultValue("5000") int traceLimit,
            @DefaultValue("") String component3Url, @DefaultValue("") String component3Token,
            Map<String, String> metricNames) {
        public Connections {
            for (String url : List.of(prometheusUrl, tempoUrl)) checkUrl(url);
            if (component3Url != null && !component3Url.isBlank()) checkUrl(component3Url);
            if (services == null || services.isEmpty() || services.size() > 100 || services.stream().anyMatch(s -> s == null || s.isBlank())
                    || namespace == null || namespace.isBlank() || gatewayService == null || gatewayService.isBlank()
                    || timeout == null || timeout.isNegative() || timeout.isZero() || scrapeInterval == null
                    || scrapeInterval.isNegative() || scrapeInterval.isZero() || maximumScrapeAge == null
                    || maximumScrapeAge.isNegative() || maximumScrapeAge.isZero() || traceLimit < 100 || traceLimit > 10000) {
                throw new IllegalArgumentException("Invalid collector configuration");
            }
            services = List.copyOf(services);
            metricNames = metricNames == null ? Map.of() : Map.copyOf(metricNames);
            for (String name : metricNames.values()) if (!name.matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) throw new IllegalArgumentException("Invalid metric name");
        }
        private static void checkUrl(String url) {
            var uri = java.net.URI.create(url);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Expected an HTTP(S) URL without embedded credentials");
            }
        }
    }
}
