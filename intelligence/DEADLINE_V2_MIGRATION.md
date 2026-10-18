# Deadline Recommendation v2: Phase 0 inventory and migration list

Companion to [`C4-DEADLINE-V2-IMPLEMENTATION.md`](C4-DEADLINE-V2-IMPLEMENTATION.md) (the guide). Phase 0 changes no code.

| Item | Decision (owner, 18 Oct 2026) |
|---|---|
| Work branch | `c4/deadline-v2`, created from `main` at `e570d67` |
| Old deadline logic | **Not kept in this branch and no engine switch** (`deadline.engine=v1|v2` is not built). The old logic stays on branch `component-4-intelligence-on-main` (`e570d67`), which is the way back |
| Never committed | `deadline.pdf` |

In this file "old logic" means the deadline path on `component-4-intelligence-on-main`; "v1" in test class names
refers to the separate legacy `analysis/` engine (`/api/v1`), which this work does not touch.

## 1. Baseline (before any change)

| Item | Result |
|---|---|
| Command | `./mvnw -q test` in `intelligence/` |
| Result | **87 tests, 0 failures, 0 errors, 0 skipped** (matches the README) |
| JDK | Needs JDK 17+. The default `java` on this machine is a Java 8 JRE, so the build fails with "No compiler is provided". Run with `JAVA_HOME="C:\Program Files\Java\jdk-22"` |
| Log noise | Two `HttpMessageNotReadableException` warnings come from negative tests of the legacy v1 endpoints; they are expected |

| Test class | Tests |
|---|---|
| `AutomaticRecommendationEndpointTests` (v1) | 6 |
| `CancellationAnalysisEndpointTests` (v1) | 9 |
| `DeadlineRecommendationEndpointTests` (v1) | 8 |
| `IntelligenceLayerApplicationTests` | 1 |
| `RecommendationEndpointTests` (v1) | 7 |
| `RecommendationReviewEndpointTests` (v1) | 7 |
| `analysis.service.RecommendationReviewServiceTests` (v1) | 8 |
| `adaptive.AdaptiveEndpointTests` | 4 |
| `adaptive.AdaptiveEngineTests` | 11 |
| `adaptive.Component3BridgeTests` | 5 |
| `adaptive.DeadlineMathTests` | 4 |
| `adaptive.LiveDeadlineCollectionTests` | 3 |
| `adaptive.PolicyWorkflowTests` | 5 |
| `adaptive.RouteDeadlineCollectionTests` | 6 |
| `adaptive.TempoWindowsTests` | 3 |

## 2. Test migration list

`KEEP` = must pass unmodified. `UPDATE` = intended behaviour change; the test gets the comment
`// v2: intended behaviour change (see DEADLINE_V2_MIGRATION.md)`. `REMOVE` = only for deleted logic.

**Result: 75 KEEP, 12 UPDATE, 0 REMOVE.** Because there is no engine switch (D1), the old deadline formulas are
deleted, so the four `DeadlineMathTests` are rewritten to test the v2 formulas that replace them (UPDATE, not deleted).

### 2.1 Legacy v1 engine (`analysis/`, `/api/v1`): 46 tests, all KEEP
The v1 engine is not touched by this work. All tests in `AutomaticRecommendationEndpointTests`,
`CancellationAnalysisEndpointTests`, `DeadlineRecommendationEndpointTests`, `RecommendationEndpointTests`,
`RecommendationReviewEndpointTests`, `RecommendationReviewServiceTests` and `IntelligenceLayerApplicationTests`: **KEEP**.

### 2.2 `adaptive` package: 41 tests

