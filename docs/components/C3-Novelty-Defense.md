# C3 novelty defence: per-request-consistent rollout of resilience policies

**Owner:** Kalhara D.M.R (IT23139626), Component 3 (Kubernetes Enforcement Layer), project J26-SE-336 (RPE). **Written:** 18 Oct 2026, after commit `a31d1d9`. **Purpose:** everything needed to explain, demonstrate and defend the novelty in front of the panel: the problem, the exact idea, how it is built, the proof, the research it comes from, the papers to read, the demo and the pitch, and honest answers to hard questions.

Related files: [C3-Status-and-Plan.md](C3-Status-and-Plan.md) (current status, section 2 is the short version of this document), [C3-K8s-Enforcement-Kalhara.md](C3-K8s-Enforcement-Kalhara.md) (the A-to-Z guide, §5.1a honest limits), [C3-Integration-Requirements.md](C3-Integration-Requirements.md) (what C2 and C4 are asked to do, section 12 results).

---

## 0. How to use this document

### 0.1 What to memorise (the five things the panel will test)

1. **The novelty in one sentence:** "C3 rolls out a resilience change that spans several services so that no request is ever governed by two versions of it: every version gets an epoch, the gateway stamps it into each request, the sidecars count the requests of each epoch, and the Operator activates the loosening parts first, waits until no request of the old epoch is in flight, and only then activates the tightening parts."
2. **The problem in one sentence:** "A joint change reaches the sidecars at different times; in between, requests can meet half of the change, so the change itself harms requests, and today's tools accept that window because they only promise that everything converges eventually."
3. **The five steps:** stage and verify, loosen (gateway first, it stamps the new epoch), drain gate, tighten, retire.
4. **The proof:** E4, 5 runs each: consistent rollout 0 expired and 0 mixed-policy requests in 5 of 5 runs; naive with the rate first 2 to 3 expired and 14 mixed in 5 of 5 runs; naive with the gateway first 0 expired but 16 to 17 mixed; naive random 1 of 5 runs harmed.
5. **The honest positioning:** the idea comes from consistent network updates in software-defined networking (Reitblatt et al., SIGCOMM 2012) and from version consistency of dynamic reconfiguration (Ma et al., ESEC/FSE 2011). C3's contribution is applying it to resilience policies of plain HTTP microservices, with the version carried in the request context, the drain detected from measured per-epoch in-flight counts, and an order (loosen before tighten) for settings that cannot be chosen per request, and measuring the harm it prevents.

### 0.2 Study plan (7 days)

| Day | Read | Do |
|---|---|---|
| 1 | Sections 1 to 4 of this document | Explain the problem and the five steps aloud, without notes, in 2 minutes |
| 2 | Reitblatt et al. 2012 (sections 1 to 5; reading guide in 8.1) | Draw their two-phase update and our five steps side by side |
| 3 | Version consistency (Ma et al. 2011), Kramer and Magee 1990 (quiescence) | Write down in 3 sentences how C3 differs from each |
| 4 | Envoy xDS "eventual consistency considerations", Kubernetes ConfigMap update delay, Istio proxy-status | Prepare the "why not Istio" answer (section 11.2) |
| 5 | Sections 5 and 6 of this document, the code files in 5.1 | Run suite 96 and one E4 run yourself; read the CSV |
| 6 | Sections 9 to 11 | Rehearse the demo and the 3-minute pitch twice; record a backup video |
| 7 | Section 11 (question bank), section 12 (limits) | Ask a teammate to fire 15 questions from section 11 at you |

### 0.3 Rules for the defence

- Every claim must point to something: code, a test, the E4 CSV, or a cited source.
- Say the limits before the panel finds them (section 12).
- Never say "Istio cannot do this". Say "as far as its documentation shows, this is not provided as a built-in guarantee".
- Give credit to the prior work early; it makes the contribution more credible, not less.

---

## 1. The novelty in one page

### 1.1 Statement

**Per-request-consistent rollout of joint resilience-policy changes in a sidecar-based microservice system.** One approved decision that changes resilience settings of several services is delivered as **one policy version with one epoch** and a part per service. The gateway sidecar **stamps** the epoch into every new request's context (`pe` in `X-RPE-Context`). Every sidecar **pins** each request to the epoch it carries and counts the requests of each epoch in flight. The Operator activates the parts in an order derived from the change itself: **loosening parts first, then a drain gate** (wait until no request of the old epoch is in flight), **then the tightening parts**, then it retires the old epoch. The result: no request meets a combination of old and new settings, and the system can show it with numbers.

### 1.2 Contributions (what you claim)

1. **Per-request version accounting for resilience policy across a chain of unmodified HTTP services.** The version travels in the request context that RPE already carries; the sidecars pin, count and report it. The application code is not changed.
2. **A safe activation order for joint changes.** Each part is classified as a loosening or a tightening by comparing it with the previous version; loosening parts are activated first and tightening parts only after the drain gate. This handles settings that belong to a sidecar and cannot be chosen per request (the leaky-bucket rate).
3. **A drain gate based on measured in-flight counts per epoch**, not on a guessed timeout.
4. **An evaluation with a harm metric:** requests that expire because of the delivery itself, and mixed-policy requests, compared between consistent and naive delivery (E4), plus a live demonstration in the Admin Panel.

### 1.3 What it is not (say this if the panel confuses it)

- Not autoscaling and not traffic shaping. It does not decide what the settings should be (that is C4 and a human).
- Not canarying a new application version (Argo Rollouts, Flagger). It is about changing the **rules** (resilience settings) safely across services.
- Not a service mesh. RPE has its own sidecar (C2) and its own context (C1); C3 is the control plane that delivers policies.
- Not a new deadline or rate mechanism. The mechanisms belong to C2; C3 changes only **when and in what order** an approved change reaches them.

---

## 2. Background the panel expects you to know

### 2.1 RPE in one page

RPE (resilience propagation) adds resilience to plain HTTP microservices without changing their code. Four components:

| Component | Owner | What it does |
|---|---|---|
| C1 protocol | Ruwanmali | The request context `X-RPE-Context` (fields such as `rid` request id, `ttl` time left, `bud` budget, `rb` retry budget, `cs` cancellation state, `pe` policy epoch, `rt` route) and the result header `X-RPE-Result`; libraries `rpe-go`, `rpe-java`; rules such as R-T01 (`pe` is immutable along the chain) |
| C2 sidecar | Pinto | The Netty proxy next to every service: deadline propagation (the deadline travels as `ttl`), cancellation propagation (`cs`, "signal, never reject"), backpressure (inbound leaky bucket, pressure signals, adaptive concurrency, circuit breaker) |
| C3 enforcement layer | Kalhara | Kubernetes webhook that injects the sidecar, PostgreSQL policy store, Operator that delivers approved policies to the right sidecars, verifies them, repairs drift, audits coverage, and the novelty: consistent rollout |
| C4 intelligence | Rathnayake | Learns from metrics and traces and recommends policy changes; a human approves them |

The demo chain: `gateway → order-svc → (inventory-svc, payment-svc, loyalty-svc, legacy-svc → shipping-svc)`.

### 2.2 Kubernetes concepts used

- **Sidecar:** a second container in the same pod as the service; all traffic goes through it (ports 15001 outbound, 15006 inbound, 15020 control API, 15090 metrics).
- **Mutating admission webhook:** Kubernetes calls it when a pod is created; it adds the sidecar container and its configuration.
- **Custom resources (CRDs):** `RPEPolicy` (mirror of an approved policy) and `RPERollout` (one rollout with its phase and status).
- **Operator and reconcile loop:** a controller that watches `RPERollout` objects and moves each one through its phases (Pending, Staging, Activating, Draining, Complete). Its state is in the object's status, so an Operator restart continues where it stopped.
- **Policy store:** PostgreSQL, append-only, a policy row cannot exist without a human approver; the Operator learns about new versions through LISTEN/NOTIFY and catches up after downtime. Redis is a disposable fast path.

### 2.3 Resilience settings that C3 delivers today

| Setting | Where it is applied | What it does |
|---|---|---|
| Chain deadline per route (`spec.routes["GET /api/orders"].deadlineMs`) | Gateway sidecar | The budget the gateway stamps into each new chain (`bud`, `ttl`). Every hop counts time against it. When it is exceeded the chain is marked cancelled (`cs=expired`); the request still flows (signal, never reject) |
| Rate limit (`spec.leakyBucket.ratePerSec`) | The target service's sidecar | Release rate of the inbound leaky bucket: requests wait in a FIFO queue and leave at this rate; queue waiting time counts against the deadline |
| Retry ratio (`spec.retry.budgetRatio`) | The target service's sidecar | Delivered and acknowledged; enforcement waits for C2's retry phase |

Important for the novelty: the **deadline travels inside the request** (each request carries the one it was born with), while the **rate is a property of the sidecar** (all requests at that sidecar share it). That difference is why the order of activation matters.

### 2.4 How configuration reaches proxies in practice

- **Push (like Envoy xDS, and RPE):** a control plane sends the new configuration to each proxy. Envoy's documentation says the xDS APIs are **eventually consistent** and that "traffic may drop briefly during updates"; it recommends a **make-before-break** order of its configuration types (clusters first, then endpoints, listeners, routes, then remove stale ones) [D1].
- **ConfigMap volumes:** Kubernetes updates a mounted ConfigMap after up to the kubelet sync period plus the cache propagation delay; a ConfigMap used as environment variables is never updated without a restart [K2].
- **Polling:** each instance asks for the configuration every few seconds, so instances switch at different moments.
- **Status of delivery:** Istio reports per proxy whether it acknowledged the latest push (`istioctl proxy-status`: SYNCED or STALE) [D2]. That is **convergence per proxy**, not a statement about each request.

In every case different instances switch at different moments. The time between the first and the last switch is the **propagation window**.

### 2.5 Words you must use precisely

- **Version / epoch:** the identifier of one approved policy version; C3 names it `<policyId>-v<n>`, for example `chain-orders-v8`.
- **Stamp:** the gateway sidecar writes the epoch into the new request's context (`pe`).
- **Pin:** a sidecar serves a request under the epoch it carries (if it holds that epoch), otherwise under its active epoch.
- **Mixed-policy request:** a request that a sidecar served under another version of the policy than the epoch it carries (`rpe_policy_mismatch_total`).
- **Eventual consistency:** all instances end up with the same version, but there is no guarantee about what happens before that.

