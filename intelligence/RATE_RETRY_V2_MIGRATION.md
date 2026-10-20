# Rate and Retry v2: Step 0 inventory and migration list

Companion to [`C4-RATE-RETRY-V2-IMPLEMENTATION.md`](C4-RATE-RETRY-V2-IMPLEMENTATION.md) (the guide). Step 0 changes no code.

| Item | Value |
|---|---|
| Work branch | `c4/rate-retry-v2`, created from `c4/deadline-v2` at `a49f09f` (identical to `main`) |
| Old rate/retry logic | Replaced, not kept (guide DECISION 2); it stays in git on `c4/deadline-v2` |
| Baseline | `./mvnw -q test` with JDK 22: **164 tests, 0 failures, 0 errors** |

## 1. Section 2 facts, checked in the code

| Fact | Evidence | Confirmed |
|---|---|---|
| Request latency starts before the request enters the leaky-bucket queue | `ProxyHandler.channelRead0`: `requestStartNanos` (line 164) and `RpeContractMetrics.begin(…, requestStartNanos)` (line 173) come before `backpressure.enqueueInbound` (line 525); durations are `nanoTime − requestStartNanos` | ✅ p95 includes queue wait |
| `rpe_inflight_requests` counts queued requests, inbound and outbound | `RpeContractMetrics.begin` increments `IN_FLIGHT` for every hop at request start | ✅ not usable as "being processed" |
| Queue depth is exposed | `BackpressureController.prometheusText`: `rpe_backpressure_inbound_queue_depth` (gauge, `inboundQueue.size()`) | ✅ not yet queried by C4 |
| `rpe_retries_total` is never incremented | `RpeContractMetrics`: the `RETRIES` adders are only created (line 47), never incremented ("the proxy does not retry yet") | ✅ retry effectiveness unknown live |
| Sync sets `healthyBucketTarget = current rate` | `Component3Bridge.sync` line 134: `new Policy(…, rate, rate, ratio, null)` | ✅ recovery capped at the current rate |

## 2. Test migration list

`KEEP` = must pass unmodified. `UPDATE` = intended change, marked
`// rate-retry v2: intended behaviour change (see RATE_RETRY_V2_MIGRATION.md)`. No test is removed.