| Class | Test | Verdict | Reason |
|---|---|---|---|
| `AdaptiveEngineTests` | `correctedMadExamplePersistsAndRateUsesSuccessfulCapacity` | KEEP | Rate path; untouched |
| | `nearZeroMadFromVerySteadyTrafficDoesNotTurnNoiseIntoABreach` | KEEP | Service classification |
| | `retryEffectivenessExampleProducesTwentyExtraAttempts` | KEEP | Retry path |
| | `criticalOverloadClassificationStillWaitsTwoCyclesForAutomaticPolicies` | KEEP | Service classification |
| | `recoveryRequiresHealthyWindowsAndAppliedPolicyThenHonoursCooldown` | KEEP | Rate/retry recovery |
| | `deadlineBandsAndRestorationRequireApproval` | **UPDATE** | Asserts the 10/20/30% band (1440 ms), margin `.20` and restoration `max(baseline×1.10, current×0.90)` (1296 ms). v2: Case A / Case D, different values; approval assertions stay |
| | `overloadBlocksDeadlineChangesAndLargeIncreasesAreBoundedSteps` | **UPDATE** | Asserts "overload blocks every deadline change" and the +50% clamp (1500 ms). v2: Case B lowers under overload; steps are ×1.5 / ×0.75 / ×0.80 |
| | `missingCountsAndTooFewRequestsNeverBecomeHealthy` | KEEP | Service evidence checks |
| | `retrySafetyAndRepeatedWindowsPreventUnsafeChanges` | KEEP | Retry safety |
| | `mediansAndMadMatchTheDocumentAndZeroMadUsesOnlyTheFloor` | KEEP | Statistics |
| | `deadlineFailuresMustPersistAcrossDistinctFreshCyclesAndGapsResetThem` | **UPDATE** | Asserts 1440 after 2 *consecutive cycles*. v2 counts *disjoint* windows. The incomplete-window → `INSUFFICIENT_DATA` and "repeated window is not a new cycle" assertions stay |
| `DeadlineMathTests` | `correctedMarginBandsHaveExactBoundaries` | **UPDATE** | The 10/20/30% bands are deleted; rewritten for the v2 margin `clamp(3σ̂/T_h, 0.10, 0.30)` boundaries |
| | `restorationNeverDropsBelowTheHealthyTargetOrMoreThanTenPercent` | **UPDATE** | `restore` is deleted; rewritten for Case D (`max(D*, ceil10(0.80·D))`, never below D*) |
| | `missingOrInvalidPercentilesCannotCreateADeadline` | **UPDATE** | Same intent (invalid input cannot create a deadline) for the v2 functions |
| | `censoredLatencyUsesTheBaselineAsALowerBoundAndStepsAreBounded` | **UPDATE** | `increaseCensored`/`clamp` are deleted; rewritten for the censored Case A step (`min(max, ceil10(1.5·D))`) and the bounded geometric steps |
| `TempoWindowsTests` | all 3 | KEEP | Trace parsing and completeness; v2 only adds |
| `RouteDeadlineCollectionTests` | `coverageAllowsExtrapolationButRejectsLargeGapsInEitherDirection` | KEEP | Coverage tolerance |
| | `poolsDifferentPathsOnceAndProducesOneEnforceableRouteDeadline` | **UPDATE** | Collection assertions stay; the final engine proposal (1440 ms after 2 cycles) changes under v2 |
| | `routeCoverageUsesToleranceAndDoesNotHideIncompleteSearchesOrBudgetChanges` | KEEP | Completeness checks are a contract |
| | `alternatePathHealthIsIncludedAndHttpMethodsRemainSeparate` | KEEP | `ChainWindow.health` (whole-chain) is an existing API field the Admin Panel shows. v2 adds per-request labels **beside** it and does not change it (the guide expected UPDATE; see D7) |
| | `hopsWithoutASidecarAreReportedButDoNotBlockChainHealth` | KEEP | |
| | `requestsStartingBeforeWindowButFinishingInsideItAreIncluded` | KEEP | |
| `LiveDeadlineCollectionTests` | `existingComponent2TelemetryProducesAnApprovalWithoutManualPolicyOrRetryMetrics` | **UPDATE** | Asserts 1440 after the second failing cycle. Under v2 every request exceeds 1000 ms, T_h is CENSORED → Case A proposes 1500 after k disjoint windows. Approval, measurement-key and mixed-budget assertions stay |
| | `routeDeadlineComesFromTheGatewayStampedBudgetAndMixedBudgetsAreRejected` | KEEP | |
| | `confirmedAppliedPolicyIsNeverOverwrittenByAStaleStartupGauge` | KEEP | |
| `PolicyWorkflowTests` | all 5 | KEEP | Workflow contract |
| `Component3BridgeTests` | all 5 | KEEP | Delivery contract |
| `AdaptiveEndpointTests` | `isolatedDeadlineScenarioMatchesCorrectedExampleAndNeverEntersLiveDelivery` | **UPDATE** | `deadline-increase` scenario asserts 1440. v2: Case A on healthy over-signalling. Isolation/no-delivery assertions stay |
| | `deadlineRestorationExampleIs1296AndRequiresApproval` | **UPDATE** | `deadline-recovery` asserts 1296. v2: Case D |
| | `multipleChainsHaveIndependentEvidenceAndRecommendationsWithoutLiveDelivery` | **UPDATE** | Asserts 1440 / 715 / 1080 and 3 changes. v2 values differ; independence and no-delivery assertions stay |
| | `liveControlIsProtected` | KEEP | Auth + `/queries` |

