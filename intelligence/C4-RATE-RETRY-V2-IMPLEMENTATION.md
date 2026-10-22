# C4 Rate and Retry v2: Implementation Guide for Claude Code

**Component:** C4 Resilience Intelligence Layer (`intelligence/`), owner R.M.S.B. Rathnayake
**Project:** RPE (J26-SE-336), SLIIT 4th-year research
**Base branch:** `c4/deadline-v2`
**Purpose:** fix the errors in the leaky bucket and retry recommenders, and make rate and retry recommendations **approved automatically** (deadline stays human-approved), without breaking any other component or the existing C4 behaviour.

> **Claude Code: read this whole file first.** Then read `CLAUDE.md`, `intelligence/README.md`, `intelligence/DEADLINE_V2_MIGRATION.md`, and `AdaptiveEngine.java`, `PolicyWorkflow.java`, `Component3Bridge.java`, `PrometheusWindows.java`, `LeverDiagnosis.java`, `DeadlineVerifier.java`, `AdaptiveConfig.java`.

---

## DECISION (20 Oct 2026) - overrides the rest of this guide where they differ
1. **Scope: leaky bucket AND retry.** Implement rate v2 (section 4) and retry v2 (section 5), automatic approval (section 6), and sections 7-11.
2. **No old logic kept, no unnecessary code.** Do **not** add `rrpe.adaptive.rate.mode` or `rrpe.adaptive.retry.mode`. **Replace** the old rules inside `bucket()` and `retry()` with v2 and remove the code that only the old rules used (old ×0.75/×0.90 cut, "+5% of baseline" recovery, `0.10 × effectiveness` target, STRESSED halving, per-request safety checks inside the budget rule). Keep everything that is still correct and used: health classification and baselines, cooldown, `maximumRateChange`/`maximumRetryRatio` limits, the protective "overloaded → retry 0" rule, `offer`/`withdraw`, evidence, and the whole deadline v2 path. The old logic remains available in git on branch `c4/deadline-v2`.
3. **Keep the right things:** do not remove or rename anything other components or the Admin Panel use (API fields, `Action` names `LEAKY_BUCKET`/`RETRY_BUDGET`, C3 payload fields, audit table, env vars, scenario names). If something looks unused, check all callers (including tests, hub and console) before removing it, and list every removal in the migration doc.
4. **Tests:** existing tests that check old bucket or retry numbers are **updated** to v2, each with the comment `// rate-retry v2: intended behaviour change (see RATE_RETRY_V2_MIGRATION.md)`, and listed in a new `intelligence/RATE_RETRY_V2_MIGRATION.md`. No test is deleted without being listed there with the reason (only tests of removed old formulas). All other tests (deadline v2, workflow, bridge, audit, controller, connections, health) must pass **unchanged**.
5. **Automatic approval** behind `rrpe.adaptive.auto-approve-actions` (default empty in code, so workflow tests stay unchanged). In `deploy/k8s/platform/intelligence.yaml` set `BUCKET_RAISE,BUCKET_CUT,RETRY_DECREASE`. Retry increases stay ADVISORY until C2 implements retries (PP2); then `RETRY_INCREASE` can be added. Chain deadlines are **never** auto-approved.
6. Everything else in this guide (hard rules, integration safety, branch `c4/rate-retry-v2`, stop conditions) still applies. Where the guide below mentions `legacy`/`v2` modes or "164 tests unchanged with legacy defaults", read it as point 2 and point 4 above.

---

## 0. Summary in simple words

- **Today's leaky bucket rule can make things worse.** If a service's rate is too low, its queue fills, it returns 503s and requests wait longer. C4 reads that as "overloaded" and **cuts the rate again**, and again (×0.75 each time, no floor).
- **Today's recovery never raises the rate in live mode**, because the "healthy target" is overwritten with the current rate every time C4 reads C3.
- **Fix:** before changing the rate, C4 asks **"is the app slow, or is it just waiting in our own queue?"**
  - waiting in the queue, app fine → **raise** the rate (the bucket is the bottleneck);
  - app itself slow or failing → **cut** the rate, but never below a floor, and only cut again if the last cut helped;
  - healthy → keep about **25% headroom** above demand.
