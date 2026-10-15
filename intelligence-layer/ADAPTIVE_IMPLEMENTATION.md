# Corrected adaptive intelligence — deadline validation first

This implementation follows `Resilience_Intelligence_Adaptive_Recommendations_Corrected (1) (1).pdf`. The canonical implementation is under `org.rrpe.intelligence.adaptive`, served by `/api/v2/intelligence` and `/adaptive.html`. The existing `/api/v1` snapshot endpoints and original dashboard remain legacy demonstrations with their old calculation rules; they are not connected to the new live delivery workflow. Do not use the legacy fixed-margin endpoint to validate the corrected deadline algorithm.

Cancellation collection and cancellation analysis have not been changed.

## Run the deadline examples without installing Java or running Prometheus

From the repository root, using the portable Java already present on this machine:

```powershell
$env:JAVA_HOME = 'C:\Users\User\Tools\Java\jdk-17.0.20.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:RRPE_MOCK_ENABLED = 'true'
cd intelligence-layer
.\mvnw.cmd spring-boot:run
```

Open http://localhost:8090/adaptive.html. Select **Deadline increase: 1440 ms** or **Deadline restoration: 1296 ms**, then run the scenario. These simulations use an isolated engine and virtual clock to learn a full baseline and run distinct cycles immediately. They do not fabricate live observations, modify live baselines, enqueue live policies or contact Component 3.

The same examples are available from PowerShell:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8090/api/v2/intelligence/demo/deadline-increase `
    -ContentType 'application/json' -Body '{}'
Invoke-RestMethod -Method Post -Uri http://localhost:8090/api/v2/intelligence/demo/deadline-recovery `
    -ContentType 'application/json' -Body '{}'
