package org.rrpe.intelligence.analysis.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * Adjustable PoC settings for the proposal's Leaky Bucket and retry-budget
 * recommendations. These values require experimental validation and agreement
 * with the sidecar's policy interface; they are not production tuning values.
 * This file defines settings only. Policy generation and approval are separate.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecommendationPolicyConfig.Settings.class)
public class RecommendationPolicyConfig {

    /**
     * Override properties using the prefix {@code rrpe.recommendation}.
     * Rate multipliers apply to the baseline requests per second.
     * Retry-budget ratios mean additional retries per original, non-retry
     * request within the same accounting window, not retries per attempt.
     * For example, 0.05 allows at most 5 additional retries per 100 originals.
     */
    @ConfigurationProperties(prefix = "rrpe.recommendation")
    public record Settings(
            @DefaultValue("0.90") double stressedRateMultiplier,
            @DefaultValue("0.75") double overloadedRateMultiplier,
            @DefaultValue("0.05") double stressedRetryBudgetRatio,
            @DefaultValue("0.0") double overloadedRetryBudgetRatio,
            @DefaultValue("5m") Duration recommendationTtl,
            @DefaultValue("1.20") double deadlineSafetyMargin,
            @DefaultValue("1.50") double maximumDeadlineIncreaseFactor,
            @DefaultValue("10000") long maximumChainDeadlineMs,
            @DefaultValue("100") long minimumChainRequests
    ) {
        public Settings {
            requireFraction(stressedRateMultiplier, "stressed-rate-multiplier", false);
            requireFraction(overloadedRateMultiplier, "overloaded-rate-multiplier", false);
            requireFraction(stressedRetryBudgetRatio, "stressed-retry-budget-ratio", true);
            requireFraction(overloadedRetryBudgetRatio, "overloaded-retry-budget-ratio", true);
            if (overloadedRateMultiplier > stressedRateMultiplier) {
                throw new IllegalArgumentException("overloaded-rate-multiplier must not exceed stressed-rate-multiplier");
            }
            if (overloadedRetryBudgetRatio > stressedRetryBudgetRatio) {
                throw new IllegalArgumentException("overloaded-retry-budget-ratio must not exceed stressed-retry-budget-ratio");
            }
            if (recommendationTtl == null || recommendationTtl.isZero() || recommendationTtl.isNegative()) {
                throw new IllegalArgumentException("recommendation-ttl must be positive");
            }
            if (!Double.isFinite(deadlineSafetyMargin) || deadlineSafetyMargin < 1
                    || !Double.isFinite(maximumDeadlineIncreaseFactor) || maximumDeadlineIncreaseFactor < 1) {
                throw new IllegalArgumentException("Deadline margin and maximum increase factor must be finite and at least 1");
            }
            if (maximumChainDeadlineMs <= 0 || minimumChainRequests < 100) {
                throw new IllegalArgumentException("maximum-chain-deadline-ms must be positive and minimum-chain-requests must be at least 100 for p99 analysis");
            }
        }

        private static void requireFraction(double value, String name, boolean allowZero) {
            if (!Double.isFinite(value) || value > 1 || value < 0 || (!allowZero && value == 0)) {
                throw new IllegalArgumentException(name + " must be finite and "
                        + (allowZero ? "between 0 and 1" : "greater than 0 and at most 1"));
            }
        }
    }
}
