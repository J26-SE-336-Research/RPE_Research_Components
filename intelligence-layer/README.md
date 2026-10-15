# Component 4: Resilience Intelligence Layer

Standalone Spring Boot application for deadline recommendations, service-health
classification, Leaky Bucket rates and retry budgets. Its dashboard is served by
the same application on port **8090**; no separate frontend build is needed.

This component was copied from `RPE_POC1`, branch `component-4-integrated-v2`,
commit [`775fd0a`](https://github.com/J26-SE-336-Research/RPE_POC1/commit/775fd0a).
The Java source, tests, dashboard, Maven wrapper and examples are preserved.
Microservices, sidecars, databases, Prometheus and Tempo remain external systems.
No source code from those systems is required to build this component.

## Prerequisites

- JDK 17, installed or extracted as a portable JDK. Set `JAVA_HOME` to the JDK folder.
- The Maven wrapper downloads Maven/dependencies on first use; an existing Maven cache can be reused.
- For live analysis, reachable Prometheus and Tempo servers receiving Component 2 telemetry.

## Run synthetic examples

From this repository's root, in PowerShell:

```powershell
cd intelligence-layer
$env:RRPE_MOCK_ENABLED = 'true'
$env:PROMETHEUS_COLLECTION_ENABLED = 'false'
.\mvnw.cmd spring-boot:run
```

On macOS/Linux, use `./mvnw` with the same environment variables.

Open **http://localhost:8090/adaptive.html**. Select **Synthetic examples**, choose
a scenario and click **Run example**. Examples use an isolated engine and virtual
clock; they do not change live baselines or policies. `/api/v2/intelligence` is
the current API; `/api/v1` and the original page remain legacy demonstrations.

## Connect to live metrics and traces

Start the application services and Component 2 telemetry infrastructure separately.
If port-forwarding locally, leave both forwards running and use:

```powershell
# Run from intelligence-layer; stop the previous application first.
$env:RRPE_MOCK_ENABLED = 'false'
$env:PROMETHEUS_COLLECTION_ENABLED = 'true'
$env:PROMETHEUS_URL = 'http://localhost:9090'
$env:TEMPO_URL = 'http://localhost:3200'
# Choose your own non-empty value before using live approval/control endpoints.
$env:RRPE_CONTROL_TOKEN = 'REPLACE_WITH_YOUR_OWN_CONTROL_TOKEN'
.\mvnw.cmd spring-boot:run
```

Use the actual reachable server addresses; `localhost` inside a container refers
to that container. No Kubernetes context or credentials are included. Prometheus
and Tempo addresses remain configurable. No live cluster changes are made by
copying or building this component.

In the dashboard, select **Live data**, open **Connections**, then click
**Check connections**. Reachability is only the first check: complete traces,
fresh scrapes, representative traffic and learned healthy baselines are also
required. Default service labels are `apigateway`, `order`, `inventory`, `payment`
and `notification`; change `rrpe.connections.*` configuration for another contract.
Scrape-health checks require the PodMonitor to attach the pod's `app` label to
targets (`podTargetLabels: [app]`), as in the source project's Component 2 monitor.

One enforceable deadline is calculated per HTTP method and normalized route.
Complete gateway durations are pooled across that route's observed service paths;
individual service/path percentiles are never added or averaged to obtain route p99.
Count tolerance is `max(1 request, 10% of the Prometheus completion count)`.
Known incomplete traces/searches, stale or conflicting budgets and unavailable
required evidence still block recommendations. The dashboard includes the captured
coverage counts and paths supporting a recommendation.

Deadline changes require human approval. Leave `COMPONENT3_POLICY_URL` and
`COMPONENT3_POLICY_TOKEN` unset until the team configures and verifies that delivery
contract. Collection and recommendation do not require Component 3.

## Validation and current limits

```powershell
# From intelligence-layer
.\mvnw.cmd test
```

The migrated suite has 79 backend tests. Tests validate calculations, coverage
tolerance, route aggregation, evidence, approval and mock isolation; passing
fixtures does not prove live cluster accuracy or sidecar enforcement.

Full service policies require original-request, retry-attempt, successful-retry
and rejection counters that the inspected Component 2 contract does not expose.
Missing data is not replaced with zero. The deadline-only health guard works
independently with existing request/performance metrics. State is in memory and
is lost on restart. Existing legacy cancellation files are preserved as part of
the migration; no new cancellation functionality is implemented.

See [ADAPTIVE_IMPLEMENTATION.md](ADAPTIVE_IMPLEMENTATION.md) for algorithm details,
API contracts and limitations. Historical machine/cluster observations in that
document describe the original development environment.

## Container and deployment reference

Build using this component folder as the Docker context:

```powershell
docker build -t intelligence-layer:local .
```

[`deploy/kubernetes.yaml`](deploy/kubernetes.yaml) preserves the source Component 4
deployment/service manifest as a reference. Before deploying, replace its image
with the image you built/published and configure the real Prometheus/Tempo URLs.
Live collection is disabled by default. It does not provision the external
microservices or observability stack; do not apply it until those settings are correct.