---

## 3. The problem

### 3.1 Resilience changes are joint changes

A resilience decision is rarely one number in one place. When a service is overloaded, a sensible decision changes several services at once, and the values only make sense together. C4's recommendation is designed to be **joint**: the deadline, the retry budget and the rate are computed together so that they never contradict each other (C4 guide; contract A3.4 has coupled fields such as `fanout`, `replaySafe`, `commitStage`, retry limits, backpressure mode, cancellation tiers).

### 3.2 The worked example (the one in the demo)

payment-svc is overloaded. One decision is approved:
- **gateway:** chain deadline of `GET /api/orders` 1000 ms → 4000 ms (loosening);
- **payment-svc:** rate 100 → 6 requests per second (tightening).

Why they depend on each other: at 6 requests/s, a burst of 8 chain requests leaves payment's queue at 0, 0.17, 0.33, ... 1.17 s; with the background traffic the last one waits about 1.4 s. With the new 4000 ms deadline that is fine. With the old 1000 ms deadline it is too long: the request **expires** (`cs=expired`), the chain is marked cancelled, non-critical services skip their work, and the answer is late.

### 3.3 The propagation window creates a harmful mixed state

The gateway sidecar and the payment sidecar do not switch at the same moment.

| Order of switching | During the window | Effect |
|---|---|---|
| Payment first, gateway later | New low rate at payment, but new requests still carry the old 1000 ms deadline | Requests expire **because of the rollout**, not because of the overload |
| Gateway first, payment later | Requests carry 4000 ms, payment still at 100/s | Harmless combination, but requests that started before the gateway switched still carry 1000 ms and can meet the new rate if payment switches before they finish |

Nobody controls this order in a real fleet; it depends on network delays, pod load and retries of the control plane.

### 3.4 Why "it converges eventually" is not enough for RPE

- RPE's promise is that RPE must not become the reason a request fails (CLAUDE.md design rules, "signal, never reject"). A rollout that makes requests expire breaks that promise in exactly the moment the system is already overloaded.
- Changes are the main cause of outages in production: the Google SRE book reports that "roughly 70% of outages are due to changes in a live system" [S1]. Facebook changes configuration thousands of times a day and treats configuration changes as a major source of incidents, which is why it canaries them [C1]. Studies of real upgrade failures in distributed systems show that interaction between old and new versions during an upgrade is a recurring cause, and that a failure during an upgrade extends the disruption the upgrade already causes [U1].
- Envoy's own documentation says traffic may drop during eventually consistent updates and recommends an order of updates to avoid it [D1]. That ordering is inside one proxy. RPE's problem is the same kind of problem **across services**, for **resilience settings**, and **per request**.

### 3.5 The research gap (say it like this)

Configuration systems for proxies (Envoy xDS, Istio, ConfigMaps, polling) distribute configuration per proxy and converge eventually; they report which proxies have acknowledged a version, not which version each request was served under, and they do not order a change that spans several services by its effect. Consistent updates are well studied for packet forwarding in software-defined networks [R1]-[R7] and for transactions in component-based systems [V1]-[V4], but not for **resilience policies of HTTP microservices behind sidecars**, where some settings travel with the request (the deadline) and others belong to a sidecar and cannot be chosen per request (the rate). There is no mechanism in this setting that (a) knows which version each request belongs to across the chain, (b) orders a multi-service resilience change so that no request meets incompatible settings, and (c) measures the harm of not doing so.

---

## 4. The solution, precisely

### 4.1 Definitions

- **Joint policy:** one approved policy version with **parts**, one per service: `spec.parts = [{scope: {service, route?}, spec}, ...]`. All parts share one version and one epoch.
- **Part:** the share of a joint policy one service enforces. Each sidecar receives only its own part; its content hash is the hash of that part.
- **Loosening part:** a part that relaxes a limit or leaves the limits alone (a longer deadline, a higher rate, a higher retry ratio, or a new setting).
- **Tightening part:** a part that makes any limit stricter (a shorter deadline, a lower rate, a lower retry ratio). A part that does both is a tightening.
- **Unchanged part:** a part with the same content as in the previous version (for example the gateway's part when only a service's rate in the joint policy changes). It never starts the drain gate, so a single rate change inside a joint policy is applied without waiting.
- **Drain gate:** the wait until the number of in-flight requests pinned to the previous epoch is 0 on every target sidecar (bounded by `DRAIN_MAX_MS`, 30 s).

### 4.2 The property

**Consistency property:** during the rollout of version N+1 replacing version N, no request that carries epoch N meets a tightened setting of version N+1, and no sidecar serves a request under a different version of the policy than the epoch it carries.

What we check:
- `rpe_policy_mismatch_total` (mixed-policy requests) does not increase during the rollout;
- no demo request that started after the approval expires because of the delivery (`cs=expired` with the old deadline).

### 4.3 The algorithm

1. **Stage:** every target sidecar receives its part of version N+1, inactive (`PUT /rpe/v1/policy`, state staged). The sidecar computes the content hash itself; the Operator compares it with the approved hash; the sidecar also acknowledges to the policy API. Nothing changes in the traffic yet.
2. **Plan:** each part is compared with the same service's part in version N (`planJoint`): step 1 for loosening parts, step 2 for tightening parts. In the demo: gateway (deadline 1000 → 4000 ms) = loosening, step 1; payment (rate 100 → 6/s) = tightening, step 2.
3. **Loosen (step 1):** the step-1 parts are activated, gateway sidecars first. From this moment the gateway stamps epoch N+1 into every new request.
4. **Drain gate:** requests stamped with epoch N are still running. Every sidecar reports `inflight[N]`; the gateway's pin lasts for the whole chain, so its count covers every chain still running. The Operator waits until the sum is 0 (or `DRAIN_MAX_MS` is reached; then it logs that and continues).
5. **Tighten (step 2):** the step-2 parts are activated. Every request now in the system carries epoch N+1 and its new 4000 ms deadline.
6. **Retire:** epoch N is removed from every target sidecar; version N+1 becomes the active version in PostgreSQL; the mixed-policy count is read once more after `SETTLE_MS` (3 s).

When the gateway is not in step 1 (for example the way back: rate up first, deadline down second), there is no gate between the steps: requests of version N with their long deadline can safely meet the higher rate; the final drain before retiring still applies.

### 4.4 Why the stamp is necessary

Without a version in each request, the Operator cannot tell when the requests that were admitted under the old settings have finished. It could only wait a fixed time and hope. With the stamp, every sidecar counts the requests of each epoch, so "is it safe to tighten?" becomes a number the system reports. In the demo the gate waited 75 to 90 ms, not a guessed second.

### 4.5 Why the order is necessary

The rate is a setting of the sidecar, not of a request: once payment's sidecar releases at 6/s, every request in its queue waits, whatever epoch it carries. Per-request pinning alone cannot protect a request of the old version from a sidecar-wide setting. The only safe rule is to make sure no request of the old version is still in the system when such a setting is tightened. Hence: loosen first (so new requests already carry the safe deadline), drain, then tighten.

### 4.6 The naive baseline

Naive delivery is what a system with one configuration per proxy does: each sidecar switches to version N+1 when the change reaches it and drops version N at once. The arrival order is random (`skewMs`, a random delay up to the given value per sidecar), or fixed for the experiment (`order: services-first` or `gateway-first`, the second group `skewMs` later). The fixed orders exist so that the experiment can show both orders that a real fleet produces by chance.

### 4.7 Failure cases (how the design behaves)

| Situation | Behaviour |
|---|---|
| Operator restarts during a rollout | The rollout's phase, step and pods are in the `RPERollout` status; the reconcile loop continues from there |
| A target pod disappears during the rollout | Marked `gone`; the rollout continues with the others; drift repair gives the new pod the active version afterwards |
| A sidecar holds other content than approved | Verification by content hash fails; the rollout retries and fails after `MAX_ATTEMPTS`; nothing is activated before the staged content is verified |
| Old-epoch requests never reach 0 (stuck or very slow) | The gate waits at most `DRAIN_MAX_MS` (30 s), logs it, then continues: the guarantee is bounded, and this is stated as a limit |
| A request without an epoch (not through the gateway) | Served under the sidecar's active epoch; not counted as mixed |
| Several gateway replicas | All gateway sidecars of step 1 are activated before services; each request carries the epoch its gateway stamped; the gate counts in-flight requests on every target |
| A request that is already late | It keeps flowing (signal, never reject) and stays counted until it finishes, so the gate waits for it |
| Two rollouts at the same time | Rollouts run one at a time; the later one waits (the gateway stamps one epoch) |
| The application creates its own root context with a stale copy of the policy | Found while building: the demo gateway app polled the policy every 2 s and could start chains with the old deadline under the new epoch. Fixed in the demo app: it uses the deadline its sidecar stamped (`stampedBudget`). A customer gateway app needs the same rule |

---

## 4A. Worked example: chain deadline 1200 ms + payment rate 10/s through order, payment, inventory

One recommendation from C4 (sent as one decision) for the chain `GET /api/orders`: chain deadline 1200 ms and payment-svc rate 10 requests/s. Chain: `gateway → order-svc → payment-svc → inventory-svc`.

### 4A.0 Starting point (before the recommendation)

| | Value in force | Which version |
|---|---|---|
| Chain deadline of `GET /api/orders` (gateway) | 1000 ms | `chain-orders-v1` |
| payment-svc rate | 100 requests/s | `chain-orders-v1` |
| order-svc, inventory-svc | their own settings | not part of this policy, untouched |

Why the pair must change together: at 10/s, a burst of 8 requests leaves payment's queue at 0, 0.1, ... 0.7 s. Adding about 0.25 s of work, the last one needs about 0.95 to 1.0 s. With 1000 ms that is just too tight; with 1200 ms it fits. C4 computed **1200 ms because of 10/s**, so they must arrive as a pair.

### 4A.1 What an epoch is

- An **epoch** is the name of one approved version of a policy: `<policyId>-v<number>`. Today `chain-orders-v1`; C4's recommendation becomes `chain-orders-v2`.
- One version = **one decision**. Both values (1200 ms and 10/s) belong to the same epoch `chain-orders-v2`. It is stored as **parts**, one per service:

