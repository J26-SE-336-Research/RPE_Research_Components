# Rate and retry v2: results (same scenarios as the old-logic baseline)

Recorded on 20 Oct 2026 on branch `c4/rate-retry-v2` (Step 6 code). Compare with
[`rate-retry-v1-baseline.md`](rate-retry-v1-baseline.md): same service model (`ServiceSimulation`), same settings
(window 5 min, cycle 1 min, 6 baseline samples, persistence 2, recovery 3, cooldown 3 min), same scenarios and format.
A temporary JUnit harness (not committed) ran each scenario twice:

- **Reviewer mode** (as in the baseline): MOCK source, a simulated reviewer approves every proposal; it is applied on the
  next cycle.
- **Automatic mode** (the demo setting `BUCKET_RAISE,BUCKET_CUT,RETRY_DECREASE`): live source, no reviewer; only changes
  the guard approves (`c4-auto`) or undoes (`c4-auto-revert`) are applied.

v2 also receives the queue signals the sidecar exports (utilisation and the maximum queue depth of the window). One
scenario was added: **Recovery after an earlier cut** (rate 60, app fine, demand 90).

## Results: v2, reviewer mode

| Scenario | Rate changes (applied in order) | Retry changes | Lowest rate | Final rate | Shed at end (req/s) | Correct outcome | Correct? |
|---|---|---|---|---|---|---|---|
| **Example 1: rate too low (demand grows to 150 above the rate 100)** (rate 100, retry 0.050) | 100 → 107.100 (healthy (learn), demand 80); 107.100 → 160.600 (demand 150 > rate 100, app fine); 160.600 → 176.700 (demand 150 > rate 100, app fine); 176.700 → 194.400 (demand 150 > rate 100, app fine); 194.400 → 204.800 (demand 150 > rate 100, app fine) | none | 100 | 204.800 | 0 | raise the rate (about 150, then 187.5); never cut | **yes**: raised in steps (+50% at most), never cut, nothing shed at the end |
| **Example 1b: rate too low from day one (the bucket sheds from the start)** (rate 100, retry 0.050) | 100 → 150 (demand 150 > rate 100 from the start, app fine); 150 → 165 (demand 150 > rate 100 from the start, app fine); 165 → 181.500 (demand 150 > rate 100 from the start, app fine); 181.500 → 199.700 (demand 150 > rate 100 from the start, app fine); 199.700 → 204.800 (demand 150 > rate 100 from the start, app fine) | none | 100 | 204.800 | 0 | learn the app time despite shedding, then raise | **yes**: learns the app time from the shedding windows, then raises; nothing shed |
| **Example 2: real overload (app capacity drops to 60)** (rate 100, retry 0.050) | 100 → 110 (healthy (learn), demand 90); 110 → 55 (app capacity 60, demand 90); 55 → 82.500 (app capacity 60, demand 90); 82.500 → 50 (app capacity 60, demand 90); 50 → 74.300 (app capacity 60, demand 90); 74.300 → 50 (app capacity 60, demand 90); 50 → 66.900 (app capacity 60, demand 90); 66.900 → 50 (app capacity 60, demand 90); 50 → 60.200 (app capacity 60, demand 90); 60.200 → 50 (app capacity 60, demand 90); 50 → 54.200 (app capacity 60, demand 90); 54.200 → 81.300 (app recovered (capacity 200)); 81.300 → 114.500 (app recovered (capacity 200)); 114.500 → 117 (app recovered (capacity 200)) | 0.050 → 0 (app capacity 60, demand 90); 0.050 → 0 (app capacity 60, demand 90) | 50 | 117 | 0 | cut once to the floor (50), not below; retry 0 while the app is overloaded | **partly**: never below the floor 50, retry 0, recovers; but with a reviewer approving every step it probes up and down 5 times before settling at 54.2 (in automatic mode the probing does not happen, see below) |
| **Example 3: one short burst (2x demand for one cycle)** (rate 120, retry 0.050) | none | none | 120 | 120 | 0 | no change | **yes**: no change |
| **Example 4: repeating bursts (80 / 160, inside every window)** (rate 120, retry 0.050) | 120 → 157.500 (bursts 80 / 160); 157.500 → 169.500 (bursts 80 / 160) | none | 120 | 169.500 | 0 | raise to cover the bursts (about 160 × 1.25 = 200); never cut | **yes**: raised to 169.5 above the 160 bursts, never cut, nothing shed (headroom is over the window-average demand, so it stays below 200) |
| **Retry storm from a too-low rate (shed 503s are retried, 10% succeed)** (rate 100, retry 0.050) | 100 → 107.100 (healthy (learn), demand 80); 107.100 → 160.600 (demand 130 > rate 100, every shed request retried once); 160.600 → 174 (demand 130 > rate 100, every shed request retried once); 174 → 177.500 (demand 130 > rate 100, every shed request retried once) | none | 100 | 177.500 | 0 | raise the rate; hold the retry budget (do not punish retries for our own shedding) | **yes**: raises the rate, holds the retry budget, nothing shed |
| **Recovery after an earlier cut (rate 60, the app is fine again, demand 90)** (rate 60, retry 0.020) | 60 → 65 (healthy (learn), demand 50); 65 → 97.500 (demand 90 > rate 60, app recovered); 97.500 → 107.300 (demand 90 > rate 60, app recovered); 107.300 → 118 (demand 90 > rate 60, app recovered); 118 → 119.300 (demand 90 > rate 60, app recovered) | none | 60 | 119.300 | 0 | raise back in steps of at most +50% to demand × 1.25; never cut | **yes**: raised back in steps, never cut, nothing shed |

