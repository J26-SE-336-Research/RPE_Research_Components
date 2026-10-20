# Old rate and retry logic: baseline results (before rate/retry v2)

Recorded on 20 Oct 2026 on branch `c4/rate-retry-v2` at `a49f09f`, **before any code change**: the old `AdaptiveEngine.bucket()`
and `retry()` (identical to `c4/deadline-v2` and `main`). Compare with [`rate-retry-v2-results.md`](rate-retry-v2-results.md),
which uses the same service model, scenarios and format.

## How the scenarios were run

- A temporary JUnit harness (not committed) fed the old `AdaptiveEngine.service(...)` one service window per cycle on a virtual
  clock: window 5 min, cycle 1 min, 6 baseline samples, persistence 2, recovery 3, cooldown 3 min. The service has a C3
  policy (current rate and retry ratio). A simulated reviewer approves every proposal and it is applied on the next cycle.
- **Service model** (deterministic, ±4% jitter per cycle so baselines have spread): the leaky bucket admits `min(demand, rate)`
  requests/s and sheds the rest with 503 (queue full: utilisation 100%, depth 200). The app completes `min(admitted, capacity)`;
  above its capacity the app time grows and the excess fails. Measured p95 = app time + queue wait (`depth / rate`), as the
  sidecar measures it (the latency timer starts before the queue). Retry attempts = retries per shed or failed request.
- The old logic sees only the aggregate service window (requests, p95, errors, retries, rejections, throughput); it has no
  queue depth or utilisation input.

| Scenario | Model |
|---|---|
| 1 Rate too low | 16 min demand 80 (learn), then demand 150 with rate 100; app capacity 1000, app time 40 ms |
| 1b Rate too low from day one | demand 150 with rate 100 from the start |
| 2 Real overload | 16 min demand 90 (capacity 200), then app capacity drops to 60 for 60 min, then back to 200 for 40 min |
| 3 One short burst | rate 120, demand 80, one cycle at 160, then 80 |
| 4 Repeating bursts | rate 120, demand alternating 80 / 160 every cycle |
| Retry storm | rate 100, demand 130; every shed request is retried once, 10% of retries succeed |

## Results: old logic

