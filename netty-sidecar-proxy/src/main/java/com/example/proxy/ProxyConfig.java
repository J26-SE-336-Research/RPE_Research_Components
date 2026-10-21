package com.example.proxy;

import java.util.Map;

public record ProxyConfig(
        String listenHost,
        int listenPort,
        String metricsListenHost,
        int metricsListenPort,
        long defaultDeadlineMillis,
        long requestContextTtlMillis,
        boolean detectInboundDeadline,
        boolean gatewaySidecar,
        GatewayDeadlineRules gatewayDeadlineRules,
        String serviceName,
        String otelExporterOtlpEndpoint,
        double inboundRequestsPerSecond,
        int inboundQueueCapacity,
        int outboundQueueCapacity,
        int circuitBreakerFailureThreshold,
        long circuitBreakerOpenMillis,
        String backpressureControlToken,
        int[] backpressureSignalThresholdsPercent,
        int backpressureSignalClearPercent,
        Integration integration
) {
    /** Default ports used when the RPE admission webhook (Component 3) injects this proxy. */
    /** Default inbound leaky-bucket rates (requests/s); BACKPRESSURE_INBOUND_RATE_PER_SECOND overrides both. */
    static final double SERVICE_INBOUND_RATE_PER_SECOND = 100.0;
    static final double GATEWAY_INBOUND_RATE_PER_SECOND = 300.0;
    /** Default inbound queue capacities (waiting requests); BACKPRESSURE_INBOUND_QUEUE_CAPACITY overrides both. */
    static final int SERVICE_INBOUND_QUEUE_CAPACITY = 200;
    static final int GATEWAY_INBOUND_QUEUE_CAPACITY = 1000;
    static final int RPE_OUTBOUND_PORT = 15001;
    static final int RPE_INBOUND_PORT = 15006;
    static final int RPE_ADMIN_PORT = 15020;
    static final int RPE_METRICS_PORT = 15090;

    /**
     * Settings that connect the proxy to the other RPE components. {@link #standalone()} keeps the original
     * single-listener TPROXY behaviour.
     *
     * @param inboundListenPort         separate inbound listener (0 = inbound traffic shares {@code listenPort})
     * @param inboundTransparent        inbound listener uses IP_TRANSPARENT (TPROXY); false for REDIRECT
     * @param appPort                   application port for REDIRECTed inbound connections
     * @param adminListenPort           control API for policies and health (0 = disabled)
     * @param podName                   Pod name used as a metric label
     * @param missingContextPassthrough forward requests that carry no request context unchanged (default); false = HTTP 400
     * @param rootRetryBudget           retry budget stamped into contexts created by the gateway proxy
     */
    public record Integration(int inboundListenPort, boolean inboundTransparent, int appPort, int adminListenPort,
                              String podName, boolean missingContextPassthrough, long rootRetryBudget) {
        public static Integration standalone() {
            return new Integration(0, true, 0, 0, "unknown-pod", true, 3);
        }
    }

    /** The original constructor: a standalone proxy without the RPE component integration. */
    public ProxyConfig(String listenHost, int listenPort, String metricsListenHost, int metricsListenPort,
                       long defaultDeadlineMillis, long requestContextTtlMillis, boolean detectInboundDeadline,
                       boolean gatewaySidecar, GatewayDeadlineRules gatewayDeadlineRules, String serviceName,
                       String otelExporterOtlpEndpoint, double inboundRequestsPerSecond, int inboundQueueCapacity,
                       int outboundQueueCapacity, int circuitBreakerFailureThreshold, long circuitBreakerOpenMillis,
                       String backpressureControlToken, int[] backpressureSignalThresholdsPercent,
                       int backpressureSignalClearPercent) {
        this(listenHost, listenPort, metricsListenHost, metricsListenPort, defaultDeadlineMillis,
                requestContextTtlMillis, detectInboundDeadline, gatewaySidecar, gatewayDeadlineRules, serviceName,
                otelExporterOtlpEndpoint, inboundRequestsPerSecond, inboundQueueCapacity, outboundQueueCapacity,
                circuitBreakerFailureThreshold, circuitBreakerOpenMillis, backpressureControlToken,
                backpressureSignalThresholdsPercent, backpressureSignalClearPercent, Integration.standalone());
    }

    public static ProxyConfig fromEnvironment() {
        return from(System.getenv());
    }

    /**
     * Reads the configuration. The proxy's own variables always win; when the RPE admission webhook injected the
     * proxy (it sets {@code RPE_ROLE}), its {@code RPE_*} variables and the RPE sidecar ports are the fallbacks.
     */
    static ProxyConfig from(Map<String, String> env) {
        boolean rpe = !env(env, "RPE_ROLE", "").isEmpty() || !env(env, "RPE_SERVICE_NAME", "").isEmpty();
        int[] signalThresholds = signalThresholdsEnv(env, "BACKPRESSURE_SIGNAL_THRESHOLDS_PERCENT", "50,65,80,95");
        int signalClearPercent = boundedPercentageEnv(env, "BACKPRESSURE_SIGNAL_CLEAR_PERCENT", 30);
        if (signalClearPercent >= signalThresholds[0]) {
            throw new IllegalArgumentException("BACKPRESSURE_SIGNAL_CLEAR_PERCENT must be below the first signal threshold");
        }
        String role = env(env, "PROXY_ROLE", env(env, "RPE_ROLE", "service"));
        String inboundMode = env(env, "PROXY_INBOUND_MODE", env(env, "RPE_INBOUND_MODE", "tproxy")).toLowerCase();
        if (!inboundMode.equals("tproxy") && !inboundMode.equals("redirect")) {
            throw new IllegalArgumentException("PROXY_INBOUND_MODE must be tproxy or redirect");
        }
        String missingContext = env(env, "PROXY_MISSING_CONTEXT", env(env, "RPE_MISSING_CONTEXT", "passthrough")).toLowerCase();
        if (!missingContext.equals("reject") && !missingContext.equals("passthrough")) {
            throw new IllegalArgumentException("PROXY_MISSING_CONTEXT must be reject or passthrough");
        }
        long rootRetryBudget = longEnv(env, "PROXY_ROOT_RETRY_BUDGET", longEnv(env, "RPE_RETRY_BUDGET", 3));
        if (rootRetryBudget < 0 || rootRetryBudget > 255) {
            throw new IllegalArgumentException("PROXY_ROOT_RETRY_BUDGET must be from 0 to 255");
        }
        Integration integration = new Integration(
                optionalPortEnv(env, "PROXY_INBOUND_LISTEN_PORT",
                        rpe ? optionalPortEnv(env, "RPE_INBOUND_PORT", RPE_INBOUND_PORT) : 0),
                inboundMode.equals("tproxy"),
                portEnv(env, "PROXY_APP_PORT", portEnv(env, "RPE_APP_PORT", 8080)),
                optionalPortEnv(env, "PROXY_ADMIN_LISTEN_PORT",
                        rpe ? optionalPortEnv(env, "RPE_ADMIN_PORT", RPE_ADMIN_PORT) : 0),
                env(env, "RPE_POD_NAME", env(env, "HOSTNAME", "unknown-pod")),
                missingContext.equals("passthrough"),
                rootRetryBudget);
        return new ProxyConfig(
                env(env, "PROXY_LISTEN_HOST", "0.0.0.0"),
                portEnv(env, "PROXY_LISTEN_PORT", rpe ? portEnv(env, "RPE_OUTBOUND_PORT", RPE_OUTBOUND_PORT) : 8080),
                env(env, "METRICS_LISTEN_HOST", "0.0.0.0"),
                portEnv(env, "METRICS_LISTEN_PORT", rpe ? portEnv(env, "RPE_METRICS_PORT", RPE_METRICS_PORT) : 9090),
                longEnv(env, "DEFAULT_DEADLINE_MILLIS", longEnv(env, "RPE_DEFAULT_DEADLINE_MS", 5000)),
                positiveLongEnv(env, "REQUEST_CONTEXT_TTL_MILLIS", 20000),
                Boolean.parseBoolean(env(env, "PROXY_DETECT_INBOUND_DEADLINE", "true")),
                "gateway".equalsIgnoreCase(role),
                GatewayDeadlineRules.parse(env(env, "GATEWAY_DEADLINE_RULES", env(env, "RPE_ROUTE_DEADLINES", ""))),
                env(env, "SERVICE_NAME", env(env, "RPE_SERVICE_NAME", "unknown-service")),
                env(env, "OTEL_EXPORTER_OTLP_ENDPOINT", ""),
                // the gateway takes every chain request of the estate, so its leaky bucket releases faster and holds more
                // by default (C2 owner, 21 Oct 2026); a service sidecar keeps 100 requests/s and 200 waiting requests
                boundedPositiveDoubleEnv(env, "BACKPRESSURE_INBOUND_RATE_PER_SECOND",
                        "gateway".equalsIgnoreCase(role) ? GATEWAY_INBOUND_RATE_PER_SECOND : SERVICE_INBOUND_RATE_PER_SECOND, 1_000_000),
                boundedPositiveIntEnv(env, "BACKPRESSURE_INBOUND_QUEUE_CAPACITY",
                        "gateway".equalsIgnoreCase(role) ? GATEWAY_INBOUND_QUEUE_CAPACITY : SERVICE_INBOUND_QUEUE_CAPACITY, 100_000),
                boundedPositiveIntEnv(env, "BACKPRESSURE_OUTBOUND_QUEUE_CAPACITY", 1000, 100_000),
                positiveIntEnv(env, "BACKPRESSURE_CIRCUIT_BREAKER_FAILURE_THRESHOLD", 10),
                positiveLongEnv(env, "BACKPRESSURE_CIRCUIT_BREAKER_OPEN_MILLIS", 5000),
                env(env, "BACKPRESSURE_CONTROL_TOKEN", ""),
                signalThresholds,
                signalClearPercent,
                integration
        );
    }

    private static String env(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int intEnv(Map<String, String> env, String key, int defaultValue) {
        String value = env.get(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Integer.parseInt(value.trim());
    }

    private static long longEnv(Map<String, String> env, String key, long defaultValue) {
        String value = env.get(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Long.parseLong(value.trim());
    }

    private static int portEnv(Map<String, String> env, String key, int defaultValue) {
        int value = intEnv(env, key, defaultValue);
        if (value < 1 || value > 65535) throw new IllegalArgumentException(key + " must be a TCP port from 1 to 65535");
        return value;
    }

    /** Like {@link #portEnv} but 0 is allowed and disables the listener. */
    private static int optionalPortEnv(Map<String, String> env, String key, int defaultValue) {
        int value = intEnv(env, key, defaultValue);
        if (value < 0 || value > 65535) throw new IllegalArgumentException(key + " must be 0 or a TCP port");
        return value;
    }

    private static long positiveLongEnv(Map<String, String> env, String key, long defaultValue) {
        long value = longEnv(env, key, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than 0");
        }
        return value;
    }

    private static int positiveIntEnv(Map<String, String> env, String key, int defaultValue) {
        int value = intEnv(env, key, defaultValue);
        if (value <= 0) throw new IllegalArgumentException(key + " must be greater than 0");
        return value;
    }

    private static double positiveDoubleEnv(Map<String, String> env, String key, double defaultValue) {
        String raw = env.get(key);
        double value = raw == null || raw.isBlank() ? defaultValue : Double.parseDouble(raw.trim());
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(key + " must be a finite number greater than 0");
        }
        return value;
    }

    private static double boundedPositiveDoubleEnv(Map<String, String> env, String key, double defaultValue, double maximum) {
        double value = positiveDoubleEnv(env, key, defaultValue);
        if (value > maximum) throw new IllegalArgumentException(key + " must not exceed " + maximum);
        return value;
    }

    private static int boundedPositiveIntEnv(Map<String, String> env, String key, int defaultValue, int maximum) {
        int value = positiveIntEnv(env, key, defaultValue);
        if (value > maximum) throw new IllegalArgumentException(key + " must not exceed " + maximum);
        return value;
    }

    private static int[] signalThresholdsEnv(Map<String, String> env, String key, String defaultValue) {
        String raw = env(env, key, defaultValue);
        java.util.TreeSet<Integer> thresholds = new java.util.TreeSet<>();
        for (String part : raw.split(",")) {
            int value;
            try { value = Integer.parseInt(part.trim()); }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(key + " must be comma-separated percentages from 1 to 100");
            }
            if (value < 1 || value > 100) {
                throw new IllegalArgumentException(key + " must contain percentages from 1 to 100");
            }
            thresholds.add(value);
        }
        if (thresholds.isEmpty()) throw new IllegalArgumentException(key + " must not be empty");
        return thresholds.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int boundedPercentageEnv(Map<String, String> env, String key, int defaultValue) {
        int value = intEnv(env, key, defaultValue);
        if (value < 0 || value > 99) throw new IllegalArgumentException(key + " must be from 0 to 99");
        return value;
    }
}
