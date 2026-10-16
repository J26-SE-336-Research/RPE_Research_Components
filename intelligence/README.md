# C4: Resilience Intelligence Layer

**Owner:** R.M.S.B. Rathnayake. **Status:** live analysis, human-approval workflow, delivery to C3 and audit trail are built (87 tests; e2e suites 70 and 95). The full plan and status are in [`docs/components/C4-Intelligence-Rathnayake.md`](../docs/components/C4-Intelligence-Rathnayake.md).

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
| Deadline maths | `DeadlineMath` | Increase = p99 × (1 + margin by latency change). **Censoring-aware:** while requests fail on the deadline, observed p99 is cut at the deadline, so the uncensored baseline p99 × 1.10 is a lower bound. **Bounded step:** at most ±50% per change. Restoration = max(baseline p99 × 1.10, current × 0.90) |
| Workflow | `PolicyWorkflow` | Every recommendation is `PENDING_APPROVAL`. A re-offered one keeps its id and decision and refreshes its evidence. A rejection holds for 30 minutes. Only `APPROVED`, non-simulated recommendations are delivered |
| Delivery | `Component3Bridge` | `POST /api/k8s/policies` (next version of the target's current policy, `source=c4-recommendation`, `recommendationId`, `approvedBy` = reviewer). Chain deadlines are written to `spec.routes[route].deadlineMs` on the gateway policy. Then a two-phase rollout; ACCEPTED becomes **APPLIED** when the rollout completes. C3's effective policies are synced back as the current reference |
| Audit | `AuditLog` | PostgreSQL `intel.audit` (append-only, migration `k8s/policy-store/migrations/003_intel_audit.sql`). Falls back to memory without `RPE_AUDIT_JDBC_URL` |
| Simulation | `MockScenarios` | Synthetic telemetry in an isolated engine with a virtual clock. Never queued or delivered |

## API (`/api/v2/intelligence`)

| Method / path | Purpose |
|---|---|
| `GET /` | Collection status, assessments, recommendations |
| `POST /changes/{id}/review` | `{ reviewer, approve, comment }`; live recommendations need `X-RPE-Control-Token` |
| `GET /audit?limit=` | Audit trail |
| `GET /connections` | Prometheus / Tempo reachability and freshness |
| `POST /baselines/reset` | Re-learn baselines after an intentional change (token) |
| `POST /demo/{scenario}` | Simulation: `healthy`, `stressed`, `overloaded`, `retry-stressed`, `recovery`, `deadline-increase`, `deadline-recovery`, `multiple-chains` |
| `GET /queries?service=` | The PromQL used for a service |

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

The full manifest is [`deploy/k8s/platform/intelligence.yaml`](../deploy/k8s/platform/intelligence.yaml). On the demo cluster a freshly started instance needs about 5–7 minutes to learn its baselines.

## Build, test, run

```bash
./mvnw -q test                                         # 87 tests
make images IMAGES=intelligence && make restart        # from the repository root
RRPE_MOCK_ENABLED=true ./mvnw spring-boot:run          # local, simulations only
curl -s -X POST localhost:8090/api/v2/intelligence/demo/deadline-increase | jq '.changes'
```

## Not built yet

- Remove the legacy v1 engine (`analysis/`, `/api/v1`).
- Joint recommendations under feasibility constraints.
- Impact preview before approval and outcome verification after rollout.
- Comparison against simpler and ML detectors.

## Historical files

[`ADAPTIVE_IMPLEMENTATION.md`](ADAPTIVE_IMPLEMENTATION.md) and [`deploy/kubernetes.yaml`](deploy/kubernetes.yaml) come from the original `RPE_POC1` prototype (branch `component-4-integrated-v2`, commit `775fd0a`). They are kept as algorithm background only; their run instructions, dashboard and metric names no longer apply.
