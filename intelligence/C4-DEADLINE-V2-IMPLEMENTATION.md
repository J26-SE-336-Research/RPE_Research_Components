# C4 Deadline Recommendation v2: Implementation Guide for Claude Code

**Component:** C4 Resilience Intelligence Layer (`intelligence/`), owner R.M.S.B. Rathnayake
**Project:** RPE (J26-SE-336)
**Purpose:** replace the chain-deadline recommendation logic in C4 with the corrected v2 design, without breaking C1/C2/C3, the Admin Panel, e2e tests or any existing contract.

> **Claude Code: read this whole file before doing anything.** Then follow section 3 (workflow) phase by phase. Stop at the end of every phase, report, and wait for the user.

---

## 0. Quick summary

**What the deadline is for in RPE.** RPE never rejects a late request. When a chain passes its deadline, the sidecar sets `cancelled = true` (`cs=expired`) and signals the services. Services that are marked non-critical (for example loyalty) skip work; critical work (payment) completes. So the deadline is an **overload trigger**:

- **Quiet when healthy:** with all services on a request's path healthy, at most about 1% of requests get `cs=expired`.
- **Fires under overload:** when services are overloaded and requests get clearly slower, at least 5% of those requests get `cs=expired`.

**What v2 does.** For each chain (`METHOD /route` at the gateway):

1. Learn the **healthy time `T_h`**: the pooled p99 duration of requests whose own path touched only healthy services.
2. Compute the **target `D* = T_h × (1 + m)`**, margin `m` between 10% and 30%.
3. Each cycle choose one action from the decision table (section 6.5): raise (A), lower under overload (B), keep while firing (C), tighten when healthy (D), or hold.
4. Every proposal goes through the **existing** approval workflow and C3 delivery, unchanged.
5. After a change is applied, **verify** it worked; if not, propose a revert (also human-approved).

---

## 1. Hard rules (never break these)

1. **Do not change the proxy (`netty-sidecar-proxy/`, C2)**: its deadline propagation, cancellation propagation (`cs`), or backpressure handling (leaky bucket, pressure signals, adaptive concurrency, circuit breaker). See `CLAUDE.md`.
2. **Do not change C1, C2 or C3 code** (`spec/`, `libs/`, `conformance-kit/`, `netty-sidecar-proxy/`, `k8s/`, `charts/`), the demo app, `console/` or `hub/` in this work. Only `intelligence/` changes.
3. **Preserve "signal, never reject".** Never write code or tests that expect a 504 for a deadline. e2e `95` must keep asserting `cs=expired`.
4. **Keep every existing contract** (section 2). All new data is **additive**: new fields, new classes, new config properties, new audit event types. Never rename or remove an existing field, endpoint, env var, scenario name or table.
5. **Never add metrics or tracing code to demo services.** Observability belongs to the sidecar. Any sidecar metric/trace addition goes in a **separate, measure-only PR** labelled "C2 owner approval required" (section 9), and C4 must work without it (fallbacks).
6. **Do not delete or weaken tests to make them pass.** Tests whose expected behaviour changes on purpose are listed in `intelligence/DEADLINE_V2_MIGRATION.md` (Phase 0) and updated with a comment `// v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md)`.
7. **Do not touch the rate (leaky bucket) recommendation logic** (`AdaptiveEngine.bucket(...)`) except to read whether a rate proposal is pending (cross-lever lock).
8. **The names in this guide are proposals.** The Java source was not visible when this guide was written. Before using any class, method, metric or span attribute name, find the real one in the code and adapt. If something does not exist, use the fallback described here and say so.

---

## 2. Contracts that must stay unchanged

| Area | Must stay the same |
|---|---|
| REST API `/api/v2/intelligence` | `GET /`, `POST /changes/{id}/review`, `GET /audit`, `GET /connections`, `POST /baselines/reset`, `POST /demo/{scenario}`, `GET /queries`: same paths, same existing JSON fields. New fields only added. |
| `PolicyWorkflow` | Every recommendation is `PENDING_APPROVAL`; re-offer keeps id; rejection holds 30 min; approval keeps original expiry; stale evidence invalidates; only `APPROVED` non-simulated recommendations are delivered. |
| `Component3Bridge` | Same payload: `POST /api/k8s/policies`, `spec.routes["METHOD /path"].deadlineMs` on the gateway policy, `source=c4-recommendation`, `recommendationId`, `approvedBy`; two-phase rollout; `ACCEPTED` → `APPLIED`; effective-policy sync. |
| `AuditLog` | Append-only PostgreSQL `intel.audit`; existing columns and event types. New event types only added. No migration that alters existing columns. |
| Collection checks | `TempoWindows` completeness checks: partial traces rejected, search-limit/incomplete search blocks the window, trace count vs `rpe_chain_requests_total` within ±max(1, 10%), mixed budgets block, budget must match the observed gauge. Keep them all. |
| `RobustStatistics` | Existing median/MAD API and the 5%-of-median floor. Only add methods. |
| Health classification | `DeadlineHealth` and service/chain classification used by the rest of C4 and the Admin Panel. v2 *reads* it; do not change its outputs. |
| `MockScenarios` names | `healthy`, `stressed`, `overloaded`, `retry-stressed`, `recovery`, `deadline-increase`, `deadline-recovery`, `multiple-chains`. Expectations may change; names stay. New scenarios are added. |
| Env vars | All existing `RRPE_*`, `PROMETHEUS_*`, `TEMPO_URL`, `COMPONENT3_POLICY_URL`, `RPE_AUDIT_*` names and meanings. |
| Admin Panel | Fields it already reads from the API. New fields are optional. |
| e2e | `tests/e2e/70-intelligence.sh`, `tests/e2e/95-intelligence-live.sh` keep passing. Only additions. |

---

## 3. Workflow (branch, phases, safe merge)