```
chain-orders-v2
  part for gateway:      GET /api/orders deadline 1200 ms
  part for payment-svc:  rate 10 requests/s
```

- Each sidecar receives **only its own part**: the gateway never gets the rate, and payment never gets the deadline. Each part has its own content hash, which the sidecar computes and C3 checks.
- order-svc and inventory-svc are not named in the decision, so they receive nothing and keep their settings.

### 4A.2 What the gateway does with the epoch (the "stamp")

Every request that enters the system goes through the gateway sidecar. The gateway creates the request's context `X-RPE-Context` and writes into it:
- `bud` and `ttl`: the chain deadline of the version it enforces, for example 1000 ms;
- `pe`: the **epoch** of that version, for example `pe="chain-orders-v1"`.

That context travels with the request through order-svc, payment-svc and inventory-svc, and nobody changes `pe` on the way (protocol rule R-T01).

So **every request carries the name of the version it was born under**. Each sidecar:
- **pins** the request to that epoch (it serves the request under the epoch it carries, when it holds that epoch);
- **counts** how many requests of each epoch are in flight: `rpe_policy_inflight_requests{epoch="chain-orders-v1"}`.

The gateway's count lasts for the whole chain, from when the request enters until the answer goes back.

### 4A.3 The steps, with the example

#### Step 1: Stage (nothing changes yet)

- C3 sends the gateway its part (1200 ms) and payment its part (10/s), both marked **staged** (inactive).
- Each sidecar computes the hash of what it received and acknowledges. C3 compares the hash with the approved one.
- If one sidecar fails, nothing is activated and the rollout stops.
- Traffic is unchanged: the gateway still stamps `chain-orders-v1` / 1000 ms; payment still releases at 100/s.

#### Step 2: Plan the order

C3 compares each part with the same service's part in v1:

| Part | Change | Kind | Step |
|---|---|---|---|
| gateway | deadline 1000 → 1200 ms | **loosen** (more time is never harmful) | 1 |
| payment-svc | rate 100 → 10/s | **tighten** (only safe for requests that already have 1200 ms) | 2 |

Rule: loosen first, tighten last, and between them wait until the old version is gone.

#### Step 3: Loosen (activate the gateway's part)

- The gateway activates `chain-orders-v2`.
- From this moment, every **new** request is stamped `pe="chain-orders-v2"`, `bud=1200`.
- Requests that entered a moment earlier still carry `pe="chain-orders-v1"`, `bud=1000`. Their deadline cannot be changed, because it travels inside the request.
- payment is still at 100/s.

#### Step 4: Drain (the wait in the middle)

**Drain** means waiting until no request of the old version is still running anywhere.

- C3 reads, every 200 ms, the count of in-flight requests stamped `chain-orders-v1` on the gateway and payment sidecars.
- Example: at the moment of step 3 there are 6 old requests in flight. The count goes 6 → 3 → 1 → 0 as their answers return. In E4 this took 75 to 90 ms.
- When the sum is **0**, every request still in the system carries `chain-orders-v2` and 1200 ms.
- If old requests never finish, C3 waits at most `DRAIN_MAX_MS` (30 s), logs it and continues.

The epoch is what makes this possible. Without the stamp, C3 could not tell the old requests from the new ones, so it could only guess a waiting time.

#### Step 5: Tighten (activate payment's part)

- payment activates `chain-orders-v2`: its queue now releases at 10/s.
- Every request reaching that queue has 1200 ms, which fits the longer wait.

#### Step 6: Retire

- `chain-orders-v1` is removed from the gateway and payment.
- `chain-orders-v2` becomes the active version in PostgreSQL.
- C3 reads the mixed-policy counter once more (after 3 s) and records the result.

### 4A.4 Four requests through the chain

| Request | Enters at | Stamped | order-svc | payment-svc | inventory-svc | Combination it ran under |
|---|---|---|---|---|---|---|
| R1 | before step 3 | v1, 1000 ms | own settings | 100/s (step 5 waits for R1 to finish) | own settings | all v1 |
| R2 | between steps 3 and 5 | v2, 1200 ms | own settings | 100/s, pinned to v2 (payment already holds v2, staged) | own settings | new deadline + old rate: always safe (more time, faster queue) |
| R3 | after step 5 | v2, 1200 ms | own settings | 10/s | own settings | all v2 |
| R4 | during step 1 or 2 | v1, 1000 ms | own settings | 100/s | own settings | all v1 (nothing changed yet) |

**No request ever runs under "1000 ms + 10/s"**, the combination nobody recommended.

### 4A.5 The same recommendation without the novelty (naive)

Each sidecar switches when the change reaches it and drops v1 at once. If payment's sidecar happens to switch first:
- payment releases at 10/s while the gateway still stamps 1000 ms;
- requests carrying 1000 ms wait up to about 1 s in payment's queue and are marked cancelled (`cs=expired`) against a deadline the same decision had already replaced;
- payment serves v1 requests under v2 (it no longer holds v1), and its counter `rpe_policy_mismatch_total` rises.

E4 measured this:

| Delivery | Mixed-policy requests | Cancel signals on requests with the old deadline |
|---|---|---|
| naive, payment first | 14 per run | 2 to 3 per run |
| C3 coordinated | 0 | 0 |

### 4A.6 The steps in one picture

```
time ──────────────────────────────────────────────────────────────►
gateway stamps:  v1 (1000 ms) ────────┐ v2 (1200 ms) ──────────────────────
payment rate:    100/s ───────────────────────────────┐ 10/s ─────────────
                 [1 stage][2 plan] [3 loosen] [4 drain: v1 count 6→0] [5 tighten] [6 retire v1]
requests:        R4 (v1)    R1 (v1) ──done   R2 (v2, old rate, safe)   R3 (v2, new rate)
```

### 4A.7 How to say it (one paragraph)

"C4 recommends a 1200 ms chain deadline and a 10 per second rate at payment as one decision. C3 makes it one version, `chain-orders-v2`, with a part for the gateway and a part for payment. The gateway stamps that version into every new request, so every sidecar knows which version each request belongs to and counts them. C3 stages both parts, activates the longer deadline first, waits until no request of the old version is still running (the drain, about 80 ms in our runs), then activates the lower rate, and retires the old version. So no request ever meets the new rate with the old deadline."

### 4A.8 Does the drain delay the system? 

**No request waits because of the drain.** What waits is only **one configuration step**: payment's rate cut.

| | During the drain (step 4) |
|---|---|
| New requests | Enter normally. They already get the new 1200 ms deadline, which was activated in step 3 before the drain started |
| The 6 old requests | Keep running normally. C3 does not hold, slow down or block them; it only **watches** their count fall |
| Traffic and latency | Unchanged. C3 is not in the request path at all; it only reads counters from the sidecars every 200 ms |
| What is postponed | Only the switch of payment from 100/s to 10/s, by the drain time |

**How long is that postponement?** At most as long as the slowest request that was already running when the gateway switched:
- Only requests that started in the last moment before step 3 are "old". With a normal chain time of about 0.2 to 0.3 s, that is a handful of requests, finishing within a few hundred milliseconds. In E4 the drain took **75 to 90 ms**; the whole rollout about 0.4 s.
- It can never exceed `DRAIN_MAX_MS` (30 s); after that C3 continues anyway and logs it.

**When is the postponement a real cost?** When payment is **already overloaded**, the rate cut is its protection, so delaying it keeps payment overloaded a little longer. Under overload the old requests are also slower, because late requests are never cut, so the drain can take longer than 80 ms. This is exactly the C2 review's problem 2. The answer is to choose the order per decision:

| Situation | Order | Effect |
|---|---|---|
| Tuning, recovery, nothing overloaded | **consistent-first** (built): loosen, drain, tighten | Protection is postponed by the drain (about 0.1 s in E4); no request runs under the unapproved pair |
| A service is overloaded and the rate cut is protection | **protect-first** (proposed in `C3-Response-to-C2-Review.md`): rate cut and deadline together, no drain before the cut | Protection starts immediately; the few requests already in flight with 1000 ms can get a cancel signal, and the epoch tells exactly which ones |

So the drain is not a delay of the system. It is a short, bounded postponement of one setting, and protect-first removes even that when protection matters more. Both are better than naive delivery, where the order and the length of the window are left to chance (4 s in the E4 runs with payment first).

---

## 5. How it is built

### 5.1 Code map

| File | What to know |
|---|---|
| `k8s/operator/joint.go` | `Part`; `parts()`, `joint()`, `services()`, `forService()`, `projections()`; `jointSpec`, `withPart` (a one-service change keeps the other parts), `validateParts`; `classify` (loosen or tighten, with the changes in words), `planJoint`, `stepOf`, `describePlan` |
| `k8s/operator/rollout.go` | The state machine. `resolveAll` (targets of every part, each with its part), `partOf`, `start` (computes the plan from the previous version), `stage` (each target gets its own part and hash), `activate` (steps; gateways first; naive with random or fixed order; `NextStepAt` for the fixed gap), `finishDrain` (the gate between steps and the final drain), `complete` (retire, store active, effective per part) |
| `k8s/operator/api.go` | `POST /api/k8s/policies` accepts `parts`; a request without `parts` for a joint policy keeps its other parts; `POST /api/k8s/rollouts` accepts `order`; `rolloutSpec` sets the scopes |
| `k8s/operator/types.go`, `config/crd/rperollouts.yaml` | `RolloutSpec.Services`, `Order`; `RolloutStatus.Plan`, `Step`, `GateStartedAt`, `GateMs`, `NextStepAt`; per pod `Part`, `ActiveAt` |
| `k8s/operator/fleet.go`, `rehydrate.go` | A joint policy is expected (drift check and repair) and effective on each of its services with that service's part |
| Sidecar `PolicyStore.java` | Epochs staged and active; `pin` (the epoch a request is served under), `release`, `inflight`, `mismatched`, metrics `rpe_policy_inflight_requests{epoch}`, `rpe_policy_requests_total{epoch}`, `rpe_policy_mismatch_total`; `contentHash` |
| Sidecar `ActivePolicy.java`, `ProxyHandler.java`, `RpeWire.java` | Gateway stamps `ActivePolicy.epoch()` into the root context; `pe` fixed per chain on the gateway's outbound call (`RpeWire.withEpoch`); each inbound hop pinned (`ActivePolicy.pin`); route deadlines and the rate applied on activation |
| `hub/consistency.go` | The demo runner: baseline, traffic, joint change, measurement, restore; `jointParts` builds the parts from each service's current policy |
| `hub/features.go` | `POST /api/hub/policies` with `changes` (a joint change) or `control` (one control); `publish` |
| `console/src/pages/Consistency.tsx` | The Rollouts card: delivery choice, Run, runs table, latency chart |
| `demo-app/policy.go` | `stampedBudget`: the gateway app uses the deadline its sidecar stamped |

