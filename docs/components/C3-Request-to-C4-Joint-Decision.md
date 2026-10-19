# C3 request to C4: one joint decision, delivered and verified as one unit

**From:** Kalhara D.M.R (C3, Kubernetes Enforcement Layer). **To:** Rathnayake (C4, Intelligence Layer). **Date:** 19 Oct 2026.
**Status:** request for agreement. Nothing in C4 changes until you agree.

## 1. Why this request

C4's novelty is the **joint** recommendation: deadline, retry budget and rate computed together so they never contradict each other (C4 guide §5.1), with an impact preview that is **verified after approval**.

C3's novelty is to make such a decision **take effect as one unit**:
- every sidecar holds its part first (all or nothing);
- the gateway stamps one version (epoch) into every request;
- the parts are activated in a planned order;
- the old version is removed only when no request uses it.

The two fit together, but today they do not meet:
- C4 sends each setting as its own proposal (`Action` = `LEAKY_BUCKET`, `RETRY_BUDGET` or `CHAIN_DEADLINE`), and `Component3Bridge.deliver` posts one policy per proposal.
- Your deadline rule and your rate rule run separately, each with its own cooldown, and the deadline increase is blocked while the chain is overloaded.
- So the pair in force at a moment (for example deadline 1000 ms with payment at 10/s) is never computed or approved as a pair.
- C3 receives separate versions and cannot deliver them as one decision.

What you gain:
- your joint recommendation is applied exactly as approved;
- the rollout itself does not create deadline failures that your chain rule would read as evidence (you read `rpe_chain_deadline_exceeded_total`);
- you can verify your impact preview by comparing requests of the old version with requests of the new version, because every request carries its version.

## 2. What C3 already provides (built and tested)

- `POST /api/k8s/policies` accepts a joint policy: **one** version with a part per service (`parts`). Each sidecar receives only its part; one epoch for the whole decision.
- `POST /api/k8s/rollouts` (mode `two-phase`) delivers it:
  - stage and verify every part;
  - activate in a planned order (loosening parts first, then the tightening parts after the old version has drained);
  - retire the old version.
- The rollout status shows `plan` (each part: service, loosen or tighten, step, changes), `gateMs`, `mixedPolicyRequests`, and per pod `part`, `activeAt`, `hash`, `verified`, `confirmedAt`.
- A later single-control change to a joint policy keeps its other parts. Your current bridge keeps working unchanged.
- Every request carries the epoch (`pe` in `X-RPE-Context`). Sidecars:
  - count requests per epoch (`rpe_policy_requests_total{epoch}`, `rpe_policy_inflight_requests{epoch}`);
  - tag their trace spans with `rpe.policy_epoch`.
- Evidence: e2e suite 96 (29 checks), experiment E4.

## 3. The exact request

### J1. A joint proposal: several changes, one decision

Add a group to your model, for example in `AdaptiveModels`:

```java
public record JointProposal(UUID id, String chain, List<Proposal> parts, String reason,
        Map<String, Double> feasibility, Instant generatedAt, Instant expiresAt) {}
```

- One review approves or rejects **all parts** together (`PolicyWorkflow`). There is no partial approval.
- The Recommendations page shows the group as **one card**: every part with old and new value, the feasibility numbers, one Approve and one Reject button.
- The existing single proposals stay as they are, for changes that are really independent.

### J2. The feasibility rule (why the parts belong together)

A joint proposal is only created when the parts are needed together. The rule, per chain, on its slowest path:

**new chain deadline ≥ chain work time + sum of the expected queue waits at the new rates**

- expected queue wait at a service ≈ requests queued or in flight at that service ÷ new rate (you already collect `inFlight` and `successfulThroughput` per service);
- chain work time = baseline chain p99 without queueing (you already keep the baseline).

Show the numbers in the evidence, for example: "payment 10/s, about 8 queued → 0.8 s wait; work 0.25 s; needs at least 1.05 s; proposed 1200 ms".

If the rule fails, there is no joint proposal: either change the deadline in the same group or do not change the rate.

### J3. Two joint decisions to produce

You already have the halves as separate scenarios in `MockScenarios`. The request is to produce each pair **together**, as one `JointProposal`.

**D1, overload** (your scenarios `overloaded` + `deadline-increase`). Chain `GET /api/orders`, payment-svc overloaded:

| Part | Old | New | Why |
|---|---|---|---|
| payment-svc leaky-bucket rate | 100/s | 10/s | Protect the overloaded service (your rate rule) |
| gateway chain deadline `GET /api/orders` | 1000 ms | 1200 ms | Needed by J2 at 10/s; replaces the "deadline increase blocked during overload" rule **for this pair only** |

**D2, recovery** (your scenarios `recovery` + `deadline-recovery`). Payment healthy again:

| Part | Old | New | Why |
|---|---|---|---|
| payment-svc leaky-bucket rate | 10/s | 60/s | Healthy recovery (your rate rule, capped by the healthy target) |
| gateway chain deadline `GET /api/orders` | 1200 ms | 1000 ms | Healthy restoration (your deadline rule), only if J2 holds at 60/s |