- **Retry:** keep "overloaded → 0". Replace the unusual "0.10 × effectiveness" target with a fixed safe ratio (SRE: 10%). Today the sidecar never counts retries, so only the protective "cut to 0" can actually happen.
- **Automatic approval** for rate and retry, with safety rules: limits, check after each change, automatic undo, and pause if it keeps flipping.
- **The old rate and retry rules are replaced, not kept.** Only intended rate/retry tests change; everything else (deadline v2, workflow, C3 bridge, audit, API) stays exactly as today.

---

## 1. Hard rules (never break)

1. Only change files under `intelligence/` (plus C4 env values in `deploy/k8s/platform/intelligence.yaml`). **Never** change `netty-sidecar-proxy/` (C2), C1, C3, `hub/`, `console/`, `demo-app/`.
2. Keep every contract: REST paths and existing JSON fields (only add optional fields), the C3 payload (`spec.leakyBucket.ratePerSec`, `spec.retry.budgetRatio`), two-phase rollout, `PolicyWorkflow` rules for deadlines, `AuditLog` table, scenario names, env vars.
3. **Do not change deadline v2** logic or the shared health classifier output.
4. All existing tests pass; only the bucket/retry tests listed in `RATE_RETRY_V2_MIGRATION.md` change, on purpose. With `auto-approve-actions` empty (the code default), workflow behaviour is exactly as today.
5. **Chain deadline proposals are never auto-approved.**
6. Never delete or weaken tests. Stop and ask if a rule would be broken.

---

## 2. Facts checked in the code (c4/deadline-v2)