### 5.2 The API formats

A joint policy (what C3 stores and what C4 will send):

```json
{ "policyId": "chain-orders", "source": "admin",
  "approval": { "approvedBy": "admin", "decision": "approved" },
  "parts": [
    { "scope": { "service": "gateway", "route": "GET /api/orders" },
      "spec": { "routes": { "GET /api/orders": { "deadlineMs": 4000 } } } },
    { "scope": { "service": "payment-svc" }, "spec": { "leakyBucket": { "ratePerSec": 6 } } } ] }
```

A joint change from the Admin Panel (`POST /api/hub/policies`):

```json
{ "policyId": "chain-orders", "rollout": "two-phase",
  "changes": [ { "control": "chain-deadline", "route": "GET /api/orders", "deadlineMs": 4000 },
               { "control": "rate-limit", "service": "payment-svc", "ratePerSec": 6 } ] }
```

What a rollout shows (`kubectl get rper -n rpe-system -o yaml`, or the drawer in the panel): `status.plan` (per part: service, kind, step, changes), `status.step`, `status.gateMs`, `status.mixedPolicyRequests`, `status.pods[].part`, `status.pods[].activeAt`, `status.pods[].hash`, `verified`, `confirmedAt`.

### 5.3 Metrics and where to read them

| Metric | Where | Meaning |
|---|---|---|
| `rpe_policy_inflight_requests{epoch}` | each sidecar `:15090/metrics`, also `GET :15020/rpe/v1/policy` (`inflight`) | Requests in flight pinned to each epoch; the drain gate reads it |
| `rpe_policy_mismatch_total` | each sidecar | Mixed-policy requests |
| `rpe_policy_info{epoch,hash,state}` | each sidecar | What the sidecar holds |
| `rpe_mixed_policy_requests_total{mode}`, `rpe_rollout_convergence_seconds{mode}` | Operator `:8081/metrics` | Per rollout, after it settled |
| `cs` in `X-RPE-Result` | every response | `expired` when the chain deadline was exceeded |

---

## 6. Proof

### 6.1 Unit tests

| Test | Proves |
|---|---|
| `TestJointPartsAndProjections` (Operator) | A joint policy has a part per service, each part keeps the epoch and carries only its service's settings; a one-service change keeps the other part; one part per service |
| `TestClassifyOrdersLooseningBeforeTightening` | A longer deadline loosens (step 1), a lower rate tightens (step 2); the way back is ordered the other way; the first version has one step; a lower retry ratio tightens |
| `TestJointRolloutLoosensFirstAndTightensOnlyAfterTheOldVersionDrained` | Each proxy holds only its part, verified by that part's hash; the gateway is activated first; payment is not activated while old-epoch requests are in flight; after the drain payment is activated; the old epoch is retired on both |
| `TestJointRolloutWithoutGatewayLooseningNeedsNoGate` | On the way back, payment's higher rate first, the gateway's shorter deadline second, no gate |
| `TestNaiveArrivalOrderServicesFirst` | Naive with a fixed order switches payment before the gateway and drops the old version; no plan |
| Earlier rollout tests (`rollout_test.go`) | Service policies never reach the gateway; naive drops the previous version; hash mismatch fails before activation; one rollout at a time; acknowledgements |
| `TestJointChangeIsOnePolicyWithAPartPerService` (hub) | A joint change from the panel becomes one policy with two parts, each built from that service's current policy; the order is passed to the rollout |
| `TestConsistencyInputIsValidated` (hub) | Demo input rules and defaults |
| `TestGatewayUsesTheDeadlineItsSidecarStamped` (demo-app) | The gateway app uses the stamped deadline over a lagging copy |
| `PolicyStoreTest` (sidecar, 8 tests) | Requests are pinned to the epoch they carry; an epoch no longer held is a mixed-policy hop; other policies and unpinned requests are not mixed; the gateway serves the epoch it stamped; metrics; content hash equals the Operator's |

### 6.2 End-to-end suite 96 (`tests/e2e/96-consistent-rollout.sh`, 29 checks, all passing)

1. **Two-phase run:** finished; demo traffic ran (24 requests during the change); 0 expired at the baseline; **0 expired because of the change; 0 mixed-policy requests**; the gateway's change classified as loosening step 1, payment's as tightening step 2; the gateway enforced the new version before payment; the run restored the baseline.
2. **One version, one epoch, a part per service** (on the restored baseline): payment enforces 100/s; the gateway and payment sidecars enforce the same epoch; the gateway's part has the chain deadline and no rate; payment's part has the rate and no route deadlines.
3. **Naive, payment first:** **requests expired** (2 to 3 of 24), all with the old deadline; **mixed-policy requests above 0**; payment switched before the gateway.
4. **Two-phase again:** 0 expired, 0 mixed (the way back to the baseline is safe too).
5. **A one-control change to the joint policy:** stays one version of the joint policy with both parts; the gateway's part is unchanged; payment enforces the new rate.

### 6.3 Experiment E4 (`experiments/c3/e4-consistent-rollout.sh`)

**Setup:** kind cluster on a laptop (2 nodes), one replica per service, the background traffic of the demo cluster on (about 3 requests/s on `GET /api/orders`). Each run: restore the baseline (1000 ms, 100/s) two-phase; demo traffic = a burst of 8 chain requests every 4 s; after 6.5 s approve the joint change (4000 ms, 6/s) and deliver it; observe 9 s after completion; restore the baseline. Deliveries are interleaved (one run of each, then the next), 5 runs each.

**Metrics:**
- `requests_during`: demo requests that started after the approval (24 per run, 3 bursts);
- `expired`: of those, responses with `cs=expired`; `expired_old_deadline`: of those, the ones stamped with the old 1000 ms deadline;
- `mixed_policy_requests`: the sidecars' mismatch count during the rollout (all traffic, relative to its start);
- `without_2xx`: demo requests without a 2xx answer;
- `gate_ms`: how long payment's tightening waited (two-phase only);
- `convergence_ms`: from rollout start until every target enforced the version.

**All 20 rows** (`experiments/c3/results/e4-20261018-150733.csv`):

| Delivery | Run | During | Expired | Old deadline | Mixed | Without 2xx | Gate ms | Convergence ms | First to switch |
|---|---|---|---|---|---|---|---|---|---|
| two-phase | 1 | 24 | 0 | 0 | 0 | 0 | 75 | 412 | gateway |
| naive-services-first | 1 | 24 | 2 | 2 | 14 | 0 | 0 | 4152 | payment-svc |
| naive-gateway-first | 1 | 24 | 0 | 0 | 16 | 0 | 0 | 4161 | gateway |
| naive-random | 1 | 24 | 0 | 0 | 8 | 0 | 0 | 1556 | gateway |
| two-phase | 2 | 24 | 0 | 0 | 0 | 0 | 87 | 416 | gateway |
| naive-services-first | 2 | 24 | 3 | 3 | 14 | 0 | 0 | 4156 | payment-svc |
| naive-gateway-first | 2 | 24 | 0 | 0 | 17 | 0 | 0 | 4159 | gateway |
| naive-random | 2 | 24 | 0 | 0 | 15 | 0 | 0 | 3083 | gateway |
| two-phase | 3 | 24 | 0 | 0 | 0 | 0 | 88 | 416 | gateway |
| naive-services-first | 3 | 24 | 2 | 2 | 14 | 0 | 0 | 4180 | payment-svc |
| naive-gateway-first | 3 | 24 | 0 | 0 | 17 | 0 | 0 | 4237 | gateway |
| naive-random | 3 | 24 | 0 | 0 | 1 | 0 | 0 | 1095 | payment-svc |
| two-phase | 4 | 24 | 0 | 0 | 0 | 0 | 89 | 414 | gateway |
| naive-services-first | 4 | 24 | 2 | 2 | 14 | 0 | 0 | 4167 | payment-svc |
| naive-gateway-first | 4 | 24 | 0 | 0 | 17 | 0 | 0 | 4176 | gateway |
| naive-random | 4 | 24 | 0 | 0 | 9 | 0 | 0 | 1810 | gateway |
| two-phase | 5 | 24 | 0 | 0 | 0 | 0 | 90 | 444 | gateway |
| naive-services-first | 5 | 24 | 3 | 3 | 14 | 0 | 0 | 4159 | payment-svc |
| naive-gateway-first | 5 | 24 | 0 | 0 | 17 | 0 | 0 | 4195 | gateway |
| naive-random | 5 | 24 | 2 | 2 | 9 | 0 | 0 | 2350 | payment-svc |

**Summary:**

| Delivery | Runs | Runs with expired | Expired mean (min to max, sd) | Mixed mean (min to max, sd) |
|---|---|---|---|---|
| Consistent (two-phase) | 5 | **0** | **0.0** (0 to 0, 0.0) | **0.0** (0 to 0, 0.0) |
| Naive, payment first (4 s gap) | 5 | 5 | 2.4 (2 to 3, 0.5) | 14.0 (14 to 14, 0.0) |
| Naive, gateway first (4 s gap) | 5 | 0 | 0.0 (0 to 0, 0.0) | 16.8 (16 to 17, 0.4) |
| Naive, random spread up to 3 s | 5 | 1 | 0.4 (0 to 2, 0.9) | 8.4 (1 to 15, 5.0) |