### 3.1 Before starting
1. Read: `CLAUDE.md`, `docs/components/C4-Intelligence-Rathnayake.md`, `intelligence/README.md`, `intelligence/ADAPTIVE_IMPLEMENTATION.md` (historical background), and **every class and test** in `intelligence/src/main/java/org/rrpe/intelligence/adaptive/` and its test folder.
2. Work only on the **C4 intelligence-layer branch**. Never commit to `main`.
3. Bring the branch up to date with `main` first (`git fetch`, then merge or rebase `main` into the C4 branch as the user prefers) and run the full test suite. Record the baseline: number of tests, all green? If anything is already red, report it before changing code.
4. Make a work branch from the C4 branch, for example `c4/deadline-v2`. Commit per phase with clear messages (`c4(deadline-v2): phase 1 data model`).

### 3.2 Phases (stop after each one)
| Phase | Content | Behaviour change? |
|---|---|---|
| 0 | Inventory and migration list | None |
| 1 | Data model + monitoring (samples, load series, disjoint windows) | None |
| 2 | Analysis (load classifier, request labels, statistics, healthy model, lever) | None (not yet wired) |
| 3 | Planning (new `DeadlineMath`, `DeadlinePolicy`, wire into `AdaptiveEngine.chain`, config) | **Yes**: new decisions |
| 4 | Verification, workflow hooks, audit events | Additive |
| 5 | API evidence, scenarios, e2e additions, docs | Additive |
| 6 | Optional sidecar observability (separate PR, C2 owner approval) | Measure-only |

After each phase: run `./mvnw -q test` in `intelligence/`, report results, list any intentionally changed tests, and wait.

### 3.3 Safe merge checklist
- [ ] `git diff --stat <c4-branch>...c4/deadline-v2` shows changes **only under `intelligence/`** (plus the e2e additions in `tests/e2e/70-intelligence.sh` and `95-intelligence-live.sh`, and the demo overlay values for new env vars if needed).
- [ ] No change to `netty-sidecar-proxy/`, `libs/`, `spec/`, `k8s/`, `charts/`, `demo-app/`, `console/`, `hub/`.
- [ ] All tests green; changed tests are exactly those in `DEADLINE_V2_MIGRATION.md`.
- [ ] API responses: every existing field still present with the same meaning (compare a `GET /api/v2/intelligence` response before and after).
- [ ] `make images IMAGES=intelligence && make restart`, then e2e `70` and `95` pass on the kind cluster.
- [ ] A feature flag exists (section 8.3, `rrpe.adaptive.deadline.engine=v2|v1`), default `v2`, so the team can switch back without a revert if a demo breaks.
- [ ] Merge `c4/deadline-v2` into the C4 branch; the C4 branch goes to `main` through the team's normal PR review.

---

## 4. Problems this fixes (from the design review)

| # | Problem in the current design | v2 fix |
|---|---|---|
| P1 | Treats an exceeded deadline as a failure to remove | Objective is an overload trigger: quiet when healthy, fires under overload |
| P2 | Never lowers the deadline under overload (all changes blocked) | **Case B**: overloaded and not firing → lower toward `D*` so the signal fires |
| P3 | Raises the deadline when services slow down (recent p99 × 1.1/1.2/1.3; only fully Overloaded blocks) | **Stress gate**: no raise while ≥5% of requests touch a STRESSED or OVERLOADED service; `T_h` learned only from healthy requests |
| P4 | Deadline too short from day one is never fixed (baseline needs zero deadline failures) | Learn despite exceedances; if ≥1% of healthy samples are late, p99 ≥ D is proven → Case A raises ×1.5 |
| P5 | Cannot tell "deadline too short" from "rate too low" (queue wait counts against the deadline) | **Lever diagnosis**: queue-wait dominated + low CPU → RATE; high CPU/throttling → CAPACITY; deadline held |
| P6 | Whole chain judged by its worst service on any path | Each request labelled by **its own path**; strata weighted by traffic share |
| — | Overlapping windows count as repeated evidence | Persistence counts **disjoint** windows only |
| — | No small-sample allowance | One-sided **Wilson** bounds and minimum requests |
| — | No check after a change | **Verification** after APPLIED; failed → revert proposal |