| Fact | Where | Consequence |
|---|---|---|
| Request latency (`rpe_request_duration_seconds`) starts **before** the request enters the leaky bucket queue | `ProxyHandler.channelRead0` (`requestStartNanos`, `RpeContractMetrics.begin`) | p95 includes queue wait, so a too-low rate looks like a slow service |
| `rpe_inflight_requests` is incremented at request start, for **inbound and outbound**, including **queued** requests | `RpeContractMetrics.begin` | **Cannot** be used as "requests being processed by the app". Do not use it for capacity estimates |
| `rpe_backpressure_inbound_queue_depth` exists (requests waiting in the queue) | `BackpressureController.prometheusText` | Use it for queue wait: `Wq ≈ depth / rate` (Little's law). C4 does not query it yet |
| `rpe_backpressure_inbound_queue_utilization_percent` (0–100) | same | Already queried as `loadQueuePressurePercent` |
| `rpe_rejections_total{reason="overload"}` | C2 | Already queried (`loadOverloadRejections`) |
| `rpe_retries_total` is exposed but **never incremented** (retries deferred in C2) | `RpeContractMetrics` | Retry attempts are always 0, so retry effectiveness is unknown. Only "overloaded → 0" can fire |
| `Component3Bridge.sync` sets `healthyBucketTarget = current rate` | `Component3Bridge` line ~134 | Healthy recovery is capped at the current rate, so it never raises |
| `bucket()`: overload/stress → `min(rate, min(baseline, recent throughput) × 0.75/0.90)`; healthy → `+5% of baseline`, capped at target and baseline | `AdaptiveEngine.bucket` | Ratchet down, no floor, no headroom |
| `retry()`: overload → 0; healthy → `0.10 × effectiveness`; non-zero needs per-request safety data that is null live | `AdaptiveEngine.retry` | Non-zero retry is always blocked live |
| Every proposal is `PENDING_APPROVAL`; bridge delivers `APPROVED` ones | `PolicyWorkflow.offer`, `deliverable()`, `Component3Bridge.run` | Auto-approval = set `APPROVED` in `offer` for allowed actions; bridge unchanged |
| `LeverDiagnosis` (deadline v2) already says RATE when queue ≥ 80% with low/unknown CPU | `LeverDiagnosis.diagnose` | Reuse the same idea for rate decisions |

---

## 3. Errors found (ranked)

| # | Error | Effect | Severity |
|---|---|---|---|
| E1 | **Wrong lever:** 503s and queue wait made by our own bucket count as overload, so a too-low rate is cut further | Positive feedback; healthy demand is shed | Critical |
| E2 | **Downward ratchet:** after a cut, throughput ≈ new rate, so the next cut goes lower (×0.75ⁿ; 5 cuts ≈ 24%) with no floor | Rate collapses | Critical |
| E3 | **Recovery disabled live:** healthy target overwritten by sync; capped at baseline throughput (no headroom); +5% steps are slow | Rate never recovers; bursts fill the queue | High |
| E4 | **Baseline can't be learned if the bucket sheds from day one** (clean window needs zero rejections) | Never fixed (same as deadline P4) | High |
| E5 | **Retry target 0.10 × effectiveness** is not a standard budget | Odd tiny ratios (e.g. 0.005) | Medium |
| E6 | Retry halved when STRESSED even if the stress is from the bucket | Wrong lever | Medium |
| E7 | No check or undo after a change | Dangerous once changes are automatic | High (with auto-approval) |

---

## 4. Rate v2 (new rule)

### 4.1 Inputs per service window (all available)
- `R` = current rate (from C3 policy)
- `U` = max queue utilisation % (`loadQueuePressurePercent`)
- `Q` = average queue depth: **new query** `avg(avg_over_time(rpe_backpressure_inbound_queue_depth{service,namespace}[W]))`
- `Wq = Q / R` seconds (estimated queue wait)
- `Papp = max(0, p95 − Wq)` (estimated app time; an approximation, so used with a 1.5× tolerance)
- `P0` = baseline `Papp` (median of healthy windows, stored in C4)
- `A` = accepted requests/s (`successfulThroughput`), `S` = overload rejections/s (`loadOverloadRejections / window`)
- Demand `D = A + S`
- `errorsHigh` = service error rate breached (existing `errorRate` signal; 503 overload rejections are **not** errors)
- C4 keeps its own state per service: `goodRate` (last rate that passed verification), `brokeAt` (rate at which the app slowed, expires after 30 min), `healthyRate`

### 4.2 Diagnosis (must hold for 2 cycles)
| Condition | Diagnosis | Action |
|---|---|---|
| `Papp > 1.5 × P0` **or** `errorsHigh` | **APP_OVERLOADED** | Cut |
| (`U ≥ 80` or `S > 0`) and `Papp ≤ 1.5 × P0` and not `errorsHigh` | **RATE_TOO_LOW** | Raise |
| `U < 50`, `S = 0`, `Papp ≤ 1.5 × P0`, healthy for 3 cycles | **HEALTHY** | Move slowly toward demand + headroom |
| `50 ≤ U < 80`, app normal | **WATCH** | Hold |
| Missing data | **UNKNOWN** | Hold, record reason |

### 4.3 Formulas
```
headroom h = 0.25, step a = 0.10 × goodRate
floor      = max(rate.min-absolute, 0.5 × goodRate)
ceiling    = rate.max-absolute, and if brokeAt is known: min(ceiling, 0.9 × brokeAt)

RAISE (RATE_TOO_LOW):  target = min(ceiling, max(R + a, D × (1 + h)));  Rnew = min(target, R × 1.5)
CUT (APP_OVERLOADED):  Rnew = max(floor, 0.8 × min(R, A));  brokeAt = R
HEALTHY:               target = clamp(D × (1 + h), floor, ceiling)
                       raise by at most a per stabilisation period (5 min, demo 1 min);
                       lower only if R > 2 × target for 30 min, and by at most 10% per step
ANTI-RATCHET:          a second cut is allowed only if the previous cut made Papp fall by >= 20%
                       or back to <= 1.5 × P0; otherwise undo the previous cut and hold (the rate was not the problem)
```
Use the **maximum demand of the last 3 windows** for `D` in the healthy rule, so repeating bursts are covered.

### 4.4 Worked examples (use as tests)
1. **Rate too low from day one.** R = 100, demand 150, queue full (depth 200), P0 = 40 ms, p95 ≈ 2040 ms → Wq = 2.0 s, Papp ≈ 40 ms → RATE_TOO_LOW. Target = max(110, 187.5) = 187.5, step limit → **150**; after verification → **187.5**. Old logic: 100 → 75 → 56 → 42.
2. **Real overload.** R = 100, demand 90, queue empty, Papp 40 → 600 ms, errors up, accepted 60 → APP_OVERLOADED → max(50, 0.8 × 60 = 48) = **50**, brokeAt = 100. If Papp recovers, later raise probes 50 → 60 → 70… (ceiling 90). If the cut did **not** help Papp → undo, hold (anti-ratchet). Old logic: 45 → 34 → 25…
3. **Short burst** (2× for 40 s, once): not persistent for 2 cycles → no change.
4. **Repeating bursts:** healthy rule uses the max demand of 3 windows → rate covers the bursts with 25% headroom.

### 4.5 Baseline for `P0` when the bucket sheds from the start (E4)
New setting `rrpe.adaptive.baseline.allow-shed-windows` (default `false`). When `true`, a window with rejections but normal errors may feed the `Papp` baseline (not the throughput baseline). If no baseline after N windows and `U ≥ 80`, use the lowest window `Papp` as `P0`.

### 4.6 Stable healthy target (E3)
C4 stores `healthyRate` in its own state. In v2 mode, `Component3Bridge.sync` only updates the current rate and sets `healthyRate` only when it does not exist yet. Legacy mode unchanged.

---

## 5. Retry v2 (new rule)

| Situation | Proposal |
|---|---|
| APP_OVERLOADED persistent | **0** (keep today's protective rule) |
| Bucket shedding (RATE_TOO_LOW) | **Hold**, never raise (retries into a full queue just become 503s) |
| Retry data exists (≥ 30 attempts) and retry success rate is clearly low (Wilson upper bound < 0.20) | Step down to 0.02, then 0 |
| Healthy 3 cycles, retry success rate OK | Move toward **0.10** (max 0.20) by +0.02 per stabilisation period |
| STRESSED for a non-rate reason | Hold (no halving) |

- **Today** the sidecar never counts retries, so only the first two rows can fire. Any **increase** is marked ADVISORY (reason `retry-data-unavailable`) and is **not** delivered.
- **Why the retry counter is 0:** C2 has not implemented retries yet (`CLAUDE.md`: retries and retry budget are deferred to the next phase; the C2 owner plans them for PP2). `rpe_retries_total` is exposed but nothing increments it.
- **When C2 adds retries (PP2):** the counter gets real values and all rows of the table start working **without any C4 code change**. Increases then stop being ADVISORY automatically (the rule is "advisory only while retry data is missing"). To also auto-approve increases, add `RETRY_INCREASE` to `auto-approve-actions` at that time; until then keep only `RETRY_DECREASE`.
- Write the retry part so it is fully covered by unit tests with **simulated** retry data now, so it is ready for PP2.
- Per-request checks (retryable, safe operation, remaining deadline, cancelled chain) belong to the sidecar's retry logic, not to the service budget. C4 only reports them.
- When computing rate demand, include retries: `D_total = D × (1 + retryRatio)`.

---

## 6. Automatic approval with safety rules

1. **Allow-list** `rrpe.adaptive.auto-approve-actions` (default empty). Values: `BUCKET_RAISE`, `BUCKET_CUT`, `RETRY_DECREASE`, `RETRY_INCREASE` (enable `RETRY_INCREASE` only after C2 implements retries in PP2). `CHAIN_DEADLINE` can never be listed.
2. In `PolicyWorkflow.offer`: if the action is allowed, the source is not MOCK, and `AutoApprovalGuard` passes → create as `APPROVED`, reviewer **`c4-auto`**, detail "Approved automatically by policy", call `audit.decided`. Otherwise `PENDING_APPROVAL` as today. `Component3Bridge` is unchanged (it sends `approvedBy = c4-auto`).
3. **Guard:** within floor/ceiling; step limits (up ≤ +50%, down ≤ −20%, retry ±0.02 except protective 0); persistence 2 cycles; cooldown (existing); no pending deadline proposal on a chain through the service (cross-lever lock); service not paused.
4. **Check after each change** (2 disjoint windows after APPLIED, reuse the deadline v2 window tracker):
   - raise passes if rejections fall and `Papp ≤ 1.5 × P0`;
   - cut passes if `Papp` improves by ≥ 20% or returns to ≤ 1.5 × P0.
   - On fail → **automatic undo** to the previous value through the same bridge, reviewer `c4-auto-revert`, audited.
5. **Pause:** 2 undos or 3 direction changes for the same service within 30 min → `autoPaused` for 60 min; while paused, proposals go back to `PENDING_APPROVAL` (a human decides).
6. **Audit** every automatic change: diagnosis, inputs (U, Q, Wq, Papp, P0, D, errors), before → after, check result.

---

## 7. Configuration (`rrpe.adaptive.*`)

| Property | Default (code) | Demo (`intelligence.yaml`) |
|---|---|---|
| `rate.headroom` | 0.25 | 0.25 |
| `rate.queue-high-percent` / `queue-low-percent` | 80 / 50 | same |
| `rate.app-tolerance` | 1.5 | 1.5 |
| `rate.cut-factor` | 0.8 | 0.8 |
| `rate.step-fraction` | 0.10 | 0.10 |
| `rate.min-absolute` / `max-absolute` | 5 / 1000 | 5 / 500 |
| `rate.floor-fraction` | 0.5 | 0.5 |
| `rate.broke-at-memory` | 30m | 10m |
| `rate.stabilisation` | 5m | 1m |
| `retry.base-ratio` / `max-ratio` | 0.10 / 0.20 | same |
| `retry.min-success` / `min-attempts` / `step` | 0.20 / 30 / 0.02 | same |
| `baseline.allow-shed-windows` | false | true |
| `auto-approve-actions` | (empty) | `BUCKET_RAISE,BUCKET_CUT,RETRY_DECREASE` |
| `auto.verify-windows` / `max-reverts` / `max-flips` / `pause` | 2 / 2 / 3 / 60m | 2 / 2 / 3 / 10m |

Env form follows the existing pattern (`RRPE_ADAPTIVE_RATE_HEADROOM`, `RRPE_ADAPTIVE_AUTOAPPROVEACTIONS`, …). Reuse existing settings (`maximumRateChange`, `maximumRetryRatio`, `cooldown`, `persistence`, `recoveryWindows`) instead of adding duplicates.

---

## 8. Code changes

| File | Change |
|---|---|
| `AdaptiveEngine.bucket()` | **Replace** the old rules with rate v2 (section 4). Remove code only the old rules used |
| `AdaptiveEngine.retry()` | **Replace** the old rules with retry v2 (section 5); keep the protective "overloaded → 0" rule |
| New `RateDiagnosis` | pure function: inputs → APP_OVERLOADED / RATE_TOO_LOW / HEALTHY / WATCH / UNKNOWN |
| New `RateState` | `healthyRate`, `goodRate`, `brokeAt`+expiry, `P0`, last change, undos, flips, `autoPaused` |
| New `AutoApprovalGuard`, `RateVerifier` | section 6 |
| `PrometheusWindows` | add the queue-depth query (and list it in `/queries`) |
| `Component3Bridge.sync` | Keep the existing `healthyRate` (set it only when absent); still update the current rate and retry ratio |
| `PolicyWorkflow.offer` | auto-approve branch (section 6.2); everything else unchanged |
| `AuditLog` | new event types `RATE_AUTO_APPROVED`, `RATE_AUTO_REVERTED`, `RATE_AUTO_PAUSED` |
| `AdaptiveController` | optional `rateEvidence` per service in `GET /` |
| `MockScenarios` | new: `rate-too-low`, `rate-real-overload`, `rate-recovery`, `rate-bursts` (hub allowlist note: hub only lists old names; do not change hub) |

## 9. Tests
- `RateDiagnosisTest`: every row of 4.2, including queue-full-but-app-fine → RATE_TOO_LOW.
- `BucketV2Test`: examples 1–4; floor never crossed; anti-ratchet undo; healthy headroom; brokeAt ceiling; max of 3 windows for demand.
- `SyncHealthyRateTest`: sync keeps healthyRate, still updates the current rate.
- `RetryV2Test`: overload → 0; shedding → hold; low success → 0.02 → 0; healthy → +0.02 up to 0.10; increase ADVISORY when retry data missing.
- `AutoApprovalTest`: allowed actions APPROVED by `c4-auto`; deadline never auto; empty allow-list → exactly today's behaviour; undo on failed check; pause after 2 undos / 3 flips; audit fields.
- **All existing tests pass.** Only the bucket/retry tests listed in `RATE_RETRY_V2_MIGRATION.md` are updated; deadline v2, workflow, bridge, audit, controller, connections and health tests pass **unchanged**.
- e2e: check whether `70`, `80` or `95` expect rate/retry cards to be `PENDING_APPROVAL`; report before changing anything.

## 10. Definition of done
1. All tests pass; changed tests are exactly those in `RATE_RETRY_V2_MIGRATION.md`; no unused old rate/retry code remains.
2. `git diff --stat` shows only `intelligence/` (plus C4 env values).
3. Demo with auto-approval on: example 1 raises instead of cutting; example 2 cuts once and stops at the floor; every automatic change is audited as `c4-auto`.
4. Before replacing the old rules, run examples 1–4 and a retry storm with the old logic and save the results to `intelligence/evaluation/rate-retry-v1-baseline.md`; after, save the v2 results to `intelligence/evaluation/rate-retry-v2-results.md` (same format) for the thesis comparison.

## 11. Prompt to paste into Claude Code
```
Read intelligence/C4-RATE-RETRY-V2-IMPLEMENTATION.md completely, then CLAUDE.md,
intelligence/README.md, intelligence/DEADLINE_V2_MIGRATION.md and the classes listed at the top.

Branch: git checkout c4/deadline-v2 && git checkout -b c4/rate-retry-v2
Work only there. Push only with git push -u origin c4/rate-retry-v2. Never commit or push to
main, component-4-intelligence-on-main or c4/deadline-v2. Never commit deadline.pdf.
Use JAVA_HOME="C:\Program Files\Java\jdk-22".

Follow the DECISION section at the top: implement BOTH leaky bucket and retry v2, REPLACE the old
rules (no mode switches, no unused old code), keep everything that is still correct and used.

Hard rules: only change intelligence/ (plus C4 env values in deploy/k8s/platform/intelligence.yaml);
never touch the proxy, C1, C3, hub, console or demo-app; keep all API fields, Action names, C3
payload, workflow, bridge and audit contracts; deadline v2 unchanged; chain deadlines never
auto-approved; only the bucket/retry tests listed in RATE_RETRY_V2_MIGRATION.md may change; never
weaken tests.

Step 0 (then STOP and report): confirm the facts in section 2 in the code (latency includes queue
wait, rpe_inflight_requests includes queued requests, queue depth is exposed, rpe_retries_total is
never incremented, sync sets healthyBucketTarget = current rate); run the tests and report the
count; list the tests that check old bucket/retry behaviour and the old code you plan to remove
(with all its callers); check e2e 70/80/95 and the console for anything expecting rate/retry cards
to be PENDING_APPROVAL; write intelligence/RATE_RETRY_V2_MIGRATION.md.
After my OK: first save the old-logic results (rate-retry-v1-baseline.md), then implement in this
order, running all tests and committing after each: config + RateDiagnosis + RateState; rate v2 in
bucket(); sync keeps healthyRate; retry v2 in retry(); auto-approval guard, verifier, undo and pause;
API/scenarios/docs; v2 results file.
Stop and ask if a non-bucket/retry test fails, a file outside intelligence/ would change, an
existing workflow/bridge/audit test would need changing, or a metric does not exist.
At the end give me a report and the old vs v2 comparison.
```

## 12. Thesis notes
- **One line:** "C4's rate recommender separates waiting in our own queue from slow processing, raises the rate when the bucket is the bottleneck, cuts with a floor and an anti-ratchet rule when the app is overloaded, and applies rate and retry changes automatically with verification and automatic undo; chain deadlines stay human-approved."
- **Grounding:** AIMD (Chiu & Jain 1989); Netflix concurrency-limits (AIMD backoff 0.9, Gradient2 tolerance 1.5); Envoy adaptive concurrency; WeChat DAGOR (SoCC'18) and Breakwater (OSDI'20) use queueing delay, not response time; AWS Builders' Library on load shedding (avoid false-positive shedding) and retries with backoff; Google SRE "Handling Overload" (retry ratio 10%, 3 attempts) and "Addressing Cascading Failures"; Linkerd/Finagle retry budgets (20%); Kubernetes HPA stabilisation (protective fast, relax slowly); metastable failures (Bronson et al. HotOS'21; Huang et al. OSDI'22, retries the most common sustaining effect).
- **Limitations:** `Papp` is estimated (p95 minus average queue wait); no CPU metrics; thresholds are engineering choices, so report a small sensitivity check.
- **Retry wording for the thesis:** "The retry recommender is implemented and tested with simulated data. C2 does not perform retries yet (planned for PP2), so live retry counters are zero and C4 only applies the protective rule (budget 0 under overload). Once C2 implements retries, the full rule set activates without changes to C4."
- **Evaluation:** old logic vs v2 on rate too low, real overload, recovery, bursts, retry storm. Measure healthy demand shed (503s), healthy p99, recovery time, lowest rate reached, number of automatic changes, undos, direction flips.
