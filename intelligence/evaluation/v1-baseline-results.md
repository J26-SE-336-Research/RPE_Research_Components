# Old deadline logic: baseline results (before v2)

Recorded on 18 Oct 2026 on branch `c4/deadline-v2` at commit `8f4dee4`, **before any code change**, with the old
deadline logic (identical to `component-4-intelligence-on-main` / `main` at `e570d67`). Compare with
[`v2-results.md`](v2-results.md), which uses the same scenarios, traffic and format.

## How the scenarios were run

- A temporary JUnit harness (not committed) fed the old `AdaptiveEngine.chain(...)` one window per cycle on a virtual
  clock: window 5 min, cycle 1 min, 6 baseline samples, persistence 2, recovery 3 windows, cooldown 3 min, 300 requests
  per window. Simulation only: nothing is sent to Component 3.
- A simulated reviewer approves every proposal at once, and Component 3 "applies" it on the next cycle, so a scenario shows
  where the algorithm goes when every proposal is accepted.
- Traffic is deterministic. Each path has a body (97% of requests, spread evenly between two values) and a top 3%
  that cycles through three values per window (this gives the per-window p99 and a non-zero MAD). Durations are the full
  end-to-end time ("signal, never reject": nothing cuts a request at its deadline), except in example 6.
- The old engine sees only the aggregate window (request count, `duration ≥ D` count, pooled p99, chain health = worst
  service on any path). It has no queue-pressure, per-path or 504 signal.
- Each example starts with 16 min of healthy traffic (enough for both engines to learn), then the problem phase begins.

| # | Scenario | Traffic (body / top 3%, ms) | Problem phase |
|---|---|---|---|
| 1 | Too long under overload | 150–350 / 380·400·420, D=2000 | 120 min: payment OVERLOADED, requests 800–880 / 895·900·905; then 120 min recovered |
| 2 | Services slowing | 70% via payment and 30% via inventory, both 500–950 / 980·1000·1020, D=1250 | 120 min: inventory STRESSED, its path 700–1190 / 1230·1260·1290 |
| 3 | Too short from day one | 75% 150–295, 22% 310–445 / 480·500·520 (true p99 500), D=300 | 200 min, all healthy |
| 4 | Queue rate too low | 300–560 / 580·600·620, D=800 | 120 min: inventory queue full (pressure 95%, 5% shed with 503, queue wait 75% of time, CPU 25%), inventory latency guard STRESSED, requests 900–1400 / 1450·1500·1550 |
| 5 | Mixed paths | 70% cache 50–140 / 145·150·155, 30% database 300–580 / 590·600·610, D=700 | 120 min: database STRESSED, its path 400–780 / 790·800·810 |
| 6 | Rejecting middleware | 300–560 / 580·600·620, D=1000 | 60 min: 3% of requests cut with 504 at 0.99·D |

"Healthy exceedance" is the share of requests of the healthy traffic that would exceed the final deadline
(target ≤ 1%: quiet when healthy). "Overload exceedance" is the share of the overloaded requests that exceed the deadline
in force at the end of the overload (target ≥ 5%: the signal fires).

## Results: old logic

| Scenario | Proposals (applied in order) | Final deadline | Healthy exceedance at final D | Overload exceedance at final D | Correct outcome | Converged / correct? |
|---|---|---|---|---|---|---|
| **Example 1: deadline too long under overload** (start 2000 ms) | 2000 → 1800 (healthy (learn)); 1800 → 1620 (recovered); 1620 → 1458 (recovered); 1458 → 1313 (recovered); 1313 → 1182 (recovered); 1182 → 1064 (recovered); 1064 → 958 (recovered); 958 → 863 (recovered); 863 → 777 (recovered); 777 → 700 (recovered); 700 → 630 (recovered); 630 → 567 (recovered); 567 → 511 (recovered); 511 → 460 (recovered); 460 → 441 (recovered) | 441 ms | 0.0% | 0.0% (D=1800 at end of overload) | decreases only, until the overload fires (≥5% expired); after recovery tighten into [D*, 1.25·D*] | **no**: overload never fired; healthy exceedance 0.0% |
| **Example 2: services slowing (inventory STRESSED)** (start 1250 ms) | 1250 → 1125 (healthy (learn)); 1125 → 1428 (inventory stressed, its paths slower) | 1428 ms | 0.0% | n/a | no increase (stress gate); late requests on the stressed path are signalled | **no**: deadline changed 1125 → 1428 during the problem phase |
| **Example 3: deadline too short from day one** (start 300 ms) | none | 300 ms | 25.0% | n/a | raise in a few steps until healthy exceedance ≤1% (300 → 450 → 680) | **no**: healthy exceedance 25.0% |
| **Example 4: queue rate too low, not the deadline** (start 800 ms) | 800 → 720 (healthy (learn)); 720 → 1080 (inventory queue full (pressure 95%, 503s, queue wait 75%)); 1080 → 1620 (inventory queue full (pressure 95%, 503s, queue wait 75%)) | 1620 ms | 0.0% | n/a | no deadline change; advise raising the inventory rate | **no**: deadline changed 720 → 1620 during the problem phase |
| **Example 5: mixed paths (cache 70% / database 30%), database STRESSED** (start 700 ms) | 700 → 638 (healthy (learn)); 638 → 957 (database stressed) | 957 ms | 0.0% | n/a | no change; the healthy time stays near the mixed p99 (not the cache p99) | **no**: deadline changed 638 → 957 during the problem phase |
| **Example 6: an older rejecting middleware cuts requests at the deadline (504)** (start 1000 ms) | 1000 → 900 (healthy (learn)); 900 → 810 (3% cut by 504 at ≈D) | 810 ms | 0.0% | n/a | no change; hold and report the cutting middleware | **no**: deadline changed 900 → 810 during the problem phase |

### Built-in scenarios (old `MockScenarios`)

| Scenario | Changes returned |
|---|---|
| `deadline-increase` | CHAIN_DEADLINE POST /api/orders: 1000 → 1440 (PENDING_APPROVAL);  |
| `deadline-recovery` | CHAIN_DEADLINE POST /api/orders: 1440 → 1296 (PENDING_APPROVAL);  |
| `overloaded` | none |
| `stressed` | none |
| `retry-stressed` | none |
| `recovery` | none |
| `healthy` | none |
| `multiple-chains` | CHAIN_DEADLINE POST /api/orders: 1000 → 1440 (PENDING_APPROVAL); CHAIN_DEADLINE GET /api/inventory: 600 → 715 (PENDING_APPROVAL); CHAIN_DEADLINE POST /api/notifications: 1200 → 1080 (PENDING_APPROVAL);  |

## Summary

- **0 of 6** examples end correctly with the old logic.
- Example 1: the overload never triggers the signal: increases are blocked under overload and nothing lowers the deadline,
  so it stays at 1800 ms while overloaded requests take ~900 ms. After recovery it walks down 14 steps to 441 ms.
- Example 2, 4, 5: a slowdown or a full queue makes the old logic **raise** the deadline (to 1428, 1620 and 957 ms),
  which silences the overload signal and treats a rate problem as a deadline problem.
- Example 3: the baseline needs failure-free windows, so a deadline that is too short from day one is never learned or fixed
  (25% of healthy requests stay over the deadline).
- Example 6: requests cut by another component are not recognised; the old logic keeps changing the deadline.
- Built-in scenarios: `deadline-increase` 1000 → 1440, `deadline-recovery` 1440 → 1296, `multiple-chains` 1440 / 715 / 1080;
  the service scenarios make no deadline change.