Additional corrections to the replacement proposal:
- `T_h` is the **pooled** p99 over eligible windows, not the median of per-window p99s (with ~100 requests a window's p99 is almost its maximum).
- Spread uses the scaled MAD `σ̂ = 1.4826 × MAD`.
- Case D (tighten when healthy) never goes below `D*` and does not rely on proving a zero rate (impossible at n = 100: Wilson upper bound ≈ 2.6%).
- Only backpressure 503s and 4xx are excluded from timing data; slow 5xx are kept.
- Requests cut by **other** timers (client/gateway timeouts, the older rejecting `rpe-go` Middleware that returns 504 near the deadline) are detected; the window is not used for learning and C4 holds.
- No Kaplan–Meier: the deadline is a fixed, known censoring point per window, so the p99 is exact when fewer than 1% are censored, and otherwise the data prove the deadline is too short.

---

## 5. Inputs

### 5.1 From Tempo (gateway root span per complete trace), via `TempoWindows`
| Field | Source | Fallback |
|---|---|---|
| chain | `http.request.method` + `rpe.chain.route`; skip `/_unmatched` | existing route normalisation |
| duration | root span end − start | — |
| budget | `rpe.deadline.budget_ms` | — (window blocked if missing, as today) |
| status | `http.response.status_code` | — |
| path | services of the trace's server spans (`rpe.direction`), as a sorted set | existing path extraction |
| expired | `rpe.cancel.state == "expired"` or `rpe.deadline.exceeded` if present | `duration >= budget` (mark `expiredInferred`) |
| queue wait | `rpe.queue.wait_ms` if present | none (lever falls back to pressure) |

### 5.2 From Prometheus, via `PrometheusWindows`
Verify every metric name against a sidecar's `/metrics` (port 15090) and the existing `GET /queries` output before using it.

```promql
# chain totals (cross-check, already used)
sum by (route, method) (increase(rpe_chain_requests_total[$W]))
# per-service queue pressure (needs a service label; else Prometheus relabel pod label app -> service)
max by (service) (max_over_time(rpe_backpressure_inbound_queue_utilization_percent[$W]))
# per-service load shedding
sum by (service) (increase(rpe_rejections_total{reason="overload"}[$W]))
# circuit breaker open (name to verify)
max by (service) (max_over_time(rpe_circuit_breaker_open[$W]))
# CPU / throttling / memory (cAdvisor + kube-state-metrics; optional)
sum by (service) (rate(container_cpu_usage_seconds_total{container!="rpe-sidecar"}[$W]))
  / sum by (service) (kube_pod_container_resource_limits{resource="cpu",container!="rpe-sidecar"})
sum by (service) (rate(container_cpu_cfs_throttled_periods_total{container!="rpe-sidecar"}[$W]))
  / sum by (service) (rate(container_cpu_cfs_periods_total{container!="rpe-sidecar"}[$W]))
max by (service) (container_memory_working_set_bytes{container!="rpe-sidecar"})
  / max by (service) (kube_pod_container_resource_limits{resource="memory",container!="rpe-sidecar"})
# optional (phase 6): queue wait
histogram_quantile(0.95, sum by (service, le) (rate(rpe_inbound_queue_wait_seconds_bucket[$W])))
```

A missing optional series must not block the algorithm. It only removes that signal (record which signals were available in the evidence). A missing **required** series (the ones `DeadlineHealth` already needs) makes the service UNKNOWN.

### 5.3 Excluded requests
- Backpressure 503 (queue full, `Retry-After`) and open-breaker fast fails: excluded from timing and from exceed rates (they belong to the rate lever).
- 4xx client errors: excluded.
- Passthrough requests (no RPE context): excluded.
- Partial traces: excluded (existing rule).
- Slow 5xx: **kept**.

### 5.4 "Cut" requests (censoring by something other than RPE)
Mark `cut = true` with a hint:
- status 504 and `|duration − budget| ≤ 5% of budget` → `REJECTING_MIDDLEWARE` (the older rejecting `rpe-go` Middleware, rule R-D05);
- other 504 / 502 / 499 → `OUTER_TIMEOUT_OR_CLIENT`;
- error status with `duration < budget` that ended abnormally → `ABNORMAL_END`.

If cut share in a window > 0.5%: window is **not eligible for learning** and the decision is `HOLD(CENSORED_OTHER)` with the hint. C4 reports it; it never changes the cause.

---

## 6. Algorithm

### 6.1 Windows and disjointness
- Window `[t − W, t]` evaluated every cycle `C` (existing collection, ending `evaluation-delay` in the past).
- A window **counts** for persistence, learning and verification only if `windowEnd − lastCountedEnd ≥ W` for that chain (`DisjointWindowTracker`). With W = 5m / C = 1m this is once every 5 cycles; with the demo's 1m / 15s, once every 4 cycles.
- **Eligible for learning**: counted, complete (existing checks), single budget, cut share ≤ 0.5%, not search-limited.

### 6.2 Service load per window (`ServiceLoadClassifier`)
| State | Condition (first match) |
|---|---|
| UNKNOWN | a required input is missing or stale |
| OVERLOADED | queue pressure ≥ 80%, or overload rejections > 0, or CPU ≥ 0.85, or throttling ≥ 0.25, or memory ≥ 0.90, or breaker open, or `DeadlineHealth` = OVERLOADED |
| STRESSED | queue pressure ≥ 50%, or CPU ≥ 0.70, or `DeadlineHealth` = STRESSED |
| HEALTHY | otherwise |

Record which signal caused the state; it goes into the evidence.

### 6.3 Request labels (`RequestLabeler`), by the request's **own** path
- UNKNOWN if any service on the path is UNKNOWN;
- else **O** if any is OVERLOADED;
- else STRESSED if any is STRESSED;
- else **H**.

Services without a sidecar (for example `legacy`) are reported and ignored for labelling, as today.
Stratum = path signature, `String.join(">", sortedServices)`.

### 6.4 Formulas
**Healthy time `T_h` (`HealthyLatencyModel`)**
- Learning set L = all H samples from the last `N_learn` eligible windows (`baseline-samples`).
- Stratum weight `w_s = share_s(all requests) / share_s(H requests)` so that fast paths are not over-represented.
- Censored samples (expired or cut) are placed at +∞.
- `T_h` = weighted nearest-rank 0.99 quantile of L.
- Status **IDENTIFIED** if `T_h` < the smallest budget of any learning window that had a censored sample; otherwise **CENSORED** (only "p99 ≥ D" is known).
- **Coverage gap:** a stratum with ≥ 5% of all traffic but fewer than `coverage-min-samples` H samples → keep the last IDENTIFIED `T_h` (freeze) and block Case D.
- Learning does **not** require zero deadline failures and is **not** reset when the deadline changes. `POST /baselines/reset` clears it.
- **Drift:** if the per-window H p99 differs from `T_h` by more than 25% for `drift-windows` consecutive disjoint windows → drop older history, relearn, audit "healthy time re-learned".

**Margin and target**
```
q_w      = per-window H p99 (windows with enough H samples)
sigma    = max(1.4826 * MAD(q_w), 0.05 * median(q_w))
m        = clamp(3 * sigma / T_h, 0.10, 0.30)
D*       = clamp(ceil10(T_h * (1 + m)), 100, 10000)        // ms, rounded up to 10 ms
```

**Exceed rates with one-sided Wilson bounds** (z = 1.645; `n_eff = n / designEffect`, default designEffect 1.0)
```
p   = x / n
LB  = (p + z²/2n − z·sqrt(p(1−p)/n + z²/4n²)) / (1 + z²/n)
UB  = (p + z²/2n + z·sqrt(p(1−p)/n + z²/4n²)) / (1 + z²/n)
```
Computed for H (`p_H`) and O (`p_O`). A set needs at least `min-window-requests`; low traffic widens the window in steps of W up to `max-widen`, else INSUFFICIENT_DATA → hold.

**Impact preview** (attached to every proposal)
- `e_pred_H(D')` = weighted share of L with duration > D'.
- `e_pred_O(D')` = share of current O samples with duration > D' (an upper bound after a decrease, because skipping shortens durations).

**Lever diagnosis** (`LeverDiagnosis`, per OVERLOADED service on O paths)
- RATE if queue-wait share of duration ≥ 0.5 and CPU < 0.70;
- CAPACITY if CPU ≥ 0.85 or throttling ≥ 0.25;
- otherwise DEADLINE.
- Without queue-wait data: queue pressure ≥ 80% and CPU < 0.70 → RATE with `leverInferred = true`.

Shares used below: `oShare`, `stressedShare`, `unknownShare`, `cutShare` = fraction of the window's included requests with that label/flag.

### 6.5 Decision table (evaluate in order; first match wins)
| # | Case | Conditions | Action |
|---|---|---|---|
| 0 | **Guards → HOLD(reason)** | chain PAUSED; window not counted or not complete; mixed budget; unknownShare > 10%; cutShare > 0.5% (`CENSORED_OTHER` + hint); a pending proposal exists for this chain (any lever); a rate proposal is pending for a service on its paths; cooldown active; proposals in the last hour ≥ max; state LEARNING (except Case A with CENSORED) | No proposal; reason in evidence |
| 1 | **Inconsistent → HOLD** | Case A and Case B conditions both true | Hold, flag for review |
| 2 | **C: firing → KEEP** | oShare ≥ 5% for k disjoint windows and `LB(p_O) ≥ e_fire` | Keep D; withdraw any pending increase for this chain |
| 3 | **Wrong lever → HOLD** | oShare ≥ 5% and lever ∈ {RATE, CAPACITY} | Hold; advisory "consider rate/capacity at <service>" |
| 4 | **B: overloaded, not firing → LOWER** | oShare ≥ 5% for k windows; `UB(p_O) < e_fire`; D > 1.10·D*; T_h IDENTIFIED | if `e_pred_O(D*) ≥ e_fire`: propose `max(D*, ceil10(0.75·D))`; else HOLD("deadline is not the lever") |
| 5 | **A: healthy over-signalled → RAISE** | oShare < 5%; stressedShare < 5%; `LB(p_H) > e_target` for k windows; (T_h CENSORED or D < D*) | IDENTIFIED: `min(D*, ceil10(1.5·D))`; CENSORED: `min(10000, ceil10(1.5·D))` |
| 6 | **D: healthy, too long → TIGHTEN** | oShare < 5%; stressedShare < 5%; IDENTIFIED; no coverage gap; D > 1.25·D* for k windows | D' = `max(D*, ceil10(0.80·D))`; propose only if `e_pred_H(D') ≤ e_target`, else HOLD("preview failed") |
| 7 | **In band → HOLD** | otherwise | No change |

Requests on stressed paths that exceed the deadline fall to HOLD **on purpose**: the signal is firing early, which is what RPE wants.

### 6.6 Per-chain state machine
| From | To | When |
|---|---|---|
| LEARNING | READY | ≥ `min-learning-windows` eligible windows and ≥ `min-learning-requests` H samples |
| READY | PROPOSED | Case A, B or D proposal offered |
| PROPOSED | READY | rejected (existing 30-min hold), expired, withdrawn, or stale evidence |
| PROPOSED | SETTLING | C3 reports APPLIED (and new root spans carry the new budget) |
| SETTLING | READY | verification PASS, or INCONCLUSIVE (flagged) |
| SETTLING | PROPOSED | verification FAIL → revert proposal (reason `VERIFICATION_FAILED`) |
| any | PAUSED | 2 reversals or 2 verification failures within 1 h; configuration conflict (stamped budget ≠ last approved policy, existing check); `REJECTING_MIDDLEWARE` seen in 2 counted windows |
| PAUSED | READY | `POST /baselines/reset` (→ LEARNING), or the cause has cleared for 30 min |

### 6.7 Verification (`DeadlineVerifier`)
After APPLIED: skip `settle-windows` counted windows, then evaluate `verify-windows` counted windows with only the new budget.
- After A or D: PASS if `LB(p_H) ≤ e_target`.
- After B: PASS if `p_O ≥ 0.5·e_fire` (or the overload has cleared) **and** `LB(p_H) ≤ e_target`.
- FAIL → revert proposal through `PolicyWorkflow` (human approval). Not enough data → INCONCLUSIVE.
- Always audited.

### 6.8 Why it settles (for the thesis)
- Every step moves D toward D* and never past it; steps are geometric (×1.5 up, ×0.75 / ×0.80 down), so convergence takes a few steps (2000 → 490 ms: 4 steps).
- Healthy no-action zone `[D*, 1.25·D*]` plus 10% overload deadband: estimation noise cannot cause flapping (same idea as the Kubernetes HPA 10% tolerance).
- Case C stops lowering as soon as the signal fires.
- Lowering D sheds work, which moves the chain from O toward H; the only healthy action then is Case D, floored at D*; no raise follows because healthy signalling at D ≥ D* is ≤ ~1% by construction.
- Cooldown, disjoint persistence, max proposals per hour, pause on reversals, verification and human approval are outer safety nets.

---

## 7. Worked examples (use as test fixtures)

1. **Too long under overload (P2).** T_h = 400, MAD = 20 → σ̂ = 29.7 → m = 0.222 → D* = 490. D = 2000. Payment OVERLOADED, O requests ≈ 900 ms, p_O = 0, e_pred_O(490) ≈ 100%. Case B: 2000 → 1500 → 1130 → 850. At 850 the 900 ms requests expire → Case C keeps. After recovery, Case D: 850 → 680 → 550 (in band). Old algorithm: stays at 2000.
2. **Services slowing (P3).** T_h = 1000, D = 1250. Inventory STRESSED, its paths' p99 rises to 1200. Old algorithm proposes 1440. v2: stressedShare ≥ 5% → HOLD (stress gate); requests over 1250 are signalled.
3. **Too short from day one (P4).** D = 300, true healthy p99 = 500, m = 0.24. p_H ≈ 25% and T_h CENSORED → Case A: 300 → 450; still ≈ 3% censored → 680; now IDENTIFIED, T_h = 500, D* = 620, 680 in band. Old algorithm never learns.
4. **Rate, not deadline (P5).** Inventory bucket 100 req/s, offered 120; pressure 95%, 503s present, queue wait ≈ 75% of duration, CPU 0.25 → lever RATE → HOLD + advisory "raise inventory inbound rate"; no deadline proposal.
5. **Mixed paths (P6).** 70% cache path (p99 150), 30% DB path (p99 600); healthy mixture p99 ≈ 560. DB becomes STRESSED. Without weighting, T_h would fall to ~150. With strata: DB stratum coverage gap → T_h frozen at 560, Case D blocked, stress gate blocks raises.
6. **Rejecting middleware.** 504s at ≈ D on a chain → cut share > 0.5%, hint `REJECTING_MIDDLEWARE` → HOLD; PAUSED after 2 counted windows.

---

## 8. Implementation, phase by phase

Package: `intelligence/src/main/java/org/rrpe/intelligence/adaptive/` (adapt names to the real code).

### Phase 0: Inventory (no code change)
1. Read all classes and tests listed in section 3.1.
2. Create `intelligence/DEADLINE_V2_MIGRATION.md` with a table of every existing test: `KEEP`, `UPDATE` (intended behaviour change, with reason) or `REMOVE` (only for deleted logic).
3. Expected UPDATE/REMOVE (confirm in the real code):
   - `DeadlineMath` margin 10/20/30% tests and the 1440 ms example → UPDATE/REMOVE;
   - `increaseCensored` tests → REMOVE (logic deleted);
   - restoration `max(baseline × 1.10, current × 0.90)` → UPDATE (now Case D);
   - baseline learning requires `deadlineFailures == 0` → UPDATE;
   - "overload blocks all deadline changes" → UPDATE (Case B lowers);
   - "3 consecutive healthy windows" restoration → UPDATE (disjoint windows);
   - TempoWindows "any overloaded participant blocks increases" (whole-chain) → UPDATE (per-request labels);
   - `MockScenarios` expectations for `deadline-increase` / `deadline-recovery` → UPDATE.
4. Also list: the real names of the metrics in section 5.2 that exist today, which ones are missing, and how `DeadlineHealth`, `TempoWindows.collect/parseTrace`, `AdaptiveEngine.chain`, `observeConfiguredDeadline`, `resetBaselines`, `PolicyWorkflow.offer/withdraw` actually work.
5. **Stop and show the user.**

### Phase 1: Data model and monitoring (no behaviour change)
**`AdaptiveModels.java`** (add; do not change existing types):
```java
public record TraceSample(String traceId, String method, String route, Instant end,
    double durationMs, int status, long budgetMs, SortedSet<String> path,
    boolean expired, boolean expiredInferred, boolean cut, String cutHint,
    OptionalDouble queueWaitMs) {}
