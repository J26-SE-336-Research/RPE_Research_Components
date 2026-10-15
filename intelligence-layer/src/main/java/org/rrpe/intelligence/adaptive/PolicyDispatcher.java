package org.rrpe.intelligence.adaptive;

import java.net.URI;
import java.net.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;

@Service
public class PolicyDispatcher {
    private final PolicyWorkflow workflow;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.DeliveryEndpoints endpoints;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    public PolicyDispatcher(PolicyWorkflow workflow, AdaptiveConfig.Connections connections, AdaptiveConfig.DeliveryEndpoints endpoints) {
        this.workflow = workflow; this.connections = connections; this.endpoints = endpoints;
        http = HttpClient.newBuilder().connectTimeout(connections.timeout()).build();
    }
    @Scheduled(fixedDelayString = "${rrpe.delivery-poll:15s}")
    public synchronized void dispatch() {
        for (var change : workflow.deliverable()) {
            String destination = endpoints.url(change.proposal().action(), connections);
            if (destination.isBlank()) continue;
            try {
                var request = HttpRequest.newBuilder(URI.create(destination)).timeout(connections.timeout())
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .header("Idempotency-Key", change.proposal().id().toString());
                if (!connections.component3Token().isBlank()) request.header("Authorization", "Bearer " + connections.component3Token());
                var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(change))).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Component 3 HTTP " + response.statusCode());
                var ack = json.readValue(response.body(), Acknowledgement.class);
                workflow.deliveryResult(change.proposal().id(), ack, null);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
            catch (Exception exception) { workflow.deliveryResult(change.proposal().id(), null, exception.getMessage()); }
        }
    }
}
