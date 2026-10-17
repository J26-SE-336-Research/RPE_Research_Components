package com.example.proxy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Sidecar-initiated delivery acknowledgements to the Component 3 policy API (contract A3.5): after every successful
 * stage, activate or retire, {@code POST /api/k8s/acks {pod, service, epoch, version, hash, state, ts}}, where
 * {@code hash} is the hash this sidecar computed from the policy it holds. Sent asynchronously; a failed ack never
 * affects the control API (the Operator also verifies through {@code GET /rpe/v1/policy}).
 */
final class PolicyAcks {
    private static final Logger LOGGER = Logger.getLogger(PolicyAcks.class.getName());
    private final URI endpoint;
    private final String pod;
    private final String service;
    private final HttpClient http;
    private volatile long lastWarningMillis;

    PolicyAcks(String policyApiUrl, String pod, String service) {
        this.endpoint = policyApiUrl == null || policyApiUrl.isBlank() ? null
                : URI.create(policyApiUrl.replaceAll("/+$", "") + "/api/k8s/acks");
        this.pod = pod;
        this.service = service;
        this.http = endpoint == null ? null : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /** {@code PROXY_POLICY_API_URL}, else {@code RPE_POLICY_API_URL} (set by the webhook); empty disables acks. */
    static PolicyAcks from(Map<String, String> env, ProxyConfig config) {
        String url = env.getOrDefault("PROXY_POLICY_API_URL", "");
        if (url.isBlank()) url = env.getOrDefault("RPE_POLICY_API_URL", "");
        return new PolicyAcks(url.trim(), config.integration().podName(), config.serviceName());
    }

    boolean enabled() { return endpoint != null; }

    void send(PolicyStore.Entry entry, String state) {
        if (endpoint == null || entry == null) return;
        ObjectNode body = PolicyStore.JSON.createObjectNode()
                .put("pod", pod)
                .put("service", service)
                .put("epoch", entry.epoch())
                .put("version", entry.version())
                .put("hash", entry.hash())
                .put("state", state)
                .put("ts", Instant.now().toString());
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            if (failure != null || response.statusCode() >= 300) {
                long now = System.currentTimeMillis();
                if (now - lastWarningMillis > 60_000) {
                    lastWarningMillis = now;
                    LOGGER.warning("Policy ack to " + endpoint + " failed: "
                            + (failure != null ? failure.toString() : "HTTP " + response.statusCode()));
                }
            }
        });
    }
}