public enum ServiceLoad { HEALTHY, STRESSED, OVERLOADED, UNKNOWN }
public enum RequestLabel { H, STRESSED, O, UNKNOWN }
public record LabelledSample(TraceSample sample, RequestLabel label, String stratum) {}
public record Proportion(int events, int n, double p, double lower, double upper) {}
public enum Lever { DEADLINE, RATE, CAPACITY, NONE }
public enum DeadlineCase { A_RAISE, B_LOWER_OVERLOAD, C_KEEP_FIRING, D_TIGHTEN, HOLD }
public enum ChainDeadlineState { LEARNING, READY, PROPOSED, SETTLING, PAUSED }
public record HealthyModel(String status /* IDENTIFIED|CENSORED|LEARNING */, double thMs,
    double sigmaMs, double margin, long targetMs, int windows, int requests,
    double censoredShare, boolean coverageGap, Map<String, Double> strataShares) {}
public record DeadlineEvidence(DeadlineCase decisionCase, String holdReason, long currentMs,
    Long proposedMs, HealthyModel model, Proportion hExceed, Proportion oExceed,
    double oShare, double stressedShare, double unknownShare, double cutShare, String cutHint,
    Double predictedHExceed, Double predictedOExceed, Lever lever, boolean leverInferred,
    Map<String, String> serviceLoadReasons, ChainDeadlineState state, String verification,
    List<String> exampleTraceIds) {}
