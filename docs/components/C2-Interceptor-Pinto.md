# C2: Service Interceptor Layer (A-to-Z Guide)

**Owner:** R.R.D Pinto (IT23319806). **Project:** J26-SE-336 RPE. **Guide version:** 1.0 (15 Oct 2026).

> This file is self-contained. It holds everything about C2: the proposal commitments, groomed scope, novelty, design, contracts, existing code, plan, evaluation, UI, viva and checklists. The appendix at the end is the shared ecosystem reference, identical in all four component files.

---

## 0. How to use this file with Claude

From the repository root:

```
claude "Read docs/components/C2-Interceptor-Pinto.md completely before doing anything. Then <task>."
```

Example tasks:
- `"...Then scaffold sidecar/ per §7.1 (Netty, pooled upstream connections, inbound + outbound listeners) with tests."`
- `"...Then implement the leaky bucket + graded backpressure levels (P2.1) per §7.4."`
- `"...Then implement the coordinated Resilience Decision Function (P3.1) per §7.3 with unit tests for every branch."`
- `"...Then write the Envoy-equivalent baseline config (P4.2) per §11."`
- `"...Then build the C2 console module per §12."`
- `"...Then check my work against the Definition of Done in §15."`

**Rules Claude must follow for C2 work:**
1. Obey **Appendix A8**. Never remove a proposal commitment (§4). **The interceptor notifies; it never forcibly terminates application work.**
2. C2 owns: `sidecar/`, `experiments/c2/`, `scenarios/` (lead), its pages in `admin-panel/web/src/pages/`. Don't edit other components' folders.
3. **Use C1's `rpe-java` library for every header read and write.** No ad-hoc headers (no `X-Proxy-*`, no `Request_id`). If you need a new field, propose it to C1 (`spec/CHANGES.md`).
4. Metric names and labels follow **A3.3** exactly; C4 depends on them.
5. Java 21, Netty 4.1.x, `./mvnw -q verify` must pass. Every mechanism needs unit tests and an integration test against a stub upstream.
6. Every mechanism must be toggleable through the policy `mechanisms` block (needed for the ablation and the console).
7. After finishing a phase task, update **§2 Status** in this file.

---

## 1. Identity and role

- **Component:** Service Interceptor Layer (C2).
- **Role:** *enforces the rules at runtime.* An HTTP sidecar proxy sits in front of every service. It reads the RPE context with C1's library and enforces deadline propagation, cancellation propagation, retry-storm prevention and backpressure. It emits telemetry for C4 and applies the policies C3 delivers.
- **Research group / cluster / SDGs:** Software Systems and Technologies; Distributed Systems and Cloud Native Computing; SDG 9, 12, 13.

## 2. Status (update after every phase)

| Item | State (15 Oct 2026) |
|---|---|
| Netty sidecar | **Exists** in `_research/old_shits/netty-sidecar-proxy/` (Java 17, Netty 4.1.115, about 1,400 LOC, **1 trivial test**). Works for deadline and cancellation *flags* in a TPROXY setup: the gateway creates a request id and an absolute deadline per route (`GatewayDeadlineRules`); sidecars store context per request id and detect expiry with a timer; OTel spans and Prometheus metrics exist |
| Problems | (1) Doesn't use C1's library; uses its own headers `Request_id`, `deadlinevalue`, `deadlineExceded` (misspelled), `cancellation_Triggered`, `Request_route`, `Request_deadline_millis`, `Request_method`. (2) **A new TCP connection per request** (new `Bootstrap` each time, no pooling), which would ruin the overhead results. (3) Cancellation only flips a flag; nothing is delivered to the app or downstream connections. (4) **Retry budget missing.** (5) **Backpressure missing** in this repo (C2's newer code with 7 `X-Proxy-*`/`X-Backpressure-*` headers isn't committed). (6) A static global `ConcurrentHashMap` keyed by request id; a 500-line god class (`ProxyHandler`); INFO log per request; `HttpObjectAggregator` buffers whole bodies (10 MB) |
| Worth reusing | TPROXY original-destination handling; loopback forwarding for inbound (avoids re-interception); gateway trust boundary (regenerate the context at the edge, never trust the client); `GatewayDeadlineRules` (method + path-template → budget); OTel / Prometheus setup in `ProxyTelemetry` and `ProxyMetrics`; the `DEADLINE_TPROXY_GUIDE.md` notes |
| **Built in the monorepo (15 Oct 2026)** | `sidecar/`: a clean Java 21 + Netty **pass-through skeleton** (P1.1 done). It has:
- an outbound listener 15001 (destination from the Host header or absolute URI);
- an inbound listener 15006 (REDIRECT → `127.0.0.1:RPE_APP_PORT`; TPROXY keeps the original port);
- **pooled keep-alive upstream connections** (`UpstreamPool`), replacing the per-request `Bootstrap`;
- `X-RPE-Hop` stripping;
- the control API on 15020 (`/rpe/v1/health`, policy PUT staged/active, activate, retire-if-not-active, and since 16 Oct `GET /rpe/v1/policy/active`, which returns the active policy document; the gateway app follows it for its epoch and route deadlines);
- Prometheus on 15090, using the A3.3 contract names:
  - `rpe_requests_total{direction,outcome}` (every outcome series initialised to 0);
  - `rpe_original_requests_total{direction}`;
  - `rpe_retries_total{result}` and `rpe_rejections_total{reason}` (genuinely 0 until P2/P3 land);
  - `rpe_request_duration_seconds`, `rpe_proxy_overhead_seconds`, `rpe_inflight_requests`;
  - on the gateway, per-route `rpe_chain_requests_total` and `rpe_chain_deadline_exceeded_total`, from `X-RPE-Result`, capped at 64 routes.
  Prometheus (`rpe-prometheus`) scrapes every sidecar, and C4 analyses these live.

The image is `rpe/sidecar:dev` and runs as UID 1337. The same image serves as the `rpe-init` container. 8 JUnit tests; e2e suites 20/50 verify interception, including root containers, on Kubernetes; suite 95 verifies the metrics in Prometheus.

Extension points are marked in `ProxyHandler` and `AdminHandler`.

**Next:** P1.2, parsing the context with `rpe-java`. It is now available in `libs/rpe-java`: `RpeContext.decode/child`, `ContextGuard`, and `http.Admission` for the inbound step. Then the deadline, cancellation, leaky-bucket and retry mechanisms, incrementing `rpe_retries_total` / `rpe_rejections_total` as they act. |
| Proposal appendix PoC | Spring filters (`DeadlineLoggingGlobalFilter`, `CancellationContext`, `CancellationRequestWrapper`, `DeadlinePropagationFeignConfig`, `api.js`), not in this repo. The ideas carry over into the sidecar and C1's adapters |
| Verdict | **Rewrite** in `sidecar/`, borrowing the parts above (about 15% reuse) |

## 3. Problem and research gap

When a service is overloaded, the overload spreads along the request chain (cascading failure). Retries amplify load (retry storms), and expired requests keep consuming resources.

Existing tools treat each mechanism separately:
- Resilience4j works inside one app.
- Envoy and Istio apply timeouts, retries and rate limits independently and carry no deadline/cancellation context across hops.
- gRPC has deadlines and cancellation only inside gRPC.
- BLOC focuses on overload control.

There's no proxy that enforces **all four** mechanisms for **plain HTTP** inter-service calls without major code changes, and none that *coordinates* them.

## 4. Proposal commitments (MUST KEEP; this is the checklist)

Source: the C2 proposal PDF (IT23319806, September 2026).

**Abstract:**
- [ ] A proxy-level framework enforcing **deadline propagation, cancellation propagation, retry-storm prevention, backpressure handling** for HTTP services.
- [ ] Minimal existing code modification.
- [ ] The proxy sits between HTTP services.
- [ ] Evaluation through near-deadline requests, traffic bursts, and enforced vs unenforced behaviour, measuring the impact on **resiliency, network traffic and cloud cost**.

**Design statements in the gap and architecture sections:**
- [ ] Backpressure uses a **leaky bucket** in the interceptor. When many requests are rejected, a **backpressure signal is sent to the caller** so it slows down.
- [ ] Deadline values come from the adaptability/intelligence layer (C4).
- [ ] When a deadline is exceeded, the interceptor **propagates a cancellation notification downstream**. The service decides whether to stop; **the interceptor does not forcefully terminate work**, and safe cancellation is the developer's responsibility.
- [ ] The interceptor adds the resilience values (deadline, cancellation state, backpressure level) to the header defined by the protocol layer, and **validates using C1's library**.
- [ ] Deployed as a **Kubernetes sidecar**.
- [ ] **Sends metrics to the intelligence layer.**

**Main objective:** [ ] implement and evaluate a service interceptor enforcing the four mechanisms.

**Specific objectives:**
1. [ ] An interceptor proxy that reads and enforces the 4 resilience signals carried by the RPE protocol.
2. [ ] Deadline propagation and cancellation propagation.
3. [ ] Backpressure using the leaky bucket, **signalling backpressure levels to callers** to slow them down.
4. [ ] Retry-storm prevention using **jitter, backoff and a retry budget**.
5. [ ] Evaluate under different workloads and failures: request latency, rejected requests, retry activity, recovery behaviour.
6. [ ] Compare microservices **with vs without** the interceptor (overload, cascading failures, resiliency).

**Methodology:**
- [ ] Design Science Research + controlled experiments.
- [ ] Same topology and workload for baseline and treatment.
- [ ] Plan → execute → collect → analyse → interpret (Wohlin).

