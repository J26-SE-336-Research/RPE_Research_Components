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
@EnableConfigurationProperties({AdaptiveConfig.Settings.class, AdaptiveConfig.Connections.class, AdaptiveConfig.DeliveryEndpoints.class})
public class AdaptiveConfig {
    @ConfigurationProperties("rrpe.delivery")
    public record DeliveryEndpoints(@DefaultValue("") String deadlineUrl,
            @DefaultValue("") String leakyBucketUrl, @DefaultValue("") String retryBudgetUrl) {
        public DeliveryEndpoints {
            for (String url : List.of(deadlineUrl, leakyBucketUrl, retryBudgetUrl)) if (!url.isBlank()) Connections.checkUrl(url);
        }
        public static DeliveryEndpoints disabled() { return new DeliveryEndpoints("", "", ""); }
        public String url(AdaptiveModels.Action action, Connections connections) {
            String specific = switch (action) {
                case CHAIN_DEADLINE -> deadlineUrl;
                case LEAKY_BUCKET -> leakyBucketUrl;
                case RETRY_BUDGET -> retryBudgetUrl;
            };
            return specific.isBlank() ? connections.component3Url() : specific;
        }
    }
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
