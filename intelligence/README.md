# C4: Resilience Intelligence Layer

**Owner:** R.M.S.B. Rathnayake. **Status:** live analysis, approval workflow, delivery to C3, audit trail, **deadline recommendation v2** (impact preview and verification after rollout) and **rate and retry v2** (leaky bucket and retry budget, with optional automatic approval) are built (204 tests; e2e suites 70 and 95). Deadline v2: [`C4-DEADLINE-V2-IMPLEMENTATION.md`](C4-DEADLINE-V2-IMPLEMENTATION.md), [`DEADLINE_V2_MIGRATION.md`](DEADLINE_V2_MIGRATION.md). Rate and retry v2: [`C4-RATE-RETRY-V2-IMPLEMENTATION.md`](C4-RATE-RETRY-V2-IMPLEMENTATION.md), [`RATE_RETRY_V2_MIGRATION.md`](RATE_RETRY_V2_MIGRATION.md). Old logic vs v2 results: [`evaluation/`](evaluation/). The full plan and status are in [`docs/components/C4-Intelligence-Rathnayake.md`](../docs/components/C4-Intelligence-Rathnayake.md).

This Spring Boot service (port **8090**):
1. analyses sidecar metrics (Prometheus) and request traces (Tempo) every cycle;
2. classifies every service and request chain as Healthy, Stressed or Overloaded against a baseline learned from its own healthy traffic;
3. recommends rate limits, retry budgets and chain deadlines with evidence;
4. **delivers nothing until it is approved**: chain deadlines always by a human; rate and retry changes listed in `RRPE_ADAPTIVE_AUTOAPPROVEACTIONS` may be approved automatically by a safety guard (reviewer `c4-auto`), all others by a human.

Approved recommendations become new approved policy versions in C3 and are rolled out in two phases. Every step is recorded in an append-only audit trail.

In the Admin Panel, **Configuration → Recommendations** shows:
- the approval queue, with a review drawer and Approve / Reject;
- health;
- reviewed changes and their delivery status;
- the audit trail;
- simulation.

## How it works

