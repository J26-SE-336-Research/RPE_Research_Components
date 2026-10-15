package com.example.proxy;

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
        int backpressureSignalClearPercent
) {
    public static ProxyConfig fromEnvironment() {
        int[] signalThresholds = signalThresholdsEnv("BACKPRESSURE_SIGNAL_THRESHOLDS_PERCENT", "50,65,80,95");
        int signalClearPercent = boundedPercentageEnv("BACKPRESSURE_SIGNAL_CLEAR_PERCENT", 30);
        if (signalClearPercent >= signalThresholds[0]) {
            throw new IllegalArgumentException("BACKPRESSURE_SIGNAL_CLEAR_PERCENT must be below the first signal threshold");
        }
        return new ProxyConfig(
                env("PROXY_LISTEN_HOST", "0.0.0.0"),
                intEnv("PROXY_LISTEN_PORT", 8080),
                env("METRICS_LISTEN_HOST", "0.0.0.0"),
                intEnv("METRICS_LISTEN_PORT", 9090),
                longEnv("DEFAULT_DEADLINE_MILLIS", 5000),
                positiveLongEnv("REQUEST_CONTEXT_TTL_MILLIS", 20000),
                Boolean.parseBoolean(env("PROXY_DETECT_INBOUND_DEADLINE", "true")),
                "gateway".equalsIgnoreCase(env("PROXY_ROLE", "service")),
                GatewayDeadlineRules.parse(env("GATEWAY_DEADLINE_RULES", "")),
                env("SERVICE_NAME", "unknown-service"),
                env("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
                boundedPositiveDoubleEnv("BACKPRESSURE_INBOUND_RATE_PER_SECOND", 100.0, 1_000_000),
                boundedPositiveIntEnv("BACKPRESSURE_INBOUND_QUEUE_CAPACITY", 200, 100_000),
                boundedPositiveIntEnv("BACKPRESSURE_OUTBOUND_QUEUE_CAPACITY", 200, 100_000),
                positiveIntEnv("BACKPRESSURE_CIRCUIT_BREAKER_FAILURE_THRESHOLD", 10),
                positiveLongEnv("BACKPRESSURE_CIRCUIT_BREAKER_OPEN_MILLIS", 5000),
                env("BACKPRESSURE_CONTROL_TOKEN", ""),
                signalThresholds,
                signalClearPercent
        );
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Integer.parseInt(value);
    }

    private static long longEnv(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Long.parseLong(value);
    }

    private static long positiveLongEnv(String key, long defaultValue) {
        long value = longEnv(key, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than 0");
        }
        return value;
    }

    private static int positiveIntEnv(String key, int defaultValue) {
        int value = intEnv(key, defaultValue);
        if (value <= 0) throw new IllegalArgumentException(key + " must be greater than 0");
        return value;
    }

    private static double positiveDoubleEnv(String key, double defaultValue) {
        String raw = System.getenv(key);
        double value = raw == null || raw.isBlank() ? defaultValue : Double.parseDouble(raw);
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(key + " must be a finite number greater than 0");
        }
        return value;
    }

    private static double boundedPositiveDoubleEnv(String key, double defaultValue, double maximum) {
        double value = positiveDoubleEnv(key, defaultValue);
        if (value > maximum) throw new IllegalArgumentException(key + " must not exceed " + maximum);
        return value;
    }

    private static int boundedPositiveIntEnv(String key, int defaultValue, int maximum) {
        int value = positiveIntEnv(key, defaultValue);
        if (value > maximum) throw new IllegalArgumentException(key + " must not exceed " + maximum);
        return value;
    }

    private static int[] signalThresholdsEnv(String key, String defaultValue) {
        String raw = env(key, defaultValue);
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

    private static int boundedPercentageEnv(String key, int defaultValue) {
        int value = intEnv(key, defaultValue);
        if (value < 0 || value > 99) throw new IllegalArgumentException(key + " must be from 0 to 99");
        return value;
    }
}