**How to read it:**
1. The consistent rollout produced **no mixed-policy request and no expired request in every run**. Its gate waited 75 to 90 ms; the whole rollout took about 0.41 to 0.44 s.
2. Naive delivery **always** produced mixed-policy requests (14 to 17 per run with a 4 s gap).
3. Whether the mixed state also **harmed** requests depended only on the order: payment first harmed every run; gateway first harmed none; random harmed 1 of 5. In the random runs, payment switched first in runs 3 and 5; run 5 harmed requests, run 3 did not because no burst fell into its short window. The order is not under anyone's control in a real fleet.
4. Every expired request carried the old deadline: the harm is exactly the "new rate with old deadline" combination.
5. No request failed (0 without 2xx): the harm is lateness and the cancellation signal (signal, never reject).

### 6.4 Threats to validity (say them yourself)

| Threat | Why it matters | What we did or say |
|---|---|---|
| One replica per service, a laptop kind cluster | Not production scale | Stated; scale tests (E3 at 10 to 200 pods with stub sidecars) are planned |
| 5 runs per delivery | Small sample | The two-phase result is deterministic by design (0 in all runs); the naive result is shown per order |
| Fixed 4 s gap in the ordered naive runs | Could look like a chosen worst case | The random run shows the harm also happens by chance; a 4 s gap is short compared with ConfigMap-mounted configuration (up to the kubelet sync period plus cache delay) [K2] |
| Synthetic traffic (bursts every 4 s) | The size of the harm depends on the traffic | We report the harm per run (2 to 3 of 24, about 10 %) and say it is driven by one burst in the window |
| Background traffic on | Adds noise | Kept on on purpose: the demo cluster always has it; both modes see the same |
| Only two enforced settings (deadline, rate) | The harm is shown with these two | Stated; more coupled settings come with C2's retry phase |
| The demo gateway app lag | Could have caused mixed states that are the app's, not the rollout's | Found and fixed before E4 (`stampedBudget`) |

### 6.5 How to reproduce every number

```bash
# Git Bash, repo root, platform deployed (scripts/deploy.sh)
MSYS_NO_PATHCONV=1 scripts/test-e2e.sh 96                       # suite 96, about 3 minutes
MSYS_NO_PATHCONV=1 experiments/c3/e4-consistent-rollout.sh       # E4, 5 runs x 4 deliveries, about 13 minutes
RUNS=10 MSYS_NO_PATHCONV=1 experiments/c3/e4-consistent-rollout.sh   # more runs
# unit tests
cd k8s/operator && go test ./...      # (on Windows: go test -c -o <scratch>/op.test.exe . and run it)
cd hub && go test ./...
mvn -f netty-sidecar-proxy/pom.xml test
```

---

## 7. Related work and positioning

### 7.1 The idea comes from consistent network updates (software-defined networking)

**Reitblatt, Foster, Rexford, Schlesinger, Walker, "Abstractions for Network Update", SIGCOMM 2012 [R1]** (preceded by the HotNets 2011 paper [R2]). Problem: when the forwarding rules of many switches are changed, packets in flight can be forwarded partly by old and partly by new rules (loops, black holes, security holes). Idea: **per-packet consistency**: every packet is processed entirely by the old configuration or entirely by the new one. Mechanism (two-phase update): (1) install the new rules on the internal switches, matching a new **version tag**, which no packet carries yet, so nothing changes; (2) switch the **ingress** switches to stamp the new tag on arriving packets; (3) wait until packets with the old tag have left the network; (4) remove the old rules. They prove that such updates preserve a large class of properties.

| Reitblatt et al. | C3 |
|---|---|
| Packets, switches, forwarding rules | Requests, sidecars, resilience settings |
| Version tag in the packet header (for example a VLAN field) | Epoch in the request context (`pe` in `X-RPE-Context`) |
| Ingress switches stamp the tag | The gateway sidecar stamps the epoch |
| Internal switches match on the tag | Sidecars pin each request to the epoch it carries |
| Wait for old packets to leave (in practice a bound on packet lifetime) | **Drain gate on measured in-flight counts per epoch** |
| Every rule can match on the tag, so per-packet consistency is enough | **The rate belongs to the sidecar and cannot match on the epoch**, so C3 adds an **order: loosen, drain, tighten** |

How to say it: "We apply the two-phase consistent update of Reitblatt et al. to resilience policies in an HTTP microservice chain. Two things are different: our version travels in the application request context and the drain is a measured count, and some of our settings cannot be selected per request, so we also order the change by its effect."

**Follow-up work on ordering and scheduling updates [R3]-[R7]:** Mahajan and Wattenhofer (HotNets 2013) study weaker but cheaper consistency through ordered updates; zUpdate (SIGCOMM 2013) plans updates in data centres with zero loss; Dionysus (SIGCOMM 2014) schedules updates dynamically by dependencies; McClurg et al. (PLDI 2015) synthesise correct update orders; the survey by Foerster, Schmid and Vissicchio (2019) covers the whole area. Relation: C3's loosen-before-tighten rule is a small instance of "order the update by its dependencies", applied to resilience settings.

### 7.2 Version consistency and safe dynamic reconfiguration (software engineering)

**Kramer and Magee, "The Evolving Philosophers Problem: Dynamic Change Management", IEEE TSE 1990 [V3]:** a component may be changed safely when it is **quiescent** (not involved in any transaction and will not be). Relation: our drain gate is a quiescence condition for the old version: we tighten only when no request of the old epoch is in flight. Difference: we do not stop the service; new requests continue under the new epoch.

**Vandewoude et al., "Tranquility", IEEE TSE 2007 [V4]:** a weaker, less disruptive condition than quiescence. Relation: same family of conditions; we use a count per version instead of blocking components.

**Ma, Baresi, Ghezzi, Panzica La Manna, Lu, "Version-consistent Dynamic Reconfiguration of Component-based Distributed Systems", ESEC/FSE 2011 [V1]**, journal version by Baresi, Ghezzi, Ma, Panzica La Manna, "Efficient Dynamic Updates of Distributed Components Through Version Consistency", IEEE TSE [V2] (check the exact author list and year in the ACM DL and IEEE Xplore before citing). **This is the closest prior art; name it.** Idea: a distributed transaction must be served by the old versions of all components or by the new versions of all components (**version consistency**), which allows updating without waiting for full quiescence. Difference from C3:
- they update **code** of components and track transaction dependencies inside the middleware; C3 updates **resilience settings** of unmodified services, and the version travels in the request context the sidecars already carry;
- C3 has settings that cannot be per request (the rate) and therefore adds the loosen-before-tighten order;
- C3 measures the harm in a running Kubernetes system and shows it live.

**Ajmani, Liskov, Shrira, "Modular Software Upgrades for Distributed Systems", ECOOP 2006 [V5]:** upgrades of distributed systems where old and new versions run at the same time, with simulation objects between versions. Relation: the mixed-version period is a known, studied problem; C3 avoids mixed versions per request instead of translating between them.

**Zhang et al., "Understanding and Detecting Software Upgrade Failures in Distributed Systems", SOSP 2021 [U1]:** 123 real upgrade failures in 8 widely used distributed systems; many come from incompatibilities between old and new versions while both are running; upgrade failures extend the disruption the upgrade causes. Relation: evidence that the window in which two versions coexist is dangerous in practice.

### 7.3 Configuration management in industry

**Tang et al., "Holistic Configuration Management at Facebook", SOSP 2015 [C1]:** configuration changes thousands of times a day; configuration errors are a major source of incidents; Facebook uses canarying and gradual rollout of configuration. Relation: industry treats configuration changes as risky and rolls them out gradually; that reduces the blast radius of a bad value, but it does not guarantee that a request sees one version of a change that spans services.

**Yin et al., SOSP 2011 [C2]; Xu et al., OSDI 2016 [C3]; Gunawi et al., SoCC 2016 [C4]; Maurer, "Fail at Scale", ACM Queue 2015 [S5]:** studies and reports on configuration errors and outages; use them as background for "changes cause outages".

**Google SRE book [S1]:** "roughly 70% of outages are due to changes in a live system"; recommends progressive rollouts, fast detection and safe rollback.

### 7.4 Service meshes and Kubernetes configuration

| System | What it provides | What it does not provide (as far as its documentation shows) |
|---|---|---|
| Envoy xDS [D1] | Push of configuration to each proxy; explicitly eventually consistent; recommends a make-before-break order of configuration types inside a proxy | No version per request; no ordering of a change across different services by its effect |
| Istio [D2] | Translates Kubernetes resources into xDS; `istioctl proxy-status` shows SYNCED or STALE per proxy | Shows per-proxy convergence; no per-request version guarantee is described. **Check the current Istio documentation before claiming this on a slide** |
| Kubernetes ConfigMap [K2] | Configuration as files or environment variables | Mounted files update after up to the kubelet sync period plus cache delay, per pod at different times; environment variables never update without a restart |
| Progressive delivery (Argo Rollouts, Flagger) | Canary releases of application versions with automatic analysis | Concerned with application versions and traffic splitting, not with the consistency of resilience settings across a chain |
| Feature flags | A flag value evaluated per request in each service | Each service evaluates its own flags; nothing makes two services in one chain use the same version of a joint change |

### 7.5 Analogies the panel may use

- **Snapshot isolation in databases (Berenson et al., SIGMOD 1995 [T2]):** a transaction reads one consistent snapshot. Our request is "served under one snapshot of the policy". Good to mention as an intuition.
- **Consistent cuts and distributed snapshots (Chandy and Lamport, 1985 [T1]):** the idea of a consistent global state. The epoch boundary is a consistent cut of the policy across the chain.
- **gRPC deadlines and cancellation [D3]:** the framework delivers the signal, the application decides; RPE's deadline model follows the same idea (C2), and C3 makes sure the deadline a request carries fits the settings it meets.

### 7.6 The honest novelty claim

"The concept of per-packet or per-transaction consistency is not new; it comes from Reitblatt et al. and from version consistency. What is new in our work is its application to **resilience policies of HTTP microservices behind sidecars on Kubernetes**: the version is carried in the request context that the system already propagates; the drain is decided by **measured per-epoch in-flight counts** reported by the sidecars; the change is **ordered by its effect** (loosen, drain, tighten) because some settings belong to a sidecar and cannot be selected per request; and we **measure** the harm that naive delivery causes and show that the consistent rollout prevents it."

---

## 8. Reading list

Mark: **must** = read before the panel, **should** = read the sections listed, **background** = know what it is.

### 8.1 Must read