| Scenario | Rate changes (applied in order) | Retry changes | Lowest rate | Final rate | Shed at end (req/s) | Correct outcome | Correct? |
|---|---|---|---|---|---|---|---|
| **Example 1: rate too low (demand grows to 150 above the rate 100)** (rate 100, retry 0.050) | 100 → 79.200 (healthy (learn), demand 80); 79.200 → 59.400 (demand 150 > rate 100, app fine); 59.400 → 43.659 (demand 150 > rate 100, app fine); 43.659 → 31.434 (demand 150 > rate 100, app fine); 31.434 → 24.519 (demand 150 > rate 100, app fine); 24.519 → 18.757 (demand 150 > rate 100, app fine); 18.757 → 14.068 (demand 150 > rate 100, app fine); 14.068 → 10.340 (demand 150 > rate 100, app fine); 10.340 → 7.445 (demand 150 > rate 100, app fine); 7.445 → 5.807 (demand 150 > rate 100, app fine); 5.807 → 4.442 (demand 150 > rate 100, app fine); 4.442 → 3.332 (demand 150 > rate 100, app fine); 3.332 → 2.449 (demand 150 > rate 100, app fine); 2.449 → 1.763 (demand 150 > rate 100, app fine); 1.763 → 1.375 (demand 150 > rate 100, app fine); 1.375 → 1.052 (demand 150 > rate 100, app fine) | 0.050 → 0 (healthy (learn), demand 80) | 1.100 | 1.100 | 148.900 | raise the rate (about 150, then 187.5); never cut | **no**: cut 16 times to 1.1 req/s, 148.9 req/s of healthy demand shed |
| **Example 1b: rate too low from day one (the bucket sheds from the start)** (rate 100, retry 0.050) | none | none | 100 | 100 | 50 | learn the app time despite shedding, then raise | **no**: never learns (no clean window), keeps shedding 50 req/s |
| **Example 2: real overload (app capacity drops to 60)** (rate 100, retry 0.050) | 100 → 89.100 (healthy (learn), demand 90); 89.100 → 45 (app capacity 60, demand 90); 45 → 33.075 (app capacity 60, demand 90); 33.075 → 23.814 (app capacity 60, demand 90); 23.814 → 18.575 (app capacity 60, demand 90); 18.575 → 14.210 (app capacity 60, demand 90); 14.210 → 10.657 (app capacity 60, demand 90); 10.657 → 7.833 (app capacity 60, demand 90); 7.833 → 5.640 (app capacity 60, demand 90); 5.640 → 4.399 (app capacity 60, demand 90); 4.399 → 3.365 (app capacity 60, demand 90); 3.365 → 2.524 (app capacity 60, demand 90); 2.524 → 1.855 (app capacity 60, demand 90); 1.855 → 1.336 (app capacity 60, demand 90); 1.336 → 1.042 (app capacity 60, demand 90); 1.042 → 0.797 (app capacity 60, demand 90); 0.797 → 0.598 (app recovered (capacity 200)); 0.598 → 0.439 (app recovered (capacity 200)); 0.439 → 0.316 (app recovered (capacity 200)); 0.316 → 0.247 (app recovered (capacity 200)); 0.247 → 0.189 (app recovered (capacity 200)); 0.189 → 0.142 (app recovered (capacity 200)); 0.142 → 0.104 (app recovered (capacity 200)); 0.104 → 0.075 (app recovered (capacity 200)); 0.075 → 0.058 (app recovered (capacity 200)); 0.058 → 0.045 (app recovered (capacity 200)) | 0.050 → 0 (healthy (learn), demand 90) | 0 | 0 | 90 | cut once to the floor (50), not below; retry 0 while the app is overloaded | **no**: ratchets down to 0.045 req/s and keeps cutting after the app recovered |
| **Example 3: one short burst (2x demand for one cycle)** (rate 120, retry 0.050) | 120 → 79.200 (healthy (learn), demand 80); 79.200 → 59.400 (back to 80); 59.400 → 43.659 (back to 80); 43.659 → 31.434 (back to 80); 31.434 → 24.519 (back to 80); 24.519 → 18.757 (back to 80); 18.757 → 14.068 (back to 80); 14.068 → 10.340 (back to 80); 10.340 → 7.445 (back to 80) | 0.050 → 0 (healthy (learn), demand 80) | 7.400 | 7.400 | 72.600 | no change | **no**: one burst starts a ratchet to 7.4 req/s |
| **Example 4: repeating bursts (80 / 160 every other cycle)** (rate 120, retry 0.050) | 120 → 79.200 (healthy (learn), demand 80); 79.200 → 59.400 (bursts 80 / 160); 59.400 → 43.659 (bursts 80 / 160); 43.659 → 31.434 (bursts 80 / 160); 31.434 → 24.519 (bursts 80 / 160); 24.519 → 18.757 (bursts 80 / 160); 18.757 → 14.068 (bursts 80 / 160); 14.068 → 10.340 (bursts 80 / 160); 10.340 → 7.445 (bursts 80 / 160); 7.445 → 5.807 (bursts 80 / 160); 5.807 → 4.442 (bursts 80 / 160); 4.442 → 3.332 (bursts 80 / 160); 3.332 → 2.449 (bursts 80 / 160); 2.449 → 1.763 (bursts 80 / 160); 1.763 → 1.375 (bursts 80 / 160); 1.375 → 1.052 (bursts 80 / 160) | 0.050 → 0 (healthy (learn), demand 80) | 1.100 | 1.100 | 158.900 | raise to cover the bursts (about 160 × 1.25 = 200); never cut | **no**: ratchets to 1.1 req/s, the bursts are shed |
| **Retry storm from a too-low rate (shed 503s are retried, 10% succeed)** (rate 100, retry 0.050) | 100 → 79.200 (healthy (learn), demand 80); 79.200 → 59.400 (demand 130 > rate 100, every shed request retried once); 59.400 → 43.659 (demand 130 > rate 100, every shed request retried once); 43.659 → 31.434 (demand 130 > rate 100, every shed request retried once); 31.434 → 24.519 (demand 130 > rate 100, every shed request retried once); 24.519 → 18.757 (demand 130 > rate 100, every shed request retried once); 18.757 → 14.068 (demand 130 > rate 100, every shed request retried once); 14.068 → 10.340 (demand 130 > rate 100, every shed request retried once); 10.340 → 7.445 (demand 130 > rate 100, every shed request retried once); 7.445 → 5.807 (demand 130 > rate 100, every shed request retried once); 5.807 → 4.442 (demand 130 > rate 100, every shed request retried once); 4.442 → 3.332 (demand 130 > rate 100, every shed request retried once); 3.332 → 2.449 (demand 130 > rate 100, every shed request retried once); 2.449 → 1.763 (demand 130 > rate 100, every shed request retried once); 1.763 → 1.375 (demand 130 > rate 100, every shed request retried once); 1.375 → 1.052 (demand 130 > rate 100, every shed request retried once) | 0.050 → 0 (healthy (learn), demand 80) | 1.100 | 1.100 | 128.900 | raise the rate; hold the retry budget (do not punish retries for our own shedding) | **no**: cuts the rate (to 1.1) and disables retries for our own shedding |

## Summary

**0 of 6 scenarios end correctly.**
- **The downward ratchet (E1, E2):** every cut makes the next cut lower (×0.75 or ×0.90 each cycle, no floor). A too-low rate fills the bucket's own queue; the queue wait (in p95) and the 503s look like overload, so the rate is cut again and again, down to about 1 req/s.
- **Recovery capped at the baseline (E3):** the "healthy" rule caps the rate at the learned throughput (79.2 < demand 80). A small shortfall then starts the same ratchet, even after a single short burst.
- **No learning from day one (E4):** a bucket that sheds from the start never produces a clean window, so nothing is learned or fixed.
- **Retries:** the budget is set to 0 as soon as the service looks overloaded, even when the "overload" is our own shedding.