The guide expected the `increaseCensored` and restoration tests to be REMOVED. They are rewritten instead, so no test is
deleted and each old intent (bounded steps, never below the healthy target, invalid input rejected) is still checked.

### 2.3 e2e (not run in Phase 0)
- `tests/e2e/70-intelligence.sh`: health + API only; unaffected.
- `tests/e2e/95-intelligence-live.sh`: **constrains v2** (see D6). With a 100 ms gateway deadline (every chain `cs=expired`)
  it waits ≤420 s for a `CHAIN_DEADLINE` proposal on `GET /api/orders` with `currentValue=100`, `101 ≤ proposed ≤ 150`,
  and evidence containing the word **"censored"**. It then checks approval, delivery, APPLIED, the stamped budget and the audit.

## 3. Real names vs the guide's proposals

### 3.1 Tempo (gateway root span), parsed in `TempoWindows.parseTrace`
| Guide field | Real today | Status / fallback |
|---|---|---|
| chain | `http.request.method` + `rpe.chain.route` | Exists |
| duration | root span end − start | Exists |
| budget | `rpe.deadline.budget_ms` (intValue) | Exists; window blocked if missing or mixed (existing) |
| status | `http.response.status_code` (set in `ProxyTelemetry.endHop`) | Exists on spans, **not parsed yet** |
| path | server spans of the trace | Exists (`TraceSample.services`, ordered list; v2 uses it as a sorted set) |
| expired | no `rpe.cancel.state` / `rpe.deadline.exceeded` attribute | **Missing**. But the proxy adds a span **event** `deadline.exceeded` (with `rpe.deadline.budget_ms`) in `ProxyTelemetry.deadlineExceeded`. Plan: use that event when present (no C2 change), else `duration >= budget` (`expiredInferred`). Phase 1 must confirm the event appears on the gateway root span in a live trace |
| queue wait | no `rpe.queue.wait_ms` | **Missing** → lever falls back to queue pressure (`leverInferred`) |

Parser gap: `TempoWindows.attributes()` reads only `stringValue` and `intValue`. Boolean/double attributes and span
`events` are ignored today. Phase 1 adds reading them (additive).

### 3.2 Prometheus (per service; Prometheus adds `service`, `namespace`, `pod` target labels)
| Guide query | Real metric today | Status |
|---|---|---|
| chain totals | `rpe_chain_requests_total{method,route}`, `rpe_chain_deadline_exceeded_total` | Exists, already used |
| queue pressure | `rpe_backpressure_inbound_queue_utilization_percent` | Exists, gauge **0–100** (convert to 0–1) |
| overload shedding | `rpe_rejections_total{reason="overload"}`; also `rpe_backpressure_inbound_rejected_total` | Both exist; use either > 0 |
| breaker open | `rpe_circuit_breaker_open_peers` (count of open peers) | Exists, **different name**: open = value > 0 |
| CPU / throttling / memory | `container_cpu_*`, `kube_pod_container_resource_limits` | **Missing**: `rpe-prometheus` only scrapes the sidecars and the operator (no cAdvisor / kube-state-metrics). Signal absent → recorded as unavailable; `CAPACITY` lever only reachable if that infra is added later |
| queue wait histogram | `rpe_inbound_queue_wait_seconds` | **Missing** (Phase 6 only) |