```

**`TempoWindows.java`**: keep everything; add a method that returns `List<TraceSample>` per chain from the same complete traces (section 5.1, 5.3, 5.4). The existing route p99 output stays.

**`PrometheusWindows.java`**: add the per-service load queries (section 5.2) into a `ServiceLoadSnapshot` record (values `OptionalDouble`, plus which were available). Add them to the `GET /queries` list.

**New `DisjointWindowTracker.java`**: `boolean isDisjoint(String chainKey, Instant windowEnd, Duration window)`, `void markCounted(String chainKey, Instant windowEnd)`, `int streak(String chainKey, String condition, boolean holds, Instant windowEnd)` (counts only disjoint windows; resets when the condition is false or a gap appears).

Unit tests for all of the above. Behaviour of the running engine is unchanged. **Stop.**

### Phase 2: Analysis (new classes, not yet wired in)
- **`ServiceLoadClassifier`**: `Map<String, ServiceLoad> classify(ServiceLoadSnapshot snap, Map<String, HealthResult> deadlineHealth, LoadThresholds t)`, plus the reason per service (section 6.2).
- **`RequestLabeler`**: `List<LabelledSample> label(List<TraceSample> samples, Map<String, ServiceLoad> loads, Set<String> unmonitored)` (section 6.3).
- **`RobustStatistics`** (add only):
```java
static Proportion wilsonOneSided(int x, int n, double z, double designEffect) {
  double ne = n / designEffect; double p = n == 0 ? 0 : (double) x / n;
  double z2 = z * z, den = 1 + z2 / ne, c = p + z2 / (2 * ne);
  double h = z * Math.sqrt(p * (1 - p) / ne + z2 / (4 * ne * ne));
  return new Proportion(x, n, p, Math.max(0, (c - h) / den), Math.min(1, (c + h) / den));
}
static double scaledMad(List<Double> xs);                         // 1.4826 * MAD
static double weightedNearestRank(List<double[]> valueWeight, double q); // value +INF = censored
```
- **`HealthyLatencyModel`** (one per chain, keyed by `METHOD /route`):
```java
HealthyModel update(String chainKey, Instant windowEnd, List<LabelledSample> window,
                    long budgetMs, boolean eligible, DeadlineConfig cfg);