Live, run D1 and then D2; the second one brings the chain back.

### J4. Deliver the group as one policy

In `Component3Bridge`, when a `JointProposal` is approved:

```http
POST /api/k8s/policies
Content-Type: application/json

{
  "policyId": "<the gateway's current effective policyId if it is a joint policy for this chain, else chain-get-api-orders>",
  "source": "c4-recommendation",
  "recommendationId": "<JointProposal.id>",
  "approval": { "approvedBy": "<reviewer>", "decision": "approved" },
  "parts": [
    { "scope": { "service": "gateway", "route": "GET /api/orders" },
      "spec": { "...": "the gateway's current effective spec", "routes": { "GET /api/orders": { "deadlineMs": 1200 } } } },
    { "scope": { "service": "payment-svc" },
      "spec": { "...": "payment's current effective spec", "leakyBucket": { "ratePerSec": 10 } } }
  ]
}
```

Then:

```http
POST /api/k8s/rollouts
{ "policyId": "<same>", "version": <version from the answer>, "mode": "two-phase", "requestedBy": "<reviewer>" }
```

Rules:
- **Each part's `spec` is that service's current effective spec with only the change applied.** This is what `deliver` does today, so the other settings are kept.
- `recommendationId` is the group id; C3 uses it for idempotency, so sending it twice creates one version.
- The answer has one `version` and one `epoch` for the whole group.

### J5. Confirm and verify by epoch

**Confirm:** when the rollout's `status.phase` is `Complete`, mark the whole group `APPLIED` with the epoch, and store `status.plan`, `status.gateMs` and `status.mixedPolicyRequests` in the audit log.

**Verify** your impact preview with requests separated by version, not by time:
- **before:** requests whose spans carry `rpe.policy_epoch = <previous epoch>`; **after:** spans with `rpe.policy_epoch = <new epoch>`. Use Tempo, which you already query.
- Counts per version: `rpe_policy_requests_total{epoch}` on the gateway and payment sidecars.
- Compare chain p99, deadline failures (`cs=expired` in the spans) and payment throughput, before against after, and the result against your preview.

**Do not learn from the rollout window:** exclude windows that overlap the rollout (from `status.startedAt` to `status.completedAt`) from baseline learning, so the change itself is not taken as new normal behaviour.

### J6. What stays the same

- No automatic approval; a human approves every joint proposal.
- Single proposals and the current bridge path stay as they are.
- No change to C2 is needed for any of this.

## 4. Order of activation, for your information

C3 decides the order from the change, so C4 does not have to:
- **D2 (recovery):** the rate goes **up first**, the deadline down second. The rate is never delayed.
- **D1 (overload):** today's order (`consistent-first`) activates the longer deadline first, waits until no request of the old version is in flight (75 to 90 ms in E4), then applies the lower rate.
  - The C2 review asked that a protective rate cut never wait. C3 proposes a `protect-first` order that applies both parts at once (`C3-Response-to-C2-Review.md`).
  - If C2 agrees, C3 will add it as a rollout field. Your bridge then sends `"activation": "protect-first"` for D1. Until then, `mode: two-phase` is enough.

## 5. Demonstration, and what "done" means

1. Start the overload preset. Your health timeline goes Stressed → Overloaded, and **one** joint card appears: payment 100 → 10/s **and** deadline 1000 → 1200 ms, with the J2 numbers.
2. Approve it once. C3's Rollouts page shows one rollout of one version with two parts, its plan, both sidecars verified and acknowledged, mixed-policy requests 0, and the old version retired.
3. Your card shows `APPLIED` with the epoch, then the verification by epoch: preview against measured, before (old epoch) against after (new epoch).
4. Start the recovery preset. The joint card for D2 appears; approve it; the rate goes first, the chain returns to the baseline.

**Done when:** each joint card produces exactly one C3 policy version with two parts and one rollout; `mixedPolicyRequests` is 0; the card shows `APPLIED` with the epoch and a verification computed from requests of each epoch.

## 6. Effort (estimate)

| Item | Effort |
|---|---|
| J1 `JointProposal`, workflow, one-card review | 2 d |
| J2 feasibility rule | 1 d |
| J3 the two scenarios together (D1, D2) | 1 d |
| J4 bridge sends `parts` | 0.5 d |
| J5 confirm and verify by epoch | 1.5 d |

About 6 days. C3 will help with J4 and J5 (API questions, test cluster, e2e checks).

## 7. Questions for you

1. Do you agree to the `JointProposal` (J1) and to one approval for all parts?
2. Is the feasibility rule in J2 acceptable, or do you want another formula (for example with retry time)?
3. For D1, may the deadline increase be allowed during overload when J2 requires it, only as part of a joint proposal?
4. Do you want the verification by epoch (J5) as part of your own novelty ("verified impact preview")? It is the cleanest before/after comparison you can get.
5. Which name for the policy id of a chain: `chain-get-api-orders`, or the gateway's existing policy id?