1. **[R1] M. Reitblatt, N. Foster, J. Rexford, C. Schlesinger, D. Walker. "Abstractions for Network Update." ACM SIGCOMM 2012.** PDF: `https://www.cs.princeton.edu/~jrex/papers/sigcomm12.pdf`. Read: introduction, the definition of per-packet consistency, the two-phase update mechanism, the discussion of costs (holding two rule sets). Take away: the four steps (install with new tag, stamp at ingress, wait, remove) and why the tag is needed. Be able to draw it.
2. **[V1] X. Ma, L. Baresi, C. Ghezzi, V. Panzica La Manna, J. Lu. "Version-consistent Dynamic Reconfiguration of Component-based Distributed Systems." ESEC/FSE 2011** (check the author list in the ACM Digital Library), and the journal version **[V2]** "Efficient Dynamic Updates of Distributed Components Through Version Consistency", IEEE Transactions on Software Engineering. Read: the definition of version consistency and how it is enforced. Take away: the closest prior art and the three differences in 7.2.
3. **[V3] J. Kramer, J. Magee. "The Evolving Philosophers Problem: Dynamic Change Management." IEEE Transactions on Software Engineering 16(11), 1990.** Read: the definition of quiescence. Take away: the drain gate is a quiescence condition for the old version.
4. **[D1] Envoy documentation, "xDS REST and gRPC protocol", section "Eventual consistency considerations"**: `https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol`. Take away the exact sentences: the xDS APIs are eventually consistent, "traffic may drop briefly during updates", and the make-before-break order.
5. **[K2] Kubernetes documentation, "ConfigMaps", section "Mounted ConfigMaps are updated automatically"**: `https://kubernetes.io/docs/concepts/configuration/configmap/`. Take away: the delay is up to the kubelet sync period plus the cache propagation delay; environment variables and subPath mounts are not updated.
6. **[S1] B. Beyer, C. Jones, J. Petoff, N. R. Murphy (eds.). "Site Reliability Engineering: How Google Runs Production Systems." O'Reilly, 2016** (free online at `https://sre.google/sre-book/table-of-contents/`). Read: the introduction (the 70 % sentence), "Addressing Cascading Failures", "Release Engineering". Take away: changes cause most outages; cascading failures and deadlines.

### 8.2 Should read

7. **[U1] Y. Zhang, J. Yang, Z. Jin, U. Sethi, K. Rodrigues, S. Lu, D. Yuan. "Understanding and Detecting Software Upgrade Failures in Distributed Systems." ACM SOSP 2021.** PDF: `https://www.cs.purdue.edu/homes/yonglezh/pub/upgrade-sosp21.pdf`. Read: abstract, introduction, the findings on version incompatibility. Take away: the coexistence of versions is a real cause of failures.
8. **[C1] C. Tang et al. "Holistic Configuration Management at Facebook." ACM SOSP 2015**, pp. 328 to 343. Read: introduction and the sections on canary and rollout of configuration. Take away: industry rolls out configuration gradually because changes are risky.
9. **[V4] Y. Vandewoude, P. Ebraert, Y. Berbers, T. D'Hondt. "Tranquility: A Low Disruptive Alternative to Quiescence for Ensuring Safe Dynamic Updates." IEEE TSE 33(12), 2007.** Read: the definition of tranquility and how it compares with quiescence.
10. **[R7] K.-T. Foerster, S. Schmid, S. Vissicchio. "Survey of Consistent Software-Defined Network Updates." IEEE Communications Surveys and Tutorials, 2019.** Read: the overview of consistency properties and of ordering-based updates. Take away: the vocabulary (per-packet consistency, loop freedom, ordering, two-phase).
11. **[S2] J. Dean, L. A. Barroso. "The Tail at Scale." Communications of the ACM 56(2), 2013.** Take away: why latency and deadlines matter in fan-out chains.
12. **[D2] Istio documentation, "Debugging Envoy and Istiod" (`istioctl proxy-status`)** and the istioctl reference. Take away: what SYNCED and STALE mean; check whether any newer Istio feature reports configuration versions per request before the panel.
13. **[S3] N. Bronson, A. Aghayev, A. Charapko, T. Zhu. "Metastable Failures in Distributed Systems." HotOS 2021**, and **[S4] L. Huang et al. "Metastable Failures in the Wild." OSDI 2022.** Take away: overload can sustain itself; changing settings during overload must not add load or harm (motivation for RPE as a whole).

### 8.3 Background (know what they are)

14. [R2] M. Reitblatt, N. Foster, J. Rexford, D. Walker. "Consistent Updates for Software-Defined Networks: Change You Can Believe In!" HotNets 2011.
15. [R3] R. Mahajan, R. Wattenhofer. "On Consistent Updates in Software Defined Networks." HotNets 2013.
16. [R4] H. H. Liu et al. "zUpdate: Updating Data Center Networks with Zero Loss." SIGCOMM 2013.
17. [R5] X. Jin et al. "Dynamic Scheduling of Network Updates" (Dionysus). SIGCOMM 2014.
18. [R6] J. McClurg, H. Hojjat, P. Černý, N. Foster. "Efficient Synthesis of Network Updates." PLDI 2015.
19. [V5] S. Ajmani, B. Liskov, L. Shrira. "Modular Software Upgrades for Distributed Systems." ECOOP 2006.
20. [C2] Z. Yin et al. "An Empirical Study on Configuration Errors in Commercial and Open Source Systems." SOSP 2011.
21. [C3] T. Xu et al. "Early Detection of Configuration Errors to Reduce Failure Damage." OSDI 2016.
22. [C4] H. S. Gunawi et al. "Why Does the Cloud Stop Computing? Lessons from Hundreds of Service Outages." SoCC 2016.
23. [S5] B. Maurer. "Fail at Scale." ACM Queue 13(8), 2015.
24. [K1] B. Burns, B. Grant, D. Oppenheimer, E. Brewer, J. Wilkes. "Borg, Omega, and Kubernetes." ACM Queue 14(1), 2016 (the reconcile-loop and control-plane idea).
25. [T1] K. M. Chandy, L. Lamport. "Distributed Snapshots: Determining Global States of Distributed Systems." ACM TOCS 3(1), 1985.
26. [T2] H. Berenson et al. "A Critique of ANSI SQL Isolation Levels." SIGMOD 1995 (snapshot isolation).
27. [D3] gRPC documentation, "Deadlines" and "Cancellation" (`https://grpc.io/docs/guides/deadlines/`).
28. Kubernetes documentation: "Dynamic Admission Control" (mutating webhooks), "Operator pattern", "Custom Resources".
29. Argo Rollouts and Flagger documentation (progressive delivery), to explain the difference in 7.4.

Note on citations: details of [R1], [K2], [D1], [S1], [U1] (title, authors, venue) and [C1] (pages) were checked on 18 Oct 2026. Check page numbers and author lists of the others in the ACM Digital Library or IEEE Xplore before putting them on a slide.

---

## 9. Demo script

### 9.1 Before the session

- `scripts/deploy.sh` done; `kubectl --context kind-rpe get pods -A` all Running; port-forward or open the Admin Panel; signed in as `admin`.
- Run suite 96 once (`MSYS_NO_PATHCONV=1 scripts/test-e2e.sh 96`): it must pass 29 of 29. This also warms up the cluster.
- Have a **backup**: a screen recording of one naive (payment first) run and one consistent run, and screenshots of the result table and the chart. Docker Desktop on this laptop crashed once during an image build; do not build images on the day.
- Open the E4 CSV and the summary in a second window.

### 9.2 Live (about 3 minutes)

1. **Open Rollouts → "Consistent rollout of a joint change".** Read the sentence at the top: the change (gateway deadline 1000 → 4000 ms, payment rate 100 → 6/s) and the traffic (a burst of 8 every 4 s).
2. **Choose "Naive: the service switches first"** (delay 4000 ms) → **Run** (about 40 s; the stage text shows progress). Point at: "Requests expired" (2 to 3), "With the old deadline" (the same number), "Mixed-policy requests" (about 14). In the chart: the red dots above the dashed 1000 ms line, between the blue line "payment-svc: rate 6/s" and the blue line "gateway: deadline 4000 ms".
3. Say: "The change itself made these requests late. Nobody did anything wrong; the sidecars simply switched in this order."
4. **Choose "Consistent (two-phase)"** → **Run**. Point at: 0 expired, 0 mixed; "Order decided by C3: 1. loosen gateway → wait for the old version to drain → 2. tighten payment-svc"; "Waited before tightening: about 80 ms"; in the chart the gateway's blue line comes first and every dot is green.
5. Optional: **"Naive: random arrival"** once: sometimes harmed, sometimes not. "That is the point: with naive delivery it is luck."
6. Optional: click the rollout in "All rollouts": plan, parts, hash verified, acknowledged by each sidecar, old epoch retired.
7. Show the E4 summary table: 0 of 5 runs harmed with two-phase; 5 of 5 with payment first.

### 9.3 If something goes wrong

- A run shows "Failed": read the error line; usually a sidecar was restarting. Run again; if it fails twice, switch to the recording.
- The naive run shows 0 expired: possible if no burst fell into the window (random arrival). Use "payment first" with 4000 ms, which harmed every run in E4.
- The panel is not reachable: show suite 96 in a terminal and the E4 CSV.

---

## 10. Pitch

### 10.1 Three minutes (word for word)

"In RPE, an approved resilience change is often a joint change: to protect an overloaded payment service we lower its rate and, at the same time, give the chain a longer deadline at the gateway, because at the lower rate requests wait longer in payment's queue. The two values only work together.

But a change never reaches every sidecar at the same moment. If payment's sidecar switches first, requests that still carry the old, short deadline meet the new, low rate and expire. The rollout itself harms requests, in the very moment the system is overloaded. Today's tools accept this, because they only promise that every proxy converges eventually; Envoy's own documentation says traffic may drop during updates.

Our idea comes from consistent network updates in software-defined networking, by Reitblatt and colleagues: every policy version gets a number, an epoch. The gateway stamps it into every request, so every sidecar knows which version each request belongs to and counts how many requests of each version are still running. The Operator first activates the parts that loosen limits, here the gateway's longer deadline. Then it waits until no request of the old version is in flight. Only then does it activate the parts that tighten limits, here payment's lower rate. Then it retires the old version.