double predictedExceed(double candidateMs);   // e_pred_H
void reset(String chainKey);                  // used by POST /baselines/reset
```
  Steps: append H samples (with censored flag and budget) and the window's H p99 if eligible and disjoint; trim to `N_learn` windows; compute strata weights and coverage gap; `T_h` by weighted nearest-rank; IDENTIFIED/CENSORED; sigma, margin, target; drift check (section 6.4).
- **`LeverDiagnosis`**: `LeverResult diagnose(String service, ServiceLoadSnapshot snap, OptionalDouble queueWaitShare, LeverThresholds t)` → `Lever` + `inferred`.

Unit tests for each (section 10). **Stop.**

### Phase 3: Planning and wiring (behaviour change)
**`DeadlineMath`**: rewrite the deadline part; keep any helper the rate path uses.
```java
static long ceil10(double ms);
static double margin(double sigma, double th, double k, double min, double max);
static long target(double th, double m, long minMs, long maxMs);
static long stepUp(long d, OptionalLong target, double factor, long maxMs);       // A
static long stepDownOverload(long d, long target, double factor);                  // B: max(target, ceil10(0.75d))
static long stepDownHealthy(long d, long target, double factor);                   // D: max(target, ceil10(0.80d))
```
Remove: recent p99 × (1 + 10/20/30%), `increaseCensored`, restoration `max(baseline × 1.10, current × 0.90)`.

**New `DeadlinePolicy`** (pure function, easy to unit-test):
```java
DeadlineDecision decide(ChainContext ctx) {
  Optional<String> g = guards(ctx);                         // row 0
  if (g.isPresent()) return hold(g.get());
  boolean a = caseA(ctx), b = caseBCore(ctx);
  if (a && b) return hold("INCONSISTENT");                  // row 1
  if (ctx.oShare() >= cfg.share() && ctx.oStreak() >= cfg.k()
      && ctx.oExceed().lower() >= cfg.eFire()) return keep("C_KEEP_FIRING");   // row 2
  if (ctx.oShare() >= cfg.share() && ctx.lever() != Lever.DEADLINE)
      return hold("LEVER_" + ctx.lever());                  // row 3
  if (b) return ctx.predictedOAt(ctx.target()) >= cfg.eFire()
      ? propose(DeadlineCase.B_LOWER_OVERLOAD, stepDownOverload(ctx.current(), ctx.target(), 0.75))
      : hold("DEADLINE_NOT_THE_LEVER");                     // row 4
  if (a) return propose(DeadlineCase.A_RAISE,
      stepUp(ctx.current(), ctx.identified() ? OptionalLong.of(ctx.target()) : OptionalLong.empty(), 1.5, cfg.maxMs()));  // row 5
  if (caseD(ctx)) { long nd = stepDownHealthy(ctx.current(), ctx.target(), 0.80);
      return ctx.model().predictedExceed(nd) <= cfg.eTarget()
          ? propose(DeadlineCase.D_TIGHTEN, nd) : hold("PREVIEW_FAILED"); }   // row 6
  return hold("IN_BAND");                                   // row 7
}
```

**`AdaptiveEngine`**: rewrite only the deadline path in `chain(...)`:
collect samples → service loads → labels → `HealthyLatencyModel.update` → Wilson proportions and shares → lever → `DeadlinePolicy.decide` → if a proposal, `PolicyWorkflow.offer(...)` with the **existing** recommendation type plus the new `DeadlineEvidence` attached; if KEEP, withdraw a pending increase. Leave `bucket(...)` unchanged. Add `boolean hasPendingRateProposal(String service)` for the cross-lever guard. Keep `observeConfiguredDeadline` (configuration conflict → PAUSED) but make sure it does **not** reset `T_h` on an approved deadline change.

**`AdaptiveConfig`**: add nested property classes (section 8.3 values). Keep all existing properties.

Update the tests listed in `DEADLINE_V2_MIGRATION.md`. Run all tests. **Stop.**

### Phase 4: Verification, workflow hooks, audit
- **New `DeadlineVerifier`** (section 6.7), creating a revert proposal through `PolicyWorkflow` on FAIL.
- **`PolicyWorkflow`** (additive only): one pending proposal per chain across levers; `withdrawPendingIncrease(chainKey)`; reason `VERIFICATION_FAILED`. Existing states, TTL, re-offer and rejection-hold rules unchanged.
- **`Component3Bridge`**: no change. Use the existing effective-policy sync to detect APPLIED (→ SETTLING) and configuration conflicts (→ PAUSED).
- **`AuditLog`**: new event types `DEADLINE_DECISION` (only when the case or hold reason changes, to avoid noise), `DEADLINE_STATE_CHANGED`, `DEADLINE_VERIFICATION`, `DEADLINE_CUT_DETECTED`, `DEADLINE_HEALTHY_RELEARNED`, payload = `DeadlineEvidence` as JSON. Still append-only; no change to existing columns.
**Stop.**

### Phase 5: API, scenarios, e2e, docs
- **`AdaptiveController`**: endpoints unchanged. `GET /` adds an optional `deadlineEvidence` object per chain. `GET /queries` lists the new queries. `POST /baselines/reset` also resets `HealthyLatencyModel` and the chain state to LEARNING.
- **Admin Panel**: no code change required in this PR. The new evidence fields (case, T_h status, D*, margin, p_H / p_O with bounds, predicted exceed rates, lever, state, verification) are available for a later UI change.
- **`MockScenarios`**: keep names; update expectations:
  - `deadline-increase` → Case A on healthy over-signalling;
  - `deadline-recovery` → Case D after overload;
  - `overloaded` → Case B or C;
  - `stressed` → HOLD (stress gate);
  - `retry-stressed` → no increase.
  Add: `deadline-too-long-under-overload` (example 1), `deadline-too-short-day-one` (3), `services-slowing` (2), `queue-rate-not-deadline` (4), `mixed-paths` (5), `rejecting-middleware-504` (6). Simulations stay isolated (virtual clock, never queued or delivered).
- **e2e**:
  - `70-intelligence.sh`: add `deadline-too-long-under-overload` (assert `proposedMs < currentMs` and case `B_LOWER_OVERLOAD`) and `queue-rate-not-deadline` (assert no deadline proposal and lever `RATE`).
  - `95-intelligence-live.sh`: keep the `cs=expired` assertions. During the overload phase assert no **increase** proposal (case ∈ {B, C, HOLD}).
- **Docs**: update `intelligence/README.md` (deadline maths row, parameters) and add a short note at the top of `ADAPTIVE_IMPLEMENTATION.md` that v2 replaces the margin/restoration rules. Update §2 Status in `docs/components/C4-Intelligence-Rathnayake.md` (own section only; do **not** edit the shared Appendix).
**Stop.**

### 8.3 Configuration (`rrpe.adaptive.*`)
Env var form follows the existing pattern: `RRPE_ADAPTIVE_DEADLINE_<NAME>`, `RRPE_ADAPTIVE_LOAD_<NAME>`, `RRPE_ADAPTIVE_LEVER_<NAME>`.

| Property | Production default | Demo cluster |
|---|---|---|
| `window` / `cycle` / `evaluation-delay` (existing) | 5m / 1m / 0s | 1m / 15s / 20s |
| `baseline-samples` (existing; = N_learn windows) | 30 | 5 |
| `deadline.engine` | `v2` | `v2` (`v1` = old logic, kept behind the flag until the team signs off) |
| `deadline.min-learning-windows` / `min-learning-requests` | 10 / 1000 | 3 / 150 |
| `deadline.quantile` | 0.99 | 0.99 |
| `deadline.margin-k` / `margin-min` / `margin-max` | 3 / 0.10 / 0.30 | same |
| `deadline.min-ms` / `max-ms` (existing min/max deadline if present) | 100 / 10000 | same |
| `deadline.e-target` / `e-fire` / `z` / `design-effect` | 0.01 / 0.05 / 1.645 / 1.0 | same |
| `deadline.min-window-requests` / `max-widen` | 100 / 30m | 30 / 5m |
| `deadline.persistence-windows` (k) | 3 | 2 |
| `deadline.share-threshold` / `unknown-max-share` / `cut-max-share` | 0.05 / 0.10 / 0.005 | same |
| `deadline.step-up` / `step-down-overload` / `step-down-healthy` | 1.5 / 0.75 / 0.80 | same |
| `deadline.deadband-overload` / `deadband-healthy` | 0.10 / 0.25 | same |
| `deadline.cooldown` (existing cooldown if present) | 3m | 1m |
| `deadline.settle-windows` / `verify-windows` | 1 / 2 | 1 / 2 |
| `deadline.max-proposals-per-hour` | 4 | 6 |
| `deadline.drift-threshold` / `drift-windows` | 0.25 / 3 | 0.25 / 2 |
| `deadline.coverage-min-share` / `coverage-min-samples` | 0.05 / 20 | 0.05 / 10 |
| `load.pressure-stressed` / `pressure-overloaded` | 0.50 / 0.80 | same |
| `load.cpu-stressed` / `cpu-overloaded` / `throttle-overloaded` / `memory-overloaded` | 0.70 / 0.85 / 0.25 / 0.90 | same |
| `lever.queue-share` / `lever.cpu-low` | 0.5 / 0.70 | same |

Demo values go into the existing demo overlay (`deploy/k8s/platform/intelligence.yaml`), next to the existing `RRPE_ADAPTIVE_*` values. On the demo cluster a chain becomes READY in about 5–6 minutes.

If an existing property already covers a value (min/max deadline, cooldown, minimum chain requests), **reuse it** instead of adding a duplicate.

---

## 9. Phase 6 (optional, separate PR, needs C2 owner approval, measure-only)

These only improve data quality. C4 must work without them (fallbacks in section 5).
1. Root span attributes `rpe.deadline.exceeded` (bool) and `rpe.cancel.state` (`none`/`cancel`/`expired`) in `ProxyTelemetry.endHop`.
2. Queue wait: histogram `rpe_inbound_queue_wait_seconds{service,pod}` and span attribute `rpe.queue.wait_ms`, recorded with two timestamps around the existing enqueue/release. `LeakyQueue.submit/releaseNext/scheduleNext` must not change.
3. `service` and `pod` labels on `rpe_backpressure_*` and circuit-breaker series (`BackpressureController.prometheusText`).
4. Route chain-duration histogram buckets 50 ms … 10 s.
5. `rpe.chain.route` and `http.request.method` on every span.

Infra-only (allowed, no proxy code): Tempo keeps all gateway root traces; cAdvisor/kube-state-metrics scrape; Prometheus relabel `app` → `service`.

Rules: record values only; no change to any decision, timing, order, header, flag or response; only add metrics/attributes; all existing proxy tests (`mvn -f netty-sidecar-proxy/pom.xml test`) pass unchanged; diff touches only `ProxyMetrics`, `RpeContractMetrics`, `ProxyTelemetry`, `BackpressureController.prometheusText`, their call lines, and Tempo/Prometheus config.

---

## 10. Tests

### New unit tests (fixed `Clock` / virtual clock)
- `RobustStatisticsTest`: zero events at n = 100 → UB ≈ 0.0263; n = 541 → UB ≤ 0.005; LB monotone in x; designEffect 2 widens the interval; scaledMad; weighted nearest-rank with +∞ values.
- `HealthyLatencyModelTest`: learns despite exceedances; CENSORED when ≥ 1% censored; IDENTIFIED equals ground truth; not reset by a deadline change; reset by `reset()`; drift only after k disjoint windows; coverage gap freezes T_h; pooled p99 vs median-of-window-p99 on a 100-request fixture.
- `ServiceLoadClassifierTest`, `RequestLabelerTest`: every threshold; UNKNOWN precedence; DeadlineHealth fold-in; mixed paths labelled per request.
- `DisjointWindowTrackerTest`: 5m/1m counts once per 5 cycles; 1m/15s once per 4; streak resets on gaps.
- `DeadlineMathTest` (rewritten): margin clamp; ceil10 and bounds; steps never cross the target; sequence 2000 → 1500 → 1130 → 850 with D* = 490.
- `DeadlinePolicyTest`: every row of the decision table, including INCONSISTENT, LEVER_RATE, LEVER_CAPACITY, DEADLINE_NOT_THE_LEVER, PREVIEW_FAILED, every guard, Case C withdrawing a pending increase.
- `LeverDiagnosisTest`: RATE, CAPACITY, DEADLINE, inferred fallback.
- `DeadlineVerifierTest`: PASS / FAIL / INCONCLUSIVE; revert proposal; PAUSED after 2 FAILs in 1 h.
- `TempoWindowsTest` additions: 504 at ≈ D → `REJECTING_MIDDLEWARE`; 503 with Retry-After excluded; slow 5xx kept; expired fallback `duration >= budget`.
- `AdaptiveEngineDeadlineIT`: examples 1–6 end to end (proposal sequence, no reversals, final D).

### Existing tests
- Update only those in `DEADLINE_V2_MIGRATION.md`, each with the `// v2:` comment.
- Workflow, bridge, audit, controller, connections, health classification and rate (`bucket`) tests must pass **unmodified**. If one fails, it is an integration regression: fix the code, not the test.