| Class | Test | Verdict | Reason |
|---|---|---|---|
| `AdaptiveEngineTests` | `correctedMadExamplePersistsAndRateUsesSuccessfulCapacity` | **UPDATE** | Asserts the old cut `min(baseline, recent) × 0.90 / × 0.75` (90 and 60). The health assertions (PENDING_BREACH → STRESSED → OVERLOADED, baseline 100/10, frozen baseline) stay |
| | `retryEffectivenessExampleProducesTwentyExtraAttempts` | **UPDATE** | Asserts the old `0.10 × effectiveness`, halved when STRESSED (0.02, 20 extra attempts) |
| | `recoveryRequiresHealthyWindowsAndAppliedPolicyThenHonoursCooldown` | **UPDATE** | Asserts the old "+5% of baseline" recovery (66) and retry +0.01 steps (0.03, 0.04). The cooldown assertion stays |
| | `retrySafetyAndRepeatedWindowsPreventUnsafeChanges` | **UPDATE** | Asserts the per-request safety check inside the budget rule (removed: those checks belong to the sidecar's retry logic, guide §5). The "repeated window is not a new cycle" assertion stays |
| | `criticalOverloadClassificationStillWaitsTwoCyclesForAutomaticPolicies` | KEEP | The protective "overloaded → retry 0 after 2 cycles" rule is kept (see decision R2) |
| | `nearZeroMadFromVerySteadyTrafficDoesNotTurnNoiseIntoABreach`, `missingCountsAndTooFewRequestsNeverBecomeHealthy`, `mediansAndMadMatchTheDocumentAndZeroMadUsesOnlyTheFloor` | KEEP | Health classification only |
| | 3 deadline tests | KEEP | Deadline v2 unchanged |
| `PolicyWorkflowTests` | all 5 | KEEP | With `auto-approve-actions` empty (code default) the workflow is exactly as today |
| `Component3BridgeTests` | all 5 | KEEP | They assert the delivered payload and the synced current rate (`bucketRate() == 90`), which v2 keeps; `healthyBucketTarget` is not asserted |
| `DeadlineScenarioEndpointTests` | `serviceScenariosKeepTheirRateResultsAndAddTheDeadlineSide` | KEEP | Asserts only the deadline side and the number of outcomes |
| `DeadlineEvaluationReportTests` | 1 | KEEP | Asserts only the six deadline examples (its printed table will show the new rate/retry numbers) |
| All other classes | all | KEEP | Deadline v2, statistics, examples, verifier, endpoints and the legacy `/api/v1` engine are not touched |

**Result: 160 KEEP, 4 UPDATE, 0 REMOVE.**

## 3. Old code to remove, with all callers

| Old code | Where | Callers | Plan |
|---|---|---|---|
| Overload/stress cut `min(rate, min(baseline, recent) × 0.75 / 0.90)` | `AdaptiveEngine.bucket` | `AdaptiveEngine.service` only | Replace with rate v2 |
| Healthy recovery `+5% of baseline`, capped at `healthyBucketTarget` and baseline | `AdaptiveEngine.bucket` | same | Replace with the v2 healthy rule |
| Retry target `maximumRetryRatio × effectiveness`, halved when STRESSED, +0.01 steps | `AdaptiveEngine.retry` | `AdaptiveEngine.service` only | Replace with retry v2 |
| Per-request checks in the budget rule (`retryableFailure`, `safeOperation`, `remainingDeadlineMs`, `retryDurationMs`) | `AdaptiveEngine.retry` lines 159–161 | same | Remove from the rule; report them in the evidence |

**Kept, because others use them:**
- **`ServiceWindow` fields** `retryableFailure`, `safeOperation`, `remainingDeadlineMs`, `retryDurationMs`: they're part of the `POST /api/v2/intelligence/services` JSON, `MockScenarios` and tests.
- **`Policy.healthyBucketTarget`**: used by `PolicyWorkflow` (validation, kept on APPLIED), `Component3Bridge`, the `PUT /policies` JSON and many tests.
- **`maximumRateChange` and `maximumRetryRatio`**: still the step limit and the cap.
- **The protective "overloaded → retry 0" rule, cooldown, `offer` and `withdraw`.**

## 4. e2e, Admin Panel and hub

| Place | Expects rate/retry PENDING_APPROVAL? | Note |
|---|---|---|
| `tests/e2e/70-intelligence.sh` | No | Health, API object and deadline simulations only |
| `tests/e2e/80-admin-panel.sh` | No | Line 63 sets a gateway rate of 10 through the hub; no C4 card is checked |
| `tests/e2e/95-intelligence-live.sh` | No (only `CHAIN_DEADLINE` must be PENDING_APPROVAL, which stays) | It sets the gateway policy to `ratePerSec 100, retryRatio 0.1`. With auto-approval on, the cross-lever lock (guide §6.3) blocks automatic gateway changes while the deadline proposal is pending. Before that, the healthy rule lowers a rate only after 30 min and a raise needs a full queue, so no gateway change is expected during the test |
| `console/src/pages/Recommendations.tsx` | No | The Queue shows only `PENDING_APPROVAL`; auto-approved changes appear in **Reviewed** with reviewer `c4-auto`. The page description "Nothing is applied until a reviewer approves it" becomes inaccurate for rate and retry; the console is outside this work (note for the console owner) |
| `hub/` | No | It only forwards reviews; `approvedBy` comes from C4's reviewer field (`c4-auto`) |

## 5. Decisions (approved by the owner, 20 Oct 2026)

Approved as proposed below, with these notes:
- **R1:** UNKNOWN → hold applies to live windows without load data. `MockScenarios` (the existing `healthy`, `stressed`,
  `overloaded`, `retry-stressed`, `recovery` and the new rate scenarios) **provide queue depth and utilisation**, so the
  simulations still produce rate decisions. Any changed expectation of e2e `70` or of tests using the scenarios is reported.
- **R2:** APP_OVERLOADED (app evidence) → retry 0; RATE_TOO_LOW → hold.
- **e2e `95`:** the auto-approval guard (persistence + cooldown + cross-lever lock) must not change the gateway's rate or
  retry while it runs; any doubt is reported before running it.
- **Note for the console owner:** the Recommendations page says "Nothing is applied until a reviewer approves it". With
  `RRPE_ADAPTIVE_AUTOAPPROVEACTIONS` set, this is true **only for request deadlines**: allowed rate and retry changes are
  approved automatically (reviewer `c4-auto`) and appear in **Reviewed**, not in the Queue. The console is not changed in this work.

| # | Question | Proposed answer |
|---|---|---|
| R1 | A service window without load data (`engine.service(window)` from tests, `MockScenarios` or `POST /services`, or a missing queue-depth series) | Rate diagnosis **UNKNOWN → hold**, as guide §4.2 says, with the reason in the evidence |
| R2 | Retry when the rate diagnosis is UNKNOWN but health is persistently OVERLOADED | Keep today's protective **retry → 0** (the safe direction), so the protective rule doesn't depend on queue data. When the diagnosis is RATE_TOO_LOW, hold instead (guide §5) |
| R3 | How to show an ADVISORY retry increase (retry data missing) | **No proposal**: an evidence line "Advisory: retry X → Y not proposed (retry-data-unavailable)". Nothing can be approved or delivered. With retry data (simulations now, C2 PP2 later), an increase is a normal proposal: PENDING_APPROVAL unless `RETRY_INCREASE` is allowed |
| R4 | `healthyRate` storage | C4's own `RateState`, set once from the first synced rate, then only from verified healthy decisions. `Component3Bridge.sync` keeps the policy's existing `healthyBucketTarget` instead of overwriting it with the current rate (current rate and retry ratio are still updated) |
| R5 | Queue-depth query | `avg(avg_over_time(rpe_backpressure_inbound_queue_depth{service,namespace}[W]))`, added to `loadQueries` (so it also appears in `GET /queries`); missing → R1. **Implemented as the maximum, see §6** |

## 6. Implementation notes and deviations (recorded per step)

- **R5 uses the maximum (Step 2).** The query is `max(max_over_time(rpe_backpressure_inbound_queue_depth{...}[W]))`,
  not the average. When the queue fills in bursts, p95 contains the full-queue waits. An average depth underestimates
  `Wq = depth / rate`, so `Papp = p95 − Wq` made the app look slow and repeating bursts were diagnosed APP_OVERLOADED
  (a cut). With the maximum, example 4 is diagnosed RATE_TOO_LOW and the rate is raised.
- **Burst model corrected (Step 2).** The first model alternated whole windows at 80 and 160. Real bursts happen inside
  every window, so `ServiceSimulation` now averages the base and burst halves of each window and keeps the worst queue
  depth, utilisation and p95. The old-logic example 4 in `evaluation/rate-retry-v1-baseline.md` is re-recorded with this model.
- **Cooldown before any state change (Step 2).** During the per-service cooldown the diagnosis is still computed and shown
  in the evidence, but the anti-ratchet and `goodRate` state are not changed.
- **Lowering the healthy rate is never automatic.** The slow step down (rate > 2 × target for 30 min) is proposed
  without an automatic kind, so it always waits for a reviewer.
- **Example 2 with a human approving every step.** After the first cut to `max(floor, 0.8 × accepted)`, v2 keeps probing
  upwards while the bucket sheds. Every probe that slows the app lowers the `brokeAt` ceiling to 0.9 × that rate, so the
  probes get smaller and the rate settles between the floor (50) and about 1.1 × the app capacity (60). It never ratchets
  below the floor. In automatic mode the flip limit (Step 5) pauses this probing.
- **Retry v2 (Step 4).** The rows of guide §5 are checked in order: overload → 0 (APP_OVERLOADED for 2 cycles, or rate
  UNKNOWN with persistent OVERLOADED health, R2), RATE_TOO_LOW → hold (a pending retry proposal is withdrawn), low
  success → 0.02 then 0, STRESSED → hold, healthy → +0.02. Details:
  - Low success needs ≥ 30 attempts and a one-sided 95% Wilson upper bound (z = 1.645, the existing
    `RobustStatistics.wilsonOneSided`) below 0.20 for 2 consecutive windows. A decrease below STRESSED is allowed (it is the
    safe direction); the table rows are applied in the guide's order.
  - An increase also needs a quiet queue (rate diagnosis HEALTHY), and comes at most once per stabilisation period
    (`rate.stabilisation`, 5 min) after the last applied retry change. The target is `min(retry.base-ratio 0.10,
    maximum-retry-ratio)`, so the guide's "max 0.20" is not reachable while the workflow caps the ratio at 0.10.
  - The per-request safety fields of `ServiceWindow` (`retryableFailure`, `safeOperation`, `remainingDeadlineMs`,
    `retryDurationMs`) are kept in the API but no longer used by the budget rule: these checks belong to the sidecar's
    retry logic (guide §5).
  - The protective step down goes straight from the current ratio to 0.02 (for example 0.05 → 0.02), as in the guide's table.
