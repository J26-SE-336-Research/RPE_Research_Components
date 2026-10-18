# C3 integration requirements for C2 (interceptor) and C4 (intelligence)

**Status: REQUEST for discussion with the C2 and C4 owners. C2 answered on 18 Oct 2026: C2-4 is declined, the other C2 requests are accepted. The C4 requests are still open.**
**Date:** 18 Oct 2026. **From:** Kalhara D.M.R (C3). Everything below is a request: each owner decides, and every item can be switched off so existing behaviour is unchanged.

## 1. Purpose

C3's novelty is the **per-request-consistent rollout** of a policy: stamp the new version at the gateway, let every hop apply the version the request carries, drain the old version, retire it. This document lists what C2 and C4 would need to do so that the novelty can be **demonstrated** in front of the panel, and what C3 does itself. Scope guard: C3 does not change the sidecar's mechanisms or C4's decisions.

## 2. The novelty on one page, with an example

A joint change is approved as one decision: chain deadline 1000 to 4000 ms (applied at the gateway) and payment rate 100 to 6 requests/s (applied at the payment sidecar). At the new low rate a burst of 8 chain requests waits in payment's queue for up to about 1.4 s. (The first draft of this document used 200 to 2 requests/s; the built demo uses 6 requests/s so that the background traffic of the demo cluster, about 3 requests/s, does not overload payment at the new rate.)

| Delivery | What happens | Result |
|---|---|---|
| Naive: each sidecar switches when the change reaches it, the rate lands first | Requests wait up to about 1.4 s in payment's queue but still carry the old 1000 ms deadline | Requests expire (`cs=expired`, still served), the sidecars count mixed-policy requests |
| Epoch rollout: stage everywhere, the gateway flips and stamps the new version, wait until requests pinned to the old version have drained, then lower the rate | No request meets the new rate with the old deadline | No expiry; every request sees one consistent version |

## 3. What exists today (verified in the code)

| Capability | State |
|---|---|
| Gateway stamps `pe` in the root context | Built |
| `pe` kept fixed along the chain at the gateway proxy | Built |
| Each hop pinned to the epoch the request carries; per-epoch in-flight gauge `rpe_policy_inflight_requests{epoch}`; mismatch counter | Built |
| Staged / active / retire, sidecar-computed hash, acknowledgements | Built |
| The gateway route deadlines and the rate applied on activation | Built |
| `pv` (policy epoch applied) in `X-RPE-Result`, written by the sidecar per hop | Not built (C2-2). The gateway application (rpe-go) already reports the epoch it stamped |
| Hop events to the hub (`POST /api/hub/events`) | Not built |
| A coupled setting applied by the request's epoch | Not built (Level B declined) |
| Joint (grouped) proposal in C4 | Not built (C4-1) |
| Joint policy delivery in C3: one version with a part per service, ordered activation with the drain gate, naive baseline with a fixed or random arrival order | **Built 18 Oct 2026** (C3 step 0b) |
| Consistency demo (Admin Panel, Rollouts) and experiment E4 | **Built 18 Oct 2026** (`tests/e2e/96-consistent-rollout.sh`, `experiments/c3/e4-consistent-rollout.sh`) |

So the "sticker" (stamping and pinning) was done, and the joint delivery and the visible proof (Level A) are now built by C3 alone. Still open: `pv` written by the sidecar (C2-2), and the joint proposal in C4 (C4-1 to C4-4), which today is created by an admin in the Admin Panel.

**Found while building (18 Oct 2026):** the demo's gateway application started each chain with a copy of the policy it polls every 2 s. For up to 2 s after the gateway sidecar switched, new chains could carry the new epoch but the old deadline, which is itself a mixed-policy state. The demo application now uses the deadline its sidecar stamped into the context it receives (`demo-app/policy.go`, `stampedBudget`). A customer application that creates its own root contexts needs the same rule; the sidecar was not changed.

## 4. Three levels of demonstration