### e2e
- Section 8, phase 5. Run on the kind cluster: `make images IMAGES=intelligence && make restart`, then `scripts/test-e2e.sh` (on Windows Git Bash with `MSYS_NO_PATHCONV=1`).

---

## 11. Definition of done
1. All new tests pass; existing tests pass except those listed in `DEADLINE_V2_MIGRATION.md`, which are updated (only `increaseCensored` and restoration-formula tests are removed).
2. e2e `70` and `95` pass with the additions.
3. The main PR changes files only under `intelligence/` (plus the e2e additions and demo overlay values). Phase 6 is a separate PR.
4. API paths, existing JSON fields, workflow / bridge / audit contracts, scenario names and env names are unchanged; new data is additive.
5. The `deadline.engine` flag switches between `v2` and `v1`.
6. On the demo cluster: a chain is READY in ≤ 6 min; example 1 converges with decreases only to a firing deadline; example 3 is fixed in two steps.
7. Every proposal carries an impact preview and `DeadlineEvidence`; every applied change has an audited verification.
8. `intelligence/README.md`, `ADAPTIVE_IMPLEMENTATION.md` note and C4 guide §2 Status are updated.

---

## 12. Prompt to paste into Claude Code (VS Code)

```
Read intelligence/C4-DEADLINE-V2-IMPLEMENTATION.md completely (or the attached copy), then
CLAUDE.md, docs/components/C4-Intelligence-Rathnayake.md, intelligence/README.md,
intelligence/ADAPTIVE_IMPLEMENTATION.md, and every class and test in
intelligence/src/main/java/org/rrpe/intelligence/adaptive.

Implement "Deadline Recommendation v2" exactly as the guide describes, on a new branch
c4/deadline-v2 created from my C4 intelligence-layer branch (bring it up to date with main
first and run the tests to record the baseline). Never commit to main.

Hard rules: never change the proxy's deadline propagation, cancellation propagation or
backpressure handling; do not modify C1/C2/C3, demo-app, console or hub code; keep
"signal, never reject"; keep every existing API path, JSON field, PolicyWorkflow rule,
Component3Bridge payload, AuditLog table, MockScenarios name and env var; all new data is
additive; never delete or weaken tests to make them pass. The class, method and metric
names in the guide are proposals: find the real names in the code first, and if something
does not exist, use the documented fallback and tell me.

Work phase by phase (Phase 0 to Phase 5). After each phase run the tests, show me the
results, the files changed, and any intentionally changed tests, then wait for my OK.
Phase 6 (sidecar observability) is a separate PR and only if I ask.
```

