# Joint healthy recalibration: Step 0 inventory and plan

Branch `c4/joint-recalibration`, created from `c4/rate-retry-v2` at `f775fa9` on 21 Oct 2026 (baseline: **204 tests, all
pass**). Source: C3's request "C3 request to C4: the joint healthy recalibration of rate and chain deadline" (19 Oct 2026,
Kalhara D.M.R), sections J1–J6 and E8. That document was written against `c4/deadline-v2`; where it describes the old rate
rules, this plan uses the rate v2 equivalent (section 3).

## 1. Rules for this work

- Off by default: `rrpe.adaptive.joint.enabled=false` in code, `true` only in `deploy/k8s/platform/intelligence.yaml`
  (`RRPE_ADAPTIVE_JOINT_ENABLED`). With `false` every hook is a no-op and the 204 existing tests pass unchanged.
- Only `intelligence/` changes (plus that env value). No change to C1, C2, C3, hub, console or demo-app.
- No new `Action`, no change to `Proposal`, `PolicyChange`, `DeadlineEvidence` or any other record that existing tests or
  clients construct or read; every API change is an additional optional field.
- Joint cards are never approved automatically; a person approves or rejects them.
- Deadline v2, rate v2 and retry v2 decisions are unchanged; their tests are not edited.

## 2. Facts checked in the code

| Fact | Where |
|---|---|
| The healthy target is C4's own and `sync` keeps it (C3's point 5A.1) | `RateState.healthyRate`, `Component3Bridge.sync` (rate v2 R4) |
| The per-window maximum queue depth is collected | `PrometheusWindows.loadQueries` → `loadQueueDepth` |
| `D* = ceil10(T_h × (1 + m))` with min/max | `DeadlineMath.target(th, m, minMs, maxMs)` |
| `T_h`, `m` per chain | `HealthyLatencyModel` → `HealthyModel.thMs`, `margin` |
| Step limits | `DeadlineSettings.stepUp` 1.5, `stepDownHealthy` 0.80; `Settings.maximumDeadlineChange` 0.50 |
| Settled-deadline lock (LEARNING/PROPOSED/SETTLING, pending, raise building, recent change) | `AdaptiveEngine.unsettledDeadline` |
| Bridge: one change per service policy per cycle | `Component3Bridge.run` |
| C3 `parts` API: `{"parts":[{"scope":{"service","route"},"spec":{...}}]}`, one part per service, one version and epoch; a later single change to a service of a joint policy is merged by C3 (`withPart`) | `k8s/operator/api.go` `createPolicy`, `k8s/operator/joint.go` (read only) |
| `DeadlineVerifier.verify` knows `A_RAISE`, `D_TIGHTEN`, `B_LOWER_OVERLOAD`; any other case is INCONCLUSIVE | `DeadlineVerifier` |
| `new Proposal(...)` is used in 6 existing test files, so `Proposal` cannot get a new component | tests |
| The console names only `LEAKY_BUCKET`, `RETRY_BUDGET`, `CHAIN_DEADLINE` | `console/src/pages/Recommendations.tsx` (read only) |

## 3. Where the hook goes

The trigger (C3 3b) is the rate v2 **HEALTHY** rule in `AdaptiveEngine.bucket`, `case HEALTHY`, where it computes
`next = RateDiagnosis.healthyStep(current, target, …)`. A joint is considered only when `next == target` (the step
**reaches** the healthy target, up or down). Protective cuts (APP_OVERLOADED), RATE_TOO_LOW raises and anti-ratchet
undos never reach this code. In rate v2 the healthy target is recomputed every cycle
(`max demand of 3 windows × 1.25`, clamped), so "the healthy target changes" is covered by "a step reaches the (new)
target".

At that point, instead of `offerAuto(...)` the engine asks `HealthyRecalibration` for a joint plan:
- no plan (flag off, a chain not healthy, or no chain through the service): unchanged, the rate goes alone as today;
- a plan whose deadline change is too small on every chain (C3 3c): unchanged, the rate goes alone;
- a plan: the joint proposal replaces the rate proposal (J3).

## 4. Design