```

## Deadline calculation and gates

| Condition | Calculation |
|---|---|
| Recent p99 change ≤10% | Recent p99 ×1.10 |
| Change >10% and ≤25% | Recent p99 ×1.20 |
| Change >25% | Recent p99 ×1.30 |
| Healthy restoration | max(baseline p99 ×1.10, current deadline ×0.90) |

Change is `(recent p99 − baseline p99) / baseline p99`. Millisecond candidates round upward so they do not undercut the chosen safety margin. With baseline 1,000 ms and recent p99 1,200 ms, the margin is 20% and the candidate is 1,440 ms. With a 1,440 ms approved deadline, restoration proposes 1,296 ms, then 1,167 ms after approval/enforcement and a new Healthy cooldown, then stops at 1,100 ms.

The engine requires a positive complete five-minute window, at least 100 completed gateway request chains, fresh telemetry, an ordered service path, a deadline-specific overload assessment and a measured current route deadline. Live collection automatically imports the consistent, fresh `sidecar_gateway_deadline_budget_milliseconds` gauge into a configuration reference; it does not require a manually entered deadline or Component 3 API. It learns a chain baseline from 30 stable, Healthy, failure-free windows at one-minute cadence after the deadline-only service guards have learned their performance references. This conservative bootstrap can require about an hour of observation, followed by the separate recent window. Baseline p99 is the median of those window p99s, not a sum of service percentiles or a pooled historical p99. It waits for a recent window separate from the initial baseline.

Deadline failures are normalized by the request count. Abnormal failures must persist for two distinct analysis cycles; repeated submissions cannot manufacture persistence and collection gaps reset it. Overload blocks an increase. Candidates must remain within configured minimum/maximum deadlines and the maximum relative change. Restoration needs three consecutive Healthy windows with normal failure rates and a cooldown after the last confirmed applied change. Healthy baselines update one sample per healthy cycle after the recovery guard; degraded samples never update them.

Every increase and restoration creates `PENDING_APPROVAL`. Authorised users approve or reject the exact generated value; they cannot edit it during approval. Approval preserves the original expiry. Stale evidence, expired policies and newer policy versions invalidate pending updates. Component 3 acceptance (`SENT`) is distinct from enforcement confirmation (`APPLIED`). Merely approving a deadline never changes the recorded current deadline.

## Live sources

```powershell
$env:PROMETHEUS_URL = 'http://YOUR_PROMETHEUS_SERVER:9090'
$env:TEMPO_URL = 'http://YOUR_TEMPO_SERVER:3200'
$env:PROMETHEUS_COLLECTION_ENABLED = 'true'
# Choose your own control token; it authorises live policy references, evidence ingress and deadline review.
$env:RRPE_CONTROL_TOKEN = 'YOUR_LOCAL_CONTROL_TOKEN'
.\mvnw.cmd spring-boot:run
```

Use actual accessible addresses. The branch's Kubernetes labels are `apigateway`, `order`, `inventory`, `payment`, and `notification`; sidecars obtain `SERVICE_NAME` from those labels. Collector defaults match them. The updated PodMonitor adds the pod `app` label to scrape targets for scrape-health checks. Apply it to your existing monitoring stack when ready. It does not install Prometheus.

Prometheus queries use `rate`/`increase` before replica aggregation and the current branch's `sidecar_service_request_duration_seconds_bucket` for service p95, converted to milliseconds. The collector checks successful scrapes, sample coverage and freshness. Available measurements are displayed even when analysis cannot proceed. Unavailable values remain missing, never measured zero.

Tempo retrieves unpruned v2 trace responses, rejects partial traces, and validates gateway and descendant span timing/parents and correlated downstream server spans. Complete traces finishing in the five-minute window are grouped by HTTP method and normalized gateway route, matching the Prometheus completion counters and configured-deadline gauge. Live policy targets are `METHOD /route`, independent of the service path. A route's services are the union of its observed paths for the deadline-health guard; any unknown service health blocks analysis and any overloaded participant blocks deadline increases.

Route p99 is the nearest-rank percentile of all complete gateway-root durations pooled across that route's paths. Individual path p99s are diagnostic evidence: they are neither summed nor averaged to calculate the route percentile. Each path's ordered service visits, trace count and diagnostic p99 remain visible in the collection response. The ordered visits are not a dependency graph. Route counts and deadline failures are used once, and only one route deadline is proposed; path-specific enforceable policies require a separate stable chain identity contract.

Trace-count coverage accepts `abs(complete traces - Prometheus completed count) <= max(1, 0.10 * Prometheus completed count)`. This accommodates `increase(...)` extrapolation and small window-boundary differences. Examples: 10 traces vs 9.8 estimated requests pass; 100 vs 102 pass; 80 vs 100 fail. The tolerance is a conservative engineering allowance, not proof that a tail estimate is unbiased: even a small missing set can include slow requests. Known invalid/partial traces, an incomplete search, reaching the search limit, missing/inconsistent failure counts, and stale or mismatched budgets still block the window regardless of the count tolerance. More traffic and representative complete traces remain necessary for a reliable p99.

Proposals snapshot complete-trace count, Prometheus count, allowed count difference, coverage result, path count and supporting path descriptions alongside p99, failure rate and persistence. The dashboard distinguishes the paths in the latest window from those captured for a recommendation. Coverage acceptance alone does not trigger a recommendation: healthy baseline learning, minimum traffic, persistent failures/recovery, overload checks, configured limits and human approval still apply. No Component 2 metric or sidecar behaviour is changed.

This branch still lacks original-request, retry-attempt, successful-retry and rejection counters. Proposed names are `sidecar_original_requests_total`, `sidecar_retry_attempts_total`, `sidecar_successful_retries_total`, and `sidecar_rejected_requests_total`, labelled by `service`. Names are configurable through `rrpe.connections.metric-names.*`. Current route failure counters include 429 and 5xx; successful throughput includes 2xx–4xx except 429. Component 2 must confirm those semantics consistently with original-request accounting before full service classification and retry policy changes. Missing counters no longer block deadlines: a separate `DeadlineHealth` guard uses only observed inbound attempts, p95, proxy failures, throughput, in-flight work and scrape quality. It measures attempt-level proxy error rate explicitly; it does not claim to implement full original-request/retry classification. Baselines are frozen during degraded windows and missing required guard measurements still block deadline changes.

The current deadline gauge has method/route labels without a service label. The collector compares min/max values across gateway replicas and checks scrape age, so a rolling deployment with conflicting budgets cannot produce an approval candidate. It also requires each trace's `rrpe.deadline.budget_ms` to match the observed gauge. The gauge describes startup configuration, not an acknowledgement of a newly applied runtime policy. It never overwrites a conflicting Component 3-confirmed applied value. Missing explicit-route gauges remain unavailable; no default deadline is invented.

Use **Check Prometheus and Tempo** on `/adaptive.html`, or `GET /api/v2/intelligence/connections`, to distinguish unreachable/query-failed servers, servers without fresh gateway data, and reachable servers with recent evidence. Live chain cards show the automatically measured current deadline, Tempo p99, counts and coverage problems separately from full service analysis. Prometheus `LIVE_DATA` requires a fresh deadline gauge and recent gateway traffic; Tempo `LIVE_DATA` means recent gateway traces exist, with completeness verified during collection.

The latest upstream branch checked on 2026-10-13 was `3430eef`. It provides the cluster-internal addresses `http://prometheus:9090` and `http://tempo:3200`, but no externally accessible server address or Kubernetes credentials. Those hostnames did not resolve from this machine, and localhost ports 9090/3200 were not reachable during the check. Code/configuration in a Git branch does not start the cluster. To verify real data, run Component 4 inside that cluster or provide reachable endpoints/port forwards from an authorised cluster connection. Component 3 remains unnecessary until an approved recommendation needs distribution.