---

## 13. Thesis notes (for the author, not for the code)

**Censoring statement:** "Because RPE signals rather than rejects, no RPE component cuts a request at its deadline, so the gateway root-span duration is the real end-to-end time. Expiry does trigger skipping of non-critical work, which can shorten work after the deadline, so expired requests are treated as right-censored at the known deadline D. D is fixed per window (mixed-budget windows are discarded), so this is Type-I censoring: the 99th percentile is exactly identifiable when fewer than 1% of samples are censored; otherwise the data prove it exceeds D, which is itself the diagnosis that D is too short. Other timers (client and gateway timeouts, the legacy rejecting middleware) can cut requests; C4 detects them, excludes those windows from learning and withholds recommendations."

**Evaluation:** compare v1 (current), v2, a static deadline (1.2 × offline p99) and v2 without per-path weighting / without disjoint persistence, on: S1 too short from day one, S2 too long under overload, S3 services slowing, S4 queue rate, S5 mixed paths. Measure time to converge, healthy false-signal rate (target ≤ 1%), overload signal coverage and time to first signal, wasted non-critical work, proposals per hour and reversals, wrong-lever proposals, and payment completion (must not change).

**Grounding:** Google SRE book ch. 22 "Addressing Cascading Failures" (deadline propagation, graceful degradation); gRPC cancellation; Klein et al., "Brownout", ICSE 2014 (skip optional work under overload); Dean & Barroso, "The Tail at Scale", CACM 2013; Amazon Builders' Library "Timeouts, retries, and backoff with jitter" and Hystrix (timeouts from healthy percentiles plus padding); Envoy adaptive concurrency and Netflix concurrency-limits (reference + buffer); Kubernetes HPA tolerance (hysteresis); Bronson et al. HotOS'21 and Huang et al. OSDI'22 (metastable failures, retries); Brown, Cai & DasGupta 2001 (Wilson); Rousseeuw & Croux 1993 (MAD); Kephart & Chess 2003 (MAPE-K; C4 is MAPE-K with a human at Plan → Execute).