- **Level A (minimum, needs no new C2 mechanism):** the joint deadline and rate change above, delivered by C3 with ordering and the drain gate, compared with naive delivery. Evidence: expired requests and the gateway deadline-exceeded counter, plus the mixed-policy counter. It can be run with an admin-created joint policy, so it does **not** need C4.
- **Level B (declined by C2, not planned):** one coupled setting that the sidecar applies **by the request's epoch** (proposed: `backpressure.mode`), so that a mixed state changes behaviour. C2 declined it on 18 Oct 2026 because it would touch the backpressure code.
- **Level C (after C2's retry phase):** `replaySafe` and `retry.maxAttempts` per epoch; a mixed state can double-charge (the C1 duplicate-charge story).

## 5. Requests to C2 (interceptor)

| # | Request | Level | Why | Where | Acceptance test | Effort |
|---|---|---|---|---|---|---|
| C2-1 | Review and accept the hooks C3 added to the sidecar (policy store, `pe` kept per chain, per-request pinning, per-epoch in-flight, hash and acks, rate application) | A | They are in C2's code and were agreed only for the rate | `PolicyStore`, `ActivePolicy`, `ProxyHandler`, `AdminEndpointHandler`, `PolicyAcks` | The owner signs off; the sidecar unit tests and suites 30, 35, 50, 93 stay green | 0.5 d |
| C2-2 | Write `pv` (the epoch applied) into `X-RPE-Result` on the response | A (shows the proof) | `pv` is already defined in the contract (A3.1); the Request Inspector can then show which version each hop applied | `RpeWire` (result writing) | A request through the gateway returns `pv` per hop; conformance vectors still pass | 0.5 d |
| C2-3 | Keep exposing `GET /rpe/v1/policy` with `inflight` per epoch and the activation order semantics (no change, just a commitment) | A | C3 uses it as the drain gate | `AdminEndpointHandler` | Unchanged API; documented as stable | 0 |
| C2-4 | **DECLINED by C2 (18 Oct 2026).** Was: apply `backpressure.mode` by the request's epoch (receiver sends pressure signals only if the request's epoch says `push`; caller honours a signal only if the call's epoch says `push`) | B | Would have given a coupled setting where a mixed state is visibly harmful | `BackpressureController`, `PolicyStore` | Not planned | not planned |
| C2-5 | Send hop events to the hub (`POST /api/hub/events`, A3.3 format, `policyEpoch` per hop) | optional | Lets the hub compute `rpe_mixed_policy_requests_total` from real hop events, as the contract describes; today C3 uses the sidecar counters | new small sender | Events arrive with `policyEpoch`; no effect on latency beyond the batch | 1.5 d |
| C2-6 | Later: read `replaySafe` and `retry.maxAttempts` per epoch when retries exist | C | The duplicate-charge demonstration | retry phase | Out of scope until retries are built | later |

C2 accepted C2-1, C2-2, C2-3 and C2-5 (C2-5 stays optional). C2-4 touched the backpressure code, which is covered by the project rule "do not change the backpressure architecture", and C2 declined it. Level A is therefore the demonstration (see "Why Level A is still a real demonstration" below).

## 6. Requests to C4 (intelligence)

| # | Request | Why | Where | Acceptance test | Effort |
|---|---|---|---|---|---|
| C4-1 | A **group proposal**: several controls for one chain or service approved or rejected as one decision (for example the deadline and the rate of one chain) | C4's own joint recommendation (its novelty) is only real if it is one decision; today each control is a separate change | `AdaptiveModels` (`Proposal` or a new `ProposalGroup`), `PolicyWorkflow`, review API, Recommendations page | One approval covers all parts; rejecting rejects all | 2 d |
| C4-2 | Deliver a group as **one policy version** with several parts (format in section 7) instead of one version per control | If the parts arrive as separate versions there is no single epoch to stamp, and the consistency guarantee cannot exist | `Component3Bridge.deliver` | The policy API shows one version with two parts and one rollout | 1 d |
| C4-3 | A scenario that produces the joint proposal (overloaded payment: lower the rate and raise the chain deadline so the queue wait fits), with the feasibility rule "chain deadline is at least the queue wait at the new rate" | Gives a repeatable, real trigger for the demo | `MockScenarios`, `AdaptiveEngine` | The scenario yields one group proposal that satisfies the rule | 1.5 d |
| C4-4 | After the rollout completes, record the epoch and the outcome evidence (expired requests during the change, mixed-policy count from C3) in the recommendation's delivery state | Closes the loop: "approved, delivered consistently, verified" | `Component3Bridge.confirm`, audit | The recommendation shows `APPLIED` with the epoch and the evidence | 1 d |

Level A does not depend on C4 (an admin can create the joint policy in the Admin Panel), so C4 can follow after the C3 side works.

## 7. What C3 provides (so nothing overlaps)

Status: everything in this section is **built** (18 Oct 2026, `k8s/operator/joint.go`, `rollout.go`, `api.go`, `hub/consistency.go`, `console/src/pages/Consistency.tsx`). The format is still a proposal for the shared appendix.

- **Policy bundle API** (proposed A3.4 v0.4, to be agreed by all four): `POST /api/k8s/policies` accepts `parts`:

  ```json
  { "policyId": "chain-orders-overload", "source": "c4-recommendation | admin",
    "recommendationId": "rec-0193", "approval": { "approvedBy": "sre@demo", "decision": "approved" },
    "parts": [
      { "scope": { "service": "gateway", "route": "GET /api/orders" },
        "spec": { "routes": { "GET /api/orders": { "deadlineMs": 4000 } } } },
      { "scope": { "service": "payment-svc" }, "spec": { "leakyBucket": { "ratePerSec": 6 } } } ] }
  ```

  A request without `parts` behaves exactly as today. The response has one `version` and one `epoch` for the whole bundle. Each sidecar receives only the part for its own service, and its content hash is the hash of that part. A later request without `parts` for a policy whose latest version is joint (for example one control changed in the Admin Panel, or by C4's bridge) keeps the other parts, so the new version is still one decision. The Admin Panel sends a joint change as `changes` (a list of controls) to `POST /api/hub/policies`.
- **Ordered activation with the drain gate:** each part is compared with the same service's part in the previous version (a shorter deadline, a lower rate or a lower retry ratio is a tightening, anything else a loosening); all parts are staged and verified; the loosening parts are activated first (the gateway first, so it stamps the new version); if the gateway was among them, the Operator waits until no request stamped with the old epoch is in flight on any target (the gateway's pin lasts for the whole chain; time limit `DRAIN_MAX_MS`); then the tightening parts are activated; the old epoch is retired. The rollout status shows the plan, the step and the waiting time (`gateMs`).
- **Naive baseline:** `mode: naive` switches each sidecar when the change reaches it and drops the old version. The spread is random (`skewMs`), or fixed with `order: services-first | gateway-first` (the second group `skewMs` later), so an experiment can show both orders that a real fleet produces by chance.
- **Evidence:** the mixed-policy counter, expired requests during the change, convergence and gate times in the rollout status; the Admin Panel consistency demo (Rollouts); experiment E4 (script and CSV).
- C3 does not change the sidecar's mechanisms or C4's decisions. No sidecar code was changed for this step.

## 8. Demo script and success criteria

Built as the **Run** button of "Consistent rollout of a joint change" (Admin Panel, Rollouts). Each run:

1. Restores the baseline (chain deadline 1000 ms, payment rate 100 requests/s), delivered two-phase.
2. Sends a burst of 8 chain requests through the gateway every 4 s (the cluster's background traffic of about 3 requests/s keeps running).
3. Approves the joint change (deadline 1000 to 4000 ms, payment rate 100 to 6 requests/s) during the traffic and delivers it as chosen: consistent (two-phase), naive with payment first, naive with the gateway first, or naive with a random spread.
4. Shows the expired requests, those of them that still carried the old deadline, the mixed-policy requests, the order in which the sidecars switched, and a latency chart of every request with both deadlines and the switch moments.

Success: naive with the rate first shows expired requests; the consistent rollout shows none in every run; E4 writes the CSV with mean, min, max and sd (results in section 12).

## 9. Order of work and who waits for whom

C3 step 0b (bundle API, ordering, drain gate, panel, E4) is done (18 Oct 2026) and needed nothing from C2 or C4. Next: C2-1 and C2-2 (1 day together) make the per-hop proof visible. C4-1 to C4-4 (about 5.5 days) make it a closed loop: C4's joint recommendation arrives as one version instead of an admin creating it. Level C (a coupled setting per epoch) returns when C2 builds retries.

## 10. Decisions needed

- C2: answered. Accepted C2-1, C2-2, C2-3; declined C2-4; still to decide: hop events (C2-5) now or later.
- C4: agree to group proposals (C4-1) and to deliver them as one version (C4-2)? Who writes the scenario (C4-3)?
- All four: accept the bundle format as A3.4 v0.4 and change the shared appendix together; confirm the ownership row stays "Policy epoch in context: C3 novelty, per-request-consistent rollout".
- Supervisor: is Level A alone (ordered joint rollout with drain, no new C2 mechanism) acceptable now that C2 has declined Level B?

## 11. Why Level A is still a real demonstration

- **The problem:** a joint change reaches the sidecars at different times. If the lower payment rate lands before the longer gateway deadline, requests wait in payment's queue longer than the deadline they still carry and expire. The rollout itself causes the failures.
- **What the stamp adds:** the gateway stamps the new version on every new request, so each sidecar can count the old-version requests still running (`rpe_policy_inflight_requests{epoch}`). Without the stamp nobody can tell when it is safe to tighten; with it the answer is a number.
- **What prevents the harm in the demo:** the ordering (loosening first) plus the drain gate (tighten only when the old version has drained), not a sidecar that changes behaviour per request. This is stated openly.
- **What is not shown:** a coupled setting that the sidecar applies per request (Level B/C). That needs C2's retry phase (`replaySafe`, `maxAttempts`) and is future work.
- **Origin of the idea:** per-packet consistent updates in software-defined networking (Reitblatt et al., SIGCOMM 2012), applied here to application-layer resilience policy.


## 12. Results (E4, 18 Oct 2026, kind cluster, 1 replica per service)

5 runs of each delivery, interleaved, each from the baseline, 24 demo requests per run after the approval (raw rows and summary: `experiments/c3/results/e4-20261018-150733*.csv`).

| Delivery | Runs with expired requests | Expired per run (mean, min to max) | Mixed-policy requests per run (mean, min to max) | Requests without a 2xx answer |
|---|---|---|---|---|
| Consistent (two-phase) | 0 of 5 | 0 | 0 | 0 |
| Naive, payment switches 4 s before the gateway | 5 of 5 | 2.4 (2 to 3), all with the old deadline | 14 | 0 |
| Naive, the gateway switches 4 s before payment | 0 of 5 | 0 | 16.8 (16 to 17) | 0 |
| Naive, random spread up to 3 s | 1 of 5 | 0.4 (0 to 2) | 8.4 (1 to 15) | 0 |

How to read it:
- The consistent rollout never let a request meet the new rate with the old deadline, and no sidecar served a request under another version than it carried. The tightening waited 75 to 90 ms for the old version to drain; the whole rollout took about 0.4 s.
- Naive delivery always produced mixed-policy requests. Whether they also caused harm depended only on the arrival order: with payment first, every run had requests that expired because of the delivery itself; with the gateway first, none did; with a random spread it is a lottery (1 of 5 runs, the order is not under anyone's control in a real fleet).
- The harm is limited to the requests in the window (about 10 % of the demo requests here) because only one burst falls into it. A longer propagation window (for example a ConfigMap-mounted configuration, which a kubelet can take up to about a minute to refresh) would widen it; that is an argument, not a measurement.
- Requests that expired were still served (signal, never reject): the harm is lateness and the cancellation signal, which tells non-critical services to skip their work.