| Part | Design |
|---|---|
| Settings | `JointSettings` (`rrpe.adaptive.joint`): `enabled` false, `min-change-fraction` 0.05, `min-change-ms` 10 |
| `Q_i` | `RateState` keeps the per-window maximum queue depth of HEALTHY windows (last `baseline-samples`); `Q_i` = median |
| `HealthyRecalibration` (new, pure) | trigger a) (shares, lever, guards, settled), the calculation of C3 section 4 and the threshold; examples 1–3 as tests |
| Joint proposal (J1) | one `LEAKY_BUCKET` proposal for the service plus one `CHAIN_DEADLINE` proposal **per chain through the service whose deadline change passes the threshold** (C3 J4: "every chain whose path includes the changed service"). They are linked by a `jointId` kept in `PolicyWorkflow` (a side map, records unchanged). Deciding any part decides all parts with the same review; if one part is superseded, expires or is withdrawn, all are. Every part's evidence starts with "Joint recommendation <id>: …" so the two (or more) cards explain each other in today's console |
| Lock (J3) | while a joint is active, `bucket()` and `retry()` offer nothing for its service and deadline v2 holds its chains (reason `JOINT_PENDING`); the existing settled-deadline lock already blocks automatic changes. If trigger a) stops holding (e.g. overload), the joint is withdrawn and the normal rules resume |
| Delivery (J4) | the bridge collects the approved parts of a joint and sends **one** `POST /api/k8s/policies` with `parts`: the gateway part (current gateway spec with every changed `routes[route].deadlineMs`) and the service part (current service spec with `leakyBucket.ratePerSec`), `policyId` = the gateway's effective policy id, `recommendationId` = `jointId`, then one two-phase rollout. All parts get the same epoch and become APPLIED together. Single proposals use the existing code path unchanged |
| After APPLIED (J5) | each deadline part is registered as the chain's applied change with the new case `DeadlineCase.RECALIBRATE` (additive enum value), so SETTLING, verification and `DeadlineSafety.applied` run as for any change; verification uses the existing rule of `A_RAISE` (deadline up) or `D_TIGHTEN` (deadline down), `DeadlineVerifier` unchanged; healthy-time learning restarts for the chain (`HealthyLatencyModel.reset`) |
| Evidence (J6) | proposal measurements (additive keys): `jointQ`, `jointRateOld`, `jointRateNew`, `jointWaitOldMs`, `jointWaitNewMs`, `thMs`, `thNewMs`, `margin`, `currentMs`, `targetNewMs`, `thresholdMs`; plus an optional `joints` field in `GET /` |
| E8 simulation | new `JointSimulation` (chain requests = path time + payment's queue wait `Q / r`, bursty healthy traffic), scenarios `joint-recovery`, `joint-capacity-drop`; arms (a) rate alone, (b) joint; results in `evaluation/joint-e8-simulation.md` |

## 5. Files planned

| File | Change |
|---|---|
| `AdaptiveConfig.java` | + `JointSettings` |
| `HealthyRecalibration.java` | new |
| `JointSimulation.java` | new (E8) |
| `RateState.java` | + healthy queue-depth samples (and calibration rates, see decision D1) |
| `AdaptiveEngine.java` | hooks: healthy-rule offer point; hold rate/retry/deadline while a joint is active; register RECALIBRATE after APPLIED; `joints` evidence |
| `PolicyWorkflow.java` | joint link map, decide/withdraw all parts together, `joints()` view (additive) |
| `Component3Bridge.java` | joint delivery with `parts`; single path unchanged |
| `AdaptiveModels.java` | `DeadlineCase.RECALIBRATE` (additive enum value) |
| `AdaptiveController.java` | optional `joints` field in `GET /` |
| `MockScenarios.java` | scenarios `joint-recovery`, `joint-capacity-drop` |
| tests (new only) | `HealthyRecalibrationTests`, `JointWorkflowTests`, `JointBridgeTests`, `JointAfterAppliedTests`, `JointScenarioTests`, `JointFlagOffTests` |
| docs | this file, `README.md`, `evaluation/joint-e8-simulation.md` |
| `deploy/k8s/platform/intelligence.yaml` | `RRPE_ADAPTIVE_JOINT_ENABLED=true` |

## 6. Outside the plan / decisions for the owner

- **D1 – which "old rate" (important).** C3's examples move the rate in one jump (50 → 100/s, the old v1 recovery). Rate v2
  moves in small healthy steps (+10% of the good rate), so the last step alone changes the wait very little and a joint
  card would almost never pass the threshold. Proposal: `r_old` = the service's rate in the windows from which the
  chain's current `T_h` was learned (median over those windows), because `T_h` contains the wait at that rate. Then
  `ΔW = Q × (1/r_new − 1/r_old)` covers the whole healthy move since the deadline was calibrated. Alternative: the last
  step only (faithful to the text, rarely triggers).
- **D2 – small hooks besides the healthy-rule hook.** J3 and J5 need three more small hooks, all no-ops with the flag off:
  (a) `bucket()`/`retry()` hold for a service with an active joint; (b) deadline v2 holds a chain with an active joint
  (`JOINT_PENDING`, so it does not replace the joint's deadline part); (c) after APPLIED, register the RECALIBRATE change
  and restart learning.
- **D3 – more than two cards.** A joint has one rate part plus one deadline part per affected chain (C3 J4). With one
  chain through the service it is exactly two cards.
- **D4 – retry while a joint waits.** Planned: retry also holds for that service ("nothing else is offered"); if the
  service becomes overloaded, trigger a) fails, the joint is withdrawn and retry v2 works as usual.
- **D5 – C3 dependency.** C3 says it still has to finish one fix: a joint version that takes over services which had
  separate policies (in the demo, `payment-svc` has its own policy) must be planned against what each service enforces.
  The live test of J4 needs that fix, or a confirmation from C3 that it is done.
- **D6 – e2e 95 with the flag on.** A joint for the gateway needs a non-empty healthy queue; the demo's healthy queue is
  about 0, so no joint is expected. If one did appear while 95 runs, its pending rate part would hold the deadline
  (deadline v2 `PENDING_RATE_PROPOSAL`), so 95 would wait for a person.
- **Expected effect in the demo:** small. With an empty healthy queue (`Q ≈ 0`) the rate goes alone (C3 example 3).
  E8 uses bursty healthy traffic to show the effect.

## 7. Note for the console owner

A joint recommendation is shown today as two (or more) linked cards: a "Rate limit" card and a "Request deadline" card.
Each card's evidence starts with "Joint recommendation <id>". Approving or rejecting **either** card decides **all**
parts. Later the Recommendations page can show one combined card: `GET /api/v2/intelligence` will have an optional
`joints` field (`jointId`, part proposal ids, service, chains, decision) to group them. Nothing in the console has to
change for the feature to work.

## 8. Owner decisions (21 Oct 2026)

- **D1:** `r_old` = the service's rate in the windows from which the chain's current `T_h` was learned (median over
  those windows). The evidence records both that rate (`jointRateOld`) and the last step's rate (`jointRateLastStep`).
- **D2:** the three extra hooks are allowed; each does nothing when `rrpe.adaptive.joint.enabled` is false (proved by the
  unchanged full suite with the flag off).
- **D3:** one rate card plus one deadline card per chain through the service.
- **D4:** retry holds while a joint waits, subject to the priority rules below.
- **D5:** the joint delivery is built and unit-tested now. **The live test waits for C3 (Kalhara) to confirm his fix**
  for a joint version that takes over services which had separate policies before.
- **D6:** the flag stays on for e2e 95; the "deadline not settled" guard blocks any joint card while 95 changes the
  deadline (test added).

### Priority: the normal recommendations come first, joint cards second

1. Rate v2, retry v2 and deadline v2 recommendations always have priority. A joint card never blocks, delays or replaces
   any of them, except the single healthy rate step it came from.
2. A joint card is created only when no normal recommendation is needed or pending for that service or chain: no
   overload cut, no RATE_TOO_LOW raise, no retry change, no deadline case A/B/C/D (the chain's last counted deadline
   decision is HOLD `IN_BAND` with no persistence streak), no pending normal proposal.
3. If, while a joint card waits, a normal recommendation becomes needed for that service or chain (OVERLOADED,
   STRESSED, RATE_TOO_LOW, retry → 0, deadline case A/B/C/D), the joint card is withdrawn immediately and the normal
   recommendation is offered in the same cycle.
4. If both could be made in the same cycle, the normal one is made and the joint card is skipped.
5. Joint cards have the normal recommendation lifetime.

This changes design point "Lock (J3)" of section 4: while a joint waits, the rate, retry and deadline rules still run
every cycle for its service and chains. When none of them needs a change, they hold (so they do not replace the joint's
parts). When one of them needs a change, the joint is withdrawn first and that change is offered.

### Showing joint cards

- `RRPE_ADAPTIVE_JOINT_ENABLED=true` in `deploy/k8s/platform/intelligence.yaml`.
- `POST /api/v2/intelligence/demo/joint-recovery` and `/demo/joint-capacity-drop` always produce a joint card with C3's
  example numbers (payment 50 → 100/s with the deadline 875 → 780 ms; 100 → 60/s with 780 → 850 ms).

## 9. Note for the hub owner

The hub's intelligence scenario list (`hub/features.go`) does not yet contain the newer C4 scenario names, so the Admin
Panel cannot start them. Please add (this work does not change the hub):
- deadline v2 examples: `deadline-too-long-under-overload`, `deadline-too-short-day-one`, `services-slowing`,
  `queue-rate-not-deadline`, `mixed-paths`, `rejecting-middleware-504`;
- rate and retry v2: `rate-too-low`, `rate-too-low-day-one`, `rate-real-overload`, `rate-recovery`, `rate-short-burst`,
  `rate-bursts`, `retry-storm-rate`;
- joint recalibration: `joint-recovery`, `joint-capacity-drop`.

## 10. Implementation notes (per step)

- **Step 1.** `JointSettings` (`rrpe.adaptive.joint.enabled` false, `min-change-fraction` 0.05, `min-change-ms` 10),
  `HealthyRecalibration` (pure), and two recording hooks that only run with the flag on: the HEALTHY windows' maximum
  queue depth per service (`RateState.healthyDepth`, `Q` = median) and the path services' rates in each counted
  eligible chain window (`ChainV2.calibrationRates`, `r_old` = median, D1).
  - **Threshold on the rate's own effect (addition).** A joint also needs `|D*' − D*| ≥ threshold`, the change of the
    target caused by the rate. Otherwise a chain whose deadline sits elsewhere in its healthy band would get a joint card
    with an empty queue (C3 example 3), although that correction is deadline v2's own job.
  - C3's example 1 uses `D` = 875 ms, while `ceil10(700 × 1.25)` = 880 ms; the results (780 ms, 850 ms, rate alone for
    `Q ≈ 0`) are the same.
- **Step 2.** Joint proposal, lock and priority rules.
  - **Two triggers (C3 3b).** (b1) the rate v2 HEALTHY step that reaches its target, and (b2) a change of the service's
    healthy target (`healthyBucketTarget`, set by an operator through `PUT /policies`; the C3 sync keeps it). C3's examples
    map to (b2): in rate v2, "50 → 100/s" with demand above 50 would be RATE_TOO_LOW (excluded by rule b), and the healthy
    rule never lowers a rate to its target (it only relaxes while above 2 × target). With the flag off a target change
    still produces nothing, as today.
  - The healthy step that reaches its target is decided **after** the retry rule (`jointStep`): if a retry change, an
    OVERLOADED/STRESSED health, APP_OVERLOADED or RATE_TOO_LOW is present, the normal recommendations are made and no joint
    (rules 2–4). Otherwise `tryJoint` checks every analysed chain through the service (`HealthyRecalibration.notReady`:
    READY, settled, last counted decision `HOLD IN_BAND`, no streak, shares, lever) and no pending normal proposal for the
    service; if any check fails, or no chain's change passes the threshold, the normal rate proposal is made unchanged.
  - Waiting joint cards: re-offered every cycle with the same ids (normal lifetime). Deadline v2 ignores joint parts for
    `PENDING_PROPOSAL` / `PENDING_RATE_PROPOSAL`, so its own cases run every window; when it needs a case (or the chain is
    no longer healthy) the joint is withdrawn first. A normal rate proposal for the service replaces the joint's rate
    part, and the workflow then withdraws the joint's other parts. The rate rule's housekeeping withdraw ("no rate change
    supported") skips joint parts when the flag is on (`withdrawExceptJoint`).
  - `JointSimulation` (main): an isolated bed with the chain and payment; C3's examples reproduce exactly
    (T_h 700 ms, m 0.254 → 875 → 780 ms; T_h 620 ms, m 0.251 → 780 → 850 ms).
- **Step 3.** `Component3Bridge` sends an approved joint as one `POST /api/k8s/policies` with `parts` (gateway part:
  the gateway's effective spec with every changed `routes[route].deadlineMs`; service part: its effective spec with the
  new `leakyBucket.ratePerSec`), `policyId` = the gateway's effective policy, `recommendationId` = the joint id (C3 makes
  it idempotent), then one two-phase rollout; every part becomes ACCEPTED, then APPLIED with the same epoch. A joint is
  sent only when all its parts are approved and all its services are free (one change per service per cycle); it is
  never sent in halves. Single proposals keep the existing payload (no `parts`). Live test: waits for C3's fix (D5).