`TelemetryHttp.prometheus` rejects multi-series results ("Ambiguous service query"), so the new load queries are
written per service (existing `{service,namespace}` selector pattern), not `sum by (service)`.

## 4. How the code works today (deadline path)

- **`DeadlineHealth.observe`**: per-service deadline guard from `observedRequests`, `p95Ms`, `errorCount`, `inFlight`,
  `successfulThroughput` + scrape checks. Learns median/MAD from `baseline-samples` stable, error-free windows; then
  STRESSED on any 2×MAD breach, OVERLOADED on a critical 3×MAD latency/error breach or 2 persistent breaches.
  Missing/stale data → `INSUFFICIENT_DATA`. v2 reads its output; it is not changed.
- **`TempoWindows.collect`**: one TraceQL search for gateway inbound spans in the window; fetches every trace; groups by
  `METHOD /route`; window `complete` only if the search was not truncated, all traces parsed, a single stamped budget,
  trace count within ±max(1, 10%) of `rpe_chain_requests_total`, and failures ≤ count. Pooled p99 of root durations.
  Chain health = worst monitored service on **any** path (`INSUFFICIENT_DATA` if one is unknown).
- **`TempoWindows.parseTrace`**: exactly one gateway server root; every span must reach the root; server spans that end
  after the root count as orphan work; path = gateway + server spans in start order.
- **`AdaptiveEngine.chain`**: rejects future/incomplete windows (resets state, withdraws). Learns a baseline only from
  HEALTHY windows with **zero** deadline failures and stable p99. Then: `abnormalFailures` streak ≥ `persistence` →
  `increaseCensored` + clamp; ≥ `recovery-windows` healthy → `restore`. OVERLOADED blocks and withdraws. Limits
  (min/max ms, max change) block. Otherwise withdraws. Every proposal goes through `offer(...)`.
- **`PolicyWorkflow.observeConfiguredDeadline`**: turns the stamped budget into a TEMPO policy reference
  (`prometheus-config:<ms>`); returns `false` when it conflicts with a confirmed applied policy → the collector withdraws and
  skips the chain (this is the "configuration conflict" v2 maps to PAUSED).
- **`resetBaselines`**: `AdaptiveController` → `AdaptiveCollector.resetBaselines` → `AdaptiveEngine.resetBaselines`
  (non-MOCK states) + `DeadlineHealth.reset`. v2 hooks its healthy model and chain state here.
- **`PolicyWorkflow.offer`**: needs the current policy version and value; identical re-offer keeps id/decision and refreshes
  evidence; a different value supersedes; rejection held 30 min; always `PENDING_APPROVAL`; audited.
  **`withdraw`**: supersedes active items for target+action+source.
- **Pending staleness**: an undecided proposal is superseded when its `windowEndEpochSeconds` is older than
  `2 × cycle + evaluation-delay`.

## 5. Where the guide does not fit the code: decisions for Phase 1+