**Experimental inputs and scenarios:**
- Inputs: [ ] request rates, processing delays, overload conditions, deadline values, cancellation states, retry conditions.
- Scenarios: [ ] normal traffic; [ ] increasing traffic → overload; [ ] requests approaching/exceeding deadlines; [ ] cancelled requests; [ ] overloaded services returning backpressure signals; [ ] rejected/failed requests triggering retries; [ ] combined overload + retry (retry storms).
- Measurements: [ ] latency, successful and rejected requests, retry activity, deadline failures, cancellation events, backpressure events, recovery time.
- [ ] Repeated runs; [ ] warm-up period.

**Baselines:**
- [ ] A baseline environment **without** the interceptor.
- [ ] An **ablation**: individual mechanisms enabled separately.
- [ ] The complete interceptor.

**Metrics table:**

| Metric | Comparison |
|---|---|
| p95/p99 latency | Baseline vs interceptor |
| Retry count per successful request | Baseline vs interceptor |
| HTTP 429 rejection rate | Baseline vs interceptor |
| Deadline-related failures | Expected behaviour under deadline propagation |
| Cancellation events (downstream and upstream) | Expected behaviour |
| Recovery time | Baseline vs interceptor |
| Request throughput | Baseline vs interceptor |
| Interceptor overhead | With vs without |

**Validation:**
- [ ] Each mechanism tested with a scenario designed to trigger it.
- [ ] Combined conditions.
- [ ] Backpressure: an overloaded downstream returns the signal, and **the caller reduces request pressure**.
- [ ] Overload + failures → retry budget, backoff and jitter limit retries.
- [ ] Near-deadline requests → exceedance detected and cancellation propagated.
- [ ] Repeated runs, descriptive statistics.

**Development approach (stages):**
1. [ ] Basic HTTP interception.
2. [ ] Deadline and cancellation.
3. [ ] Backpressure.
4. [ ] Retry-storm prevention.

Test after each stage.

**Responsibility table:**
- **Own:** the interceptor and the 4 mechanisms.
- **Shared:** protocol integration for header creation and validation (C1); Kubernetes sidecar deployment (C3); sending operational metrics to C4; integration and end-to-end validation (all).

**Functional requirements:**
1. [ ] Resilience information injected into outgoing HTTP headers.
2. [ ] Deadline propagation of the remaining deadline across the chain.
3. [ ] Cancellation enabled after deadline exceedance and propagated to relevant services.
4. [ ] Retry-storm prevention.
5. [ ] Backpressure via leaky-bucket rate limiting **and** signalling to callers.
6. [ ] Metrics collection (retry, backpressure, latency) and transmission to C4.
7. [ ] Transparent inter-service communication (apps keep using HTTP).

**Non-functional requirements:**
- [ ] **Performance:** minimal added latency, measured against no interceptor.
- [ ] **Reliability:** consistent under overload, near-deadline requests and retry bursts.
- [ ] **Compatibility:** HTTP, plus C1's headers and library.
- [ ] **Maintainability:** no major changes to service code.
- [ ] **Resource efficiency:** limited CPU and memory as a sidecar, measured.

**Appendix PoC artefacts named in the proposal:** `api.js`, `DeadlineLoggingGlobalFilter.java`, `CancellationState.java`, `CancellationRequestWrapper.java`, `CancellationContext.java`, `DeadlinePropagationFeignConfig.java`, `DeadlineLoggingFilter.java`.

**Commercialisation and budget:** shared team plan (SaaS tiers; open-source tools; cloud testing cost).

## 5. Novelty (groomed and defensible)

### 5.1 Primary novelty: a coordinated per-hop Resilience Decision Function

Instead of four independent mechanisms, the sidecar makes **one decision per request** from four inputs:
- remaining deadline (from `X-RPE-Context`);
- an estimate of downstream latency (EWMA/percentile per destination);
- downstream pressure (aggregated backpressure level);
- remaining retry budget (C1 ledger).

The outcomes are:
- **forward**;
- **early-reject**: the request can't finish in time;
- **fail-fast**: the downstream is saturated;
- **retry-allowed**: the retry fits the remaining time *and* pressure is low *and* budget remains.

All of this happens in a **transparent sidecar for plain HTTP**, with no app changes.

**Research question.** Does one coordinated per-hop decision across deadline, latency, pressure and budget beat the same mechanisms applied independently, and an equivalent Envoy configuration, on wasted work, p99 latency, retry amplification and recovery time?

### 5.2 Supporting contributions

1. **Push + piggyback graded backpressure.** This adopts C2's caller-token design, redesigned to fit C1's protocol.
   - The receiver's leaky bucket gives a utilisation, which maps to a level 0–3.
   - The receiver **pushes** "active/cleared + level + utilisation" to caller sidecars (`POST /rpe/v1/backpressure`) **and** piggybacks the same signal on responses (`X-RPE-Result: bp, bpu`).
   - The caller aggregates across the receiver's replicas, per destination service, and adapts its send rate with **AIMD**.
   - *Question:* does push reduce signal latency, overshoot and recovery-ramp time compared with piggyback-only and 429-only?
2. **Wasted-work accounting.** Time and CPU the application spends on requests that have already expired or been cancelled, with vs without the interceptor (SDG 12/13: compute, energy and cost saved).
3. **Tiered cooperative cancellation delivery** (all notification-only):
   - (T1) header flag `cs` on in-flight child calls and on the response;
   - (T2) connection close toward the app's in-flight request (opt-in per route);
   - (T3) a library hook (C1 adapters expose `onCancel`).
   - *Measured:* cancellation latency (expiry → downstream work stops) per tier.
