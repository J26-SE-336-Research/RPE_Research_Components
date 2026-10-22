# C3 request to C4: the joint "healthy recalibration" of rate and chain deadline

**From:** Kalhara D.M.R (C3, Kubernetes Enforcement Layer). **To:** Rathnayake (C4, Intelligence Layer), copy to Pinto (C2). **Date:** 19 Oct 2026.
**Status:** agreed in principle by C4 and C2. This document is the build guide. It is written against C4's branch `c4/deadline-v2`.

## 1. What is agreed

- **Joint only for a healthy recalibration.** When the **normal** (healthy) rate of a service on a chain changes for good, C4 recommends the matching chain deadline **in the same decision**. "For good" means:
  - the healthy recovery reaches the service's `healthyBucketTarget`;
  - or the `healthyBucketTarget` itself changes.
- **Never under overload.** During overload the rate and the deadline stay separate levers: lever diagnosis, cross-lever lock, and C2's signal-never-reject. Nothing in this document changes that.
- **Single recommendations stay exactly as today:** a rate alone, a deadline alone, through the current bridge.

## 2. Why the two belong together

C4 v2 sets the deadline from the **healthy time** of the chain: `D* = ceil10(T_h × (1 + m))` (`DeadlineMath.target`), where `T_h` is the pooled p99 of healthy requests (`HealthyLatencyModel`).
- A healthy request's time **includes its wait in each leaky-bucket queue** on the path: C2's proxy starts the deadline timer before the request is queued.
- That wait depends on the service's rate: with a healthy queue of `Q` requests released at rate `r`, the wait is about `Q / r`.

So **`D*` depends on the healthy rates of the services on the chain.** When a healthy rate changes for good, the correct deadline changes with it.

**Everyday example.** A checkout where customers normally wait 5 minutes, with the rule "more than 7 minutes means trouble". If a second cashier opens, the normal wait drops to 2.5 minutes, and the rule should drop to about 4 minutes in the same moment. Otherwise trouble is noticed late.