[demo: naive, then consistent]

In our experiment, five runs each: the consistent rollout produced zero expired requests and zero mixed-policy requests in every run. Naive delivery always produced mixed-policy requests, and when payment switched first, every run had requests that expired because of the delivery.

So C3 does not only deliver policies to the right sidecars and verify them; it delivers a change that spans services so that no request is ever governed by two versions of it, and it can prove that with numbers."

### 10.2 One minute

"A resilience change often changes several services at once, and the values depend on each other. Because sidecars switch at different moments, requests can meet half of the change and be harmed by the rollout itself. C3 gives every policy version an epoch, the gateway stamps it into each request, the sidecars count requests per epoch, and the Operator loosens first, waits until the old version has drained, and then tightens. In our experiment the consistent rollout harmed no request in 5 of 5 runs, while naive delivery harmed requests in every run in which the service switched first."

### 10.3 Slides (suggested)

1. **Title:** Per-request-consistent rollout of resilience policies (C3).
2. **Problem:** the joint change (two boxes: gateway deadline, payment rate), the timeline of two sidecars switching at different times, the red zone "new rate, old deadline".
3. **Why it matters:** changes cause outages (SRE 70 %); Envoy: eventually consistent, traffic may drop; ConfigMap delay; RPE must not be the cause of harm.
4. **Idea:** epoch, stamp at the gateway, pin at every hop; the five steps as a diagram; credit Reitblatt et al. 2012.
5. **Why the order:** deadline travels with the request, rate belongs to the sidecar; loosen → drain → tighten.
6. **Demo** (live or recording).
7. **Results:** the E4 summary table.
8. **Related work and contribution:** Reitblatt (SDN), version consistency (Ma et al.), Envoy make-before-break, Istio proxy-status; what C3 adds (section 7.6).
9. **Limits and next steps:** section 12 in four lines.

---

## 11. Question bank

### 11.1 Novelty and prior art

1. **Q: Is this not just Reitblatt et al.'s two-phase update?** A: The idea is theirs and we cite it. Three things differ: our version travels in the HTTP request context through unmodified services; we decide the drain from per-epoch in-flight counts that the sidecars report, not from a bound on packet lifetime; and some of our settings (the rate) cannot match on the version, so we add an order by effect: loosen, drain, tighten.
2. **Q: Version consistency (Ma et al.) already guarantees that a transaction sees one version.** A: Yes, for code of components inside a middleware that tracks transactions. We do it for resilience settings of services we do not modify, with the version in the request context of a sidecar chain, plus the order for settings that cannot be per request, plus a measured harm metric in a running Kubernetes system.
3. **Q: What exactly is your contribution then?** A: Section 7.6: application to resilience policies in a sidecar system, version in the request context, drain by measured counts, order by effect, and the evaluation.
4. **Q: Why is this research and not engineering?** A: It defines a consistency property for joint resilience changes, a mechanism that guarantees it under stated assumptions, an ordering rule derived from the type of change, and an experiment that measures the difference with a baseline.
5. **Q: Does Istio do this?** A: Istio and Envoy push configuration per proxy and are eventually consistent; Envoy recommends an update order inside a proxy, and Istio reports SYNCED or STALE per proxy. As far as their documentation shows, they do not stamp requests with a configuration version or wait until requests of the old version have drained. (Check the current documentation before the panel.)
6. **Q: Feature flags evaluate per request. Is that not the same?** A: Each service evaluates its own flags at its own time. Nothing makes two services in one chain use the same version of one joint change.
7. **Q: Is a canary release (Argo Rollouts, Flagger) not enough?** A: Canary limits how many requests see a new version; it does not stop one request from seeing the new version at one service and the old at the next. They solve a different problem and can be combined.

### 11.2 The problem

8. **Q: Is this a real problem or one you created?** A: Changes are the leading cause of outages [S1]; Envoy documents traffic drops during updates [D1]; upgrade studies show version coexistence causes failures [U1]. In RPE, C4's recommendations are joint by design, so joint changes are the normal case.
9. **Q: The deadline travels in the request. What can be mixed?** A: On its own the deadline needs no consistency. A joint change does: the deadline a request carries must fit the rate it meets. And settings two services must agree on (retry limits, `replaySafe`) need it too.
10. **Q: How big is the harm?** A: In the demo 2 to 3 of 24 requests per run with payment first, about 10 %, because one burst falls into the window; with a longer window (for example ConfigMap delivery) it would be larger. The requests are still served (signal, never reject), but late and marked cancelled, so non-critical work is skipped.
11. **Q: Why not just make the deadline long all the time?** A: A long deadline delays the detection of overload and the cancellation signal; deadlines exist to stop work that is no longer useful. The point of the joint change is to lengthen it only while the rate is low.
12. **Q: Why not just change the deadline first by hand and wait?** A: A person cannot know when the requests admitted under the old deadline have finished, and for many services and pods the order must be derived from each change. C3 does both automatically and proves it.

### 11.3 Design choices

13. **Q: Why loosen first?** A: Loosening is safe for old and new requests; tightening is only safe for requests of the new version. So loosen, wait until no old request exists, then tighten.
14. **Q: How do you decide loosening or tightening?** A: By comparing each part with the same service's part in the previous version: a shorter deadline, a lower rate or a lower retry ratio is a tightening (`classify` in `joint.go`). A part that does both counts as a tightening.
15. **Q: What if both parts tighten?** A: Both go to step 2; there is no loosening step, so the gateway is activated in step 2 and the final drain applies before retiring. The guarantee that holds then is per-request pinning plus the drain; a stricter limit always affects requests in flight at that service. This is a limit: some combinations cannot be made harmless by ordering alone.
16. **Q: Why is the gateway first within a step?** A: The gateway stamps the epoch into new requests; services must already hold the new epoch (staged) when the first request carrying it arrives.
17. **Q: Why count in-flight requests instead of waiting for the deadline?** A: Requests can outlive their deadline (signal, never reject), so a timeout is not a safe bound; a count is exact, and in the demo it took 75 to 90 ms instead of a second or more.
18. **Q: Why a part per service instead of sending the whole policy to everyone?** A: A service must not hold settings of another service (earlier, delivering a service policy to the gateway reset its deadlines; that was a real bug). Each sidecar's hash then proves it holds exactly its part.
19. **Q: Why one rollout at a time?** A: The gateway stamps one epoch; two overlapping rollouts would need two stamps per request. One at a time keeps the guarantee simple; it is a stated limit.
20. **Q: Why does the Operator not change the sidecar's mechanisms?** A: Project rule: the deadline, cancellation and backpressure mechanisms belong to C2 and must not change. C3 only decides when and in what order an approved change reaches them.

### 11.4 Correctness and failure cases

21. **Q: What if the Operator crashes during the gate?** A: The rollout's state is in its Kubernetes object; after restart the reconcile loop continues with the gate.
22. **Q: What if a sidecar restarts during a rollout?** A: The pod is marked gone; after the rollout the drift repair gives the new pod the active version (verified by hash).
23. **Q: What if old requests never finish?** A: The gate waits at most `DRAIN_MAX_MS` (30 s), logs it, and continues. The guarantee is bounded by that limit; we state it.
24. **Q: What about requests that do not pass the gateway?** A: They carry no epoch; sidecars serve them under the active version; they are outside the guarantee (they have no chain deadline from the gateway either).
25. **Q: What about several gateway replicas?** A: All gateway sidecars of the step are activated before the services; each request carries the epoch its gateway stamped; the gate counts on every target.
26. **Q: Can the application break the guarantee?** A: Yes, if it creates its own root context from an older copy of the policy. We found exactly that in the demo gateway app (2 s polling) and fixed it there by using the deadline the sidecar stamped. It is a rule for gateway applications.
27. **Q: Are clocks involved?** A: No. The deadline travels as time left (`ttl`), and the gate uses counts, not timestamps.
28. **Q: What does the sidecar do with a request whose epoch it does not hold?** A: It serves it under its active epoch and counts a mixed-policy hop if the epochs belong to the same policy.

### 11.5 Evaluation

29. **Q: Five runs is not much.** A: The two-phase result is deterministic by design and was 0 in every run; the naive result is reported per order. E4 can be re-run with more runs (`RUNS=10`).
30. **Q: You chose the bad order on purpose.** A: To show the case clearly; the random-order run shows it also happens by chance (1 of 5 runs). In a real fleet nobody controls the order.
31. **Q: Why 6 requests/s and not a smaller rate?** A: The demo cluster always has background traffic of about 3 requests/s; at 2/s payment would be overloaded in both modes, and the comparison would show overload, not the rollout. 6/s keeps the system stable and isolates the effect of the delivery.
32. **Q: What is a mixed-policy request exactly?** A: A request that a sidecar served under another version of the same policy than the epoch it carries (`rpe_policy_mismatch_total`).
33. **Q: In "gateway first" there were 17 mixed requests but no harm. So mixed is harmless?** A: Mixed means the request saw two versions; whether that hurts depends on the combination. This time the combination was harmless (long deadline, old high rate); with payment first it was harmful. The consistent rollout removes both.
34. **Q: How do you measure "expired because of the delivery"?** A: Demo requests started after the approval whose result carries `cs=expired`; all of them carried the old deadline; before the approval no request expired.
35. **Q: Does the consistent rollout make delivery slower?** A: About 0.4 s in total, of which the gate was 75 to 90 ms; naive with a 4 s gap took 4.2 s only because of the gap.
36. **Q: Does it scale?** A: Measured at one replica per service. Scale tests with stub sidecars (10 to 200 pods) are planned (step 5 of the plan). The gate time grows with the longest request in flight, not with the number of pods.

### 11.6 Scope and integration

37. **Q: Where does the joint change come from?** A: In the real flow C4 recommends it and a human approves it. Today an admin creates it in the Admin Panel, because C4 does not produce joint proposals yet (requests C4-1 to C4-4). C3 handles both identically.
38. **Q: Did you change C2's sidecar for this?** A: Not for the joint delivery. The sidecar features it uses (stamping, pinning, in-flight counts, mismatch counter, hash, acknowledgements, rate on activation) were added earlier with C2's agreement.
39. **Q: What did C2 decline?** A: Applying `backpressure.mode` per request epoch (Level B), because it touches the backpressure code. So in the demo the protection comes from the order plus the drain gate, which the stamp makes possible.
40. **Q: How is human approval kept?** A: The policy store rejects any version without an approver; C3 never creates or changes a policy by itself; the demo records the signed-in user as approver.

