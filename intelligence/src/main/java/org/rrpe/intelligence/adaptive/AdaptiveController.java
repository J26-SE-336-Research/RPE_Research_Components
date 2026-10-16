package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/intelligence")
public class AdaptiveController {
    private final AdaptiveEngine engine;
    private final PolicyWorkflow workflow;
    private final AdaptiveCollector collector;
    private final PrometheusWindows prometheus;
    private final String controlToken;
    private final boolean mockEnabled;
    private final MockScenarios scenarios;
    private final ConnectionChecks connectionChecks;
    private final AuditLog audit;

    public AdaptiveController(
            AdaptiveEngine engine,
            PolicyWorkflow workflow,
            AdaptiveCollector collector,
            PrometheusWindows prometheus,
            MockScenarios scenarios,
            ConnectionChecks connectionChecks,
            AuditLog audit,
            @Value("${rrpe.control-token:}") String controlToken,
            @Value("${rrpe.mock-enabled:false}") boolean mockEnabled) {
        this.engine = engine;
        this.workflow = workflow;
        this.collector = collector;
        this.prometheus = prometheus;
        this.controlToken = controlToken;
        this.mockEnabled = mockEnabled;
        this.scenarios = scenarios;
        this.connectionChecks = connectionChecks;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of(
                "collection",
                collector.status(),
                "assessments",
                engine.assessments(),
                "changes",
                workflow.list(),
                "mockEnabled",
                mockEnabled);
    }

    @GetMapping("/queries")
    public Map<String, String> queries(@RequestParam String service) {
        return prometheus.queries(service);
    }

    @GetMapping("/connections")
    public Object connections() {
        return connectionChecks.check();
    }

    /**
     * Audit trail of recommendations, human decisions and deliveries (persisted in PostgreSQL when
     * configured).
     */
    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "200") int limit) {
        return Map.of(
                "persistent",
                audit.persistent(),
                "entries",
                audit.recent(Math.max(1, Math.min(limit, 1000))));
    }

    /** Complete-window ingress for a trusted telemetry adapter; MOCK windows are isolated. */
    @PostMapping("/services")
    public Outcome service(
            @RequestBody ServiceWindow window,
            @RequestHeader(value = "X-RPE-Control-Token", required = false) String token) {
        authorize(window.source(), token);
        if (window.source() == Source.TEMPO)
            throw new IllegalArgumentException("Service metrics require PROMETHEUS or MOCK source");
        return engine.service(window);
    }

    @PostMapping("/chains")
    public Outcome chain(
            @RequestBody ChainWindow window,
            @RequestHeader(value = "X-RPE-Control-Token", required = false) String token) {
        authorize(window.source(), token);
        if (window.source() == Source.PROMETHEUS)
            throw new IllegalArgumentException(
                    "Complete chain timing requires TEMPO or MOCK source");
        return engine.chain(window);
    }

    @PutMapping("/policies")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void policy(
            @RequestBody Policy policy,
            @RequestParam(defaultValue = "PROMETHEUS") Source source,
            @RequestHeader(value = "X-RPE-Control-Token", required = false) String token) {
        authorize(source, token);
        workflow.putPolicy(policy, source);
    }

    @PostMapping("/changes/{id}/review")
    public PolicyChange review(
            @PathVariable UUID id,
            @RequestBody Review review,
            @RequestHeader(value = "X-RPE-Control-Token", required = false) String token) {
        authorize(workflow.find(id).proposal().source(), token);
        return workflow.decide(id, review);
    }

    @PostMapping("/changes/{id}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(
            @PathVariable UUID id,
            @RequestBody Acknowledgement ack,
            @RequestHeader(value = "X-RPE-Control-Token", required = false) String token) {
        authorize(workflow.find(id).proposal().source(), token);
        workflow.deliveryResult(id, ack, null);
    }

    /**
     * Forget the learned live baselines (after an intentional change such as a release); requires
     * the control token.
     */
    @PostMapping("/baselines/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetBaselines(
            @RequestHeader(value = "X-RPE-Control-Token", required = false) String token) {
        authorize(Source.PROMETHEUS, token);
        collector.resetBaselines();
    }

    @GetMapping("/outbox")
    public Object outbox() {
        return workflow.deliverable();
    }

    @PostMapping("/demo/{scenario}")
    public Object demo(@PathVariable String scenario) {
        authorize(Source.MOCK, null);
        return scenarios.run(scenario);
    }

    private void authorize(Source source, String supplied) {
        if (source == null) throw new IllegalArgumentException("Evidence source is required");
        if (source == Source.MOCK) {
            if (!mockEnabled)
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Synthetic input is disabled; enable RRPE_MOCK_ENABLED for a local"
                            + " demonstration");
            return;
        }
        if (controlToken.isBlank())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Live control requires RRPE_CONTROL_TOKEN");
        if (supplied == null
                || !MessageDigest.isEqual(
                        controlToken.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authorised control token required");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
