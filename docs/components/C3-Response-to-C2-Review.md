# C3 response to the C2 review of the C3 novelty

**From:** Kalhara D.M.R (C3, Kubernetes Enforcement Layer). **To:** Pinto (C2, sidecar proxy). **Date:** 18 Oct 2026.
**About:** the review "C3 Novelty in *C3 integration requirements for C2 and C4*".
**Status:** proposal. Nothing below is built yet; I would like your agreement before I start.

Thank you for the review. I checked each point against the code and the measurements. In short: you are right on problems 2 and 3, mostly right on problem 1, and problem 4 is already settled. Below is what I will change, and what stays the same for the proxy.

---

## 1. Summary

| # | Your point | My answer | What changes |
|---|---|---|---|
| 1 | A deadline exceeded in the queue is the signal working, not harm | **Agreed, mostly** | I stop calling it harm. The demo and the experiment report it as a cancel signal that was judged against a deadline the approved decision had already replaced, next to the latency, which is the same in both modes |
| 2 | Delaying the rate cut keeps the overloaded service overloaded longer | **Agreed** | A new order, **protect-first**, applies the rate cut immediately. It becomes the default. The current order becomes an option, and the experiment measures the cost of both |
| 3 | It is "safe order", not "per-request consistency" | **Agreed** | The novelty is renamed **"epoch-tracked, coordinated rollout of linked resilience settings"**. The per-request claim is limited to what is true: each request carries the version it belongs to, and the sidecars count and report it |
| 4 | Level B changes the proxy's backpressure handling | **Already dropped** | Request C2-4 stays declined. No request to change backpressure, deadline or cancellation handling remains |

**No proxy change is needed for any of this.** Everything below is in C3 (Operator, hub, Admin Panel, experiment) and in the documents.

---

## 2. Problem 1: the "harm" is the cancel signal working

### What you said
In RPE an exceeded deadline is a signal, not a failure. A request that waits long in an overloaded service's queue is a true sign of overload, and the proxy correctly marks the chain cancelled and still serves the request. So preventing it removes a useful signal.

### My answer
You are right about the proxy, and the measurements agree with you:
- In both the naive and the consistent runs, the requests after the change took the same time (bursts up to about 1.4 s). The consistent rollout did not make any request faster.
- The only difference was which deadline each request was judged against, so whether `cs=expired` was set. No request failed in any run (0 without a 2xx answer).
- So "requests harmed" was the wrong word.

What I still think is worth reporting, in neutral words: in the naive runs with payment switching first, the cancel signal was set against the **old** 1000 ms deadline, which the same approved decision had already replaced with 4000 ms. Non-critical work (loyalty) was then skipped for requests that the approved policy considered on time. That is a signal that does not match the decision in force, not a failure of the proxy, and not something C3 suppresses.

### What I will change
1. **Wording everywhere:** no "harm", "failed" or "expired because of the rollout". New terms:
   - **stale-deadline signal:** a cancel signal (`cs=expired`) on a request that still carried the deadline of the replaced version;
   - **mixed-policy request:** a request served under another version of the policy than the one it carries (unchanged).
2. **The experiment (E4) and the demo show the latency next to the signals**, so it is visible that latency is the same and only the signals differ.
3. **C3 never changes, delays or suppresses a signal.** It only decides when and in what order an approved change reaches the sidecars. I will write this sentence in the C3 guide.

### What does not change
The proxy's deadline timer (it starts before the queue), how `cs` is set and passed on, and "signal, never reject".

---

## 3. Problem 2: the rate cut must not wait

### What you said
The lower rate is the protection for an overloaded service. The current order (longer deadline first, wait until the old requests have finished, then the lower rate) delays it, and under overload the wait can be long because late requests are never cut. It is limited only by C3's time limit.

### My answer
Agreed. The wait was 75 to 90 ms in the demo, but I did not measure it under real overload, and you are right that it could get close to the 30 s limit there.

### What I will change
1. **New order "protect-first", the default.** Every part that protects a service (a lower rate) is activated at once, together with the gateway's deadline part. There is no waiting step before the protection.
   - Cost: requests already in flight under the old version can get a stale-deadline signal. The epoch stamp tells exactly which requests those were, and the experiment counts them.