**What goes wrong today** (rate alone, deadline corrected later by relearning). The deadline stays calibrated for the old rate until C4 relearns `T_h`: at least `persistenceWindows` = 3 disjoint windows of 5 minutes, then the 3-minute cooldown, then a separate approval.
- **Healthy rate up:** the deadline is too long. If overload returns in that period, the cancel signal (the overload alarm) fires late, and non-critical work is shed late.
- **Healthy rate down:** the deadline is too short. **Healthy** requests set off the alarm, so useful non-critical work is skipped for nothing (false signals; C4's target is `eTarget` = 1 %).

## 3. When C4 makes a joint card (trigger rules)

All of these must hold:
1. **The chain is healthy:**
   - the overloaded and stressed request shares are below `shareThreshold`;
   - the lever diagnosis is not RATE or CAPACITY;
   - no guard is active: `PAUSED`, `MIXED_BUDGET`, `COOLDOWN`, a pending proposal for the chain.
2. **The rate proposal is a healthy-regime change** for a service on the chain's path: the healthy-recovery step of `AdaptiveEngine.bucket` that **reaches** `healthyBucketTarget`, or a change of `healthyBucketTarget`. Protective cuts under overload never trigger it.
3. **The predicted deadline change is large enough to matter:** `|D*' − D| ≥ max(0.05 × D, 10 ms)` after `ceil10`.

If 1 or 2 fails, nothing changes compared with today. If only 3 fails, the rate goes **alone**, as today.

## 4. How C4 computes the deadline part

| Step | Formula | Source |
|---|---|---|
| Healthy queue depth of service i | `Q_i` = median over the **healthy** learning windows of `max_over_time(rpe_backpressure_inbound_queue_depth{service="<i>",namespace="<ns>"}[<window>])` | sidecar metric (C2), new query in `PrometheusWindows` |
| Healthy wait at a rate r | `W_i(r) = Q_i / r` | |
| Change of wait | `ΔW_i = W_i(r_new) − W_i(r_old)` | |
| New healthy time | `T_h' = T_h + Σ ΔW_i` over the changed services on the chain's slowest path | `HealthyModel.thMs` |
| New deadline | `D*' = DeadlineMath.target(T_h', m, minimumDeadlineMs, maximumDeadlineMs)` with the current margin `m` | `HealthyModel.margin`, `DeadlineMath.target` |
| Step limits | clamp `D*'` to the existing step rules: not above `ceil10(stepUp × D)`, not below `ceil10(stepDownHealthy × D)`, and within `maximumDeadlineChange` | `DeadlineSettings`, `Settings` |

**Example 1, recovery.** payment-svc has a healthy queue `Q` = 8. Its healthy-recovery step reaches the target: 50 → 100/s. The chain has `T_h` = 700 ms, margin `m` = 0.25, deadline `D` = 875 ms.
- `ΔW` = 8/100 − 8/50 = 0.08 − 0.16 s = **−80 ms**, so `T_h'` = 620 ms.
- `D*'` = ceil10(620 × 1.25) = **780 ms**.
- `|780 − 875|` = 95 ms ≥ 5 % of 875 (44 ms), so a **joint card**: payment 50 → 100/s **and** deadline 875 → 780 ms.

**Example 2, healthy capacity drop.** payment's `healthyBucketTarget` goes 100 → 60/s, starting from `T_h` = 620 ms, `D` = 780 ms.
- `ΔW` = 8/60 − 8/100 = 0.133 − 0.080 s = **+53 ms**, so `T_h'` = 673 ms.
- `D*'` = ceil10(673 × 1.25) = **850 ms**.
- `|850 − 780|` = 70 ms ≥ 39 ms, so a **joint card**: payment 100 → 60/s **and** deadline 780 → 850 ms.

**Example 3, no queue.** With an empty healthy queue (`Q` ≈ 0), `ΔW` ≈ 0. The threshold is not met, so the rate goes alone.

## 5. What C4 builds

| # | What | Where | Effort |
|---|---|---|---|
| J1 | `JointProposal` (several proposals, one decision): one card in Recommendations, one approval or rejection for all parts | `AdaptiveModels`, `PolicyWorkflow`, Recommendations page | 2 d |
| J2 | Trigger (section 3) and calculation (section 4) in a small helper `HealthyRecalibration`, called where `bucket()` produces a healthy-regime proposal; add the queue-depth query. Keep a **stable healthy target** per service that `sync` does not overwrite (section 5A, point 1) | `AdaptiveEngine`, new `HealthyRecalibration`, `PrometheusWindows`, `Component3Bridge.sync` | 2 d |
| J3 | **Lock:** the joint card **replaces** the rate proposal `bucket()` would have offered, and counts as pending for the chain **and** for each service in it. While it is pending, the normal deadline rule holds (`PENDING_PROPOSAL`, `PENDING_RATE_PROPOSAL`) and `bucket()` offers nothing else for those services; after APPLIED, the usual cooldown | `PolicyWorkflow`, `AdaptiveEngine.bucket` (no change to the `DeadlinePolicy` table) | 0.5 d |
| J4 | The bridge sends a joint proposal as **one** policy with `parts` (section 6.3) and starts one two-phase rollout. The gateway part holds the recalculated route deadline of **every** chain whose path includes the changed service | `Component3Bridge.deliver` | 0.5 d |
| J5 | After APPLIED: register the joint's deadline change like any applied deadline proposal, with its own case (for example `RECALIBRATE`), so SETTLING, `DeadlineVerifier` and the `DeadlineSafety` reversal rule apply. **Restart learning at the epoch boundary** (recommended over shifting `T_h` by hand), so drift detection does not propose the same correction again. Verify by comparing requests by `rpe.policy_epoch` (old vs new) | `AdaptiveEngine` (settling), `HealthyLatencyModel`, `DeadlineVerifier`, `DeadlineSafety` | 1 d |
| J6 | Evidence on the card: `Q_i`, `r_old`, `r_new`, `W` old and new, `T_h`, `T_h'`, `m`, `D`, `D*'`, the threshold check | `DeadlineEvidence` (additive) | 0.5 d |

About 6.5 days. Human approval is unchanged: every card, single or joint, is approved by a person.

## 5A. Compatibility with C4 v2 (checked on `c4/deadline-v2`)

The joint card does **not** change C4's existing logic. Single rate and single deadline recommendations keep working exactly as now.

| C4 logic | Why the joint does not affect it |
|---|---|
| Lever diagnosis, Case B (lower under overload), Case C (keep firing) | A joint card is only made when the chain is healthy and the lever is not RATE or CAPACITY |
| Rate rule under overload (factor 0.75 or 0.90) | Protective cuts never trigger a joint card; they go alone, as today |
| Cases A and D (raise or tighten in the healthy band) | Still run on their own; after a joint APPLIED the usual cooldown applies, then they see a deadline that already matches |
| Guards `MIXED_BUDGET`, `COOLDOWN`, `CENSORED_OTHER`, `LEARNING` | Unchanged; a window that contains the joint switch is held as a mixed budget, as for any deadline change |
| Configuration-conflict pause (`AdaptiveEngine.configurationConflict`) | No conflict: after APPLIED, `Component3Bridge.sync` reads the gateway's effective spec (the joint version's gateway part) and updates the chain's deadline reference |
| Bridge path for single proposals (`Component3Bridge.deliver`, `confirm`) | Unchanged |

