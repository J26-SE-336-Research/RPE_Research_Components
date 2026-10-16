package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Integration with the Component 3 policy API:
 * <ol>
 *   <li>delivers human-approved recommendations as a new policy version (the reviewer is the approver) and starts a
 *       two-phase rollout;</li>
 *   <li>confirms enforcement when the rollout completes (APPLIED);</li>
 *   <li>keeps the engine's view of each service's current policy in sync with Component 3's effective policies.</li>
 * </ol>
 */
@Service
public class Component3Bridge {
    private static final Logger LOG = Logger.getLogger(Component3Bridge.class.getName());
    private final PolicyWorkflow workflow;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.Settings settings;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.systemUTC();
    private record Pending(String rollout, String epoch) {}
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public Component3Bridge(PolicyWorkflow workflow, AdaptiveConfig.Connections connections, AdaptiveConfig.Settings settings) {
        this.workflow = workflow; this.connections = connections; this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(connections.timeout()).build();
    }

    @Scheduled(fixedDelayString = "${rrpe.delivery-poll:15s}")
    public synchronized void run() {
        if (connections.component3Url().isBlank()) return;
        try {
            confirm();
            Map<String, JsonNode> effective = effective();
            for (var change : workflow.deliverable()) deliver(change, effective);
            sync(effective());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Component 3 bridge: " + e.getMessage());
        }
    }

    private void deliver(PolicyChange change, Map<String, JsonNode> effective) {
        var p = change.proposal();
        boolean chain = p.action() == Action.CHAIN_DEADLINE;
        // A chain deadline is a route budget on the gateway, which creates the root context (R-S01).
        String service = chain ? connections.gatewayService() : p.target();
        JsonNode current = effective.get(service);
        String route = current != null ? current.path("route").asString("") : "";
        ObjectNode spec = current != null ? (ObjectNode) json.readTree(current.path("spec").asString("{}")) : json.createObjectNode();
        switch (p.action()) {
            case LEAKY_BUCKET -> object(spec, "leakyBucket").put("ratePerSec", p.proposedValue());
            case RETRY_BUDGET -> object(spec, "retry").put("budgetRatio", p.proposedValue());
            case CHAIN_DEADLINE -> {
                long deadline = (long) Math.ceil(p.proposedValue());
                object(object(spec, "routes"), p.target()).put("deadlineMs", deadline);
                // a policy scoped to this very route keeps its scope-level deadline consistent
                if (p.target().equals(route)) spec.put("deadlineMs", deadline);
            }
        }
        String policyId = current != null ? current.path("policyId").asString() : "c4-" + slug(p.target());
        var body = new LinkedHashMap<String, Object>();
        body.put("policyId", policyId);
        body.put("source", "c4-recommendation");
        body.put("recommendationId", p.id().toString());
        body.put("scope", Map.of("service", service, "route", route));
        body.put("spec", spec);
        body.put("approval", Map.of("approvedBy", change.reviewer(), "decision", "approved"));
        try {
            JsonNode created = post("/api/k8s/policies", body).path("policy");
            String epoch = created.path("epoch").asString();
            JsonNode rollout = post("/api/k8s/rollouts", Map.of("policyId", policyId, "version", created.path("version").asInt(),
                    "mode", "two-phase", "requestedBy", change.reviewer()));
            pending.put(p.id(), new Pending(rollout.path("metadata").path("name").asString(), epoch));
            workflow.deliveryResult(p.id(), new Acknowledgement("ACCEPTED", epoch, null), null);
        } catch (RuntimeException e) {
            workflow.deliveryResult(p.id(), null, e.getMessage());
        }
    }

    private void confirm() {
        for (var change : workflow.sent()) {
            var p = change.proposal();
            Pending sent = pending.get(p.id());
            if (sent == null) continue;
            try {
                JsonNode status = get("/api/k8s/rollouts/" + sent.rollout()).path("status");
                String phase = status.path("phase").asString("");
                if ("Complete".equals(phase)) {
                    workflow.deliveryResult(p.id(), new Acknowledgement("APPLIED", sent.epoch(), p.proposedValue()), null);
                    pending.remove(p.id());
                } else if ("Failed".equals(phase)) {
                    workflow.deliveryResult(p.id(), null, "Rollout failed: " + status.path("message").asString(""));
                    pending.remove(p.id());
                }
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "rollout status: " + e.getMessage());
            }
        }
    }

    /** Component 3's effective policy per service becomes the engine's current policy reference. */
    private void sync(Map<String, JsonNode> effective) {
        Instant validUntil = clock.instant().plus(settings.ttl());
        var awaiting = pending.values().stream().map(Pending::epoch).collect(java.util.stream.Collectors.toSet());
        for (var entry : effective.entrySet()) {
            JsonNode e = entry.getValue();
            String epoch = e.path("epoch").asString("");
            // An epoch we delivered is confirmed (APPLIED) first; syncing it earlier would supersede our own change.
            if (epoch.isBlank() || awaiting.contains(epoch)) continue;
            JsonNode spec = json.readTree(e.path("spec").asString("{}"));
            Double rate = positive(spec.path("leakyBucket").path("ratePerSec"));
            Double ratio = spec.path("retry").path("budgetRatio").isNumber() ? spec.path("retry").path("budgetRatio").asDouble() : null;
            if (ratio != null && (ratio < 0 || ratio > settings.maximumRetryRatio())) ratio = null;
            try {
                workflow.putPolicy(new Policy(entry.getKey(), epoch, validUntil, rate, rate, ratio, null), Source.PROMETHEUS);
            } catch (IllegalArgumentException ignored) {
                // incomplete policy for this service; the engine keeps waiting for a usable reference
            }
            if (!entry.getKey().equals(connections.gatewayService())) continue;
            for (var route : spec.path("routes").properties()) {
                Double deadline = positive(route.getValue().path("deadlineMs"));
                if (deadline == null) continue;
                try {
                    workflow.putPolicy(new Policy(route.getKey(), epoch, validUntil, null, null, null, deadline), Source.TEMPO);
                } catch (IllegalArgumentException ignored) {
                    // invalid deadline reference
                }
            }
        }
    }

    private Map<String, JsonNode> effective() {
        var out = new LinkedHashMap<String, JsonNode>();
        get("/api/k8s/effective").properties().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private JsonNode get(String path) { return send(HttpRequest.newBuilder(uri(path)).GET()); }

    private JsonNode post(String path, Object body) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    private JsonNode send(HttpRequest.Builder builder) {
        try {
            var response = http.send(builder.timeout(connections.timeout()).header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) throw new IllegalStateException("Component 3 HTTP " + response.statusCode() + ": " + response.body());
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Component 3 unreachable: " + e.getMessage());
        }
    }

    private URI uri(String path) { return URI.create(connections.component3Url().replaceAll("/$", "") + path); }

    private static ObjectNode object(ObjectNode parent, String field) {
        return parent.path(field).isObject() ? (ObjectNode) parent.get(field) : parent.putObject(field);
    }

    private static Double positive(JsonNode n) { return n.isNumber() && n.asDouble() > 0 ? n.asDouble() : null; }

    static String slug(String target) {
        String s = target.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.length() > 50 ? s.substring(0, 50).replaceAll("-+$", "") : s;
    }
}
