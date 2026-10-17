package com.example.proxy;

/**
 * The Component 3 policy this proxy currently enforces: its epoch (stamped into new request contexts as {@code pe})
 * and, on the gateway proxy, the route deadlines that override {@code GATEWAY_DEADLINE_RULES}.
 */
final class ActivePolicy {
    private static volatile String epoch = "";
    private static volatile GatewayDeadlineRules gatewayRules;

    private ActivePolicy() {}

    static void apply(ProxyConfig config, PolicyStore.Entry active) {
        GatewayDeadlineRules rules = active.routeDeadlines().isEmpty()
                ? config.gatewayDeadlineRules()
                : config.gatewayDeadlineRules().overriddenBy(active.routeDeadlines());
        gatewayRules = rules;
        epoch = active.epoch();
        if (config.gatewaySidecar()) ProxyMetrics.configureGatewayDeadlineBudgets(rules.metricBudgets());
    }

    /** Epoch of the active policy, or "" when none is active. */
    static String epoch() { return epoch; }

    /** Route deadline rules for new requests at the gateway. */
    static GatewayDeadlineRules gatewayRules(ProxyConfig config) {
        GatewayDeadlineRules rules = gatewayRules;
        return rules == null ? config.gatewayDeadlineRules() : rules;
    }
}
