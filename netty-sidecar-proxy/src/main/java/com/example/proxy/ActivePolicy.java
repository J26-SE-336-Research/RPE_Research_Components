package com.example.proxy;

import java.util.logging.Logger;

/**
 * The Component 3 policy this proxy currently enforces: its epoch (stamped into new request contexts as {@code pe}),
 * on the gateway proxy the route deadlines that override {@code GATEWAY_DEADLINE_RULES}, and the inbound
 * leaky-bucket rate a rate-limit policy sets (the rate only, never the queue capacity). It also gives the request
 * path access to the {@link PolicyStore} for per-request epoch pinning.
 */
final class ActivePolicy {
    private static final Logger LOGGER = Logger.getLogger(ActivePolicy.class.getName());
    private static volatile String epoch = "";
    private static volatile GatewayDeadlineRules gatewayRules;
    private static volatile PolicyStore store;

    private ActivePolicy() {}

    static void apply(ProxyConfig config, PolicyStore.Entry active) {
        apply(config, active, BackpressureController.current());
    }

    static void apply(ProxyConfig config, PolicyStore.Entry active, BackpressureController backpressure) {
        GatewayDeadlineRules rules = active.routeDeadlines().isEmpty()
                ? config.gatewayDeadlineRules()
                : config.gatewayDeadlineRules().overriddenBy(active.routeDeadlines());
        gatewayRules = rules;
        epoch = active.epoch();
        if (config.gatewaySidecar()) ProxyMetrics.configureGatewayDeadlineBudgets(rules.metricBudgets());
        // A rate-limit policy sets the rate of the existing inbound leaky bucket. A policy without a (valid)
        // rate leaves the current rate alone.
        // Only the rate is applied; the queue capacity is never changed by a policy.
        Double rate = PolicyStore.leakyBucketRate(active.document());
        if (rate != null && backpressure != null && backpressure.applyPolicyRate(rate)) {
            LOGGER.info(() -> "Policy " + active.epoch() + " sets the inbound rate to " + rate + " requests/s");
        }
    }

    /** Epoch of the active policy, or "" when none is active. */
    static String epoch() { return epoch; }

    /** Route deadline rules for new requests at the gateway. */
    static GatewayDeadlineRules gatewayRules(ProxyConfig config) {
        GatewayDeadlineRules rules = gatewayRules;
        return rules == null ? config.gatewayDeadlineRules() : rules;
    }

    static void track(PolicyStore policies) { store = policies; }

    static PolicyStore store() { return store; }

    /**
     * Pins an inbound request to the policy epoch it is served under ({@link PolicyStore#pin}); {@code hop} releases
     * the pin when the request completes. Returns the pinned epoch, or "" when no policy applies.
     */
    static String pin(RpeContractMetrics.Hop hop, String requestEpoch, boolean stamping) {
        PolicyStore policies = store;
        PolicyStore.Pin pin = policies == null ? null : policies.pin(requestEpoch, stamping);
        if (pin == null) return "";
        hop.onComplete(() -> policies.release(pin));
        return pin.epoch();
    }
}
