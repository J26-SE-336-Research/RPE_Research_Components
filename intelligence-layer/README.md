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

Deadline changes require human approval. Leave Component 3 delivery URLs unset
until the team configures and verifies that receiver. Collection and recommendation
do not require Component 3.

## Automatically POST updates to Component 3

Component 3 hosts the receiving endpoints; Component 4 sends `PolicyChange` JSON
to them. Configure the full URLs when its receiver is running, for example:

```powershell
$env:COMPONENT3_DEADLINE_URL = 'http://COMPONENT3_HOST:PORT/api/v1/policies/deadlines'
$env:COMPONENT3_LEAKY_BUCKET_URL = 'http://COMPONENT3_HOST:PORT/api/v1/policies/leaky-bucket'
$env:COMPONENT3_RETRY_BUDGET_URL = 'http://COMPONENT3_HOST:PORT/api/v1/policies/retry-budget'
```

Replace host/port with reachable values. All three defaults are empty, so no
delivery occurs without configuration. A missing type-specific URL falls back
to the existing `COMPONENT3_POLICY_URL`, if configured. Otherwise that type stays
pending without an HTTP call or a failed-attempt increment. Type-specific URLs
take precedence over the common URL. The delivery poll defaults to 15 seconds.

Requests include `Content-Type: application/json`, `Accept: application/json` and
`Idempotency-Key: <proposal.id>`. With `COMPONENT3_POLICY_TOKEN` unset or empty,
no Authorization header is sent. If configured, it sends `Authorization: Bearer
<token>`. The callback to Component 4 independently requires its own
`X-RRPE-Control-Token`; that is not the optional receiver bearer token.

The same message envelope is sent to all three URLs. Illustrative deadline JSON
(timestamps and IDs must be generated afresh for real delivery):

```json
{
  "proposal": {
    "id": "591fce21-d942-41b5-a24d-821755cc3c62",
    "target": "POST /api/orders",
    "action": "CHAIN_DEADLINE",
    "source": "TEMPO",
    "baseVersion": "v1",
    "currentValue": 1000,
    "proposedValue": 1440,
    "unit": "milliseconds",
    "generatedAt": "2026-10-15T10:00:00Z",
    "expiresAt": "2026-10-15T10:05:00Z",
    "measurements": {"baselineP99Ms": 1000, "recentP99Ms": 1200, "failureRate": 0.02},
    "evidence": ["Persistent deadline failures supported this change"]
  },
  "decision": "APPROVED",
  "reviewer": "reviewer-name",
  "decidedAt": "2026-10-15T10:01:00Z",
  "delivery": "READY",
  "detail": "Awaiting delivery and enforcement acknowledgement",
  "attempts": 0,
  "lastAttempt": null,
  "appliedVersion": null
}
```

| Type | `proposal.action` | Target | Unit | Decision |
|---|---|---|---|---|
| Deadline | `CHAIN_DEADLINE` | Method + normalized route | `milliseconds` | `APPROVED` |
| Leaky Bucket | `LEAKY_BUCKET` | Service name | `requests/second` | `AUTOMATIC` |
| Retry budget | `RETRY_BUDGET` | Service name | `extra attempts/original request` | `AUTOMATIC` |

For automatic decisions the reviewer is `adaptive-controller`. Measurements and
evidence depend on the action. Leaky Bucket changes the rate, not bucket capacity.
A retry value of `0.02` allows two extra attempts per 100 original requests in
the budget window, not two attempts per request.

The receiver must deduplicate by proposal ID, check expiry, validate target/action,
and compare the current value and base version with its applied reference before
applying an update. HTTP failures or invalid acknowledgements are retried with
the same proposal ID, at least 30 seconds apart, at most five attempts and only
while the proposal and evidence remain current. GET reads and POST retries do
not guarantee exactly-once application; the receiver must provide idempotency.

Return a 2xx JSON response on acceptance (202 is recommended):

```json
{"status": "ACCEPTED"}
```

Component 4 then records `SENT`, not `APPLIED`. Once sidecars confirm application,
Component 3 calls the existing callback with the proposal ID:

```http
POST /api/v2/intelligence/changes/{proposalId}/acknowledge
Content-Type: application/json
X-RRPE-Control-Token: <Component4-control-token>
```

```json
{"status": "APPLIED", "policyVersion": "v2", "appliedValue": 1440}
```

Alternatively return the `APPLIED` JSON directly to the original POST, only if
application is already confirmed. Component 3 supplies a new policy version;
Component 4 requires a non-empty version different from `baseVersion` and the
exact proposed value before updating its reference. The next recommendation
uses the confirmed version. Initial observed deadline references may be
`prometheus-config:<milliseconds>` rather than `v1`. API `/v1/` paths are unrelated
to policy version strings. A stale base version should receive a non-2xx response
such as 409 rather than silently overwriting a newer policy.

Message metadata is already generated by Component 4: proposal UUID, target,
current/proposed values, unit, base version, timestamps and evidence. Service
policies also need a valid current policy reference; the trusted
`PUT /api/v2/intelligence/policies?source=PROMETHEUS` interface can register that
reference with the control token. Live deadlines import the current budget from
Prometheus automatically. These interfaces do not supply missing telemetry.
Original-request, retry-attempt, successful-retry and rejection counters are
still absent from the inspected Component 2 contract. Automatic live retry
changes also need safe/retryable-operation and remaining-deadline evidence; the
current Prometheus adapter does not infer those fields. No absent measurements
are replaced with zero. Synthetic messages are never sent to Component 3.

Tests use a local HTTP receiver to verify POST routing, request JSON, optional
authentication, approval gates, idempotency-key reuse, missing destinations,
acceptance/application separation and version validation. This does not verify
the actual Component 3 service or sidecar enforcement.

## Read policy updates from outside Component 4

Component 3 or another consumer can fetch eligible updates separately:

| Policy | Read endpoint |
|---|---|
| Deadline (after approval) | `GET /api/v2/intelligence/outbox/deadlines` |
| Leaky Bucket | `GET /api/v2/intelligence/outbox/leaky-bucket` |
| Retry budget | `GET /api/v2/intelligence/outbox/retry-budget` |
| All eligible types | `GET /api/v2/intelligence/outbox` |

Use the reachable base URL `http://<COMPONENT4_HOST>:8090`. Responses are JSON
arrays of `PolicyChange` records with the proposal, current/proposed values,
target, unit, version, expiry, decision and evidence. An empty array means no
eligible updates of that type exist. These endpoints preserve the shared outbox
checks: no synthetic, unapproved-deadline, expired or already-sent updates.
Reads do not consume updates; consumers must deduplicate using `proposal.id`.

Application is confirmed using the existing authenticated
`POST /api/v2/intelligence/changes/{proposalId}/acknowledge` endpoint. A response
of `ACCEPTED` is distinct from `APPLIED`; only confirmed application updates the
current policy reference. The separate GET endpoints do not send HTTP POSTs to
Component 3. Agree on polling or the existing push adapter to avoid duplicate
processing; fetching data alone does not apply it to sidecars.

## Validation and current limits

```powershell
# From intelligence-layer
.\mvnw.cmd test
```

Backend tests validate calculations, coverage
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
