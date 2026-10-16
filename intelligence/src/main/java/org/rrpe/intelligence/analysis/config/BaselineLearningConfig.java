package org.rrpe.intelligence.analysis.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Configurable timings and data-quality limits for automatic baseline learning.
 * Recent window, analysis interval and sample interval follow the proposal.
 * Other defaults are initial PoC choices to validate through experiments.
 * This configuration is independent of the eventual storage implementation.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BaselineLearningConfig.Settings.class)
public class BaselineLearningConfig {

    /** Override values using properties prefixed with {@code rrpe.baseline}. */
    @ConfigurationProperties(prefix = "rrpe.baseline")
    public record Settings(
            @DefaultValue("5m") Duration recentWindow,
            @DefaultValue("1m") Duration analysisInterval,
            @DefaultValue("15s") Duration sampleInterval,
            @DefaultValue("30m") Duration baselineWindow,
            @DefaultValue("2m") Duration startupExclusion,
            @DefaultValue("30s") Duration maxSampleGap,
            @DefaultValue("16") int minimumRecentSamples,
            @DefaultValue("100") int minimumBaselineSamples,
            @DefaultValue("0.15") double stabilityTolerance
    ) {
        public Settings {
            requirePositive(recentWindow, "recent-window");
            requirePositive(analysisInterval, "analysis-interval");
            requirePositive(sampleInterval, "sample-interval");
            requirePositive(baselineWindow, "baseline-window");
            requirePositive(maxSampleGap, "max-sample-gap");
            if (startupExclusion == null || startupExclusion.isNegative()) {
                throw new IllegalArgumentException("startup-exclusion must be nonnegative");
            }
            if (baselineWindow.compareTo(recentWindow) <= 0) {
                throw new IllegalArgumentException("baseline-window must be longer than recent-window");
            }
            if (analysisInterval.compareTo(recentWindow) > 0) {
                throw new IllegalArgumentException("analysis-interval must not exceed recent-window");
            }
            if (sampleInterval.compareTo(analysisInterval) > 0) {
                throw new IllegalArgumentException("sample-interval must not exceed analysis-interval");
            }
            if (maxSampleGap.compareTo(sampleInterval) < 0) {
                throw new IllegalArgumentException("max-sample-gap must be at least sample-interval");
            }
            requireSampleCount(minimumRecentSamples, recentWindow, sampleInterval, "minimum-recent-samples");
            requireSampleCount(minimumBaselineSamples, baselineWindow, sampleInterval, "minimum-baseline-samples");
            if (!Double.isFinite(stabilityTolerance) || stabilityTolerance <= 0 || stabilityTolerance >= 1) {
                throw new IllegalArgumentException("stability-tolerance must be finite and between 0 and 1 exclusively");
            }
        }

        private static void requirePositive(Duration duration, String name) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }

        private static void requireSampleCount(int count, Duration window, Duration interval, String name) {
            if (count < 2 || count - 1L > window.dividedBy(interval)) {
                throw new IllegalArgumentException(name + " must be at least 2 and fit within the configured window");
            }
        }
    }
}