## Results: v2, automatic mode

| Scenario | Rate changes (applied in order, reviewer) | Retry changes | Lowest rate | Final rate | Shed at end (req/s) | Correct outcome | Correct? |
|---|---|---|---|---|---|---|---|
| **Example 1: rate too low (demand grows to 150 above the rate 100)** (rate 100, retry 0.050) | 100 → 107.100 (healthy (learn), demand 80, c4-auto); 107.100 → 160.600 (demand 150 > rate 100, app fine, c4-auto); 160.600 → 176.700 (demand 150 > rate 100, app fine, c4-auto); 176.700 → 194.400 (demand 150 > rate 100, app fine, c4-auto); 194.400 → 196.900 (demand 150 > rate 100, app fine, c4-auto); 196.900 → 200.800 (demand 150 > rate 100, app fine, c4-auto) | none | 100 | 200.800 | 0 | raise the rate (about 150, then 187.5); never cut | **yes**: raised in steps (+50% at most), never cut, nothing shed at the end |
| **Example 1b: rate too low from day one (the bucket sheds from the start)** (rate 100, retry 0.050) | 100 → 150 (demand 150 > rate 100 from the start, app fine, c4-auto); 150 → 165 (demand 150 > rate 100 from the start, app fine, c4-auto); 165 → 181.500 (demand 150 > rate 100 from the start, app fine, c4-auto); 181.500 → 199.700 (demand 150 > rate 100 from the start, app fine, c4-auto); 199.700 → 200.800 (demand 150 > rate 100 from the start, app fine, c4-auto); 200.800 → 204.800 (demand 150 > rate 100 from the start, app fine, c4-auto) | none | 100 | 204.800 | 0 | learn the app time despite shedding, then raise | **yes**: learns the app time from the shedding windows, then raises; nothing shed |
| **Example 2: real overload (app capacity drops to 60)** (rate 100, retry 0.050) | 100 → 110 (healthy (learn), demand 90, c4-auto); 110 → 100 (app capacity 60, demand 90, c4-auto-revert); 100 → 80 (app capacity 60, demand 90, c4-auto); 80 → 64 (app capacity 60, demand 90, c4-auto); 64 → 51.200 (app capacity 60, demand 90, c4-auto); 51.200 → 57.600 (app capacity 60, demand 90, c4-auto); 57.600 → 86.400 (app recovered (capacity 200), c4-auto); 86.400 → 110.300 (app recovered (capacity 200), c4-auto); 110.300 → 112.500 (app recovered (capacity 200), c4-auto); 112.500 → 114.800 (app recovered (capacity 200), c4-auto) | 0.050 → 0 (app capacity 60, demand 90, c4-auto) | 51.200 | 114.800 | 0 | cut once to the floor (50), not below; retry 0 while the app is overloaded | **yes**: cut in checked −20% steps, never below the floor 50, settles at 57.6 under the app capacity 60, retry 0, rises again after recovery |
| **Example 3: one short burst (2x demand for one cycle)** (rate 120, retry 0.050) | none | none | 120 | 120 | 0 | no change | **yes**: no change |
| **Example 4: repeating bursts (80 / 160, inside every window)** (rate 120, retry 0.050) | 120 → 157.500 (bursts 80 / 160, c4-auto); 157.500 → 169.500 (bursts 80 / 160, c4-auto) | none | 120 | 169.500 | 0 | raise to cover the bursts (about 160 × 1.25 = 200); never cut | **yes**: raised to 169.5 above the 160 bursts, never cut, nothing shed (headroom is over the window-average demand, so it stays below 200) |
| **Retry storm from a too-low rate (shed 503s are retried, 10% succeed)** (rate 100, retry 0.050) | 100 → 107.100 (healthy (learn), demand 80, c4-auto); 107.100 → 160.600 (demand 130 > rate 100, every shed request retried once, c4-auto); 160.600 → 176.700 (demand 130 > rate 100, every shed request retried once, c4-auto); 176.700 → 177.500 (demand 130 > rate 100, every shed request retried once, c4-auto) | none | 100 | 177.500 | 0 | raise the rate; hold the retry budget (do not punish retries for our own shedding) | **yes**: raises the rate, holds the retry budget, nothing shed |
| **Recovery after an earlier cut (rate 60, the app is fine again, demand 90)** (rate 60, retry 0.020) | 60 → 65 (healthy (learn), demand 50, c4-auto); 65 → 97.500 (demand 90 > rate 60, app recovered, c4-auto); 97.500 → 107.300 (demand 90 > rate 60, app recovered, c4-auto); 107.300 → 118 (demand 90 > rate 60, app recovered, c4-auto) | none | 60 | 118 | 0 | raise back in steps of at most +50% to demand × 1.25; never cut | **yes**: raised back in steps, never cut, nothing shed |

