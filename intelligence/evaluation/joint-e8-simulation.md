# Experiment E8 (simulation): does the joint healthy recalibration keep the deadline calibrated better?

Recorded on 21 Oct 2026 on branch `c4/joint-recalibration`. C3's experiment E8 (request section 8), run as a
**simulation** on C4's real engine; the live run with C3's `experiments/c3/e8-healthy-recalibration.sh` comes later (it
waits for C3's fix, see `JOINT_RECALIBRATION_MIGRATION.md` D5). Reproduce: `JointE8Tests` (with `-Djoint.e8.out=<file>`
it writes the table below).

## Set-up

- `JointSimulation`: one chain (gateway → payment, 300 requests per window) analysed per request by deadline v2, and
  payment's service window with its leaky-bucket queue, in an isolated engine on a virtual clock. Window 5 min, cycle
  1 min, 6 baseline samples; deadline v2 demo settings (3 learning windows, persistence 2).
- Payment and the chain are learned first (healthy, deadline in its band). At t0 payment's healthy rate changes for good
  (the operator changes its healthy target). From then on the chain's healthy requests are faster or slower by the change
  of the queue wait `Q / r` (C3 section 2).
- **(a) rate alone:** the rate change is approved and applied; the deadline is left to deadline v2 (a simulated reviewer
  approves every later recommendation). **(b) joint:** the joint card (rate + deadline) is approved and applied at t0.
- 15 minutes after the change. Case 2 injects an overload of payment from minute 3 (requests 300 ms slower, payment
  OVERLOADED).
- Measures: healthy false-signal rate (healthy requests at or above the deadline, `cs=expired`); minutes from the start of
  the overload to the first window where at least 5% of requests are signalled; healthy minutes with the deadline outside
  C4's healthy band `[D*, 1.25 × D*]` of the true new healthy time; reviewer decisions (a joint card is one decision);
  payment completion (completed / offered requests).

## Results

| Case | Arm | Healthy false-signal rate | First cancel signal after the overload | Minutes outside the healthy band | Reviewer decisions | Payment completion | Final deadline | Final rate |
|---|---|---|---|---|---|---|---|---|
| 1: capacity drop 100 → 60/s, Q = 8 | (a) rate alone | 0.0% | n/a | 15 of 15 | 1 | 100% | 780 ms | 60/s |
| 1: capacity drop 100 → 60/s, Q = 8 | (b) joint | 0.0% | n/a | 0 of 15 | 1 | 100% | 850 ms | 60/s |
| 1b: capacity drop 100 → 60/s, bursty healthy traffic, Q = 24 | (a) rate alone | 1.0% | n/a | 15 of 15 | 2 | 100% | 820 ms | 60/s |
| 1b: capacity drop 100 → 60/s, bursty healthy traffic, Q = 24 | (b) joint | 0.0% | n/a | 0 of 15 | 1 | 100% | 980 ms | 60/s |
| 2: recovery 50 → 100/s, Q = 8, overload (+300 ms) from minute 3 | (a) rate alone | 0.0% | none within 12 min | 0 of 2 | 2 | 100% | 875 ms | 50/s |
| 2: recovery 50 → 100/s, Q = 8, overload (+300 ms) from minute 3 | (b) joint | 0.0% | 0 min | 0 of 2 | 2 | 100% | 780 ms | 50/s |

## What it shows

- **Case 1 (capacity drop, C3 example 2):** with the rate alone the deadline stays at 780 ms, below the new healthy band
  (`D*` = 850 ms), for all 15 minutes; deadline v2 does not correct it because no healthy request exceeds 780 ms yet
  (no Case A). The joint card moves it to 850 ms at once. With Q = 8 there are no false signals in either arm.
- **Case 1b (bursty healthy traffic, Q = 24):** here the old deadline flags healthy requests (1.0% false signals: up to
  C4's target of 1%, which it reaches); deadline v2 raises it later to 820 ms, still below the band, with a second reviewer decision. The
  joint card (780 → 980 ms) gives 0% false signals with one decision.
- **Case 2 (recovery, C3 example 1, overload at minute 3):** with the rate alone the deadline stays at 875 ms (still inside
  C4's band, so 0 minutes outside), and the overload is **not signalled within 12 minutes**: the overloaded requests
  (≈ 920 ms at the slowest) stay below 875 ms except the slowest 3%. With the joint card (780 ms) the overload is signalled
  in its first minute.
- **Payment completion is unchanged (100%)** in every case and arm. In case 2 the final rate is 50/s in both arms because
  the overload cut of rate v2 was approved in both.
- **Accept criteria (C3 section 8):** lower false-signal rate in case 1: yes with bursty traffic (1b), equal (0%) with
  Q = 8; earlier overload signal in case 2: yes; payment completion unchanged: yes.

## Limits

- A simulation of one chain and one service with C3's example numbers; the size of the effect depends on the healthy queue
  `Q` and on how far the healthy time moves compared with the deadline's margin (C3 section 9).
- In the demo cluster the healthy queue is about 0, so a joint card is not expected from live traffic; the demo scenarios
  `joint-recovery` and `joint-capacity-drop` show it.
