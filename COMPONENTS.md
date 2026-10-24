# RPE: Components and How to Run

**Project:** J26-SE-336, *A Resilience Protocol Ecosystem for Microservices (RPE)*, SLIIT B.Sc. (Hons) IT, Software Engineering.
**Status date:** 15 October 2026.

This document explains every component of the ecosystem, how they work together, what is built today, and how to run and test everything locally on Kubernetes.

---

## Contents

1. [What RPE is](#1-what-rpe-is)
2. [The components](#2-the-components)
3. [How the components work together](#3-how-the-components-work-together)
4. [Shared contracts at a glance](#4-shared-contracts-at-a-glance)
5. [Current status](#5-current-status)
6. [How to run](#6-how-to-run)
7. [Repository layout](#7-repository-layout)

---

## 1. What RPE is

**The problem.** In HTTP microservices, every service handles timeouts, retries, cancellation and overload on its own. That causes several failures:
- A downstream service keeps working after the caller has already given up.
- Retries multiply load on a service that is already overloaded (retry storms).
- Overload spreads along the request chain.
- A failed fan-out that gets retried repeats side effects that already happened (a card charged twice).

Existing tools each solve part of this. gRPC handles deadlines and cancellation, but only inside gRPC. Envoy, Istio and Resilience4j apply timeouts and retries locally, with no shared context across hops.

**The solution.** Four components form one closed loop:

```
        ┌──────────── C1 Protocol: X-RPE-* headers + rpe-go / rpe-java libraries ─────────────┐
        │                (the rules every hop follows)                                          │
        ▼                                                                                       │
client → [gateway + C2 sidecar] → [order-svc + C2 sidecar] → [payment / inventory / ... + C2 sidecars]
               │ metrics, traces, hop events                         ▲ policies (staged/active by epoch)
               ▼                                                     │
     Prometheus / Tempo ──► C4 Intelligence ──(human approval)──► C3 policy store (PostgreSQL)
                               ▲     recommendations                  │ LISTEN/NOTIFY
                               │                                       ▼
                               └──── outcome verification ◄──── C3 Operator (rollout, acks)
```

| # | Component | Owner | One-line role |
|---|---|---|---|
| C1 | Protocol & Specification Layer | Ruwanmali K.H (IT23151642) | **Defines** the resilience context and its rules |
| C2 | Service Interceptor Layer | R.R.D Pinto (IT23319806) | **Enforces** the rules at runtime in an HTTP sidecar |
| C3 | Kubernetes Enforcement Layer | Kalhara D.M.R (IT23139626) | **Deploys and distributes**: sidecar injection, policy store, rollout |
| C4 | Resilience Intelligence Layer | R.M.S.B. Rathnayake (IT23289420) | **Decides**: analyses telemetry and recommends policies for human approval |

---

## 2. The components

### C1: Protocol & Specification Layer

**What it is.** A single versioned HTTP context that carries the four resilience signals together:
- **deadline**: remaining time;
- **retry budget**;
- **cancellation state**;
- **backpressure** (on responses).

It comes with formal rules that every hop must obey, and Java and Go libraries that implement those rules identically.

**Main novelty.** The protocol itself, defended on four points:
- **Coordinated semantics:**
  - the deadline never grows;
  - the retry budget never grows and is conserved when a call fans out;
  - cancellation is terminal;
  - a deadline-expired response is terminal and must never be retried.
- **Skew-tolerant deadlines:** a relative TTL plus locally measured elapsed time.
- **Self-describing requests:** a bounded per-hop trail rebuilds the request path and shows which hop used up the deadline.
- **Cross-language conformance:** the Java and Go libraries are tested against the same vectors.

**Headers:**

| Header | Scope | Example |
|---|---|---|
| `X-RPE-Context` | End-to-end request context | `v=1, rid="4bf9…", iid="00f0…", pid="0000…", ttl=287, bud=300, rb=3, cs=none, pe="e42", rt="POST /api/orders"` |
| `X-RPE-Hop` | One hop only; stripped before the app | `cb="k3f9a2", att=2` |
| `X-RPE-Trail` | Optional per-hop trail | `rv=7, inc=?0, h="3:0:1840:F:12;…"` |
| `X-RPE-Result` | Response context | `v=1, out=partial, rsn=deadline, term=?1, bp=2, bpu=78, rbu=1, kids="inv:ok:80;…"` |

**Built today:**

`libs/rpe-go`:
- RFC 8941 dictionary codec for the headers, with unknown fields preserved.
- Context guard checking rules R-T01..R-T06; each violation reports its rule ID.
- Child-context derivation.
- Fan-out budget splitter.
- `Middleware`: the gateway creates a fresh context (it never trusts the client); expired or cancelled requests are rejected before the app runs; the request context expires at the local deadline.
- `Transport`: outbound calls automatically carry the child context; a call that outlives the deadline becomes a terminal 504. An existing `traceparent` of the same trace (for example a tracing client span) is kept.

`libs/rpe-java` (Java 21, no runtime dependencies):
- the same codec, context guard (rule IDs), child derivation and budget splitter as `rpe-go`;
- `http.Admission`, a framework-neutral inbound step (trust boundary, rejects expired or cancelled requests before the app);
- `http.RpeHttpClient`, a `java.net.http` adapter that propagates the child context and bounds each call by the remaining deadline.

`conformance-kit/`:
- 103 language-neutral vectors covering decode, canonical encode, transitions, child derivation, budget split, `X-RPE-Result`, `X-RPE-Hop` and trace binding. Errors are compared by code and rule ID;
- a runner for each language. **`rpe-go` and `rpe-java` both pass 100%.**
- a **differential test**: 5,000 deterministic mutations of valid headers with the Go verdict, replayed by `rpe-java`. There are 0 divergences.

**Planned:**
- the RFC-style spec (`spec/`);
- RestTemplate, WebClient, Feign and OkHttp adapters for `rpe-java`;
- the wire-format and trail benchmarks;
- the protocol service (validation API, trail collector, topology rebuilder).

### C2: Service Interceptor Layer

**What it is.** An HTTP sidecar proxy injected next to every service. It enforces deadline propagation, cancellation propagation, leaky-bucket backpressure with signals back to callers, and retry-storm prevention (retry budget, exponential backoff, jitter). It exports telemetry for C4 and applies the policies C3 delivers.

**Main novelty.** One coordinated per-hop decision instead of four independent mechanisms. For every outbound call the sidecar combines:
- remaining deadline;
- expected downstream latency;
- downstream pressure;
- remaining retry budget.

It then chooses one of: **forward**, **reject early** (the request cannot finish in time), **fail fast** (the downstream is saturated), or **allow a retry** (only if the retry fits the deadline and pressure is low). It also measures the wasted work it saves and supports push + piggyback graded backpressure.

**Ports:**

| Port | Purpose |
|---|---|
| 15001 | Outbound (app → other services, redirected by iptables) |
| 15006 | Inbound (other services → app) |
| 15020 | Control API: `/rpe/v1/health`, `/rpe/v1/policy` (staged/active epochs), `/rpe/v1/policy/active` (the active policy document), `/rpe/v1/policy/activate`, `/rpe/v1/backpressure` (planned) |
| 15090 | Prometheus `/metrics` |

**Built today (`netty-sidecar-proxy/`, Java 21 + Netty; image `rpe/sidecar:dev`):**
- **Deadline propagation and cancellation propagation, signal-only.** The gateway proxy creates each chain (request id, route budget from `resilience.rpe.dev/route-deadlines` or the active C3 policy). Every proxy turns the received `ttl` into a local deadline. When the deadline passes, the chain is marked `cs=expired` and the signal travels downstream in `X-RPE-Context` and upstream in the `cs` member of `X-RPE-Result`. The proxy never rejects or stops a request because of its deadline; the application decides what to cancel.
- **Backpressure handling:** inbound leaky-bucket queue (503 when full), queue-pressure signals to caller proxies (direct `POST /backpressure/signal` plus response headers), adaptive per-destination concurrency, outbound queue and circuit breaker. See `netty-sidecar-proxy/BACKPRESSURE_HANDLING_GUIDE.md`.
- `X-RPE-Context` is read and written with `rpe-java`. An application may also send only `Request_id` on its outbound calls; the proxy then adds the chain context it stored.
- Requests without any RPE context (probes, admin calls, callers without a sidecar) pass through unchanged (the default; counted in `rpe_passthrough_requests_total`; `reject` gives HTTP 400).
- The control API with policy epochs.
- Metrics (contract A3.3, scraped by Prometheus):
  - `rpe_requests_total{direction,outcome}` (every outcome series starts at 0);
  - `rpe_original_requests_total{direction}`;
  - `rpe_retries_total{result}`;
  - `rpe_rejections_total{reason}` (`overload`, `pressure_fail_fast`, `invalid_context`);
  - `rpe_request_duration_seconds`;
  - `rpe_proxy_overhead_seconds`;
  - `rpe_inflight_requests`;
  - on the gateway, per-route `rpe_chain_requests_total` and `rpe_chain_deadline_exceeded_total`, capped at 64 routes;
  - the proxy's own `rpe_route_*`, `rpe_gateway_*` and `rpe_backpressure_*` series.

  Retries stay at zero: retries and retry-storm prevention are the next phase.

**Planned:**
- retries and the retry budget (retry-storm prevention);
- the decision function;
- the wasted-work metric;
- the Envoy-baseline experiments;
- the Scenario Lab.

### C3: Kubernetes Enforcement Layer

**What it is.** The Kubernetes integration:
- a **Mutating Admission Webhook** that injects the sidecar and an `rpe-init` container (iptables redirection) into every pod in namespaces labelled `rpe-injection=enabled`;
- a **PostgreSQL policy store** with event notification;
- a **Kubernetes Operator** that rolls approved policies out to running sidecars and verifies delivery;
- a **Helm chart** for the whole platform.

**Main novelty.** Per-request-consistent rollout of resilience policies:
1. The Operator first *stages* the new policy on every sidecar.
2. The gateway then stamps new requests with the new policy epoch (`pe`).
3. Every sidecar applies the version the request carries.

So a single request never sees a mix of old and new rules. This applies consistent-update ideas from networking (Reitblatt et al., SIGCOMM'12) to L7 resilience policies.

**Built today:**
- **`k8s/admission-webhook`**, hardened:
  - egress interception skips only UID/GID 1337, so root application containers are intercepted (the legacy uid-0 bypass is fixed);
  - REDIRECT inbound mode by default, TPROXY as an opt-in;
  - inbound ports auto-detected from the pod's containerPorts;
  - reserved-port and annotation validation;
  - unit tests.
- **`k8s/policy-store`** (PostgreSQL):
  - a policy is rejected without a human approval record;
  - approved policies are append-only;
  - every approval fires `NOTIFY rpe_policy_approved` and is logged in `policy_events` for Operator catch-up.

**Also built:**
- **`k8s/operator`, the RPE Operator and policy API** (Go, controller-runtime):
  - `POST /api/k8s/policies` accepts only policies with a human approval record (422 otherwise) and waits for the store's `NOTIFY`;
  - approvals are mirrored as **RPEPolicy** resources, with catch-up after downtime from `policy_events` using a Redis checkpoint;
  - **RPERollout** resources drive the **two-phase** rollout (stage on every target proxy → activate at the gateway first, then services → drain → retire the previous version) or the **immediate** baseline;
  - every delivery is recorded per pod;
  - **drift repair** re-delivers the active policy to restarted pods.
- **Redis** (`rpe-redis`), the fast path next to PostgreSQL:
  - event stream `rpe:events`;
  - effective-policy cache `rpe:effective:<service>`;
  - per-epoch acks `rpe:acks:<epoch>`;
  - change broadcast `rpe:policy:changed`;
  - Operator checkpoint.

  PostgreSQL stays the system of record, as C3's proposal commits.

**Planned:**
- Halting a rollout on regression, using C4's health signals.
- The push vs ConfigMap vs polling distribution study.
- Request-level epoch pinning in the proxy (with C2).
- The Helm chart.
- The Istio comparison.

| Pod annotation (`resilience.rpe.dev/…`) | Meaning |
|---|---|
| `inject: disabled` | Opt a pod out inside an enabled namespace |
| `role: gateway \| service` | Gateway creates the root context (default `service`) |
| `inbound-ports`, `outbound-ports`, `excluded-outbound-ports` | Ports to intercept (defaults: declared containerPorts / `80,8080`) |
| `inbound-mode: redirect \| tproxy` | Interception mode (default `redirect`) |
| `service`, `default-deadline-ms` | RPE service name (default label `app`), gateway default budget |

### C4: Resilience Intelligence Layer

**What it is.** Every minute it analyses Prometheus metrics and Tempo traces over a 5-minute window against a learned baseline, and classifies services and request chains as Healthy, Stressed or Overloaded.

It recommends Leaky Bucket rates, retry budgets and chain deadlines, and detects cancellation problems. Every recommendation carries evidence and **requires human approval** before it goes to C3. After rollout, C4 watches the outcome and proposes gradual restoration.

**Main novelty.** Protocol-informed, explainable **joint** recommendations:
- deadline, retry budget and rate are chosen together under feasibility constraints, for example "a retry is only allowed if it fits inside the deadline";
- the evidence includes cross-hop data from C1 and C2;
- an **impact preview** shows the expected effect before approval, and its accuracy is measured after rollout.

**Built today (`intelligence/`, Spring Boot, 87 tests), deployed in live mode:**
- **Live analysis.**
  - It reads the sidecar metrics from Prometheus and the gateway traces (`rpe.*` span attributes) from Tempo.
  - Trace counts are cross-checked against the gateway's per-route chain counters, within ±10%.
  - Hops without a sidecar are reported as unmonitored instead of blocking the analysis.
  - The demo cluster uses a 1-minute window, a 15-second cycle and a 20-second ingestion delay. Live recommendations therefore appear within minutes.
- **Every recommendation needs human approval:**
  - rate limit, retry budget and chain deadline alike;
  - a rejection holds for 30 minutes, so the same recommendation is not asked again every cycle;
  - only approved, non-simulated recommendations are ever delivered.
- **Delivery to C3** (`Component3Bridge`):
  - an approved recommendation becomes the next version of the target's current policy, with `recommendationId` and the reviewer as `approvedBy`. Chain deadlines become `spec.routes[route].deadlineMs` on the gateway policy;
  - it is rolled out in two phases;
  - it is marked **APPLIED** only after the rollout completes;
  - C3's effective policies are synced back as the engine's current reference.
- **Audit trail.** Every proposal, decision and delivery is recorded in PostgreSQL `intel.audit`, which a trigger makes append-only (migration 003).
- **Analysis fixes found on live data:**
  - **MAD floor.** Very steady traffic gives a near-zero MAD, which turned noise into "breaches". A practical floor now always applies.
  - **Deadline 504s are not service errors.** The overload guard counts only real failures. Otherwise a deadline that is too tight could never be fixed.
  - **Censoring-aware deadline increase.** While requests fail on the deadline, observed latency is cut at the deadline. The uncensored baseline p99 + 10% becomes a lower bound.
  - **Bounded steps.** One change moves a deadline by at most 50%, instead of being dropped.
- **Operator action.** *Re-learn baselines* is available after an intentional change such as a release.

**Planned:**
- remove the old v1 engine;
- the joint recommender, impact preview and outcome verification;
- comparison against simpler and ML detectors.

### 2.1 The Admin Panel

**URL:** `make console`, then **http://localhost:8800**. Accounts: `admin/admin` and `sre/sre` can make changes; `viewer/viewer` is read-only.

**Architecture:**
- `rpe-hub` is a Go service in `rpe-system`. It serves the React console and the `/api/hub/*` API, with signed-cookie sessions and roles.
- It acts on the real system only:
  - the Kubernetes API (least-privilege RBAC);
  - the demo services;
  - every proxy's control API and `/metrics`;
  - the PostgreSQL policy store;
  - the intelligence API.

It is organised like any operations console: by task, not by student.

| Section | Page | What it does |
|---|---|---|
| Monitor | **Overview** | Service health, proxy coverage, request volume, error rate, edge p95, service map, control-plane status, recent policy activity |
| Monitor | **Services** | Every workload with proxy status and traffic (requests, errors, p50/p95, policy epoch); a row opens a detail drawer |
| Monitor | **Request Inspector** | Send a request through the gateway and trace the `X-RPE-Context` at every hop (deadline left, retry budget, cancellation, epoch, protocol checks) |
| Monitor | **Backpressure** | Per service: inbound leaky-bucket fill against the 70/80/90/95 % signal levels and the 50 % clear, release rate, 503s and signals sent; per caller → destination: adaptive concurrency limit, waiting calls, reported pressure and breaker state; 2-minute charts; an experiment that overloads payment-svc and restores it 
| Configuration | **Policies** | Approved policy versions with their status (not rolled out, rolling out, active, superseded) and the activity log from the Redis event stream; *New policy* publishes an immutable version with you as the approver and can start its rollout; *Roll out* / *Restore* on any version |
| Configuration | **Rollouts** | Every rollout with its strategy (two-phase or immediate), phase, proxies converged and duration; a drawer shows the state of each proxy |
| Configuration | **Recommendations** | **Approval queue** of live recommendations; a review drawer shows evidence and measurements with *Approve* / *Reject* (a reason is required). **Health** of every service and request chain from live telemetry, with *Re-learn baselines*. **Reviewed** changes with delivery status (rolling out → applied, with the policy version). **Audit trail** (PostgreSQL). **Simulation** of synthetic telemetry profiles, isolated from delivery |
| Infrastructure | **Proxies** | Every sidecar proxy with traffic, latency, overhead and in-flight requests; a drawer to stage, activate and retire policy epochs |
| Infrastructure | **Workloads** | Injection per namespace and pod (traffic setup, proxy user, role, interception mode); *Preview admission* dry-runs a pod through the webhook |
| Testing | **Fault Injection** | Faults per service; load test (success rate, throughput, percentiles); experiments (slow dependency, partial failure) with full traces |
| Testing | **Protocol Tools** | Decode an `X-RPE-Context`, validate a hop transition, compute retry-budget allocation |

**Where each component is demonstrated** (for progress presentations and the viva):

| Component | Pages |
|---|---|
| C1 Protocol | Request Inspector, Protocol Tools, Fault Injection → Experiments (deadline, terminal results, fan-out) |
| C2 Interceptor | Backpressure (leaky bucket, pressure signals, adaptive concurrency, circuit breaker), Request Inspector and Fault Injection (deadline and cancellation propagation), Proxies, Services (traffic), Overview |
| C3 Enforcement | Workloads (injection, admission preview), Policies (approval, versions, activity), Rollouts (two-phase vs immediate, per-proxy convergence), Proxies (epochs held) |
| C4 Intelligence | Recommendations (approval queue, health, reviewed, audit trail, simulation); Policies (versions marked *Recommendation*) |

Pages show only what is built. New capabilities are added as real pages or sections when they exist (Rollouts was added with the Operator; the approval queue was added to Recommendations with C4's live workflow).

### Shared parts

| Part | What it is | Status |
|---|---|---|
| **Demo app** (`demo-app/`) | One configurable Go service playing every role in the demo topology. Uses `rpe-go` and exports OpenTelemetry spans to Tempo. The gateway follows the policy its proxy enforces (active epoch and route deadlines). Fault knobs `POST /admin/faults {latencyMs, jitterMs, errorRate, cpuBurnMs, clockOffsetMs}`, side-effect counters `GET /admin/counters`, `POST /admin/reset`, fan-out policy `partial`/`all`, point of no return before committing side effects. Responses nest each child's response, so one call shows the context at every hop | Built |
| **Demo topology** (`deploy/k8s/demo`) | `gateway → order-svc → (parallel) inventory-svc, payment-svc (~120 ms provider call), loyalty-svc (optional), legacy-svc (optional) → shipping-svc → partner-svc (optional)`, plus `fraud-check-svc` for `fanout=all` scenarios. `partner-svc` plays an outside system without RPE (no sidecar): the C1 unknown hop (R-P05). `rpe-traffic` (in `rpe-e2e`) sends about 3 requests/s so live telemetry always exists | Built |
| **Kubernetes e2e tests** (`tests/e2e/`) | Bash + kubectl + jq suites against the kind cluster | Built (12 suites, 175 checks, all passing) |
| **Admin Panel** (`console/` + `hub/`) | One operations console for the whole ecosystem (Monitor, Configuration, Infrastructure, Testing). Every action calls the real running components (see [section 2.1](#21-the-admin-panel)) | Built (e2e suite 80) |
| **Scenario Lab, k6 presets** (`scenarios/`) | k6 load profiles with ground-truth labels for C4 (the panel's Scenario Lab already drives faults and traffic) | Planned |
| **Observability** (`deploy/k8s/platform/observability.yaml`) | Prometheus scrapes every sidecar's metrics port (pod discovery in `rpe-demo`, 5 s interval). Tempo receives OTLP spans from the demo services | Built (e2e suite 95); Grafana planned |

---

## 3. How the components work together

### A request today (verified by the e2e tests)

1. A client calls `gateway:8080/api/orders`. iptables (set up by `rpe-init`) redirects the call into the gateway's **sidecar** (C2) on 15006, which forwards it to the app over loopback.
2. The gateway app's **C1 middleware** creates a fresh root context: `bud=300` for this route (`POST /api/orders` gets 500 ms), `rb=3`, policy epoch `pe=e0`, and a new `rid` bound to the W3C trace id.
3. The gateway calls `order-svc`. The **C1 transport** derives the child context: new `iid`, `pid` set to the gateway's `iid`, `ttl` reduced by the elapsed time. The call is redirected into the gateway sidecar's outbound port 15001, which forwards it over a pooled, kept-alive connection (`BackendConnectionPool`).
4. `order-svc` fans out in parallel to inventory, payment, loyalty and legacy. The retry budget is **split, not copied** (3 → 1/1/1/0).
5. `legacy-svc` has a sidecar like every other service, so `shipping-svc` behind it receives the context too. `shipping-svc` then calls `partner-svc`, which has no RPE: its call is recorded in the trail as an unknown hop.
6. If payment is slow and the 300 ms budget runs out:
   - payment notices that its own request context expired and **abandons before committing** (no card charged);
   - order's transport turns the timeout into a **terminal** 504 (`term=?1`);
   - the client gets the answer at about 1000 ms;
   - nobody retries the request.

### The policy loop (built end to end; verified by e2e suite 95)

1. The sidecars export metrics to Prometheus and traces to Tempo (the services themselves export nothing). C4 analyses both every cycle against learned baselines.
2. Example: an operator rolls out a 100 ms deadline for `GET /api/orders`, but payment alone takes about 120 ms. Every request now fails on the deadline. C4 detects the persistent deadline failures and proposes a bounded, censoring-aware increase. The evidence explains why.
3. The recommendation waits in the **approval queue**. Nothing is sent while it is pending, viewers cannot approve, and a rejection needs a reason.
4. An SRE approves it in the console. C4 sends it to the C3 policy API as the next approved version, naming the reviewer and the recommendation. PostgreSQL stores it and fires NOTIFY.
5. The C3 Operator rolls it out in two phases: stage on every proxy, activate at the gateway first, drain, retire the old version.
6. The gateway follows its proxy's active policy and stamps the new deadline and epoch into every new request.
7. C4 marks the recommendation **APPLIED** with the policy version. Every step is in the append-only audit trail.

Still to come: request-level epoch pinning inside the proxies (C2), joint recommendations with an impact preview, and outcome verification (C4).

---

## 4. Shared contracts at a glance

| Contract | Owner | Summary |
|---|---|---|
| Redis fast path | C3 | `rpe:events` stream, `rpe:effective:<service>` hash, `rpe:acks:<epoch>` hash, `rpe:policy:changed` pub/sub, `rpe:operator:last-event-id` |
| Policy API | C3 | `POST/GET /api/k8s/policies`, `POST/GET /api/k8s/rollouts[/{name}]`, `GET /api/k8s/effective`, `GET /api/k8s/events`, `POST /api/k8s/acks` (service `rpe-policy-api.rpe-system`) |
| Headers | C1 | `X-RPE-Context` / `-Hop` / `-Trail` / `-Result` (section 2, C1) |
| Ports and identity | C3 + C2 | 15001 outbound, 15006 inbound, 15020 control, 15090 metrics; sidecar UID/GID 1337 |
| Metrics | C2 emits, C4 consumes | `rpe_*` with labels `service`, `pod`, `direction`, `outcome`, `result`, `reason`, and on the gateway `method` and `route` (section 2, C2) |
| Traces | demo services emit, C4 consumes | OTLP to `rpe-tempo:4318`; span attributes `rpe.direction`, `rpe.chain.route`, `rpe.deadline.budget_ms`, `rpe.rid`, `rpe.policy_epoch`, `rpe.status_code` |
| Intelligence API | C4 | `GET /api/v2/intelligence` (status, assessments, changes), `POST /changes/{id}/review` (control token), `GET /audit`, `GET /connections`, `POST /baselines/reset` |
| Policy | C3 stores, C4/admin produce, C2 applies | JSON policy with `epoch`, `scope`, `spec` (deadline, fanout, retry, leakyBucket, backpressure, mechanisms) and a mandatory `approval` |
| Sidecar control API | C2 implements, C3 calls | `PUT /rpe/v1/policy` (staged/active), `POST /rpe/v1/policy/activate`, `DELETE /rpe/v1/policy/{epoch}`, acks to C3 |

The complete contract definitions are in the component guides (Appendix A3). See [section 7](#7-repository-layout).

---

## 5. Current status

| Component | Built and tested | Next |
|---|---|---|
| C1 | `rpe-go` (header set, guard, splitter, middleware/transport; e2e 30/35/40); `rpe-java` with the `java.net.http` adapter; conformance kit with 103 vectors, both libraries 100%, plus a 5,000-input differential test | Spec document, Spring/Feign/OkHttp adapters, trail + benchmarks |
| C2 | Netty sidecar (`netty-sidecar-proxy/`): signal-only deadline and cancellation propagation over `X-RPE-Context`, backpressure (leaky bucket, pressure signals, adaptive concurrency, circuit breaker), control API with the active-policy document, contract metrics incl. gateway chain counters (unit + in-process integration tests; e2e 20/30/35/50/95) | Retries and retry budget (retry-storm prevention), decision function |
| C3 | Hardened webhook (e2e 10/20); policy store (e2e 60); Operator + policy API + Redis, two-phase/immediate rollouts, drift repair (e2e 90); receives C4's approved recommendations (e2e 95) | Regression halt, distribution study, Helm, Istio comparison |
| C4 | Live analysis from Prometheus + Tempo; approval queue (every recommendation approved by a human); delivery to C3 with two-phase rollout and APPLIED confirmation; PostgreSQL audit trail; censoring-aware bounded deadline steps (87 tests; e2e 70/95) | Joint recommender, impact preview, outcome verification, detector comparison |
| Shared | Demo app with tracing, Admin Panel (console + hub), Prometheus + Tempo, steady traffic generator, manifests, scripts, 12 e2e suites | k6 scenario presets, Grafana, live hop-event stream |

---

## 6. How to run

### 6.1 Prerequisites

| Tool | Install (macOS) | Notes |
|---|---|---|
| Docker Desktop | docker.com | Give it **≥ 6 GB memory** (Settings → Resources) |
| kind | `brew install kind` | Local Kubernetes |
| kubectl | `brew install kubectl` | |
| helm | `brew install helm` | For the Helm chart (planned) |
| Go ≥ 1.24 | `brew install go` | rpe-go, demo app, webhook |
| Java ≥ 21 | `brew install openjdk@21` | Sidecar, intelligence (Maven runs through `./mvnw`) |
| jq | `brew install jq` | Used by the e2e tests |
| k6 | `brew install k6` | Load scenarios (planned) |

Check everything with:

```bash
make prereqs
```

### 6.2 Quick start (everything, from scratch)

```bash
make all
```

This runs:
1. unit tests;
2. creates the kind cluster `rpe` (1 control-plane + 1 worker);
3. builds and loads the four images;
4. deploys the platform and demo topology;
5. runs all e2e suites.

Then open the **Admin Panel**:

```bash
make console        # http://localhost:8800 (admin/admin, sre/sre, viewer/viewer); Ctrl-C to stop
```

The first run takes about 10 minutes (image builds download Maven and Go dependencies); later runs are much faster.

### 6.3 Step by step

| Step | Command | What happens |
|---|---|---|
| 1 | `make test-unit` | Go tests (`libs/rpe-go`, `conformance-kit/go`, `demo-app`, `k8s/admission-webhook`, `k8s/operator`, `hub`), the console build, and Maven tests (`libs/rpe-java` with the conformance and differential tests, `netty-sidecar-proxy`, `intelligence`). `SKIP_JAVA=1 make test-unit` for Go only |
| 2 | `make cluster` | Creates the kind cluster (no-op if it exists) |
| 3 | `make images` | Builds `rpe/sidecar:dev`, `rpe/admission-webhook:dev`, `rpe/demo-app:dev`, `rpe/intelligence:dev` and loads them into kind. Subset: `make images IMAGES="sidecar demo-app"` |
| 4 | `make deploy` | Namespaces (`rpe-system`, `rpe-demo` with injection, `rpe-e2e`); webhook TLS + registration; PostgreSQL with migrations; Redis; Prometheus + Tempo; intelligence (live); the Operator; the hub; the demo topology; the e2e client and the traffic generator |
| 5 | `make console` | Port-forwards the Admin Panel to **http://localhost:8800** (reconnects automatically) |
| 6 | `make test-e2e` | All suites. Subset: `make test-e2e SUITES="30 35"`. Logs: `tests/e2e/reports/` |

Every target wraps a script in `scripts/`, so for example `scripts/test-e2e.sh 35` works without make. The scripts are compatible with the stock macOS bash 3.2.

### 6.4 Explore the running system

All calls go through the e2e client pod, which is in a namespace without injection. For convenience:

```bash
alias kc='kubectl --context kind-rpe'
alias c='kc -n rpe-e2e exec e2e-client -- curl -s'
```

**A full chain request**, showing the context at every hop:

```bash
c http://gateway.rpe-demo:8080/api/orders | jq '{gateway: .context,
  order: .children[0].downstream.context,
  payment: (.children[0].downstream.children[] | select(.name=="payment") | .downstream.context)}'
```

**Deadline + point of no return.** Slow down payment, watch the terminal 504, and check that no card was charged:

```bash
c -X POST http://payment-svc.rpe-demo:8080/admin/reset          # clear counters from earlier requests
c -X POST http://payment-svc.rpe-demo:8080/admin/faults -d '{"latencyMs":1500}'
c -i http://gateway.rpe-demo:8080/api/orders | grep -iE 'HTTP/|x-rpe-result'
#   HTTP/1.1 504 Gateway Timeout
#   X-Rpe-Result: v=1, out=failed, rsn=deadline, term=?1, ... kids="order:expired:1000"
sleep 1; c http://payment-svc.rpe-demo:8080/admin/counters    # {"work_abandoned":1}, no cards_charged
c -X POST http://payment-svc.rpe-demo:8080/admin/reset
```

**Partial fan-out.** The optional loyalty service fails but the order still succeeds:

```bash
c -X POST http://loyalty-svc.rpe-demo:8080/admin/faults -d '{"errorRate":1.0}'
c http://gateway.rpe-demo:8080/api/orders | jq '.children[0].downstream | {outcome, kids: [.children[] | {name, outcome}]}'
c -X POST http://loyalty-svc.rpe-demo:8080/admin/reset
```

**Sidecar metrics and control API:**

```bash
IP=$(kc -n rpe-demo get pod -l app=payment-svc -o jsonpath='{.items[0].status.podIP}')
c http://$IP:15090/metrics | grep ^rpe_
c http://$IP:15020/rpe/v1/health
c -X PUT http://$IP:15020/rpe/v1/policy -d '{"epoch":"e1","version":1,"hash":"h","state":"staged"}'
c -X POST http://$IP:15020/rpe/v1/policy/activate -d '{"epoch":"e1"}'
```

**Injection.** Two containers plus `rpe-init` per pod, in every demo service:

```bash
kc -n rpe-demo get pods
kc -n rpe-demo get pod -l app=payment-svc -o jsonpath='{.items[0].spec.containers[*].name}'
kc -n rpe-demo logs deploy/payment-svc -c rpe-init      # the iptables rules that were applied
```

**Policy store:**

```bash
kc -n rpe-system exec deploy/rpe-postgres -- psql -U rpe -d rpe -c '\dt policy.*'
kc -n rpe-system exec deploy/rpe-postgres -- psql -U rpe -d rpe -c 'select policy_id, version, epoch, approved_by from policy.policies'
```

**Intelligence (C4), live:**

```bash
c http://rpe-intelligence.rpe-system:8090/api/v2/intelligence/connections        # Prometheus + Tempo: LIVE_DATA
c http://rpe-intelligence.rpe-system:8090/api/v2/intelligence | jq '.assessments[] | {target, health}'
c http://rpe-intelligence.rpe-system:8090/api/v2/intelligence/audit | jq '.entries[:5]'
```

**Live recommendation demo** (the panel's Recommendations page shows every step):
1. In **Policies → New policy**, choose service `gateway`, route `GET /api/orders`, deadline `100`, two-phase rollout. Every request now fails on the deadline.
2. Within about 2 minutes, **Recommendations → Approval queue** shows a *Request deadline* increase with its evidence.
3. Review and approve it. **Reviewed** shows it rolling out, then *Applied* with the new policy version. **Policies** shows the new version marked *Recommendation*, with you as the approver.
4. Restore with a new gateway policy version (deadline `1000`).

**Prometheus and Tempo** (not exposed by default):

```bash
kc -n rpe-system port-forward svc/rpe-prometheus 9090    # http://localhost:9090, e.g. rpe_chain_requests_total
kc -n rpe-system port-forward svc/rpe-tempo 3200         # Tempo API: /api/search?q={span.rpe.direction="inbound"}
```

### 6.5 Development loop

```bash
# 1. change code, then run that component's unit tests
(cd libs/rpe-go && go test -race ./...)         # or: (cd sidecar && ./mvnw -q verify)

# 2. rebuild only what changed and load it into kind
make images IMAGES="sidecar"                     # demo-app also needs rebuilding when libs/rpe-go changes

# 3. restart workloads so they pick up the new :dev image
make restart

# 4. run the relevant e2e suites (or all of them)
make test-e2e SUITES="20 50"
```

A change counts as **done** only when `make test-unit` and `make test-e2e` both pass. For milestones, run them on a fresh cluster: `make cluster-down all`.

### 6.6 Run components locally (without Kubernetes)

| Component | Command | Try it |
|---|---|---|
| rpe-go | `cd libs/rpe-go && go test -race -cover ./...` | n/a |
| Demo app | `cd demo-app && SERVICE_NAME=payment-svc SIDE_EFFECT=cards_charged PORT=18082 go run .` then in another terminal `SERVICE_NAME=gateway ROLE=gateway ROUTE_BUDGETS="GET /api/orders=1000" CHILDREN="payment=http://localhost:18082/pay" PORT=18081 go run .` | `curl -s localhost:18081/api/orders \| jq` |
| Sidecar | `cd sidecar && ./mvnw -q -DskipTests package && RPE_SERVICE_NAME=gateway RPE_APP_PORT=18081 java -jar target/rpe-sidecar.jar` (uses NIO on macOS; TPROXY needs Linux) | `curl localhost:15006/api/orders`, `curl localhost:15090/metrics` |
| Webhook | `cd k8s/admission-webhook && go test -race ./...` (it needs TLS + Kubernetes to run for real) | n/a |
| Intelligence | `cd intelligence && RRPE_MOCK_ENABLED=true ./mvnw spring-boot:run` | `curl -s -X POST localhost:8090/api/v2/intelligence/demo/stressed \| jq` |
| Admin Panel UI | `cd console && npm install && npm run dev` with the hub port-forwarded (`make console` in another terminal) | http://localhost:5173 (hot reload) |

**Demo-app environment variables:**

| Variable | Default | Meaning |
|---|---|---|
| `SERVICE_NAME` | `demo-svc` | Name in responses |
| `PORT` | `8080` | Listen port |
| `ROLE` | `service` | `gateway` creates the root context |
| `RPE_ENABLED` | `true` | `false` = a non-RPE service (no context handling) |
| `ROUTE_BUDGETS` | none | Gateway budgets, e.g. `GET /api/orders=300;POST /api/orders=500` |
| `DEFAULT_BUDGET_MS` | `1000` | Gateway budget for unmatched routes |
| `RETRY_BUDGET` | `3` | Root retry budget |
| `POLICY_EPOCH` | none | Epoch stamped by the gateway |
| `CHILDREN` | none | `name=url[?optional],…` downstream calls |
| `SPLIT_MODE` | `parallel` | `parallel` or `sequential` fan-out |
| `FANOUT` | `partial` | `partial` or `all` |
| `SIDE_EFFECT` | none | Counter incremented on commit (e.g. `cards_charged`) |
| `BASE_LATENCY_MS`, `BASE_JITTER_MS` | `0` | Steady simulated work per request (payment-svc: 120 + up to 20 ms) |
| `POLICY_FOLLOW_URL` | none | Gateway: follow the active policy of its proxy (`http://127.0.0.1:15020/rpe/v1/policy/active`) for the epoch and `spec.routes[route].deadlineMs` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | none | Export spans (OTLP/HTTP) to Tempo |
| `RESERVE_MS` | `5` | Parent reserve subtracted from child ttl |

### 6.7 Tests

**Unit tests:**

| Component | Command | What they cover |
|---|---|---|
| rpe-go | `go test -race ./...` | Codec round trips and invalid input, rule IDs, guard violations, budget conservation, middleware/transport (trust boundary, expired/cancelled rejection, deadline enforcement, trace binding), legacy rules |
| conformance-kit | `cd conformance-kit/go && go test ./...` | `rpe-go` against all 103 vectors |
| rpe-java | `cd libs/rpe-java && ./mvnw test` | All 103 vectors, the 5,000-input differential corpus against Go, admission and the `java.net.http` adapter |
| demo-app | `go test -race ./...` | Fan-out policy, chain propagation, terminal results, gateway policy follower |
| admission-webhook | `go test -race ./...` | Injection patch, regression for the uid-0 bypass, TPROXY mode, opt-out, validation, admission handler |
| sidecar | `./mvnw verify` | Inbound/outbound proxying over real sockets, pooling, 502 on dead upstreams, control API (incl. active policy), metrics (incl. capped gateway chain counters), config |
| intelligence | `./mvnw test` | 87 tests: engine and MAD floor, human-approval workflow, held rejections, censoring-aware and bounded deadline steps, Tempo/Prometheus window collection on the `rpe_*` contract, C3 bridge against a fake policy API |
| hub | `go test ./...` | Sessions and roles, metrics summary, chain checks, C4 review (signed-in reviewer, control token, rejection reason) |

**Kubernetes e2e suites (`tests/e2e/`):**

| Suite | Proves |
|---|---|
| `00-cluster` | Platform and demo topology available |
| `10-webhook-injection` | 100% injection coverage, opt-out, namespace scope, admission validation |
| `20-traffic-interception` | Inbound and outbound through sidecars, **including root containers** |
| `30-context-propagation` | Rules R-T01..R-T06, budget conservation, trust boundary, unknown edge, invalid-context rejection, expired-on-arrival served with `cs=expired` |
| `35-deadline-cancellation` | Signal, never reject: late chains are served with `cs=expired`; non-critical work skipped, critical work completes |
| `40-fanout` | Partial vs whole fan-out with per-child results |
| `50-sidecar-control-api` | Health, staged/active epochs, metrics |
| `60-policy-store` | Approval required, NOTIFY on approval, append-only |
| `70-intelligence` | C4 deployed and serving |
| `80-admin-panel` | Panel served, login and roles (viewer is read-only), and every page's feature through the hub API |
| `90-rollouts` | C3: policy API refuses unapproved policies; two-phase and immediate rollouts converge; gateways flip first; untouched out-of-scope proxies; supersede and restore; RPEPolicy mirror; Redis cache and events; per-pod deliveries; drift repair after a pod restart |
| `95-intelligence-live` | Prometheus scrapes all sidecars, Tempo has traces with RPE attributes; C4 learns live baselines; a too-tight deadline produces a bounded, censoring-aware recommendation; nothing is delivered while pending; viewer cannot approve; reject needs a reason; the SRE's approval becomes an approved C3 version (recommendationId, approvedBy) rolled out in two phases; the gateway enforces it; APPLIED; append-only audit in PostgreSQL. Takes about 4 minutes |

**Adding an e2e test:** create `tests/e2e/NN-name.sh` using the helpers in `tests/e2e/lib.sh`:

| Helper | Purpose |
|---|---|
| `client METHOD URL [JSON] [curl args]` | Sets `CODE`, `BODY`, `HEADERS`, `MS` |
| `header`, `field` | Read a response header; read a member of an RFC 8941 dictionary |
| `metric SERVICE DIRECTION` | Sidecar request count |
| `fault`, `counters`, `reset_demo` | Demo-app fault knobs and counters (`reset_demo` pauses the background traffic until the suite exits) |
| `traffic_pause`, `gateway_policy` | Pause `rpe-traffic`; read the gateway proxy's active epoch and `GET /api/orders` deadline |
| `assert_eq`, `assert_ge`, `assert_lt`, `assert_contains`, … | Assertions |
| `finish` | Print the result and exit with the right status |

### 6.8 Troubleshooting

| Symptom | Fix |
|---|---|
| Code changes don't show up in the cluster | `make images IMAGES=…` then `make restart` (the `:dev` tag doesn't change) |
| Pods in `rpe-demo` can't be created | The webhook (`failurePolicy: Fail`) must be running: `kubectl --context kind-rpe -n rpe-system get pods`. Remove it with `make undeploy` if needed |
| A pod has no sidecar | The namespace must carry `rpe-injection=enabled`; the pod must not have `resilience.rpe.dev/inject: disabled`; the pod must declare a containerPort or set `resilience.rpe.dev/inbound-ports` |
| `rpe-init` fails | `kubectl -n rpe-demo logs <pod> -c rpe-init`. Port lists must be numeric and ≤ 15 entries |
| Pods OOMKilled or Pending | Increase Docker Desktop memory to ≥ 6 GB |
| Maven fails inside `docker build` with `${os.detected…}` | Don't use `dependency:go-offline`; the sidecar Dockerfile already packages directly with a BuildKit cache |
| Webhook TLS errors after a redeploy | `make undeploy && make deploy` (regenerates certificates and the CA bundle) |
| e2e failures | Check `tests/e2e/reports/<suite>.log`; rerun one suite with `make test-e2e SUITES=30` |

### 6.9 Reset demo data

Approved policies are append-only, so test runs leave versions behind. `make reset-data` gives a clean slate:
- it recreates the policy store and Redis;
- it deletes the RPE custom resources;
- it restarts the demo services, which clears proxy epochs, faults and counters;
- it restarts the intelligence layer, which clears its baselines and open recommendations. The audit trail is recreated empty, and baselines are learned again within about 2 minutes.

Run it before a demo or viva.

### 6.10 Clean up

```bash
make undeploy       # remove RPE from the cluster, keep the cluster
make cluster-down   # delete the kind cluster
make clean          # remove build outputs
```

---

## 7. Repository layout

```
libs/rpe-go/                C1  Go library (header set, guard, deadline, ledger, splitter, middleware, transport)
libs/rpe-java/              C1  Java library (codec, guard, splitter, admission, java.net.http adapter)
conformance-kit/            C1  Shared vectors + Go runner + differential corpus generator
spec/  bench/  services/protocol-service/                          C1 (planned)
netty-sidecar-proxy/        C2  Java 21 + Netty sidecar (the deployed rpe/sidecar image)
scenarios/                  C2-led Scenario Lab (planned)
k8s/admission-webhook/      C3  Mutating admission webhook
k8s/policy-store/           C3  PostgreSQL schema and migrations
k8s/operator/               C3  Operator, CRDs and policy API
charts/rpe/                 C3  Helm chart (planned)
intelligence/               C4  Spring Boot intelligence layer
demo-app/                   shared  Configurable demo microservice
console/  hub/              shared  Admin Panel: React console + Go rpe-hub (built)
deploy/kind/                shared  kind cluster config
deploy/k8s/                 shared  platform, demo and e2e manifests
scripts/                    shared  prereqs, cluster, images, deploy, restart, unit + e2e test runners
tests/e2e/                  shared  Kubernetes e2e suites
Makefile, go.work           shared  task runner, Go workspace
docs/components/            shared  A-to-Z guide per component (commitments, novelty, design, contracts, status, plan)
_research/                  local only (gitignored): proposals, legacy prototypes, early scope notes
```

**Working on your component.** Each member has a detailed guide in [`docs/components/`](docs/components/README.md). It covers proposal commitments, novelty, design, contracts, status, plan, evaluation, UI and viva. Start Claude Code with:

```bash
claude "Read docs/components/<your-guide>.md completely before doing anything. Then <task>."
```
