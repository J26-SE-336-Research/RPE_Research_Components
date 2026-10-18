# C4: Resilience Intelligence Layer

**Owner:** R.M.S.B. Rathnayake. **Status:** live analysis, human-approval workflow, delivery to C3, audit trail and **deadline recommendation v2** (impact preview and verification after rollout) are built (163 tests; e2e suites 70 and 95). v2 design: [`C4-DEADLINE-V2-IMPLEMENTATION.md`](C4-DEADLINE-V2-IMPLEMENTATION.md); test migration and deviations: [`DEADLINE_V2_MIGRATION.md`](DEADLINE_V2_MIGRATION.md); old logic vs v2 results: [`evaluation/`](evaluation/). The full plan and status are in [`docs/components/C4-Intelligence-Rathnayake.md`](../docs/components/C4-Intelligence-Rathnayake.md).

This Spring Boot service (port **8090**):
1. analyses sidecar metrics (Prometheus) and request traces (Tempo) every cycle;
2. classifies every service and request chain as Healthy, Stressed or Overloaded against a baseline learned from its own healthy traffic;
3. recommends rate limits, retry budgets and chain deadlines with evidence;
4. **delivers nothing until a human approves.**

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
| Workflow | `PolicyWorkflow` | Every recommendation is `PENDING_APPROVAL`. A re-offered one keeps its id and decision and refreshes its evidence. A rejection holds for 30 minutes. Only `APPROVED`, non-simulated recommendations are delivered |
| Delivery | `Component3Bridge` | `POST /api/k8s/policies` (next version of the target's current policy, `source=c4-recommendation`, `recommendationId`, `approvedBy` = reviewer). Chain deadlines are written to `spec.routes[route].deadlineMs` on the gateway policy. Then a two-phase rollout; ACCEPTED becomes **APPLIED** when the rollout completes. C3's effective policies are synced back as the current reference |
| Audit | `AuditLog` | PostgreSQL `intel.audit` (append-only, migration `k8s/policy-store/migrations/003_intel_audit.sql`). Falls back to memory without `RPE_AUDIT_JDBC_URL` |
| Simulation | `MockScenarios`, `ChainSimulation`, `DeadlineExamples` | Synthetic telemetry (per-request for deadlines) in an isolated engine with a virtual clock. Never queued or delivered |

## API (`/api/v2/intelligence`)

| Method / path | Purpose |
|---|---|
| `GET /` | Collection status, assessments, recommendations; `deadlineEvidence` (per chain: case, hold reason, T_h status, D\*, margin, p_H / p_O with Wilson bounds, impact preview, lever, state, verification) |
| `POST /changes/{id}/review` | `{ reviewer, approve, comment }`; live recommendations need `X-RPE-Control-Token` |
| `GET /audit?limit=` | Audit trail |
| `GET /connections` | Prometheus / Tempo reachability and freshness |
| `POST /baselines/reset` | Re-learn baselines after an intentional change (token); also resets the deadline v2 healthy time and chain state (→ LEARNING) |
| `POST /demo/{scenario}` | Simulation: `healthy`, `stressed`, `overloaded`, `retry-stressed`, `recovery`, `deadline-increase` (Case A), `deadline-recovery` (Case D), `multiple-chains` (A, B, D); guide examples with a simulated reviewer: `deadline-too-long-under-overload`, `deadline-too-short-day-one`, `services-slowing`, `queue-rate-not-deadline`, `mixed-paths`, `rejecting-middleware-504` (the hub's scenario list does not include these yet) |
| `GET /queries?service=` | The PromQL used for a service, plus the deadline v2 load queries (`load*`) |

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
| `RRPE_ADAPTIVE_LOAD_*`, `RRPE_ADAPTIVE_LEVER_*` | defaults | Queue pressure in percent: STRESSED ≥ 50, OVERLOADED ≥ 80; CPU 0.70 / 0.85, throttling 0.25, memory 0.90 (not scraped today: unavailable, never blocking); lever queue share 0.5, low CPU 0.70 |

The full manifest is [`deploy/k8s/platform/intelligence.yaml`](../deploy/k8s/platform/intelligence.yaml). On the demo cluster a freshly started instance needs about 5–7 minutes to learn its baselines.

## Build, test, run

```bash
./mvnw -q test                                         # 163 tests (needs JDK 17+)
make images IMAGES=intelligence && make restart        # from the repository root
RRPE_MOCK_ENABLED=true ./mvnw spring-boot:run          # local, simulations only
curl -s -X POST localhost:8090/api/v2/intelligence/demo/deadline-increase | jq '.changes'
```

## Not built yet

- Remove the legacy v1 engine (`analysis/`, `/api/v1`).
- Joint recommendations under feasibility constraints.
- Impact preview and verification exist for chain deadlines (v2); not yet for rate and retry budget.
- Optional sidecar observability for v2 (guide Phase 6, separate PR, C2 owner approval): `rpe.cancel.state`, queue wait.
- Comparison against simpler and ML detectors.

## Historical files

[`ADAPTIVE_IMPLEMENTATION.md`](ADAPTIVE_IMPLEMENTATION.md) and [`deploy/kubernetes.yaml`](deploy/kubernetes.yaml) come from the original `RPE_POC1` prototype (branch `component-4-integrated-v2`, commit `775fd0a`). They are kept as algorithm background only; their run instructions, dashboard and metric names no longer apply.