## Current policy and complete-window interfaces

- `GET /api/v2/intelligence`: collection diagnostics, health/baselines and decision history.
- `GET /api/v2/intelligence/queries?service=order`: exact PromQL.
- `PUT /api/v2/intelligence/policies?source=TEMPO`: current chain policy supplied by Component 3.
- `POST /api/v2/intelligence/chains`: complete chain window from a trusted trace adapter.
- `POST /api/v2/intelligence/services`: complete service window from a trusted metrics adapter.
- `POST /api/v2/intelligence/changes/{id}/review`: `{ "reviewer": "name", "approve": true, "comment": "reason" }`.
- `GET /api/v2/intelligence/outbox`: eligible live updates; unapproved deadlines and mocks are excluded.

Live mutations require `X-RRPE-Control-Token` matching `RRPE_CONTROL_TOKEN`. MOCK input is disabled unless explicitly enabled and lives in a separate namespace. A current policy requires `target`, `version`, `validUntil`, and `deadlineMs` for a chain. The target must exactly match the collector's chain identity, such as `POST /api/orders`. Service policies use `bucketRate`, `healthyBucketTarget`, and `retryRatio` instead.

Chain windows contain `chain`, ordered `services`, `start`, `end`, `source` (`TEMPO` or `MOCK`), `complete`, `requests`, `deadlineFailures`, `p99Ms`, `health`, and example `traceIds`. Setting `complete` is a contract assertion by the authorised telemetry producer; source labels alone do not prove telemetry completeness.

## Service-control algorithms already scaffolded for the next validation step

The same engine implements MAD health classification; throughput-based Leaky Bucket rates (0.90 during stress, 0.75 during overload); retry effectiveness (`successful retries / retry attempts`) with maximum ratio 0.10 and health factors 1, 0.5, 0; and automatic recovery after three Healthy windows. Rate recovery adds 5% of baseline throughput. Retry recovery adds at most one percentage point toward the newly measured Healthy target. Nonzero retry proposals require safe/retryable operation and sufficient remaining-deadline evidence. Overload can safely disable extra retries without inventing effectiveness. Zero retry attempts leave effectiveness unknown rather than dividing by zero.

Threshold multipliers and variation floors are configurable. A variation floor is used only when MAD is zero, as required by the corrected document. Count metrics are normalized by original requests, throughput thresholds point downward, and persistent breaches combine multiple signals. Critical overload may classify immediately, but protective policy changes still pass persistence checks. Cooldowns are measured from confirmed applied changes. Leaky Bucket and retry decisions are automatic; only deadlines have approval controls.

## Component 3 — leave unconfigured until the deadline review is complete

`COMPONENT3_POLICY_URL` is a full POST endpoint for a documented adapter contract. It is empty by default: nothing is sent externally. Later, Component 3 can implement this contract or the adapter can be adjusted to its actual API. `COMPONENT3_POLICY_TOKEN` optionally supplies a bearer token. Requests contain the exact `PolicyChange` and an `Idempotency-Key` equal to its proposal UUID.

Expected responses are `{ "status": "ACCEPTED" }` or `{ "status": "APPLIED", "policyVersion": "new-version", "appliedValue": 1440 }`. An APPLIED response must match the exact proposed value and a new policy version. Asynchronous enforcement uses `POST /api/v2/intelligence/changes/{id}/acknowledge` with the same response and the control token. HTTP success alone never counts as application. Failures are recorded and retried with the same idempotency key, at least 30 seconds apart and at most five attempts before expiry. Versioned acknowledgements supersede older pending proposals based on the prior version; new analysis recalculates other controls from the current reference.

All state is in memory and lost on restart. Authentication, delivery verification and storage are PoC contracts, not a claim that Component 3 or the sidecars already implement them. Live collection and end-to-end enforcement still require the running team infrastructure. No cancellation implementation was added.

Sources: [Prometheus query functions](https://prometheus.io/docs/prometheus/latest/querying/functions/), [Tempo query API](https://grafana.com/docs/tempo/latest/api_docs/), [Tempo response definitions](https://github.com/grafana/tempo/blob/main/pkg/tempopb/tempo.proto).