In automatic mode the first healthy raise of example 2 (100 → 110) is undone automatically when the app slows down
(its capacity dropped right after); no scenario reached the pause limit (2 undos or 3 direction changes in 30 min).
In reviewer mode, example 2 lists "0.050 → 0" twice (recorded before the fix of 21 Oct): the rate cut and the retry change were applied in the same cycle;
the rate change created a new policy version first, which superseded the approved retry change, so it was proposed again. Now the approved
retry change moves to the new version and is applied once (RATE_RETRY_V2_MIGRATION.md §8).

## Old logic vs v2

| Scenario | Old logic | v2 (reviewer) | v2 (automatic) |
|---|---|---|---|
| 1 Rate too low | ratchets down to 1.1 req/s, shed 148.9 | 204.8, shed 0 | 200.8, shed 0 |
| 1b Rate too low from day one | never learns, stays 100, shed 50 | 204.8, shed 0 | 204.8, shed 0 |
| 2 Real overload | ratchets to about 0 req/s (0.045 before rounding), shed 90, retry 0 | lowest 50 (floor), probes, 54.2 → 117 after recovery | lowest 51.2, settles 57.6, 114.8 after recovery |
| 3 One short burst | ratchets to 7.4 req/s, shed 72.6 | no change | no change |
| 4 Repeating bursts | ratchets to 1.1 req/s, shed 118.9 | 169.5, shed 0 | 169.5, shed 0 |
| Retry storm | ratchets down to 1.1 req/s, retry 0, shed 128.9 | 177.5, retry held at 0.05, shed 0 | 177.5, retry held, shed 0 |
| **Correct** | **0 of 6** | **5 of 6** (example 2 partly) | **6 of 6** |

## Summary

- **No ratchet.** The queue wait is taken out of p95 (Wq = max depth / rate), so a full queue with a normal app is
  RATE_TOO_LOW (raise), not overload. Cuts have a floor (0.5 × good rate) and a second cut needs the first to have helped.
- **Day one.** With no clean window, P0 is learned from shedding windows (allow-shed-windows, or the lowest app time after
  6 shedding windows), so example 1b is fixed.
- **Bursts.** A single burst changes nothing; repeating bursts are covered with headroom over the largest window demand.
- **Retries.** Retry 0 only for real app overload; our own shedding holds the budget (retry storm).
- **Real overload with a reviewer approving everything** still probes up and down before settling, because each raise
  is a reasonable proposal on its own; a human would stop approving. In automatic mode cuts go in checked −20% steps under
  the ceiling learned from earlier cuts, so it settles without probing; the undo/flip pause is the safety net (not reached).
- Final rates above 187.5 in example 1 come from the 5% retry allowance in demand (D × (1 + retry ratio)) and the ±4% model
  jitter: 150 × 1.04 × 1.05 × 1.25 ≈ 204.8.