**Four integration points the build must handle** (already included in J2 to J5):

1. **A stable healthy target.**
   - `Component3Bridge.sync` sets `new Policy(service, epoch, validUntil, rate, rate, ratio, null)`, so in live mode `healthyBucketTarget` equals **the current rate** after every poll.
   - The trigger "the healthy recovery reaches `healthyBucketTarget`" needs a target that `sync` does not overwrite: for example the rate before the first protective cut, or a configured value.
   - This also matters for the existing healthy-recovery rule, which caps at that target.
2. **One pending proposal, not two.**
   - The lock checks `workflow.pending(service, LEAKY_BUCKET)` for the services on the chain.
   - The joint card must replace the rate proposal of `bucket()`, and count as pending for the chain and for each service in it.
   - Otherwise a second, separate rate proposal could appear for the same service while the joint card waits.
3. **Settling, verification and safety see the change.**
   - The joint's deadline part is registered like any applied deadline proposal: `lastProposalId`, its own case `RECALIBRATE`, SETTLING, `DeadlineVerifier`.
   - It counts in `DeadlineSafety.applied(from, to)`, so two reversals within an hour still pause the chain.
4. **Services shared by several chains.**
   - A healthy-rate change shifts the healthy time of every chain through that service.
   - The gateway part carries the recalculated deadline of each such route; otherwise the other chains are corrected later by relearning, as today.

## 6. The exact API requests

All calls go to C3's policy API (`rrpe.connections.component3-url`). First read the current settings, as the bridge already does:

```http
GET /api/k8s/effective
```

Per service it answers `policyId`, `epoch`, `route`, `spec`. Always build the new spec from the current one, changing only your value.

### 6.1 Single rate (unchanged)

```http
POST /api/k8s/policies
{ "policyId": "<effective policyId of payment-svc>", "source": "c4-recommendation", "recommendationId": "<proposal id>",
  "scope": { "service": "payment-svc" },
  "spec": { "...": "payment's current spec", "leakyBucket": { "ratePerSec": 100 } },
  "approval": { "approvedBy": "<reviewer>", "decision": "approved" } }

POST /api/k8s/rollouts
{ "policyId": "<same>", "version": <version from the answer>, "mode": "two-phase", "requestedBy": "<reviewer>" }
```

### 6.2 Single deadline (unchanged)

The same two calls, with scope `{ "service": "gateway", "route": "GET /api/orders" }` and the change `routes["GET /api/orders"].deadlineMs`.

### 6.3 Joint healthy recalibration (new), example 1

```http
POST /api/k8s/policies
{ "policyId": "<the gateway's effective policyId>", "source": "c4-recommendation", "recommendationId": "<joint proposal id>",
  "approval": { "approvedBy": "<reviewer>", "decision": "approved" },
  "parts": [
    { "scope": { "service": "gateway", "route": "GET /api/orders" },
      "spec": { "...": "the gateway's current spec", "routes": { "GET /api/orders": { "deadlineMs": 780 } } } },
    { "scope": { "service": "payment-svc" },
      "spec": { "...": "payment's current spec", "leakyBucket": { "ratePerSec": 100 } } } ] }

POST /api/k8s/rollouts
{ "policyId": "<same>", "version": <version from the answer>, "mode": "two-phase", "requestedBy": "<reviewer>" }
```

- The answer has **one** `version` and **one** `epoch` for both values.
- `recommendationId` makes the call idempotent: sending it twice creates one version.

### 6.4 Confirm

`GET /api/k8s/rollouts/<name>`. When `status.phase` is `Complete`, mark the whole joint proposal APPLIED with `spec.epoch`. `status.plan` shows the order C3 used; `status.mixedPolicyRequests` should be 0.

### 6.5 Later single changes

After the first joint card, the gateway and payment-svc are governed by the joint policy, and `GET /api/k8s/effective` returns its `policyId` for both. Keep sending single proposals with that id, exactly as in 6.1 and 6.2:
- C3 merges each one into a new version of the joint policy and keeps the other part;
- an unchanged part never makes the change wait.

The same holds for a later joint card: **list only the parts that change.** C3 keeps every part the card does not name. Example: the policy holds the gateway (1000 ms) and payment-svc (100/s), and a joint card names the gateway (1200 ms) and order-svc (200/s). The new version then holds the gateway at 1200 ms, payment-svc at 100/s (kept) and order-svc at 200/s. payment-svc moves to the new version with unchanged content, so it does not count requests of the new version as mixed-policy requests, and drift repair keeps covering it.