| # | Issue | Proposed handling |
|---|---|---|
| D1 | The guide both **deletes** the old deadline maths (`increaseCensored`, bands, `restore`) and keeps `deadline.engine=v1` as a switch-back flag. Both cannot be true | **Decided: v2 only, no switch.** The old maths is deleted here; the old logic is kept on branch `component-4-intelligence-on-main`. The safe-merge checklist item "feature flag exists" is intentionally not met |
| D2 | `TraceSample` already exists (`TempoWindows.TraceSample`, public, used by tests) | New record is named `DeadlineSample` |
| D3 | `Proposal`/`Settings`/`Collection` are records constructed directly in tests and `MockScenarios`. Adding fields changes their constructors and would force edits to KEEP tests | No new record components there. New config is a separate `@ConfigurationProperties("rrpe.adaptive.deadline" / ".load" / ".lever")` class; new engine dependencies use setter injection with defaults (the pattern `setEvaluationDelay` already uses) |
| D4 | The Admin Panel types `measurements` as `Record<string, number>` and shows `evidence[0]` in the queue table | `DeadlineEvidence` is **not** put in `measurements`. It is exposed as a new optional top-level `deadlineEvidence` object (per chain) in `GET /` and in demo responses. Only plain numbers (T_h, D*, margin, p_H, p_O, bounds) are added to `measurements`. The first evidence line stays a readable sentence |
| D5 | With disjoint persistence, counted windows come every 4–5 cycles, but a pending proposal is superseded after 2 cycles of stale evidence | Evaluate every cycle; count streaks, learning and verification only on disjoint windows; re-offer the same value each cycle while the case still holds, so the existing staleness rule keeps working unchanged |
| D6 | e2e `95` needs, within 420 s: an increase from 100 to 101–150 ms with "censored" in the evidence | v2 Case A with T_h CENSORED gives `min(10000, ceil10(1.5×100)) = 150` ✓. The evidence will say the healthy time is censored at the deadline. Timing: demo window 1 m / cycle 15 s → counted window every 60 s, k = 2 → about 2–3 min after the service guards are ready ✓ |
| D7 | Guide expects the whole-chain health test to change | `ChainWindow.health` is an API field; it stays. Per-request labels are computed separately |
| D8 | `intel.audit` has `proposal_id uuid NOT NULL` and `action NOT NULL`; `AuditLog.record` takes a `PolicyChange`. Its migration lives in `k8s/policy-store/` (C3 folder; must not be edited) | New event kinds (`DEADLINE_DECISION`, …) use a new public `AuditLog` method, a deterministic per-chain UUID (`UUID.nameUUIDFromBytes("deadline:" + chain)`), `action=CHAIN_DEADLINE`, and the evidence JSON in `detail`. No schema change |
| D9 | `POST /api/v2/intelligence/chains` accepts aggregate `ChainWindow`s with no per-request samples | Keep the endpoint. With v2, an aggregate-only window cannot be labelled → `HOLD(NO_REQUEST_SAMPLES)` (v1 still handles it). Mock scenarios generate synthetic per-request samples instead |
| D10 | `TempoWindows.Collection` is serialised in `GET /` (`collection.chains`) | Per-request samples are kept out of the JSON (up to 2000 traces per window) |
| D11 | Guide contract "budget must match the observed gauge" | Not in today's code (the budget is the stamped span value; there is no gauge comparison). Nothing to keep; the existing stamped-budget checks stay |
| D12 | CPU/memory/throttling series do not exist on the cluster | Treated as unavailable; recorded in the evidence. Adding cAdvisor/kube-state-metrics is infra outside `intelligence/`; not done unless you ask |
| D13 | Guide/C4 doc say Java 21; `pom.xml` targets 17 | Keep 17 (no build change) |

**Owner decisions (18 Oct 2026):** D2–D13 accepted as proposed. Queue pressure is read in percent (0–100):
STRESSED at ≥ 50, OVERLOADED at ≥ 80. Expiry uses the existing `deadline.exceeded` span event, with
`duration ≥ budget` as the inferred fallback. CPU, memory and throttling are marked unavailable and never block.

## 6. Integration safety: what will not change
- No file outside `intelligence/` in Phases 1–4. Phase 5 adds only to `tests/e2e/70-intelligence.sh`, `tests/e2e/95-intelligence-live.sh`,
  the C4 env values in `deploy/k8s/platform/intelligence.yaml`, and C4's own section §2 of `docs/components/C4-Intelligence-Rathnayake.md` (each will be shown before committing).