### 11.7 Future work

41. **Q: What next?** A: Per-hop `pv` from the sidecar (C2-2), joint recommendations from C4, retry limits and `replaySafe` per epoch when C2 builds retries (a setting where a mixed state is harmful by itself), scale tests, automatic halt and admin revert.
42. **Q: Could you prove the property formally?** A: Reitblatt et al. give a formal model for packets; a similar argument applies: no request carries the new epoch before every sidecar holds it (staging), and no tightened setting is active while a request of the old epoch exists (the gate). A formal model is future work.

---

## 12. Limits and future work (say them first)

1. **The protection comes from the order plus the drain gate,** made possible by the stamp; the sidecar does not apply the enforced settings per request. C2 declined the per-request setting (`backpressure.mode`); per-request settings come with C2's retry phase.
2. **The joint change is created by an admin** until C4 sends joint recommendations (C4-1 to C4-4).
3. **`pv` per hop** (which version each hop applied) is not yet written by the sidecar (C2-2); the gateway application reports the epoch it stamped.
4. **Scale:** measured with one replica per service on a laptop.
5. **The guarantee is bounded** by `DRAIN_MAX_MS` (30 s) and applies to requests that enter through the gateway.
6. **One rollout at a time.**
7. **Not every joint change can be made harmless by ordering:** if every part tightens, requests in flight at a service still meet its stricter limit; pinning and draining still prevent mixed versions.
8. **Gateway applications** that create their own root contexts must use the deadline their sidecar stamped.

---

## 13. Glossary

| Term | Meaning |
|---|---|
| Chain | All the calls that serve one external request, from the gateway down |
| Chain deadline | The time budget of a chain, stamped at the gateway (`bud`, `ttl`) |
| `cs` | Cancellation state in the context and the result: `none`, `cancel`, `expired` |
| Drain gate | Waiting until no request of the old epoch is in flight before tightening |
| Drift | A sidecar that does not hold the approved active version (detected by hash, repaired automatically) |
| Epoch | Identifier of one policy version, `<policyId>-v<n>` |
| Eventual consistency | All instances end with the same version; no promise about the time before |
| Joint policy | One version with a part per service, one epoch |
| Leaky bucket | Inbound queue released at a steady rate; the rate is a property of the sidecar |
| Loosening / tightening | A change that relaxes / restricts a limit |
| Make-before-break | Envoy's recommended order of configuration updates inside a proxy |
| Mixed-policy request | A request served under another version of the policy than the epoch it carries |
| Naive delivery | Each sidecar switches on arrival and drops the old version |
| Part | The share of a joint policy for one service |
| `pe` | Policy epoch field in `X-RPE-Context`, stamped by the gateway, immutable along the chain |
| Pin | Serving a request under the epoch it carries |
| Propagation window | The time between the first and the last sidecar switching |
| Quiescence | A component is idle and will stay idle, so it can be changed safely (Kramer and Magee) |
| Signal, never reject | RPE marks a late request cancelled but does not fail it |
| Stage | Deliver a version inactive, verified by hash |
| Two-phase update | Install the new version unobservably, then switch the ingress (Reitblatt et al.) |
| Version consistency | A distributed transaction is served by all-old or all-new versions (Ma et al.) |

---

## 14. Checklist before the panel

- [ ] Can say the novelty, the problem and the five steps without notes (section 0.1).
- [ ] Read [R1], [V1], [V3], [D1], [K2], [S1] (section 8.1); can draw Reitblatt's two-phase update.
- [ ] Checked the Istio documentation for any per-request configuration version feature; adjusted answer 11.1-5 if needed.
- [ ] Checked author lists and pages of every paper on the slides.
- [ ] Ran suite 96 on the demo laptop (29 of 29) on the day before.
- [ ] Recorded a backup video of a naive (payment first) and a consistent run.
- [ ] E4 CSV and summary open; numbers memorised (0 of 5, 5 of 5, 14 to 17 mixed, gate 75 to 90 ms).
- [ ] Rehearsed the 3-minute pitch twice and answered 15 questions from section 11 with a teammate.
- [ ] Agreed with the supervisor that the Level A demonstration (no C4 joint proposal yet, no per-request sidecar setting) is acceptable.
- [ ] Told the C2 owner about the demo-app gateway fix.

---

## 15. References

[R1] M. Reitblatt, N. Foster, J. Rexford, C. Schlesinger, D. Walker. Abstractions for Network Update. ACM SIGCOMM 2012. https://www.cs.princeton.edu/~jrex/papers/sigcomm12.pdf
[R2] M. Reitblatt, N. Foster, J. Rexford, D. Walker. Consistent Updates for Software-Defined Networks: Change You Can Believe In! ACM HotNets 2011.
[R3] R. Mahajan, R. Wattenhofer. On Consistent Updates in Software Defined Networks. ACM HotNets 2013.
[R4] H. H. Liu, X. Wu, M. Zhang, L. Yuan, R. Wattenhofer, D. Maltz. zUpdate: Updating Data Center Networks with Zero Loss. ACM SIGCOMM 2013.
[R5] X. Jin, H. H. Liu, R. Gandhi, S. Kandula, R. Mahajan, M. Zhang, J. Rexford, R. Wattenhofer. Dynamic Scheduling of Network Updates. ACM SIGCOMM 2014.
[R6] J. McClurg, H. Hojjat, P. Černý, N. Foster. Efficient Synthesis of Network Updates. ACM PLDI 2015.
[R7] K.-T. Foerster, S. Schmid, S. Vissicchio. Survey of Consistent Software-Defined Network Updates. IEEE Communications Surveys and Tutorials, 2019.
[V1] X. Ma, L. Baresi, C. Ghezzi, V. Panzica La Manna, J. Lu. Version-consistent Dynamic Reconfiguration of Component-based Distributed Systems. ESEC/FSE 2011 (check the author list in the ACM DL).
[V2] L. Baresi, C. Ghezzi, X. Ma, V. Panzica La Manna. Efficient Dynamic Updates of Distributed Components Through Version Consistency. IEEE Transactions on Software Engineering (check volume and year).
[V3] J. Kramer, J. Magee. The Evolving Philosophers Problem: Dynamic Change Management. IEEE Transactions on Software Engineering 16(11), 1990.
[V4] Y. Vandewoude, P. Ebraert, Y. Berbers, T. D'Hondt. Tranquility: A Low Disruptive Alternative to Quiescence for Ensuring Safe Dynamic Updates. IEEE Transactions on Software Engineering 33(12), 2007.
[V5] S. Ajmani, B. Liskov, L. Shrira. Modular Software Upgrades for Distributed Systems. ECOOP 2006.
[U1] Y. Zhang, J. Yang, Z. Jin, U. Sethi, K. Rodrigues, S. Lu, D. Yuan. Understanding and Detecting Software Upgrade Failures in Distributed Systems. ACM SOSP 2021. https://www.cs.purdue.edu/homes/yonglezh/pub/upgrade-sosp21.pdf
[C1] C. Tang, T. Kooburat, P. Venkatachalam, A. Chander, Z. Wen, A. Narayanan, P. Dowell, R. Karl. Holistic Configuration Management at Facebook. ACM SOSP 2015, pp. 328-343.
[C2] Z. Yin, X. Ma, J. Zheng, Y. Zhou, L. N. Bairavasundaram, S. Pasupathy. An Empirical Study on Configuration Errors in Commercial and Open Source Systems. ACM SOSP 2011.
[C3] T. Xu, X. Jin, P. Huang, Y. Zhou, S. Lu, L. Jin, S. Pasupathy. Early Detection of Configuration Errors to Reduce Failure Damage. USENIX OSDI 2016.
[C4] H. S. Gunawi et al. Why Does the Cloud Stop Computing? Lessons from Hundreds of Service Outages. ACM SoCC 2016.
[S1] B. Beyer, C. Jones, J. Petoff, N. R. Murphy (eds.). Site Reliability Engineering: How Google Runs Production Systems. O'Reilly, 2016. https://sre.google/sre-book/table-of-contents/
[S2] J. Dean, L. A. Barroso. The Tail at Scale. Communications of the ACM 56(2), 2013.
[S3] N. Bronson, A. Aghayev, A. Charapko, T. Zhu. Metastable Failures in Distributed Systems. HotOS 2021.
[S4] L. Huang, M. Magnusson, A. B. Muralikrishna, S. Estyak, R. Isaacs, A. Aghayev, T. Zhu, A. Charapko. Metastable Failures in the Wild. USENIX OSDI 2022.
[S5] B. Maurer. Fail at Scale. ACM Queue 13(8), 2015.
[K1] B. Burns, B. Grant, D. Oppenheimer, E. Brewer, J. Wilkes. Borg, Omega, and Kubernetes. ACM Queue 14(1), 2016.
[K2] Kubernetes documentation. ConfigMaps (Mounted ConfigMaps are updated automatically). https://kubernetes.io/docs/concepts/configuration/configmap/
[D1] Envoy documentation. xDS REST and gRPC protocol, Eventual consistency considerations. https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol
[D2] Istio documentation. Debugging Envoy and Istiod (istioctl proxy-status). https://istio.io/latest/docs/ops/diagnostic-tools/proxy-cmd/
[D3] gRPC documentation. Deadlines. https://grpc.io/docs/guides/deadlines/
[T1] K. M. Chandy, L. Lamport. Distributed Snapshots: Determining Global States of Distributed Systems. ACM Transactions on Computer Systems 3(1), 1985.
[T2] H. Berenson, P. Bernstein, J. Gray, J. Melton, E. O'Neil, P. O'Neil. A Critique of ANSI SQL Isolation Levels. ACM SIGMOD 1995.

Project evidence: `experiments/c3/results/e4-20261018-150733.csv`, `experiments/c3/results/e4-20261018-150733-summary.csv`, `tests/e2e/96-consistent-rollout.sh`, `k8s/operator/joint.go`, `k8s/operator/joint_test.go`, commit `a31d1d9`.