4. **Hot-reloadable, versioned policy with epochs** (needed for C3's consistent rollout) and per-mechanism switches (ablation).

### 5.3 Wording to USE and AVOID

- **USE:** "Coordinating **four different mechanisms** in one per-hop decision inside a **transparent HTTP sidecar**." "Retries gated jointly by remaining deadline and downstream pressure." "Wasted-work reduction."
- **AVOID (these are taken):**
  - "Coordinated overload control": DAGOR, Rajomon.
  - "Push/piggyback backpressure is new": DAGOR notifies upstream; Breakwater piggybacks credits; Rajomon propagates prices.
  - "First to drop requests that can't meet their deadline": Envoy expected-timeout early exit; patents exist.

### 5.4 Prior art and how to answer it

| Examiner says | Answer |
|---|---|
| "Envoy already has timeouts, retry budgets and rate limits." | Yes, and each acts independently, without the remaining-deadline context from upstream. **We compare against Envoy configured with equivalent settings** (E5), and the coordinated decision reduces wasted work and retries. |
| "DAGOR, Breakwater and Rajomon coordinate overload." | They coordinate *overload only*, inside RPC frameworks or libraries. We coordinate deadline, cancellation, retry and backpressure together, transparently for HTTP. We cite them and adopt the lesson that signals must flow upstream. |
| "Push notifications add load during overload." | Notifications only on level changes, with hysteresis, at most one per caller per 250 ms; overhead measured (E3). |
| "Can a sidecar link an outbound call to its inbound request?" | Through `traceparent`/`rid`, propagated by C1's adapters or by the app's existing tracing headers. This limitation is stated: without propagation, outbound calls start a new context. |
| "You don't actually stop the work." | By design (proposal commitment), cancellation is cooperative. We measure the delivery latency per tier and the wasted work saved. |

### 5.5 60-second pitch

> "Envoy-style proxies decide timeouts, retries and rate limits separately, so a proxy happily retries a request that has no time left, or forwards one to a service that's already drowning. Our sidecar makes one decision per request using four inputs: time remaining from the RPE header, expected downstream latency, downstream pressure that services push to their callers, and the retry budget. It forwards, rejects early, fails fast, or allows a retry only when the retry can actually finish. Same load, three ways (no sidecar, Envoy, RPE): RPE wastes less CPU, has lower p99 and fewer retries."

## 6. Scope boundary

| C2 does | C2 does NOT do |
|---|---|
| Intercepts inbound and outbound HTTP; enforces the 4 mechanisms; the decision function; leaky bucket; AIMD; retries; cancellation delivery; telemetry; hop events; trail entries (via C1 lib); policy API on 15020 | Define header rules (C1); inject sidecars or set iptables (C3 webhook); store/roll out policies (C3); choose policy values (C4) |
| Leads the Scenario Lab (k6 + faults) and the demo-app fault knobs (shared) | Own the console shell or hub (shared) |

## 7. Detailed design

### 7.1 Sidecar architecture (`sidecar/`, Java 21 + Netty 4.1)

```
            ┌──────────────────────────── rpe-sidecar (UID 1337) ───────────────────────────┐
inbound ───►│ :15006 InboundPipeline: HttpServerCodec → ContextIn (rpe-java parse+guard)     │──► app :8080 (127.0.0.1)
 (TPROXY)   │   → Admission (expired? insufficient time? leaky bucket) → HopEvent/Trail      │
            │   → forward to app via pooled loopback connection; track in-flight per epoch   │
app ───────►│ :15001 OutboundPipeline: original dst (SO_ORIGINAL_DST/TPROXY) → ContextOut    │──► peer pod/service
 (REDIRECT) │   (child ctx via Splitter, X-RPE-Hop cb/att) → DecisionFunction → RetryExecutor │
            │   → AIMD limiter per destination → pooled upstream connections (ChannelPool)  │
            │ :15020 Admin: /rpe/v1/policy, /activate, /mechanisms, /backpressure, /health   │
            │ :15090 Metrics (Prometheus)    OTel exporter → collector    HopEvents → rpe-hub │
            └────────────────────────────────────────────────────────────────────────────────┘
```

- **Connection pooling:** a Netty `FixedChannelPool` per destination and per loopback app port; keep-alive; HTTP/1.1 first (HTTP/2 optional later).
- **Streaming:** avoid aggregating large bodies. Use `HttpObjectAggregator` only up to a small limit (for example 1 MB); otherwise stream (a design note for the overhead NFR).
- **Correlation inbound → outbound:** the key is `rid` + inbound `iid`, found via `traceparent` / `X-RPE-Context` on the app's outbound call (propagated by C1 adapters or existing tracing). Store it in a bounded TTL map per request (Caffeine or a custom one), not a static global.
- **Roles:** `gateway` regenerates the context for external requests (trust boundary): it creates `rid`, `bud`/`ttl` from route rules, `rb` from policy and `pe` = the active epoch. `service` validates the incoming context.
- **Logging:** structured, at DEBUG per request; INFO for lifecycle only.

### 7.2 Mechanisms

**Deadline propagation (inbound and outbound):**
- Inbound: parse → `Deadline.fromContext` → R-D02 expired-on-arrival → 504 terminal.
- Outbound: child `ttl` = remaining − reserve (C1 splitter), so the deadline never extends.
- A timer at the local expiry marks the context `cs=expired` (triggers cancellation).

**Cancellation propagation (notify, never kill):**
- **Downstream:** for in-flight child calls of an expired or cancelled request:
  - T1: subsequent requests carry `cs`; new child calls are refused;
  - T2: close the upstream connection of in-flight child calls (opt-in, route policy), so the downstream sidecar sees the disconnect and marks the context `cancel`;
  - T3: the downstream sidecar notifies the app via the C1 hook (if the app uses it).
- **Upstream:** the response carries `X-RPE-Result: rsn=deadline|cancelled, term=?1`.
- Counted in `rpe_cancellations_total{kind,tier}`.

**Leaky bucket (inbound, per service and optionally per route):**
- `ratePerSec`, `burst` and `queueCapacity` come from policy.
- Utilisation `u = queueDepth / queueCapacity` (or smoothed occupancy) maps to a level using the thresholds `[0.5, 0.75, 0.9]` with hysteresis 0.05, giving level 0–3.
- Full queue → **429** `rsn=overload`, `Retry-After`, `term=?0`.

**Backpressure signalling (graded, push + piggyback):**
- Piggyback: every response carries `bp` and `bpu`.
- Push: the receiver keeps a **caller registry**, an LRU map (max 1,024 entries, TTL 30 s) from token `cb` to {observed source IP, last seen}. On a level change or clear, it sends `POST http://<sourceIP>:15020/rpe/v1/backpressure`, rate-limited to one per caller per 250 ms, with sequence numbers.
- **Safety rules:**
  - (a) send only to the observed TCP source IP, never to an address in a header;
  - (b) hysteresis;
  - (c) bounded registry;
  - (d) strip `X-RPE-Hop` before the app.
- **Caller side:** per destination *service*, keep the latest report per receiver pod (with TTL decay). Aggregated pressure = mean utilisation of fresh reports, and level = max of the fresh levels (configurable).
- **AIMD send rate per destination:**
  - on level up, multiply the rate by β(level) (β1 = 0.9, β2 = 0.7, β3 = 0.5);
  - while level is 0 or cleared, add α (for example 5% of the baseline rate) per 100 ms;
  - excess requests: queue briefly or fail fast per the decision function.
- **Known limitation:** SNAT/hairpin traffic or callers without a sidecar get piggyback/429 only.

**Mapping from C2's earlier 7-header design (WhatsApp) → protocol:**

| Old header | New |
|---|---|
| `X-Proxy-Peer-Key` | Dropped; the caller keeps a token → destination map |
| `X-Proxy-Caller-Token` | `X-RPE-Hop: cb=` |
| `X-Backpressure-Caller-Token`, `-Utilization-Percent`, `-Active` | JSON notification `POST /rpe/v1/backpressure` |
| `X-Proxy-Backpressure`, `X-Proxy-Queue-Utilization-Percent` | `X-RPE-Result: bp, bpu` |

**Retry-storm prevention:**
- Per-request budget: the C1 `RetryLedger` (`rb`).
- Per-service aggregate budget: a token bucket allowing retries ≤ `budgetRatio` × original requests (Finagle/SRE style), plus a minimum retries/s.
- Exponential backoff `base × 2^(att−1)`, capped at `backoffMaxMs`, with **full jitter**.
- Retry only when all of these hold: transient failure (connect error, 502/503/429 with `term=?0`), `replaySafe`, `term=?0`, `cs=none`, the ledger has budget, the aggregate bucket allows it, remaining time > backoff + estimated attempt time, and pressure level < 2. This is the decision function's retry branch.
- Never retry on `term=?1` (C1 R-R02).
- Count every denial reason in `rpe_retries_total{result}`.

### 7.3 Coordinated Resilience Decision Function (the main novelty)

```
on outbound call r from service S to destination D (inside inbound request ctx):
  rem   = deadline.remaining()
  est   = latency[D].estimate()           // EWMA of p50 (configurable p50/p95), per route
  p     = pressure[D].level()             // aggregated push+piggyback, decays when stale
  if ctx.cs != none                    -> REFUSE (terminal, rsn=cancelled)        // R-T04
  if rem <= 0                          -> REJECT_EXPIRED (504, term)               // R-D02
  if rem < est * alpha                 -> EARLY_REJECT (504 insufficient-time, term) // R-D03 enforced here
  if p == 3 && !route.critical         -> FAIL_FAST (503 overload, Retry-After, term=?0)
  if !aimd[D].tryAcquire(timeout = min(rem - est, maxQueueWait)) -> FAIL_FAST (429/503)
  child = splitter.child(ctx, mode, reserve); attempt(child)
on attempt failure f:
  if f.term || !transient(f) || !route.replaySafe            -> RETURN f
  backoff = jitter(base * 2^(att-1))
  if ledger.empty() || !aggBudget[S->D].allow()               -> RETURN f  (denied_budget)
  if rem - backoff < est * alpha                              -> RETURN f  (denied_deadline)
  if pressure[D].level() >= 2                                  -> RETURN f  (denied_pressure)
  ledger.spend(); sleep(backoff); re-check ReadyToDispatch; attempt(new iid, att+1)
```

- `alpha` (default 1.0), β, α and the thresholds are policy parameters.
- **Independent mode (ablation baseline):** the same mechanisms, but each makes its decision alone: timeout from ttl only, retries from budget and backoff only, the rate limiter alone. This is selected with `mechanisms.coordinated=false`.
- Every decision emits a hop event (`decision` field) and a trail entry (via C1).

### 7.4 Policy handling (with C3)

- The control API on port 15020 is as in A3.5. Policies are stored per epoch, each with a state of staged or active.
- **Applying a policy to a request:** the policy whose epoch equals `ctx.pe`. If that epoch isn't present, use the latest active epoch ≤ `pe` and log a mixed-policy warning (counted).
- **Gateway role:** stamp new requests with the active epoch after `/activate`.
- **In-flight per epoch:** the gauge `rpe_inflight_requests{epoch}` lets C3 know when an old epoch has drained.
- **Ack:** after every PUT or activate, call `POST /api/k8s/acks`.
- **Hot reload:** swap immutable policy objects atomically; never block the event loop.

### 7.5 Telemetry

- **Metrics:** exactly A3.3, including `rpe_wasted_work_seconds_total`.
- **Wasted work** is measured as follows. For inbound requests that become expired or cancelled while the app is still processing, the sidecar accumulates `t_app_response − t_expiry`, the wall time the app kept working after it was useless. The demo app's `/admin/counters` also reports CPU-ms per request id for CPU-accurate numbers in experiments.
- **Traces:** OTel span per hop with the `rpe.*` attributes; `rid` is bound to the trace id.
- **Hop events:** sent to the hub, batched every 100 ms or 100 events. Header capture only when demo capture is on.
- **Trail:** the sidecar appends the entry via `rpe-java` and submits completed trails (at the gateway) to `POST /api/protocol/trails`.

## 8. Contracts C2 provides and consumes

- **Provides:**
  - A3.3 metrics, traces and hop events;
  - A3.5 sidecar control API;
  - the backpressure notification sender and receiver (format A3.1).
- **Consumes:**
  - `rpe-java` (C1);
  - policies from C3 (A3.4 / A3.5);
  - per-route latency estimates (from its own EWMA; optional seeding from C4);
  - the ports and UID from C3's webhook (A3.2).

## 9. Legacy code guide

| Path | Verdict | Notes |
|---|---|---|
| `_research/old_shits/netty-sidecar-proxy/src/.../ProxyHandler.java` | Borrow ideas | TPROXY original destination, loopback inbound, gateway context regeneration. Don't copy the global map or the per-request `Bootstrap` |
| `GatewayDeadlineRules.java` | **Reuse** | Route matching (method + path template, query ignored) → budgets; budgets now come from policy |
| `ProxyTelemetry.java`, `ProxyMetrics.java` | Reuse the setup | Rename metrics to A3.3 |
| `BackendResponseHandler.java` | Rewrite | Response handling, with `X-RPE-Result` |
| `docs/flow.md`, `DEADLINE_TPROXY_GUIDE.md` | Read | Background for TPROXY and the flow |
| `_research/old_shits/admission-webhook/main.go` | C3's, but relevant | It injects the init container that sets the iptables rules. **Bug:** the egress exclusion uses `--uid-owner 0`, so root app containers bypass the proxy. C3 changes it to UID 1337; the sidecar image must run as 1337 |

## 10. Implementation plan

**Phase 0: Contracts (weeks 1–2):** agree A3.1/A3.3/A3.5 with C1, C3 and C4; scaffold `sidecar/` and the CI.

**Phase 1: Interception + deadline + cancellation (→ PP1):**
- P1.1 Netty inbound (15006) and outbound (15001) listeners, pooled connections, a pass-through test on kind with C3's webhook.
- P1.2 Integrate `rpe-java`: gateway context creation (route rules), parse and guard on inbound, child context on outbound, `X-RPE-Hop` strip.
- P1.3 Deadline: expired on arrival, local expiry timer, response `X-RPE-Result`.
- P1.4 Cancellation tiers T1 (flag) and T2 (connection close, opt-in); cancellation metrics; T3 hook through the C1 adapter.
- P1.5 Metrics (A3.3), OTel, hop events.
- **Acceptance:** demo app chain works through the sidecars; near-deadline scenario shows reject and propagated cancel; overhead measured for the first time.

**Phase 2: Backpressure + retries (→ PP2):**
- P2.1 Leaky bucket, utilisation → levels, 429.
- P2.2 Piggyback `bp`/`bpu`; push notifications (registry, safety rules); caller aggregation; AIMD limiter.
- P2.3 Retry executor: ledger, aggregate budget, exponential backoff with full jitter, terminal awareness.
- P2.4 Policy API (15020), epochs, acks, mechanism switches.
- P2.5 Scenario Lab presets and demo-app fault knobs (shared lead).
- **Acceptance:** each mechanism passes its trigger scenario; the ablation switches work.

**Phase 3: Coordinated decision function (→ PP2/Final):**
- P3.1 Implement §7.3 plus independent mode; full unit tests per branch.
- P3.2 Wasted-work accounting.
- P3.3 Console C2 module (§12).
- **Acceptance:** coordinated vs independent switchable live; decisions visible in hop events.

**Phase 4: Evaluation (→ Final):**
- P4.1 E1–E7 runs, ≥ 5 repeats each.
- P4.2 **Envoy baseline:** deploy Envoy as the sidecar (or Istio) with equivalent settings:
  - route timeout = the RPE budget;
  - `retry_policy` with `num_retries`, `retry_back_off` and `retry_budget` (`budget_percent` = `budgetRatio`, `min_retry_concurrency`);
  - `local_ratelimit` = the leaky-bucket rate.

  Configs go in `experiments/c2/envoy/`.
- P4.3 Thesis results and figures.

## 11. Evaluation plan

| ID | Experiment | Compared configurations | Metrics | Expected / output |
|---|---|---|---|---|
| E1 | Overhead | No sidecar vs RPE sidecar (all mechanisms on, no faults) | Added p50/p99 latency, CPU, memory per sidecar, throughput | Small, quantified overhead |
| E2 | Per-mechanism triggers | Each scenario of §4 | Deadline failures, cancellation events (down and up), 429s, retries per success | Expected behaviour in each |
| E3 | Backpressure signalling | 429-only vs piggyback vs push+piggyback | Signal latency, overshoot (requests sent after saturation), recovery ramp, notification msgs/s | Push reduces latency and overshoot |
| E4 | Ablation | None / deadline / cancel / backpressure / retry / all-independent / all-coordinated | p95/p99, error rate, retries per success, 429 rate, throughput, recovery time, wasted work | Coordinated best on wasted work and p99 |
| E5 | **vs Envoy** | No sidecar vs Envoy-equivalent vs RPE coordinated | Same as E4 + resource use | RPE ≤ Envoy on wasted work, retries, p99 under slow-payment and retry-storm |
| E6 | Cancellation tiers | T1 vs T1+T2 vs T1+T2+T3 | Cancellation latency, wasted work | Lower latency with more tiers |
| E7 | Cost lens | E4/E5 runs | CPU-seconds and network bytes saved → estimated cloud cost | Supports the proposal's "network traffic and cloud cost" claim |

Scenarios are the Scenario Lab presets (A6). Each run is 5 min after a 60 s warm-up, repeated ≥ 5 times; report mean ± sd, percentiles and CIs.

## 12. Admin Console: C2 tab and Operate contributions

**Operate side (C2-owned settings, saved as policies through C3):** per service/route:
- leaky-bucket rate, burst and queue;
- backpressure mode and thresholds;
- retry budget ratio, max attempts and backoff;
- cancellation tiers;
- mechanism switches;
- `alpha` for early reject.

**C2 Interceptor tab (Demonstrate).** The main novelty demo comes first.

| Sub-tab | Act → Observe |
|---|---|
| **Main: Coordinated vs independent vs Envoy** | Choose the slow-payment or retry-storm preset; switch the mode (no sidecar / Envoy-equivalent / RPE independent / RPE coordinated); run → side-by-side live charts of wasted work, p99, retries per success, early rejects, 429s, throughput; the decision breakdown (forward / early-reject / fail-fast / retry-allowed / denied reasons) |
| Mechanism Switches | Toggle each mechanism per service (real policy push) → the effect appears in metrics within seconds |
| Pressure Map | Live caller↔receiver graph with levels and utilisation; push vs piggyback toggle; signal-latency and overshoot gauges; notification rate |
| Retry Storm | Error-burst preset: retries/s with and without budget, jitter visualisation, denial reasons |
| Cancellation Timeline | Pick a request → per-hop timeline showing expiry, cancellation tiers firing and when downstream work stopped |
| Overhead | Sidecar CPU, memory and added latency, live |

## 13. Viva and demo script

1. **Pitch** (§5.5, 60 s), with **one diagram**: the decision box with 4 inputs → 4 outcomes.
2. **Demo 1 (main):** slow-payment preset in no sidecar → Envoy → RPE coordinated (3 min).
3. **Demo 2:** pressure map, push vs piggyback (1 min).
4. **Demo 3:** cancellation timeline (1 min).
5. **Results slides:** E4 ablation table, E5 vs Envoy, E3 signal latency, E1 overhead.

**Likely questions:**
- "Why not Envoy?" (E5)
- "DAGOR/Rajomon?" (§5.4)
- "Doesn't the sidecar add latency?" (E1)
- "How do you link inbound and outbound?" (`traceparent`/`rid`; limitation)
- "Why not kill the work?" (cooperative by design; measured)
- "Push overhead during overload?" (rate-limited, measured)

## 14. Risks and mitigations

| Risk | Mitigation |
|---|---|
| TPROXY complexity on kind | Start with REDIRECT for outbound plus an explicit inbound port; keep TPROXY from the legacy code |
| Overhead too high | Pooling, no per-request logging, bounded aggregation, measure early (P1) |
| Inbound/outbound correlation fails for apps not propagating headers | Demo app uses C1 adapters; document the limitation |
| Envoy baseline unfair | Publish the configs, equalise the parameters, review with the supervisor |
| Scope creep from the Scenario Lab | Keep k6 presets simple; the shared team helps |

## 15. Definition of Done (C2)

- [ ] Every §4 commitment is ticked, with evidence.
- [ ] Sidecar image runs as UID 1337 and is injected by C3's webhook; uses only `rpe-java` for headers.
- [ ] All 4 mechanisms, the decision function (coordinated + independent), push+piggyback backpressure with AIMD, retry executor, cancellation tiers, wasted-work metric.
- [ ] Policy API with epochs and acks works with C3; metrics match A3.3 and C4 consumes them.
- [ ] E1–E7 done with CSVs and charts in `experiments/c2/`; Envoy configs published.
- [ ] Console C2 tab works on live data; Scenario Lab presets work.
- [ ] Tests: unit per mechanism/branch; integration against stub upstreams; kind e2e.

## 16. Open questions for the supervisor

1. Approve adding the Envoy-equivalent baseline (an addition, not a replacement for the committed baselines).
2. Approve folding the 7 backpressure headers into C1's protocol fields and message.
3. Is wall-time wasted-work plus demo-app CPU-ms acceptable as the cost metric?

## 17. Glossary

- **Decision function:** the per-hop choice among forward, early-reject, fail-fast and retry.
- **AIMD:** additive increase, multiplicative decrease.
- **Piggyback:** a signal carried on responses.
- **Push:** a signal sent proactively to callers.
- **Wasted work:** app work on requests already expired or cancelled.
- **Tier T1/T2/T3:** the cancellation delivery channels.
- **Epoch:** the policy version pinned to a request.

---

# APPENDIX: Shared Ecosystem Reference (identical in all four component files)

> This appendix is copied into every component file so that each file is self-contained. If you change anything here, the change must be agreed by the team and copied into all four files (`docs/components/*.md`). Contracts are versioned; bump the version when you change them.

## A1. The ecosystem in one page

**Project:** J26-SE-336, *A Resilience Protocol Ecosystem for Microservices (RPE)*, SLIIT, B.Sc. (Hons) IT, Software Engineering.

**Supervisors:**
- Supervisor: Ms. Hansi De Silva
- Co-supervisor: Mr. Eshan Weerasinghe
- External supervisor: Mr. Susith Rupasinghe

**Problem.** HTTP microservices handle deadlines, retries, cancellation and backpressure independently, at each service. This causes:
- retry storms;
- work done after the caller has given up;
- cascading overload;
- duplicate side effects;
- slow recovery.

gRPC solves part of this, but only inside gRPC. Envoy, Istio and Resilience4j apply the mechanisms locally, without any cross-hop context.

**Solution.** Four components form one closed loop.

| # | Component | Student | One-line role |
|---|---|---|---|
| C1 | Protocol & Specification Layer | Ruwanmali K.H, IT23151642 | **Defines** the `X-RPE-*` context, its rules, the Java/Go libraries and the in-band trail |
| C2 | Service Interceptor Layer | R.R.D Pinto, IT23319806 | **Enforces** the rules at runtime in an HTTP sidecar proxy |
| C3 | Kubernetes Enforcement Layer | Kalhara D.M.R, IT23139626 | **Deploys and distributes**: sidecar injection, policy store, Operator, consistent rollout |
| C4 | Resilience Intelligence Layer | R.M.S.B. Rathnayake, IT23289420 | **Decides**: analyses telemetry, recommends policies, human approval, verifies outcome |

```
            ┌───────────────────── C1: X-RPE-* protocol + rpe-java / rpe-go libraries ───────────────────┐
            │ (used inside every sidecar and by the demo services' client adapters)                       │
            ▼                                                                                             │
 client → [gateway + C2 sidecar] → [order-svc + C2 sidecar] → [payment / inventory / loyalty + C2 sidecars]
                 │ metrics, traces, hop events                                ▲ policy (staged/active by epoch)
                 ▼                                                            │
       Prometheus / Tempo / rpe-hub ──► C4 Intelligence ──(human approval)──► C3 policy store (PostgreSQL)
                                          ▲      │ recommendations + preview        │ LISTEN/NOTIFY
                                          │      ▼                                  ▼
                                          └── outcome verification ◄────── C3 Operator (rollout, acks, drift)
```

**Loop:**
1. C2 sidecars emit telemetry.
2. C4 classifies health and proposes a joint policy with evidence and an impact preview.
3. A human approves it in the console.
4. C3 stores the policy (PostgreSQL), and the Operator rolls it out with per-request consistency (epoch stamping).
5. C2 applies it.
6. C4 verifies the outcome and later proposes gradual restoration.

**Team-level claim:** no existing work spans protocol → enforcement → policy distribution → intelligence as one closed-loop ecosystem for HTTP microservice resilience.

## A2. Naming conventions (mandatory)

- **Project prefix:** `RPE`. Not `RRPE`; the proposals for C1 and C4 say RRPE / `X-RRPE-Context`. It was renamed once, and this must be noted in the progress report. The rename removes no features.
- **Headers:** `X-RPE-Context`, `X-RPE-Hop`, `X-RPE-Trail`, `X-RPE-Result`.
- **Libraries:** `rpe-java` (Maven `dev.rpe:rpe-java`), `rpe-go` (module `github.com/J26-SE-336-Research/rpe/libs/rpe-go`).
- **Metrics:** `rpe_*`.
- **Sidecar control paths:** `/rpe/v1/*`.
- **Kubernetes annotations and labels:** `resilience.rpe.dev/*`; namespace label `rpe-injection=enabled`.
- **Java packages:** `dev.rpe.<component>`. Existing `org.rrpe.*` and `com.example.proxy` are renamed when moved.
- **Component API prefixes behind the hub:** `/api/protocol` (C1), `/api/interceptor` (C2), `/api/k8s` (C3), `/api/intel` (C4), `/api/hub` (shared).

## A3. Shared contracts (v0.2, 16 Oct 2026: the v0.1 target design plus "Implemented today" notes; owned as stated)

### A3.1 Header contract (owner: C1)

The field semantics below are stable. The wire encoding is chosen by C1's wire-format study; the default during development is an RFC 8941 Dictionary.

**Request, end-to-end: `X-RPE-Context`**

Example:
```
X-RPE-Context: v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000",
               ttl=287, bud=300, rb=3, cs=none, pe="e42", rt="POST /api/orders"
```

| Field | Type | Meaning | Rule |
|---|---|---|---|
| `v` | int | Protocol version | MUST be supported, else reject with `rsn=unsupported-version` |
| `rid` | 32 hex | Request id; equals the W3C `traceparent` trace-id | Immutable along the chain |
| `iid` | 16 hex | Invocation id of this hop/attempt | New for each child call and each retry attempt |
| `pid` | 16 hex | Parent invocation id (`0000000000000000` at the root) | Equals the parent's `iid` |
| `ttl` | int ms | Remaining deadline at send time (relative) | MUST NOT increase hop-to-hop; the receiver measures its own elapsed time locally |
| `bud` | int ms | Original end-to-end budget set at the gateway | Immutable |
| `rb` | int 0–255 | Retry budget remaining for this subtree | MUST NOT increase; sum of children's grants ≤ parent's unallocated budget |
| `cs` | token `none \| cancel \| expired` | Cancellation state | Once not `none`, MUST NOT return to `none` |
| `pe` | string | Policy epoch stamped by the gateway (C3 rollout) | Immutable along the chain; sidecars apply the policy of this epoch |
| `rt` | string ≤ 128 B | Route key `METHOD /path-template` set at the gateway | Immutable |

**Request, hop-scoped: `X-RPE-Hop`**

This header is rewritten at every hop and MUST be stripped before the request reaches the application. It is never forwarded beyond one hop.

Example: `X-RPE-Hop: cb="k3f9a2", att=2`

| Field | Meaning |
|---|---|
| `cb` | Caller backpressure token: a stable, opaque token per (caller pod, destination service) pair |
| `att` | Attempt number for this logical call (1 = first attempt) |

**Request, optional: `X-RPE-Trail`** (the in-band per-hop trail; the encoding is decided by C1's study)

Example (verbose development encoding): `X-RPE-Trail: rv=7, inc=?0, h="3:0:1840:F:12;9:1:950:F:40;11:1:-1:A:0"`

Each entry is `svcId : parentIdx : elapsedMicros (-1 = active) : decision : ttlConsumedMs`.
- Decision codes: `F` forward, `E` early-reject, `X` expired, `C` cancelled, `R` retry, `A` active, `P` partial, `O` overload.
- `rv` is the service-registry version. `inc=?1` means the trail is incomplete (a non-RPE hop or truncation).
- The trail is stripped at egress (privacy).

**Response: `X-RPE-Result`**

Example: `X-RPE-Result: v=1, out=partial, rsn=deadline, term=?1, bp=2, bpu=78, rbu=1, pv="e42", late=?0, kids="inv:ok:80;pay:ok:210;loy:expired:90"`

| Field | Meaning |
|---|---|
| `out` | Outcome: `ok \| partial \| rejected \| stopped \| failed` |
| `rsn` | Reason: `none \| deadline \| insufficient-time \| cancelled \| overload \| circuit-open \| invalid-context \| unsupported-version \| business \| transport` |
| `term` | Terminal flag. A terminal response MUST NOT be retried, whatever the remaining retry budget. Deadline-expired responses are always terminal |
| `bp` | Backpressure level 0–3 of the responding service |
| `bpu` | Utilisation 0–100 |
| `rbu` | Retry budget used by the subtree; the parent deducts it in sequential fan-out |
| `pv` | Policy epoch applied |
| `late` | Completed after the deadline because the point of no return (commit) had already been passed |
| `kids` | Per-child annotation for fan-out: `name:outcome:ms` |

**Status codes:**
- Deadline expired or insufficient time → **504** with `rsn=deadline|insufficient-time` and `term=?1`.
- Overload → **429** (or 503 fail-fast) with `rsn=overload`, `Retry-After` and `term=?0`.
- Invalid context → **400** with `rsn=invalid-context`.

**Backpressure notification message (C2 → caller sidecar; format owned by C1)**

`POST http://<observed caller source IP>:15020/rpe/v1/backpressure`

```json
{ "v": 1, "token": "k3f9a2", "receiverService": "payment-svc", "receiverPod": "payment-7f9c-abcde",
  "level": 2, "utilization": 78, "active": true, "ttlMs": 5000, "seq": 1842 }
```

**Notification rules:**
- Send only to the observed TCP source IP, never to an address taken from a header.
- Send only on a level change (with hysteresis) and on clear.
- At most one notification per caller per 250 ms.
- The caller ignores out-of-order messages (by `seq`).

### A3.2 Ports, UIDs and injection (owner: C3, agreed with C2)

| Item | Value |
|---|---|
| Sidecar outbound redirect port | 15001 |
| Sidecar inbound TPROXY port | 15006 |
| Sidecar admin/control port (policy, backpressure notifications, health) | 15020 (excluded from TPROXY/REDIRECT) |
| Sidecar metrics port | 15090 |
| Sidecar UID / GID | 1337 / 1337. Egress rules skip `--uid-owner 1337` **and** `--gid-owner 1337`, **never 0**, so root app containers are intercepted (e2e-tested) |
| Inbound interception mode | `redirect` (default: iptables nat REDIRECT → 15006; the sidecar runs non-root as 1337 and forwards to `127.0.0.1:RPE_APP_PORT`) or `tproxy` (opt-in annotation `resilience.rpe.dev/inbound-mode=tproxy`; keeps the original port; needs CAP_NET_ADMIN, so the sidecar runs as root with GID 1337) |
| Injection scope | Namespace label `rpe-injection=enabled` (the MutatingWebhookConfiguration's namespaceSelector); a pod opts out with `resilience.rpe.dev/inject=disabled`. Inbound ports default to the pod's declared TCP containerPorts |
| Pod annotations | `resilience.rpe.dev/role=gateway\|service` (default service), `resilience.rpe.dev/inbound-ports`, `resilience.rpe.dev/outbound-ports` (default `80,8080`), `resilience.rpe.dev/excluded-outbound-ports`, `resilience.rpe.dev/inbound-mode`, `resilience.rpe.dev/service` (default label `app`), `resilience.rpe.dev/default-deadline-ms`, `resilience.rpe.dev/injected=true` (set by the webhook) |

### A3.3 Metrics and telemetry contract (owner: C2 emits, C4 consumes)

Common labels: `service`, `pod`, `route`, `method`, `peer` (the other service), `direction` (`inbound|outbound`).

| Metric | Type | Extra labels | Meaning |
|---|---|---|---|
| `rpe_requests_total` | counter | `outcome` | Requests by outcome (`ok/partial/rejected/stopped/failed`) |
| `rpe_request_duration_seconds` | histogram | | Latency per hop (gateway = end-to-end chain latency) |
| `rpe_original_requests_total` | counter | | First attempts only (the denominator for rates) |
| `rpe_retries_total` | counter | `result=attempted\|succeeded\|denied_budget\|denied_deadline\|denied_pressure\|denied_terminal` | Retry activity |
| `rpe_rejections_total` | counter | `reason=expired\|insufficient_time\|overload\|invalid_context\|pressure_fail_fast` | Rejections by reason |
| `rpe_deadline_exceeded_total` | counter | | Deadline exceedances detected |
| `rpe_cancellations_total` | counter | `kind=received\|propagated`, `tier=header\|connection\|hook` | Cancellation events |
| `rpe_inflight_requests` | gauge | `epoch` | In-flight requests per policy epoch (C3 uses this for drain) |
| `rpe_backpressure_level` | gauge | | 0–3 |
| `rpe_backpressure_utilization_ratio` | gauge | | 0–1 queue fullness |
| `rpe_bp_notifications_total` | counter | `kind=active\|cleared`, `dir=sent\|received` | Push notifications |
| `rpe_bp_signal_latency_seconds` | histogram | | Receiver threshold crossing → caller rate reduction |
| `rpe_caller_send_rate` | gauge | `peer` | Caller's AIMD rate toward a peer |
| `rpe_wasted_work_seconds_total` | counter | `cause=expired\|cancelled` | Time the app kept working on requests already expired or cancelled |
| `rpe_proxy_overhead_seconds` | histogram | | Time spent inside the sidecar |
| `rpe_policy_info` | gauge (=1) | `epoch`, `version`, `hash`, `state=staged\|active` | Which policy a sidecar holds |
| `rpe_mixed_policy_requests_total` | counter | | Requests that observed more than one epoch (computed by the hub from hop events) |

**Trace span attributes** (OTel → Tempo): `rpe.rid`, `rpe.iid`, `rpe.pid`, `rpe.ttl_ms`, `rpe.rb`, `rpe.cs`, `rpe.decision`, `rpe.policy_epoch`, `rpe.bp_level`, `rpe.term`.

**Hop events** (sidecar → `POST /api/hub/events`, batched JSON array; header capture only when demo capture is on):
```json
{ "ts": "2026-10-15T10:00:00.123Z", "rid": "...", "iid": "...", "pid": "...", "service": "payment-svc", "pod": "payment-7f9c-abcde",
  "direction": "inbound", "route": "POST /pay", "peer": "order-svc", "decision": "forward",
  "ttlInMs": 287, "ttlOutMs": 250, "rbIn": 3, "rbOut": 1, "cs": "none", "bpLevel": 1, "policyEpoch": "e42",
  "latencyMs": 35, "status": 200, "term": false, "headersIn": {}, "headersOut": {} }
```

**Implemented today (v0.2):**
- The sidecar exports, and `rpe-prometheus` scrapes every 5 s:
  - `rpe_requests_total{direction,outcome=ok|failed|rejected}` (all series start at 0; 429/504 count as `rejected`);
  - `rpe_original_requests_total{direction}`, `rpe_retries_total{result}`, `rpe_rejections_total{reason}` (0 until C2's mechanisms land);
  - `rpe_request_duration_seconds{direction}`, `rpe_proxy_overhead_seconds`, `rpe_inflight_requests`;
  - **new, gateway only:** `rpe_chain_requests_total{method,route}` and `rpe_chain_deadline_exceeded_total{method,route}`. They are counted from `X-RPE-Result` and capped at 64 routes.
- Prometheus adds the `service`, `namespace` and `pod` target labels.
- C4 counts service errors as `outcome="failed"` only, because a 429 or 504 means a protection is working, not that the service failed. It uses `avg_over_time` of `rpe_inflight_requests` as mean concurrency.
- **Traces:** the demo services export OTLP/HTTP to `rpe-tempo.rpe-system:4318` and sample every trace.
  - Server spans carry `http.request.method`, `rpe.chain.route`, `rpe.direction=inbound`, `rpe.rid`, `rpe.ttl_ms`, `rpe.deadline.budget_ms`, `rpe.rb`, `rpe.policy_epoch` and `rpe.status_code`.
  - Client spans carry `rpe.direction=outbound` and `peer.service`.
  - `rpe-go`'s `Transport` keeps a same-trace `traceparent`, so the span tree stays intact.
- Hop events and the SSE stream are not built yet.

### A3.4 Policy contract (owner: C3 stores and distributes; C4 and the admin produce; C2 applies)

```json
{
  "policyId": "pol-payment-post-pay",
  "version": 42,
  "epoch": "e42",
  "source": "c4-recommendation | admin",
  "recommendationId": "rec-0193",
  "approval": { "approvedBy": "sre@demo", "approvedAt": "2026-10-15T10:00:00Z", "decision": "approved" },
  "scope": { "namespace": "demo", "service": "payment-svc", "route": "POST /pay" },
  "spec": {
    "deadlineMs": 800,
    "fanout": "partial | all",
    "split": "sequential | parallel",
    "replaySafe": true,
    "commitStage": "after-validate",
    "retry": { "budgetRatio": 0.1, "maxAttempts": 3, "backoffBaseMs": 50, "backoffMaxMs": 1000, "jitter": "full" },
    "leakyBucket": { "ratePerSec": 120, "burst": 40, "queueCapacity": 200 },
    "backpressure": { "mode": "push | piggyback | off", "levelThresholds": [0.5, 0.75, 0.9], "hysteresis": 0.05 },
    "cancellation": { "tiers": ["header", "connection", "hook"] },
    "mechanisms": { "deadline": true, "cancellation": true, "retry": true, "backpressure": true, "coordinated": true }
  }
}
```

- **C4 / admin → C3:** `POST /api/k8s/policies`. The body is the policy without `version`/`epoch`, and `approval` is mandatory. The policy is rejected without a human approval record. Header: `Idempotency-Key: <recommendationId>`. Response: `{ policyId, version, epoch, status: "approved-pending-rollout" }`.
- **Rollout:** `POST /api/k8s/rollouts { "policyVersion": 42, "mode": "two-phase | ordered | naive", "canaryPercent": 100 }`.
- **Status:** `GET /api/k8s/policies/{policyId}/status` →
  `{ version, rolloutState: "staging|activating|draining|complete|halted", stagedPods, activePods, totalPods, convergencePct, mixedPolicyRequests, driftPods }`.

**Implemented today (v0.2, `k8s/operator`, service `rpe-policy-api.rpe-system`):**
- `POST /api/k8s/policies` with body `{ policyId, source, recommendationId, scope: {service, route}, spec, approval: {approvedBy, decision: "approved"} }`. It returns 422 without an approval and responds `{ policy: {policyId, version, epoch, approvedBy, status, ...}, notification }`.
- `POST /api/k8s/rollouts { policyId, version, mode: "two-phase" | "naive", requestedBy }` returns an `RPERollout`; `GET /api/k8s/rollouts/{name}` returns `status.phase` = Staging | Activating | Draining | Complete | Failed.
- `GET /api/k8s/effective` (per service, from Redis), `GET /api/k8s/events`, `POST /api/k8s/acks`.
- **New spec field:** `spec.routes: { "<METHOD /route>": { "deadlineMs": n } }` on the gateway policy. It sets the budget the gateway stamps for that route. C4's chain-deadline recommendations are delivered this way, and a policy scoped to the same route also keeps `spec.deadlineMs` in step.
- C4 deliveries use `source=c4-recommendation`, its `recommendationId`, and `approvedBy` = the human reviewer.

### A3.5 Sidecar control API (owner: C2 implements, C3 calls, port 15020)

| Method / path | Purpose |
|---|---|
| `PUT /rpe/v1/policy` | Body `{ epoch, version, hash, state: "staged" \| "active", policy }`. Stage or activate a policy bundle; idempotent by `hash` |
| `POST /rpe/v1/policy/activate` | `{ epoch }`. Gateway role: start stamping new requests with `pe=epoch`. Service role: mark active |
| `DELETE /rpe/v1/policy/{epoch}` | Retire an epoch once its in-flight count is 0 |
| `GET /rpe/v1/policy` | `{ epochs: [{ epoch, version, hash, state }], activeEpoch }` (used by C3 drift checks) |
| `GET /rpe/v1/policy/active` | The active policy document, or 404 (**implemented**). The co-located gateway app follows it every 2 s for its epoch (`pe`) and `spec.routes` deadlines. Known limitation: one active epoch per proxy, so another service's rollout also replaces the gateway's document |
| `PUT /rpe/v1/mechanisms` | Toggle mechanisms (ablation), stored as part of the policy |
| `POST /rpe/v1/backpressure` | Backpressure notification (A3.1) |
| `GET /rpe/v1/health` | Liveness and readiness |

**Ack:** after every successful PUT or activate, the sidecar calls `POST /api/k8s/acks { pod, service, epoch, version, hash, state, ts }`. C3 also polls `GET /rpe/v1/policy` for drift.

### A3.6 Intelligence API (owner: C4; used by the console and C3)

| Method / path | Purpose |
|---|---|
| `GET /api/intel/health` | Current health per service and chain, with evidence |
| `GET /api/intel/recommendations?state=pending\|approved\|rejected\|delivered\|verified` | List recommendations |
| `GET /api/intel/recommendations/{id}` | Full evidence, constraints, impact preview |
| `POST /api/intel/recommendations/{id}/decision` | `{ decision: "approve" \| "reject", comment }` (authorised roles only) |
| `GET /api/intel/outcomes/{id}` | Predicted vs actual after rollout |
| `GET/PUT /api/intel/settings` | Thresholds, safety limits, windows |
| `GET /api/intel/audit` | Audit log |

**Implemented today (v0.2, Spring Boot `rpe-intelligence.rpe-system:8090`):**
- `GET /api/v2/intelligence`: collection status, assessments and recommendations (`proposal`, `decision` PENDING_APPROVAL | APPROVED | REJECTED, `delivery` READY | SENT | APPLIED | FAILED | EXPIRED | SUPERSEDED, `reviewer`, `appliedVersion`).
- `POST /api/v2/intelligence/changes/{id}/review { reviewer, approve, comment }`. Live sources require `X-RPE-Control-Token`.
- `GET /audit?limit=`, `GET /connections`, `POST /baselines/reset` (token), `POST /demo/{scenario}` (synthetic and isolated; never delivered).
- The audit is stored in PostgreSQL `intel.audit`, which is append-only (migration `003_intel_audit.sql`).
- The console calls these only through the hub, which sends the token and sets `reviewer` to the signed-in user.

### A3.7 Protocol service API (owner: C1)

| Method / path | Purpose |
|---|---|
| `POST /api/protocol/validate` | `{ headers, impl: "java" \| "go" \| "both" }` → parsed fields plus violations with rule IDs, side by side |
| `POST /api/protocol/transition` | `{ incoming, outgoing }` → context-guard verdict |
| `POST /api/protocol/split` | `{ ttl, rb, n, mode }` → child allocations |
| `POST /api/protocol/trails` | Sidecars/gateway submit completed trails |
| `GET /api/protocol/topology?encoding=full\|ring\|interned-delta\|bloom&window=5m` | Rebuilt graph with edges and latency |
| `GET /api/protocol/topology/compare` | Rebuilt graph vs Tempo graph vs ground truth → precision/recall |
| `GET/PUT /api/protocol/registry` | Service → 2-byte ID registry with version |
| `GET /api/protocol/attribution/{rid}` | Per-hop deadline consumption for one request |

### A3.8 Hub API (shared)

- `POST /api/hub/events` receives hop events.
- `GET /api/hub/stream?rid=&service=` is an SSE live stream.
- `POST/GET /api/hub/experiments` saves and lists runs (baseline/treatment, parameters, metric snapshots, CSV export).
- Auth: OIDC-less local accounts with roles `admin`, `sre`, `viewer`. Approve, reject and rollout require `admin` or `sre`.

**Implemented today (v0.2):**
- `/api/hub/me|overview|chain|services|load`, `/api/hub/protocol/*`, `/api/hub/demos/*`, `/api/hub/sidecars`, `/api/hub/fleet`, `/api/hub/policies`, `/api/hub/rollouts`, `/api/hub/effective`.
- `/api/hub/intel`, `/api/hub/intel/connections`, `/api/hub/intel/audit`, `POST /api/hub/intel/changes/{id}/review { approve, comment }` (a reason is required to reject), `POST /api/hub/intel/baselines/reset`.
- Sessions use signed cookies. The viewer role is read-only.
- Hop events, SSE and experiments are not built yet.

### A3.9 Redis fast path (owner: C3; decided 15 Oct 2026: Redis is added alongside PostgreSQL)

PostgreSQL stays the **system of record** (approvals, immutable versions, rollouts, deliveries), as the C3 proposal commits. Redis (`rpe-redis.rpe-system:6379`) carries the fast path:

| Key / channel | Type | Content |
|---|---|---|
| `rpe:events` | stream (≈2000 entries) | `policy-approved`, `rollout-started/staged/activated/complete/failed`, `drift-repaired` with fields (`policyId`, `epoch`, `rollout`, `pod`, …) |
| `rpe:effective:<service>` | hash | The policy the service currently enforces (`policyId`, `version`, `epoch`, `spec`, `approvedBy`, `updatedAt`) |
| `rpe:acks:<epoch>` | hash | pod → `staged \| active \| retired \| failed` (24 h TTL) |
| `rpe:policy:changed` | pub/sub | `{service, policyId, version, epoch}` when a version becomes active (C4 and C2 may subscribe) |
| `rpe:operator:last-event-id` | string | The Operator's catch-up checkpoint into `policy.policy_events` |

## A4. Who owns what on shared topics (one novelty per person)

| Topic | C1 | C2 | C3 | C4 |
|---|---|---|---|---|
| "Not enough time left" admission | Spec rule (SHOULD) and fields | **Novelty**: decision function and evaluation | n/a | Supplies per-route latency estimates |
| Per-hop deadline attribution | **Novelty**: trail records consumption | Writes trail entries | n/a | Uses it as evidence |
| Wasted / orphan work | n/a | **Novelty**: measured reduction | n/a | Detection is a supporting feature |
| Backpressure notification | Message format and validation | **Novelty**: mechanism and evaluation | Opens port 15020 | Health input |
| Policy epoch in context | Field and semantics | Applies the pinned epoch | **Novelty**: per-request-consistent rollout | Sends approved versions |
| Topology graph | **Novelty**: rebuilt from the trail | Emits trail | Uses it for ordered rollout | Uses it for chain analysis |
| Joint recommendation | n/a | n/a | n/a | **Novelty** |

## A5. Target monorepo layout

```
spec/                         C1  RPE-v1.md (RFC-style spec), rule IDs
libs/rpe-go/                  C1  Go library (+ RoundTripper adapter)
libs/rpe-java/                C1  Java library (+ RestTemplate/WebClient/Feign/OkHttp adapters)
conformance-kit/              C1  test vectors (JSON) + runners + differential fuzzer
bench/                        C1  wire-format benchmarks (JMH, Go bench, HPACK/QPACK simulator)
services/protocol-service/    C1  validation API, trail collector, topology rebuilder, registry
sidecar/                      C2  rpe-sidecar (Java 21 + Netty), Dockerfile
experiments/c2/               C2  ablation suite, Envoy baseline configs, results
k8s/admission-webhook/        C3  Go webhook (moved from old_shits, hardened)
k8s/operator/                 C3  Go Operator (controller-runtime), CRDs RPEPolicy/RPERollout
k8s/policy-store/             C3  PostgreSQL schema, migrations, NOTIFY triggers, policy API
charts/rpe/                   C3  Helm chart for the whole platform
experiments/c3/               C3  convergence / rollout experiments
intelligence/                 C4  Spring Boot intelligence layer (moved v2 engine)
experiments/c4/               C4  labelled scenarios, detector comparison, preview-error analysis
admin-panel/web/              shared  React + TS (Vite) web app; one page per function
admin-panel/server/           shared  rpe-hub server (auth, routing, SSE, experiments store)
demo-app/                     shared  polyglot demo services
scenarios/                    shared  k6 scripts + fault definitions (Scenario Lab)
deploy/observability/         shared  Grafana, OTel collector (planned); Prometheus + Tempo are built in deploy/k8s/platform/observability.yaml
deploy/kind/, deploy/k8s/     shared  kind cluster config; platform, demo and e2e manifests (reference deployment until charts/rpe exists)
scripts/                      shared  prereqs, cluster-up/down, build-images, deploy, undeploy, test-unit, test-e2e
tests/e2e/                    shared  Kubernetes e2e suites (bash + kubectl + jq) and lib.sh helpers
Makefile, go.work             shared  make all / test-unit / cluster / images / deploy / test-e2e; Go workspace
docs/components/              shared  these A-to-Z guides (committed)
_research/                    local only (gitignored): proposals, legacy code (old_shits/), scope docs; read-only reference
```

## A6. Shared infrastructure

**Demo app** (`demo-app/`, polyglot so that both of C1's libraries are actually used):

```
client → gateway (RPE gateway role)
          → order-svc (Java/Spring)
              ├─(parallel)→ inventory-svc (Go)     reserves stock   [side-effect counter]
              ├─(parallel)→ payment-svc (Java)     validate (read) → commit (point of no return) [counter: cards charged]
              ├─(parallel)→ loyalty-svc (Go)       optional → used with fanout=partial
              │             or fraud-check-svc (Java) mandatory → used with fanout=all
              └─(sequential)→ legacy-svc (NO sidecar, no RPE) → shipping-svc (Go)   [shows "unknown edge"]
```

Every service exposes:
- `POST /admin/faults { latencyMs, jitterMs, errorRate, cpuBurnMs, clockOffsetMs }`;
- `GET /admin/counters` (stock reserved, cards charged, CPU-ms spent per request id);
- `POST /admin/reset`.

**Scenario Lab** (`scenarios/`, suggested lead: C2): k6 scripts plus fault presets.

| Preset | Content |
|---|---|
| normal | Steady 50 rps |
| spike | 50 → 300 rps |
| slow-payment | payment +400 ms |
| error-burst | 30% 5xx on inventory |
| retry-storm | errors + aggressive client retries |
| near-deadline | Budgets close to p95 |
| fanout-fail | fraud-check slow with `fanout=all` |
| clock-skew | +40 ms offset on one service |
| recovery | Fault removed |
| kill-pod / kill-operator | Kill a pod or the Operator |

Every run records ground-truth labels (start/end, fault, expected health), which C4 uses as its labelled dataset.

**Observability:** OTel Collector → Tempo (traces), Prometheus (15 s scrape), Grafana (debugging only; the console is the demo UI).

**Built (16 Oct 2026):**
- `rpe-prometheus` discovers pods in `rpe-demo` and scrapes the `rpe-metrics` port every 5 s.
- `rpe-tempo` 2.6 receives OTLP spans directly from the demo services; there is no collector yet.
- `rpe-traffic` (in `rpe-e2e`) sends about 3 requests/s to `GET /api/orders`, so live telemetry always exists. Counter-exact e2e suites pause it.
- The demo services are Go-only for now (`rpe-java` now exists; Java services are still to come). `payment-svc` has a steady base latency of about 120 ms (`BASE_LATENCY_MS`).

**`rpe-hub`** (shared): auth and RBAC, a reverse proxy to the component APIs, the SSE hop-event stream, the experiments store (PostgreSQL schema `hub`).

**Admin Console** (`admin-panel/web`, shared shell; each student owns their module):

- **OPERATE:** Overview · Services & Routes · Policies & Approvals · Fleet · Service Registry · Settings & Users
- **DEMONSTRATE:** Scenario Lab · C1 Protocol · C2 Interceptor · C3 Enforcement · C4 Intelligence · Experiments

**Principle:** no fake demo endpoints. Every button calls a component's real API, and every chart shows live data. Each Demonstrate tab starts with the student's main-novelty demo. An examiner may change values and watch the real system respond.

## A7. Toolchain and local environment

| Tool | Version (minimum) | Use |
|---|---|---|
| Go | 1.24+ (the dev machine has 1.27) | rpe-go, webhook, Operator, Go demo services |
| Java | 21 LTS (the dev machine has 27; compile with `--release 21`) | rpe-java, sidecar, intelligence, Java demo services |
| Maven | via `./mvnw` wrapper | Java builds |
| Node | 20+ | Console |
| Docker | Desktop / Engine | Images |
| kind (preferred) or minikube | latest | Local Kubernetes (the proposal mentions Minikube; both are fine) |
| Helm | 3.x | `charts/rpe` |
| kubectl | matching the cluster | |
| k6 | latest | Load generation |
| PostgreSQL | 16 | Policy store (C3), audit (C4), hub |
| Prometheus, Tempo, Grafana, OTel Collector | latest | Observability |

**Install on macOS:** `brew install kind helm k6 postgresql@16`. Docker Desktop must be running. TPROXY needs Linux, so the sidecar only intercepts inside the kind/minikube Linux nodes; on the Mac, run unit tests only.

**Bring-up target** (final state): `make cluster && make images && helm install rpe charts/rpe -n rpe-system --create-namespace && make demo`.

## A8. Hard rules (for every member and for Claude)

1. **Never remove or weaken anything promised in your proposal.** You may only add. Section 4 of each file is the checklist.
2. **The four commitments that bind the whole team:**
   - C1: the `X-RPE-Context` header is the canonical end-to-end context.
   - C3: PostgreSQL + event notification + Operator; layer never modifies a policy on its own.
   - C4: every recommendation needs human approval.
   - C2: the interceptor *notifies* and never forcibly kills application work.
3. **Contracts in A3 are the only integration surface.** To change one, propose the change to its owner, bump the version, and update all four files.
4. **`_research/old_shits/` is read-only reference.** Copy code into the new layout and then refactor it there.
5. **Naming per A2.** No `X-Proxy-*` or ad-hoc headers on the wire; every header field is defined by C1.
6. **No fake demo logic.** Console demos call real APIs and show real data.
7. **Each novelty belongs to exactly one person** (A4). Don't claim another member's novelty.
8. **Claims must be worded as in each file's "Novelty" section.** Several stronger-sounding claims are false: predictive deadline admission is not new; topology rebuilt without tracing is not new; coordinated or push backpressure is not new; version acks are not new; joint retry/timeout tuning is not new.
9. **Every feature needs tests and a measurable evaluation hook** (a metric, an event or an experiment).
10. **Update your file's "Status" section** whenever you finish a phase.

## A9. Team-level related work (cite and differentiate)

- **gRPC:** deadlines, cancellation, retry throttling, `grpc-retry-pushback-ms`.
- **Envoy:** `x-envoy-expected-rq-timeout-ms`, `x-envoy-max-retries`, retry budgets, local rate limit, adaptive concurrency.
- **Istio:** xDS ACK/NACK, sidecar injection.
- **Linkerd 1 / Finagle:** deadline propagation, retry budgets.
- **Resilience4j and Hystrix.**
- **Google SRE book:** Handling Overload, Addressing Cascading Failures.
- **IETF draft Request-Timeout (2011):** a fixed value, not a shrinking deadline.
- **W3C Trace Context and Baggage.**
- **Overload control:** DAGOR (SoCC'18), Breakwater (OSDI'20), TopFull (SIGCOMM'24), Rajomon (NSDI'25), BLOC.
- **Tracing:** X-Trace (NSDI'07), Pivot Tracing (SOSP'15), Hindsight (NSDI'23), Mint (ASPLOS'25), Jaeger/Tempo sampling.
- **In-band telemetry:** INT, PINT (SIGCOMM'20).
- **Consistent updates:** Reitblatt et al. "Abstractions for Network Update" (SIGCOMM'12); Facebook Configerator (SOSP'15); Flagger.
- **Adaptive resilience:** CMU self-adaptive circuit breaking and retry; Hanada & Ishibashi (PRDC'24, IEEE Access'25); RetryGuard (arXiv 2025); MiSim; metastable failures (Huang et al. OSDI'22); MAPE-K; "The Tail at Scale".

## A10. Building and testing on Kubernetes (mandatory for every component)

Every component is tested on a local **kind** cluster (`rpe`, 1 control-plane + 1 worker), not only with unit tests.

| Command | Purpose |
|---|---|
| `make prereqs` | Check docker, kind, kubectl, helm, go, java, jq, k6 |
| `make test-unit` | Unit tests of all components, including the conformance kit and the Java↔Go differential corpus (`SKIP_JAVA=1` to skip Maven) |
| `make cluster` | Create the kind cluster |
| `make images` | Build `rpe/<component>:dev` images and `kind load` them (`IMAGES="sidecar"` for a subset) |
| `make deploy` | Webhook + TLS, PostgreSQL + Redis, Operator + policy API, Prometheus + Tempo, intelligence (live), hub, demo topology (injected), e2e client, traffic generator |
| `make test-e2e` | All suites in `tests/e2e/` (`SUITES="30 35"` for a subset); logs in `tests/e2e/reports/` |
| `make all` | Everything from scratch |

**Rules:**
1. Every new feature adds e2e assertions, either in an existing suite or a new `tests/e2e/NN-name.sh`. Use the `lib.sh` helpers: `client`, `header`, `field`, `metric`, `fault`, `counters`, `reset_demo` (also pauses background traffic), `traffic_pause`, `gateway_policy`, `hub`/`hub_login`, `proxy_holds`, `assert_*`.
2. A phase is only "done" when `make test-unit` and `make test-e2e` pass on a fresh cluster (`make cluster-down all`).
3. Scripts must stay bash-3.2 compatible (stock macOS): no associative arrays and no `mapfile`.
4. New images get a case in `scripts/build-images.sh` and manifests in `deploy/k8s/`, later in `charts/rpe`.

**Status (16 Oct 2026):** 12 suites, 175 assertions, all passing:
- 00 cluster;
- 10 webhook injection;
- 20 traffic interception, including root containers;
- 30 context propagation;
- 35 deadline & cancellation;
- 40 fan-out;
- 50 sidecar control API;
- 60 policy store;
- 70 intelligence;
- 80 admin panel;
- 90 rollouts;
- **95 intelligence live**: Prometheus/Tempo → C4 recommendation → human approval → C3 two-phase rollout → gateway enforcement → audit. It takes about 4–10 minutes.

`make reset-data` gives a clean slate before demos; it also restarts C4, which relearns its baselines in about 5–7 minutes.

**Admin Panel (built):** `admin-panel/web` (React + TS, Vite) + `admin-panel/server` (Go rpe-hub: signed-cookie auth, roles admin/sre/viewer, least-privilege RBAC). `make console` serves it at http://localhost:8800. It is a function-organised operations console, **not per-student tabs**:
- **Monitor:** Overview, Services, Request Inspector.
- **Configuration:** Policies, Rollouts, Recommendations (approval queue, health, reviewed, audit trail, simulation).
- **Infrastructure:** Proxies, Workloads.
- **Testing:** Fault Injection, Protocol Tools.

Pages per component:
- **C1:** Request Inspector, Protocol Tools, Experiments.
- **C2:** Proxies, Services.
- **C3:** Workloads, Policies, Rollouts.
- **C4:** Recommendations; Policies (versions marked *Recommendation*).

**UI rules:**
- Product language only: no student names, "novelty" banners or "coming soon" lists.
- Status uses a dot plus a text label.
- Each feature belongs in the page for its function; add a new page only for a new function (for example Rollouts).
- Back every action with a hub endpoint that calls the component's real API.
- Add assertions to `tests/e2e/80-admin-panel.sh`.

Legacy C4 HTML dashboards were deleted.