2. **The current order becomes "consistent-first", an option.** It is meant for changes where nothing is overloaded, for example tuning, or the way back to the normal settings.
   - Its time limit for waiting will be much shorter than 30 s (proposal: 2 s, configurable), so protection can never be delayed for long.
3. **Who chooses:** the approved decision carries the order (`order: protect-first` or `consistent-first`). The default is protect-first. A human approves it as part of the decision.
4. **The experiment measures the trade-off instead of claiming a winner.** For each order, and for naive delivery:
   - protection delay: from approval until the new rate is in force at payment;
   - stale-deadline signals;
   - mixed-policy requests;
   - latency.

   I expect naive delivery to be worse than both coordinated orders, because nobody controls its order or its window (in the "payment first" runs the window was 4 s long). If the numbers show something else, I will report that.

### What does not change
The leaky bucket, its rate mechanism, the queue capacity, the pressure signals and the circuit breaker. The rate is still applied by the proxy exactly as today, on activation (`applyPolicyRate`).

---

## 4. Problem 3: the name promises more than it does

### What you said
All requests share one queue, so a request carrying the old version cannot keep the old rate. What the method does is switch the changes on in a planned order. It should be named for that.

### My answer
Agreed. The docs already say that the protection comes from the order, but the name still says "per-request-consistent".

### What I will change
- **New name:** "epoch-tracked, coordinated rollout of linked resilience settings".
- **What the claim keeps:**
  - every request carries the version it belongs to (`pe`, stamped at the gateway);
  - each sidecar counts the requests of each version and the mixed-policy requests;
  - the Operator activates a change that spans several services in a planned order (protect-first or consistent-first), instead of letting each sidecar switch at a random moment;
  - the experiment measures what each order costs.
- **What the claim drops:** "every request sees one version of every setting". That holds only for settings that travel with the request, not for the rate.
- **Future work, stated as such:** settings that change what a single request does (retry limits, `replaySafe`) are where per-request consistency would really matter. They depend on your retry phase, and I will not ask for anything there until you plan it.

---

## 5. Problem 4: Level B

Request C2-4 (`backpressure.mode` per request version) stays declined. It was already removed from the demo and is marked declined in the integration requirements. I will not bring it back.

---

## 6. What I need from you

1. Do you agree with the new wording in sections 2 and 4 (stale-deadline signal, mixed-policy request, the new name)?
2. Do you agree that **protect-first is the default** and consistent-first an option with a short time limit (2 s)?
3. Please confirm that nothing here touches the proxy's deadline, cancellation or backpressure behaviour. My list of what C3 uses from the sidecar, all of it already there and unchanged:
   - `pe` stamping at the gateway and `pe` kept per chain;
   - pinning and the counters `rpe_policy_inflight_requests`, `rpe_policy_mismatch_total`;
   - stage, activate and retire, the content hash and the acknowledgements;
   - the rate applied on activation.
4. Optional, not needed for the demo: `pv` written by the proxy per hop (C2-2), which you accepted earlier. It would let the panel see which version each hop applied.
5. Anything else in the review I missed or got wrong?

---

## 7. Plan after your agreement

| Step | What | Where | Effort |
|---|---|---|---|
| 1 | Add the order choice (`protect-first` default, `consistent-first` with a short time limit) | Operator (`k8s/operator/joint.go`, `rollout.go`), policy API, rollout CRD | 0.5 d |
| 2 | Demo and panel: order choice, latency next to the signals, new wording | `hub/consistency.go`, `console/src/pages/Consistency.tsx` | 0.5 d |
| 3 | E4 again: protect-first, consistent-first and naive; protection delay, stale-deadline signals, mixed-policy requests, latency | `experiments/c3/e4-consistent-rollout.sh`, e2e suite 96 | 0.5 d |
| 4 | Documents: new name and wording, honest trade-off | C3 guide (§5.1, §5.1a), `C3-Status-and-Plan.md`, `C3-Integration-Requirements.md`, `C3-Novelty-Defense.md` | 0.5 d |
| 5 | Share the new results and documents with you before the panel | team | — |

About 2 days. No proxy code, no proxy configuration and no proxy tests change.