## 7. What C3 does with a joint card (already built)

1. **One version and one epoch;** each sidecar receives only its part.
2. **Stage and verify:** both sidecars hold their part, checked by hash and acknowledged, before anything switches. If one cannot, nothing switches.
3. **Order from the change:**
   - **recovery** (example 1): the rate goes up first (looser), then the deadline goes down (stricter), after no request of the old version is in flight;
   - **capacity drop** (example 2): the deadline goes up first, then the rate goes down after the drain.

   So no request is judged by the shorter deadline while it waits in the slower queue.
4. **Retire** the old version; the effective policy of both services points to the new version; C4 sees `Complete`.
5. **Every request carries the epoch** (`pe`), and spans are tagged `rpe.policy_epoch`, for C4's verification (J5).

C3 will also finish one fix before the first real joint card: a joint version that takes over services which had separate policies before must be planned against what each service enforces at that moment.

## 8. Experiment E8: does the joint recalibration improve resilience?

**Question:** after a persistent healthy-rate change, does sending the matching deadline in the same decision keep the overload alarm calibrated better than rate-alone with later relearning?

**Scenario:**
- bursty **healthy** traffic, so payment has a noticeable healthy queue (several requests);
- a persistent healthy-rate change in two cases:
  - **Case 1:** capacity drop 100 → 60/s (the deadline should go up);
  - **Case 2:** recovery 50 → 100/s (the deadline should go down).

**Arms, 5 runs each:**
- **(a) rate alone:** today's behaviour; C4 relearns and proposes the deadline later;
- **(b) joint:** the rate and the recalculated deadline in one decision.

**Measures:**

| Measure | Case | Expected for (b) |
|---|---|---|
| Healthy false-signal rate in the 15 minutes after the change (healthy requests with `cs=expired`) | 1 | lower |
| Time from an overload injected 3 minutes after the change to the first cancel signal | 2 | shorter |
| Minutes with the deadline outside C4's healthy band | 1, 2 | fewer |
| Proposals and approvals until the system is calibrated | 1, 2 | fewer |
| Payment completion (critical work) | 1, 2 | **unchanged** |

**Accept if:** (b) has a lower false-signal rate in case 1 and an earlier overload signal in case 2, with unchanged payment completion.

**Output:** CSV per run plus a summary (mean, min, max, sd).
- **Where:** C4's evaluation harness or scenario presets for the traffic and the decisions; C3 provides an `experiments/c3/e8-healthy-recalibration.sh` script that drives the rollouts and collects the C3 side (epochs, plan, mixed count).
- **Effort:** about 2 days, shared.

## 9. When it does not help (say so openly)

- **Empty healthy queue:** `ΔW` ≈ 0, the threshold is not met, and the rate goes alone.
- **Overload:** never used, by design.
- **Wrong queue estimate:** if `Q_i` is wrong, the deadline is wrong. C4's normal verification after APPLIED and its relearning still run, so the error is corrected the same way as today.
- **Size of the gain:** it shortens the period of a mis-calibrated deadline after a healthy-rate change. It does not prevent failures by itself.

## 10. Optional request to C2

A per-request queue-wait span attribute (`rpe.queue.wait_ms`, already anticipated in C4's v2 guide) would let C4 **measure** the healthy queue wait instead of estimating it as `Q / r`. It is not required for this feature.

## 11. Demo and definition of done

1. Run the recovery scenario. When payment's healthy recovery reaches its target, **one** joint card appears (rate and deadline), with the numbers of section 4.
2. Approve it once. C3's Rollouts page shows one rollout of one version with two parts, the rate before the deadline, both sidecars verified and acknowledged, and 0 mixed-policy requests.
3. The card shows APPLIED with the epoch, then the verification by epoch.
4. E8 is run and its CSV attached.

**Done when:**
- a healthy-regime rate change produces exactly one joint C3 version with two parts and one rollout;
- overload behaviour is unchanged (C4's suites 70 and 95 stay green);
- E8 shows the expected effect, or the result is reported honestly if it does not.

## 12. Questions for C4

1. Is the threshold `max(5 % of D, 10 ms)` acceptable?
2. For `Q_i`: median of the per-window maximum queue depth, or p90?
3. After APPLIED: shift `T_h` by `Σ ΔW`, or restart learning at the epoch boundary?
4. Which scenario presets should drive E8 (existing `recovery`, `deadline-recovery`, or new ones)?
