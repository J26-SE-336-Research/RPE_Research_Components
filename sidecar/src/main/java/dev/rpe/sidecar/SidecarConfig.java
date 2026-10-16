package dev.rpe.sidecar;

import java.util.Map;

/** Sidecar configuration from environment variables injected by the C3 admission webhook. */
public record SidecarConfig(
        String role,
        String serviceName,
        String podName,
        InboundMode inboundMode,
        int appPort,
        int outboundPort,
        int inboundPort,
        int adminPort,
        int metricsPort,
        int maxBodyBytes,
        int upstreamPoolSize,
        int connectTimeoutMillis) {

    public enum InboundMode { REDIRECT, TPROXY }

    public static final int OUTBOUND_PORT = 15001;
    public static final int INBOUND_PORT = 15006;
    public static final int ADMIN_PORT = 15020;
    public static final int METRICS_PORT = 15090;

    public static SidecarConfig fromEnvironment() {
        return from(System.getenv());
    }

    static SidecarConfig from(Map<String, String> env) {
        String role = get(env, "RPE_ROLE", "service");
        if (!role.equals("gateway") && !role.equals("service")) {
            throw new IllegalArgumentException("RPE_ROLE must be gateway or service");
        }
        return new SidecarConfig(
                role,
                get(env, "RPE_SERVICE_NAME", "unknown-service"),
                get(env, "RPE_POD_NAME", "unknown-pod"),
                InboundMode.valueOf(get(env, "RPE_INBOUND_MODE", "redirect").toUpperCase()),
                port(env, "RPE_APP_PORT", 8080),
                port(env, "RPE_OUTBOUND_PORT", OUTBOUND_PORT),
                port(env, "RPE_INBOUND_PORT", INBOUND_PORT),
                port(env, "RPE_ADMIN_PORT", ADMIN_PORT),
                port(env, "RPE_METRICS_PORT", METRICS_PORT),
                positive(env, "RPE_MAX_BODY_BYTES", 1 << 20),
                positive(env, "RPE_UPSTREAM_POOL_SIZE", 64),
                positive(env, "RPE_CONNECT_TIMEOUT_MS", 2000));
    }

    public boolean gateway() {
        return role.equals("gateway");
    }

    private static String get(Map<String, String> env, String key, String def) {
        String v = env.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int positive(Map<String, String> env, String key, int def) {
        int v = Integer.parseInt(get(env, key, Integer.toString(def)));
        if (v <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return v;
    }

    private static int port(Map<String, String> env, String key, int def) {
        int v = positive(env, key, def);
        if (v > 65535) {
            throw new IllegalArgumentException(key + " must be a TCP port");
        }
        return v;
    }
}
