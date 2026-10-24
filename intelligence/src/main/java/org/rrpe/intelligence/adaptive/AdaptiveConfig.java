package org.rrpe.intelligence.adaptive;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
     * Deadline settings, env {@code RRPE_ADAPTIVE_DEADLINE_<NAME>}. Some values come from {@link Settings}: the learning
     * windows are baseline-samples, the minimum requests per window are minimum-chain-requests, and the min and max
     * deadline and the cooldown are the same too.
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
        /** The values for the demo cluster. min-window-requests and cooldown come from {@link Settings}. */
        public static DeadlineSettings demo() {
            return new DeadlineSettings(3, 150, .99, 3, .10, .30, .01, .05, 1.645, 1.0, Duration.ofMinutes(5), 2, .05, .10, .005,
                    1.5, .75, .80, .10, .25, 1, 2, 6, .25, 2, .05, 10, Duration.ofMinutes(30));
        }
    }
    /** Service load limits for the deadline logic. Queue pressure is in percent, as the sidecar exports it. */
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
    /** Limits for choosing the lever. */
    @ConfigurationProperties("rrpe.adaptive.lever")
    public record LeverSettings(@DefaultValue("0.5") double queueShare, @DefaultValue("0.70") double cpuLow) {
        public LeverSettings { if (!(queueShare > 0 && queueShare <= 1) || cpuLow <= 0) throw new IllegalArgumentException("Invalid lever thresholds"); }
        public static LeverSettings defaults() { return new LeverSettings(.5, .70); }
    }

    /**
     * Leaky-bucket rate settings, env {@code RRPE_ADAPTIVE_RATE_<NAME>}. These come from {@link Settings}:
     * maximum-rate-change (the largest cut per step), persistence, recovery-windows, cooldown and baseline-samples.
     */
    @ConfigurationProperties("rrpe.adaptive.rate")
    public record RateSettings(@DefaultValue("0.25") double headroom, @DefaultValue("80") double queueHighPercent,
            @DefaultValue("50") double queueLowPercent, @DefaultValue("1.5") double appTolerance, @DefaultValue("0.8") double cutFactor,
            @DefaultValue("0.10") double stepFraction, @DefaultValue("5") double minAbsolute, @DefaultValue("1000") double maxAbsolute,
            @DefaultValue("0.5") double floorFraction, @DefaultValue("30m") Duration brokeAtMemory, @DefaultValue("5m") Duration stabilisation,
            @DefaultValue("30m") Duration generousWait, @DefaultValue("0.10") double relaxStep, @DefaultValue("0.5") double maxRaise,
            @DefaultValue("0.20") double minImprovement, @DefaultValue("6") int loadGrowthWindows, @DefaultValue("1.5") double loadGrowthFactor,
            @DefaultValue("1.25") double loadGrowthMinLoadRatio) {
        // brokeAtMemory: how long we remember what the app managed when it slowed down. After that, raises go in small steps.
        // load-growth-*: checks whether the app gets slower with more load over the last learning windows.
        @ConstructorBinding
        public RateSettings {
            if (!(headroom >= 0) || !(queueLowPercent > 0 && queueLowPercent < queueHighPercent && queueHighPercent <= 100) || !(appTolerance > 1)
                    || !(cutFactor > 0 && cutFactor < 1) || !(stepFraction > 0) || !(minAbsolute > 0 && maxAbsolute > minAbsolute)
                    || !(floorFraction > 0 && floorFraction < 1) || brokeAtMemory == null || stabilisation == null || generousWait == null
                    || !(relaxStep > 0 && relaxStep < 1) || !(maxRaise > 0) || !(minImprovement > 0 && minImprovement < 1)
                    || loadGrowthWindows < 3 || !(loadGrowthFactor > 1) || !(loadGrowthMinLoadRatio > 1)) {
                throw new IllegalArgumentException("Invalid rate v2 settings");
            }
        }
        public RateSettings(double headroom, double queueHighPercent, double queueLowPercent, double appTolerance, double cutFactor, double stepFraction,
                double minAbsolute, double maxAbsolute, double floorFraction, Duration brokeAtMemory, Duration stabilisation, Duration generousWait,
                double relaxStep, double maxRaise, double minImprovement) {
            this(headroom, queueHighPercent, queueLowPercent, appTolerance, cutFactor, stepFraction, minAbsolute, maxAbsolute, floorFraction, brokeAtMemory,
                    stabilisation, generousWait, relaxStep, maxRaise, minImprovement, 6, 1.5, 1.25);
        }
        public static RateSettings defaults() {
            return new RateSettings(.25, 80, 50, 1.5, .8, .10, 5, 1000, .5, Duration.ofHours(6), Duration.ofMinutes(5), Duration.ofMinutes(30), .10, .5, .20);
        }
    }
    /**
     * Retry budget settings, env {@code RRPE_ADAPTIVE_RETRY_<NAME>}. The maximum ratio is
     * {@link Settings#maximumRetryRatio()}, at most 0.10, which the workflow also checks.
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
    /**
     * Settings for the normal app time P0, env {@code RRPE_ADAPTIVE_BASELINE_<NAME>}.
     * allow-shed-windows: windows with a full queue but normal errors also teach P0, since the bucket limited the app
     * there. provisional-windows: after this many windows we use a rough P0 (the 25th percentile), and the median after
     * baseline-samples. When the load clearly varies, so the busiest quarter has load-spread times the load of the
     * quietest quarter, P0 is the median app time of the quietest quarter once it has min-quiet-windows.
     */
    @ConfigurationProperties("rrpe.adaptive.baseline")
    public record BaselineSettings(@DefaultValue("true") boolean allowShedWindows, @DefaultValue("5") int provisionalWindows,
            @DefaultValue("0.25") double quietShare, @DefaultValue("3") int minQuietWindows, @DefaultValue("2.0") double loadSpread,
            @DefaultValue("60") int loadWindows) {
        @ConstructorBinding
        public BaselineSettings {
            if (provisionalWindows < 1 || !(quietShare > 0 && quietShare <= .5) || minQuietWindows < 1 || !(loadSpread > 1) || loadWindows < 4) {
                throw new IllegalArgumentException("Invalid baseline settings");
            }
        }
        public BaselineSettings(boolean allowShedWindows) { this(allowShedWindows, 5, .25, 3, 2.0, 60); }
        public static BaselineSettings defaults() { return new BaselineSettings(true); }
    }
    /** Safety settings for automatic approval: check windows, how many undos and flips are allowed, and the pause length. */
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
     * Joint recalibration: when a healthy rate step reaches its target, the matching chain deadlines are recommended in
     * the same decision. Off by default (rrpe.adaptive.joint.enabled). A joint card is only made when the deadline
     * change is big enough, by min-change-fraction and min-change-ms.
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
    public enum AutoKind { BUCKET_RAISE, BUCKET_CUT, RETRY_DECREASE, RETRY_INCREASE,
        /** Lowering a healthy rate that stayed above twice its target for a while, in small steps. */
        BUCKET_RELAX }
    /**
     * rrpe.adaptive.auto-approve-actions (env RRPE_ADAPTIVE_AUTOAPPROVEACTIONS). Empty by default, so every
     * recommendation waits for a person. CHAIN_DEADLINE is not allowed.
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
