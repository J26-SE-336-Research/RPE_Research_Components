# Deadline recommendation v2: results

Recorded on 18 Oct 2026 on branch `c4/deadline-v2` after Phase 5. Same scenarios, traffic, virtual clock, simulated reviewer
and format as [`v1-baseline-results.md`](v1-baseline-results.md) (old logic), so the two tables compare directly.
Reproduce with:

```bash
./mvnw -q test -Dtest=DeadlineEvaluationReportTests -Devaluation.out=evaluation/v2-results-table.md
```

Settings: window 5 min, cycle 1 min, 6 learning windows kept (`baseline-samples`), 300 requests per window, cooldown 3 min;
deadline v2 with the demo-cluster values (`DeadlineSettings.demo()`: 3 learning windows / 150 healthy requests, persistence 2
disjoint windows, settle 1 + verify 2 windows). The traffic definitions are in `DeadlineExamples` (identical to the baseline).
v2 sees the per-request samples, the per-service load signals of the scenario (queue pressure 95%, CPU 25%, queue wait 75% in
example 4) and the 504 status of cut requests; the old logic saw only the aggregate window.

## Results: v2

| Scenario | Proposals (applied in order) | Final deadline | Healthy exceedance at final D | Overload exceedance at final D | Correct outcome | Converged / correct? |
|---|---|---|---|---|---|---|
| **Example 1: deadline too long under overload** (start 2000 ms) | 2000 → 1600 (healthy (learn), D_TIGHTEN); 1600 → 1200 (payment overloaded (~900 ms), B_LOWER_OVERLOAD); 1200 → 900 (payment overloaded (~900 ms), B_LOWER_OVERLOAD); 900 → 680 (payment overloaded (~900 ms), B_LOWER_OVERLOAD); 680 → 550 (recovered, D_TIGHTEN) | 550 ms — last: HOLD IN_BAND, READY | 0.0% | 100.0% (D=680 at end of overload) | decreases only, until the overload fires (≥5% expired); after recovery tighten into [D*, 1.25·D*] | **yes**: overload fired; healthy exceedance 0.0% |
| **Example 2: services slowing (inventory STRESSED)** (start 1250 ms) | none | 1250 ms — last: HOLD STRESS_GATE, READY | 0.0% | n/a | no increase (stress gate); late requests on the stressed path are signalled | **yes**: unchanged during the problem phase |
| **Example 3: deadline too short from day one** (start 300 ms) | 300 → 450 (healthy, 25% over 300 ms, A_RAISE); 450 → 680 (healthy, 25% over 300 ms, A_RAISE) | 680 ms — last: HOLD IN_BAND, READY | 0.0% | n/a | raise in a few steps until healthy exceedance ≤1% (300 → 450 → 680) | **yes**: healthy exceedance 0.0% |
| **Example 4: queue rate too low, not the deadline** (start 800 ms) | none | 800 ms — last: C_KEEP_FIRING SIGNAL_FIRING, READY | 0.0% | n/a | no deadline change; advise raising the inventory rate | **yes**: unchanged during the problem phase |
| **Example 5: mixed paths (cache 70% / database 30%), database STRESSED** (start 700 ms) | none | 700 ms — last: HOLD STRESS_GATE, READY | 0.0% | n/a | no change; the healthy time stays near the mixed p99 (not the cache p99) | **yes**: unchanged during the problem phase |
| **Example 6: an older rejecting middleware cuts requests at the deadline (504)** (start 1000 ms) | 1000 → 800 (healthy (learn), D_TIGHTEN) | 800 ms — last: HOLD PAUSED, PAUSED | 0.0% | n/a | no change; hold and report the cutting middleware | **yes**: unchanged during the problem phase |

### Built-in scenarios (`MockScenarios`, production settings)

| Scenario | Changes returned |
|---|---|
| `deadline-increase` | CHAIN_DEADLINE POST /api/orders: 1000 → 1500 (PENDING_APPROVAL);  |
| `deadline-recovery` | CHAIN_DEADLINE POST /api/orders: 1440 → 1160 (PENDING_APPROVAL);  |
| `overloaded` | CHAIN_DEADLINE POST /api/orders: 600 → 510 (PENDING_APPROVAL);  |
| `stressed` | none |
| `retry-stressed` | none |
| `recovery` | none |
| `healthy` | none |
| `multiple-chains` | CHAIN_DEADLINE POST /api/orders: 1000 → 1500 (PENDING_APPROVAL); CHAIN_DEADLINE POST /api/notifications: 1200 → 960 (PENDING_APPROVAL); CHAIN_DEADLINE GET /api/inventory: 600 → 490 (PENDING_APPROVAL);  |

## Old logic vs v2

| Example | Old logic: proposals → final | Old correct? | v2: proposals → final | v2 correct? |
|---|---|---|---|---|
| 1 Too long under overload (2000 ms) | 2000 → 1800, then blocked during the overload (signal never fires); 14 restoration steps after recovery → 441 | no | 2000 → 1600 (healthy), 1200 → 900 → 680 under overload (Case B; fires, 100% of overloaded requests signalled), 550 after recovery (Case D, in [D\*, 1.25·D\*]) | **yes** |
| 2 Services slowing (1250 ms) | 1250 → 1125, then **raised** to 1428 under stress | no | no change (stress gate) | **yes** |
| 3 Too short from day one (300 ms) | nothing (never learns; 25% of healthy requests stay over the deadline) | no | 300 → 450 → 680 (Case A, censored healthy time), then T_h identified = 500 ms | **yes** |
| 4 Queue rate too low (800 ms) | 800 → 720, then **raised** 1080 → 1620 (wrong lever) | no | no change; lever RATE, advisory "consider the inbound rate at inventory" | **yes** |
| 5 Mixed paths (700 ms) | 700 → 638, then **raised** to 957 | no | no change (stress gate; T_h frozen near the mixture p99, coverage gap blocks tightening) | **yes** |
| 6 Rejecting middleware (1000 ms) | 1000 → 900, then 900 → 810 while requests were cut | no | 1000 → 800 while healthy (Case D); no change while cut; chain PAUSED (REJECTING_MIDDLEWARE) | **yes** |
| **Total** | | **0 / 6** | | **6 / 6** |

Built-in scenarios: `deadline-increase` 1000 → 1440 (old) vs 1000 → 1500 (v2, Case A ×1.5 on a censored healthy time);
`deadline-recovery` 1440 → 1296 (old) vs 1440 → 1160 (v2, Case D, never below D\*); `multiple-chains` 1440 / 715 / 1080 (old)
vs 1500 (A) / 490 (B, lowered under overload) / 960 (D); `overloaded` now also shows a Case B decrease 600 → 510; the rate and
retry results of the service scenarios are unchanged.

## Notes and limits

- The simulated reviewer approves everything; in production every proposal waits for a human.
- Both engines learn for 16 min before the problem starts. Both may tighten a too-long deadline while healthy (old: restoration
  ×0.9; v2: Case D ×0.8, never below D\*), which is why example 1 enters the overload at 1800 (old) / 1600 (v2).
- Traffic is synthetic and deterministic (top 3% of each window cycles through three values). Live results need the kind
  cluster runs of e2e `70` and `95`, and the guide's thesis evaluation (§13).