| Part | Class | Notes |
|---|---|---|
| Collection | `AdaptiveCollector`, `PrometheusWindows`, `TempoWindows`, `TelemetryHttp` | One window per cycle, ending `evaluation-delay` in the past (Tempo ingestion lag). Uses the C2 metric contract `rpe_*` and the `rpe.*` span attributes (Appendix A3.3). Trace counts are cross-checked against the gateway's `rpe_chain_requests_total` (±10%). Hops without a sidecar are reported, not classified |
| Classification | `AdaptiveEngine`, `DeadlineHealth`, `RobustStatistics` | Median/MAD baselines. Deviation is measured in MADs, but never below a practical floor (5% of the median), so steady traffic does not turn noise into breaches. Breaches must persist; baselines freeze during overload; recovery is gradual |
| Deadline v2 (an overload trigger: quiet when healthy, firing under overload) | `TempoWindows.deadlineSamples`, `ServiceLoadClassifier`, `RequestLabeler`, `HealthyLatencyModel`, `DeadlinePolicy`, `DeadlineMath`, `LeverDiagnosis`, `DeadlineVerifier`, `DisjointWindowTracker` | Each request is labelled by **its own path** (H / STRESSED / O / UNKNOWN). Healthy time **T_h** = pooled, traffic-weighted p99 of healthy requests (expired requests are censored at +∞; IDENTIFIED or CENSORED). Target **D\* = ceil10(T_h × (1 + m))**, m = clamp(3σ̂/T_h, 0.10, 0.30), σ̂ = 1.4826 × MAD. Exceed rates use one-sided Wilson bounds; persistence counts **disjoint** windows only. Decision table: **A** raise ×1.5 (healthy over-signalled), **B** lower ×0.75 under overload when not firing, **C** keep while firing, **D** tighten ×0.80 when healthy, else hold (stress gate, wrong lever RATE/CAPACITY, cut requests, pending, cooldown…). Steps never cross D\*. Every proposal carries an impact preview; every applied change is verified (a failed one gets a human-approved revert). Old logic: branch `component-4-intelligence-on-main` |
| Rate and retry v2 | `AdaptiveEngine.bucket` / `retry`, `RateDiagnosis`, `RateState`, `AutoApprovalGuard`, `ServiceSimulation` | Separates waiting in our own queue from a slow app: queue wait **Wq = max queue depth / rate**, app time **Papp = p95 − Wq**, baseline **P0** (median Papp of clean windows). Diagnosis (2 cycles): **APP_OVERLOADED** (Papp > 1.5 × P0 or errors up) → cut `max(floor, 0.8 × min(rate, accepted))`, floor = 0.5 × good rate, a second cut only if the first helped (anti-ratchet undo); **RATE_TOO_LOW** (queue ≥ 80% or shedding, app fine) → raise toward demand × 1.25, at most +50%, below 0.9 × the rate that last slowed the app; **HEALTHY** → slowly toward max demand of 3 windows × 1.25 (lowering only by a reviewer); WATCH / UNKNOWN → hold. Demand includes retries. Retry: overloaded app → 0; shedding → hold; retries rarely succeed (≥ 30 attempts, Wilson upper bound < 0.20) → 0.02 then 0; STRESSED → hold; healthy → +0.02 toward 0.10 (advisory only without retry data). Optional automatic approval with a guard (step limits, floor/ceiling, persistence, cooldown, complete data, cross-lever lock: no automatic change while a chain through the service has an unsettled deadline, pause after 2 undos or 3 flips in 30 min), a check on 2 windows after APPLIED and an automatic undo (`c4-auto-revert`). Old logic: branch `c4/deadline-v2` |
| Workflow | `PolicyWorkflow` | Every recommendation is `PENDING_APPROVAL`, except a rate or retry change that the guard approves automatically (`APPROVED`, reviewer `c4-auto`; never a chain deadline or a simulation). A re-offered one keeps its id and decision and refreshes its evidence. A rejection holds for 30 minutes. An approved change not yet sent moves to a new policy version when its own value is unchanged there. Only `APPROVED`, non-simulated recommendations are delivered |
| Delivery | `Component3Bridge` | `POST /api/k8s/policies` (next version of the target's current policy, `source=c4-recommendation`, `recommendationId`, `approvedBy` = reviewer). Chain deadlines are written to `spec.routes[route].deadlineMs` on the gateway policy. Then a two-phase rollout; ACCEPTED becomes **APPLIED** when the rollout completes. At most one change per service policy per cycle, none while another is rolling out. C3's effective policies are synced back as the current reference |
| Audit | `AuditLog` | PostgreSQL `intel.audit` (append-only, migration `k8s/policy-store/migrations/003_intel_audit.sql`). Falls back to memory without `RPE_AUDIT_JDBC_URL` |
| Simulation | `MockScenarios`, `ChainSimulation`, `DeadlineExamples` | Synthetic telemetry (per-request for deadlines) in an isolated engine with a virtual clock. Never queued or delivered |

## API (`/api/v2/intelligence`)

| Method / path | Purpose |
|---|---|
| `GET /` | Collection status, assessments, recommendations; `deadlineEvidence` (per chain: case, hold reason, T_h status, D\*, margin, p_H / p_O with Wilson bounds, impact preview, lever, state, verification); `rateEvidence` (per service: diagnosis, reason, queue %, queue depth, queue wait, app time, P0, demand, floor, ceiling, rule, automatic-approval pause and check) |
| `POST /changes/{id}/review` | `{ reviewer, approve, comment }`; live recommendations need `X-RPE-Control-Token` |
| `GET /audit?limit=` | Audit trail |
| `GET /connections` | Prometheus / Tempo reachability and freshness |
| `POST /baselines/reset` | Re-learn baselines after an intentional change (token); also resets the deadline v2 healthy time and chain state (→ LEARNING) |
| `POST /demo/{scenario}` | Simulation: `healthy`, `stressed`, `overloaded`, `retry-stressed`, `recovery`, `deadline-increase` (Case A), `deadline-recovery` (Case D), `multiple-chains` (A, B, D); guide examples with a simulated reviewer: `deadline-too-long-under-overload`, `deadline-too-short-day-one`, `services-slowing`, `queue-rate-not-deadline`, `mixed-paths`, `rejecting-middleware-504`; rate and retry v2 examples with a simulated reviewer: `rate-too-low`, `rate-too-low-day-one`, `rate-real-overload`, `rate-recovery`, `rate-short-burst`, `rate-bursts`, `retry-storm-rate` (the hub's scenario list does not include the guide examples yet) |
| `GET /queries?service=` | The PromQL used for a service, plus the deadline v2 and rate v2 load queries (`load*`, including `loadQueueDepth`) |

The Admin Panel reaches these only through `rpe-hub`, which adds the token and always sets the reviewer to the signed-in user.

## Configuration (environment)

| Variable | Kubernetes value | Meaning |
|---|---|---|
| `PROMETHEUS_COLLECTION_ENABLED` | `true` | Live collection |
| `PROMETHEUS_URL`, `TEMPO_URL` | `http://rpe-prometheus.rpe-system:9090`, `http://rpe-tempo.rpe-system:3200` | Telemetry |
| `RRPE_CONNECTIONS_NAMESPACE`, `RRPE_CONNECTIONS_SERVICES`, `RRPE_CONNECTIONS_GATEWAYSERVICE` | `rpe-demo`, the 7 RPE services, `gateway` | What to analyse |
| `RRPE_ADAPTIVE_WINDOW`, `RRPE_ADAPTIVE_CYCLE`, `RRPE_ADAPTIVE_EVALUATION_DELAY`, `RRPE_ADAPTIVE_BASELINESAMPLES` | `1m`, `15s`, `20s`, `5` | Demo-cluster tuning (defaults: 5m, 1m, 0s, 30) |
| `COMPONENT3_POLICY_URL` | `http://rpe-policy-api.rpe-system` | Delivery to C3 (empty = no delivery) |
| `RRPE_CONTROL_TOKEN` | secret `rpe-control-token` | Required for live reviews and baseline resets |
| `RPE_AUDIT_JDBC_URL`, `RPE_AUDIT_USER`, `RPE_AUDIT_PASSWORD` | the `rpe-postgres` database | Audit persistence |
| `RRPE_MOCK_ENABLED` | `true` | Allow simulations |
| `RRPE_ADAPTIVE_DEADLINE_*` (`MINLEARNINGWINDOWS`, `MINLEARNINGREQUESTS`, `PERSISTENCEWINDOWS`, `MAXWIDEN`, `MAXPROPOSALSPERHOUR`, `DRIFTWINDOWS`, `COVERAGEMINSAMPLES`, …) | `3`, `150`, `2`, `5m`, `6`, `2`, `10` | Deadline v2 (defaults: 10, 1000, 3, 30m, 4, 3, 20; also `ETARGET` 0.01, `EFIRE` 0.05, `STEPUP` 1.5, `STEPDOWNOVERLOAD` 0.75, `STEPDOWNHEALTHY` 0.80, margins 0.10–0.30). Reused: `RRPE_ADAPTIVE_BASELINESAMPLES` (learning windows kept), `..._MINIMUMCHAINREQUESTS` (min requests per window), min/max deadline, cooldown |
| `RRPE_ADAPTIVE_AUTOAPPROVEACTIONS` | `BUCKET_RAISE,BUCKET_CUT,RETRY_DECREASE` | Rate and retry changes approved automatically by the guard (default empty: every recommendation waits for a reviewer). Also `RETRY_INCREASE`; `CHAIN_DEADLINE` is refused |
| `RRPE_ADAPTIVE_RATE_*` (`MAXABSOLUTE`, `BROKEATMEMORY`, `STABILISATION`, …), `RRPE_ADAPTIVE_BASELINE_ALLOWSHEDWINDOWS`, `RRPE_ADAPTIVE_AUTO_PAUSE` | `500`, `10m`, `1m`, `true`, `10m` | Rate v2 (defaults: 1000, 30m, 5m, false, 60m; also `HEADROOM` 0.25, `QUEUEHIGHPERCENT` 80, `QUEUELOWPERCENT` 50, `APPTOLERANCE` 1.5, `CUTFACTOR` 0.8, `MINABSOLUTE` 5, `FLOORFRACTION` 0.5, `MAXRAISE` 0.5). Retry: `RRPE_ADAPTIVE_RETRY_*` (`BASERATIO` 0.10, `MINSUCCESS` 0.20, `MINATTEMPTS` 30, `STEP` 0.02). Automatic approval: `RRPE_ADAPTIVE_AUTO_*` (`VERIFYWINDOWS` 2, `MAXREVERTS` 2, `MAXFLIPS` 3, `PERIOD` 30m) |
| `RRPE_ADAPTIVE_LOAD_*`, `RRPE_ADAPTIVE_LEVER_*` | defaults | Queue pressure in percent: STRESSED ≥ 50, OVERLOADED ≥ 80; CPU 0.70 / 0.85, throttling 0.25, memory 0.90 (not scraped today: unavailable, never blocking); lever queue share 0.5, low CPU 0.70 |

The full manifest is [`deploy/k8s/platform/intelligence.yaml`](../deploy/k8s/platform/intelligence.yaml). On the demo cluster a freshly started instance needs about 5–7 minutes to learn its baselines.

## Build, test, run

```bash
./mvnw -q test                                         # 204 tests (needs JDK 17+)
make images IMAGES=intelligence && make restart        # from the repository root
RRPE_MOCK_ENABLED=true ./mvnw spring-boot:run          # local, simulations only
curl -s -X POST localhost:8090/api/v2/intelligence/demo/deadline-increase | jq '.changes'
```

## Not built yet

- Remove the legacy v1 engine (`analysis/`, `/api/v1`).
- Joint recommendations under feasibility constraints.
- Retry increases are advisory only (an evidence line, no proposal) until C2 counts retries (`rpe_retries_total`, planned for PP2); then add `RETRY_INCREASE` to the automatic list if wanted.
- Optional sidecar observability for v2 (guide Phase 6, separate PR, C2 owner approval): `rpe.cancel.state`, queue wait.
- Comparison against simpler and ML detectors.

## Historical files

[`ADAPTIVE_IMPLEMENTATION.md`](ADAPTIVE_IMPLEMENTATION.md) and [`deploy/kubernetes.yaml`](deploy/kubernetes.yaml) come from the original `RPE_POC1` prototype (branch `component-4-integrated-v2`, commit `775fd0a`). They are kept as algorithm background only; their run instructions, dashboard and metric names no longer apply.