- Untouched: `netty-sidecar-proxy/`, `libs/`, `spec/`, `conformance-kit/`, `k8s/`, `charts/`, `demo-app/`, `console/`, `hub/`.
- Unchanged contracts: `/api/v2/intelligence` paths and existing JSON fields; `PolicyWorkflow` rules;
  `Component3Bridge` payload (`spec.routes["METHOD /path"].deadlineMs`, two-phase rollout); `intel.audit` schema;
  scenario names; env var names; `X-RPE-Control-Token`; "signal, never reject" (nothing expects a 504).
- Rate (`bucket`) and retry logic: unchanged; v2 only reads whether a rate proposal is pending.

## 7. Implementation notes and deviations from the guide (Phases 1–5)

| # | Guide says | Implemented | Why |
|---|---|---|---|
| I1 | Feature flag `deadline.engine=v1\|v2` | Not built | Owner decision D1: v2 only; old logic on `component-4-intelligence-on-main` |
| I2 | Verification FAIL → revert after A, B or D | A: PASS / **CONTINUE** (p_H fell but still above target) / FAIL; B: FAIL only if healthy requests are harmed, CONTINUE while overload is not yet signalled; D: as the guide | The guide's own examples step more than once (ex. 1: 2000 → 1500 → 1130 → 850; ex. 3: 300 → 450 → 680). A strict rule would revert the first step |
| I3 | Row 1 INCONSISTENT | Kept as a defensive check (`DeadlinePolicy.inconsistent`) | With one healthy-time status per chain, A (D < D\* or censored) and B (identified, D > 1.1·D\*) exclude each other; a grid test proves it |
| I4 | Configuration conflict → PAUSED | PAUSED only if the conflict persists for `persistence-windows` window lengths | A rollout briefly shows a stamped budget that differs from the synced C3 policy; pausing on that would block e2e `95` for 30 min |
| I5 | Case C then row 3 (wrong lever) | Same order; the RATE/CAPACITY **advisory** is also shown when Case C keeps the deadline | Example 4's queued requests already exceed the deadline, so Case C matches first |
| I6 | Windows without healthy requests are learning windows | Only windows with healthy requests enter the learning set | Otherwise a full overload pushes the healthy history out and T_h is lost (Case B needs an identified T_h) |
| I7 | Drift compares the window p99 with T_h | Compares with T_h when the drift started; windows with a coverage gap are not used for σ̂ or drift | The pooled T_h follows the drifting windows; a change in the path mix is not drift |
| I8 | A pending proposal holds the chain | The same value is re-offered each cycle (refresh); a different decision holds and stops the refresh, so the outdated proposal goes stale | The existing staleness rule supersedes proposals after 2 cycles; counted windows come every 4–5 cycles |
| I9 | `min-window-requests` 30 on the demo cluster | Reuses `minimum-chain-requests` (≥ 100, validated by the existing settings) with low-traffic widening | Guide §8.3: reuse an existing property |
| I10 | `ABNORMAL_END` for an error that ended before the budget | 502 → `ABNORMAL_END`; 504 near D → `REJECTING_MIDDLEWARE`; other 504 and 499 → `OUTER_TIMEOUT_OR_CLIENT` | No other signal for an abnormal end exists in the traces |
| I11 | e2e `95`: assert no increase during the overload phase | Not added | `95` has no overload phase; it gains an additive evidence check only |
| I12 | New scenarios in the Admin Panel | Available on the intelligence API only | `hub/features.go` has a scenario allow-list; the hub is outside this work |
| I13 | Update §2 Status in `docs/components/C4-Intelligence-Rathnayake.md` | Not changed | Outside the files allowed for this work; proposed text in the final report |
| I14 | Tests updated: 8 expected | 12 updated (8 + the 4 `DeadlineMathTests` rewritten, no engine switch) and the `LiveDeadlineCollectionTests` fixture (healthy duration 900 ms instead of exactly the 1000 ms budget, and null for the new load queries) | v2 counts `duration ≥ budget` as an exceedance |
