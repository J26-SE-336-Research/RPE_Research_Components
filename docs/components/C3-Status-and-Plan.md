# C3 Kubernetes Enforcement Layer: status and development plan

**Owner:** Kalhara D.M.R (IT23139626). **Updated:** 18 Oct 2026, after commit `a31d1d9` ("Add joint policy delivery and the consistent rollout demo"). **Branch:** Kubernetes_Enforcement_Layer_V2.

The A-to-Z guide is [C3-K8s-Enforcement-Kalhara.md](C3-K8s-Enforcement-Kalhara.md). This file is the short, current view: the novelty (what it is, what was built, how to demonstrate it), what is done, what remains, and the steps to follow. What C2 and C4 are asked to do is in [C3-Integration-Requirements.md](C3-Integration-Requirements.md).

## 1. Summary

- **Done:** automatic sidecar injection, event-driven policy distribution from PostgreSQL, targeted and hash-verified delivery acknowledged by the sidecar, drift repair, coverage audit, recovery after restarts, Redis disposable, rate-limit enforcement (with C2's agreement), and the **novelty: per-request-consistent rollout of a joint change**, built, tested and measured.
- **Demonstrable today:** Admin Panel, Rollouts, "Consistent rollout of a joint change" (section 2.6).
- **Remaining:** fresh-cluster run of all suites, Helm chart, experiments E1, E2, E5, E6, E3 at scale and E7, halt and revert, distribution study, close-out (sections 6 and 7). About 20 working days.
- **Waiting on others:** `pv` written by the sidecar (C2-2), joint recommendations from C4 (C4-1 to C4-4), retries in the sidecar (C2's next phase).

## 2. The novelty: per-request-consistent rollout

### 2.1 The problem

A resilience change is rarely one number in one place. One approved decision often changes **several services at once**, and the settings depend on each other.

Example (the one in the demo): payment-svc is overloaded, so one decision is approved:
- the **gateway** gets a longer chain deadline for `GET /api/orders`: 1000 ms to 4000 ms;
- **payment-svc** gets a lower rate: 100 to 6 requests per second.

The two values only work together. At 6 requests/s a burst of chain requests waits in payment's queue for up to about 1.4 s. That is fine for a request that carries the new 4000 ms deadline and too long for one that still carries the old 1000 ms deadline.

The change never reaches every sidecar at the same moment. If payment's sidecar switches before the gateway's, requests that still carry the old deadline meet the new rate and **expire because of the rollout itself, not because of the overload** (`cs=expired`; signal, never reject, so they are still served, but late and marked cancelled). Configuration tools usually accept this window because "everything converges eventually". For RPE that is a problem: making the system more resilient must not itself harm requests.

### 2.2 Core idea

**Every policy version gets a number (the epoch). The gateway stamps it into every new request, so every sidecar knows which version each request belongs to. The rollout then changes the services in a safe order and uses the stamp to know when the old version is gone.**

1. **Stage:** every affected sidecar receives its part of the new version, inactive. Each checks the content hash and acknowledges it.
2. **Loosen first:** the parts that relax a limit (here the gateway's longer deadline) are activated. From now on the gateway stamps the new epoch (`pe`) into every new request.
3. **Drain gate:** requests stamped with the old epoch are still running. Because each request carries its epoch, the sidecars count them (`rpe_policy_inflight_requests{epoch}`). The Operator waits until that count is 0.
4. **Tighten:** only then are the parts that make a limit stricter (here payment's lower rate) activated. No request of the old version can meet them.
5. **Retire** the old version on every sidecar.

The comparison is the **naive** rollout: each sidecar switches when the change reaches it and drops the old version. The order depends on chance.

### 2.3 What the stamp adds, and what prevents the harm

- **The stamp is what makes step 3 possible.** Without it nobody can tell when it is safe to tighten; a person or a script would have to guess a waiting time. With it the answer is a number the sidecars report.
- **What prevents the harm in the demo is the order plus the drain gate**, made possible by the stamp. It is not a sidecar that switches settings per request: the leaky-bucket rate belongs to the sidecar, not to a request, and C2 declined a per-request setting (`backpressure.mode`). Say this exactly like that.
- **Per-request pinning is real and counted:** each sidecar serves a request under the epoch it carries when it holds that epoch. A request served under another version of the same policy is a **mixed-policy request** (`rpe_policy_mismatch_total`). Two-phase keeps the old epoch until it has drained, so the count stays 0; naive drops it at once, so the count rises.

### 2.4 What was built

| Part | What | Where |
|---|---|---|
| Joint policy | One version with a **part per service** (`spec.parts[]`, each with `scope` and `spec`) and one epoch. Each sidecar receives only its own part; its content hash is the hash of that part | `k8s/operator/joint.go`, `api.go` |
| Keeping it one decision | A later one-control change (Admin Panel or C4's bridge) to a joint policy keeps its other parts | `k8s/operator/api.go` (`withPart`) |
| Ordered activation | Each part is compared with the same service's part in the previous version: shorter deadline, lower rate or lower retry ratio is a **tightening**, anything else a **loosening**. Loosening first, drain gate, then tightening. The plan, the step and the waiting time (`gateMs`) are in the rollout status | `k8s/operator/joint.go` (`planJoint`), `rollout.go` |
| Naive baseline | Each sidecar switches on arrival and drops the old version; random spread (`skewMs`) or a fixed order (`order`: `services-first` or `gateway-first`) | `k8s/operator/rollout.go`, CRD `rperollouts.yaml` |
| Already in place before | Gateway stamping of `pe`, `pe` fixed per chain, per-request pinning, in-flight count per epoch, mismatch counter, staged/active/retire, hash and acknowledgement | sidecar `PolicyStore`, `ActivePolicy`, `ProxyHandler` |
| Demo runner | Restores the baseline, sends a burst of 8 chain requests every 4 s, approves the joint change during the traffic, delivers it as chosen, measures every request, restores the baseline | `hub/consistency.go` (`/api/hub/demos/consistency`) |
| Admin Panel | Rollouts, "Consistent rollout of a joint change": delivery choice, Run, runs table, latency chart with both deadlines and the moments each sidecar switched | `console/src/pages/Consistency.tsx` |
| Tests and experiment | e2e suite 96 (29 checks); E4 script and results; unit tests for parts, plan, gate and naive order | `tests/e2e/96-consistent-rollout.sh`, `experiments/c3/e4-consistent-rollout.sh`, `k8s/operator/joint_test.go`, `hub/hub_test.go` |
| Fix in the demo app | The demo gateway application polled the policy every 2 s and could start chains with the old deadline under the new epoch. It now uses the deadline its sidecar stamped. The sidecar was not changed | `demo-app/policy.go` (`stampedBudget`) |

### 2.5 Evidence (experiment E4, 18 Oct 2026)

Kind cluster, one replica per service, background traffic on, 5 runs of each delivery interleaved, each from the baseline, 24 demo requests per run after the approval. Files: `experiments/c3/results/e4-20261018-150733.csv` and `-summary.csv`.

| Delivery | Runs with expired requests | Expired per run | Mixed-policy requests per run | Requests without a 2xx answer |
|---|---|---|---|---|
| Consistent (two-phase) | **0 of 5** | **0** | **0** | 0 |
| Naive, payment switches 4 s before the gateway | 5 of 5 | 2 to 3 (mean 2.4), all with the old deadline | 14 | 0 |
| Naive, the gateway switches 4 s before payment | 0 of 5 | 0 | 16 to 17 | 0 |
| Naive, random spread up to 3 s | 1 of 5 | 0 to 2 (mean 0.4) | 1 to 15 (mean 8.4) | 0 |

- The consistent rollout never let a request meet the new rate with the old deadline, and no request was served under another version than it carried. The tightening waited 75 to 90 ms for the old version to drain; the whole rollout took about 0.4 s.
- Naive delivery always produced mixed-policy requests. Whether they also caused harm depended only on the arrival order, which nobody controls in a real fleet.
- Suite 96 checks the same on every run of the e2e tests.

### 2.6 How to demonstrate it

**Before the session:** the platform is deployed (`scripts/deploy.sh`), all pods are Running, and you are signed in to the Admin Panel as `admin`. Have screenshots or a recording of one naive and one consistent run as a backup, in case the laptop's Docker fails.

**Live (about 3 minutes):**
1. Open **Rollouts**. Explain the change shown at the top of "Consistent rollout of a joint change": gateway deadline 1000 to 4000 ms, payment rate 100 to 6 requests/s, and why they depend on each other (section 2.1).
2. Choose **"Naive: the service switches first"** (delay 4000 ms) and press **Run** (about 40 s). Show the result: requests expired, all with the old deadline; mixed-policy requests above 0; in the chart the red dots above the 1000 ms line, right after payment switched and before the gateway did.
3. Choose **"Consistent (two-phase)"** and press **Run**. Show: 0 expired, 0 mixed-policy requests; "Order decided by C3": loosen the gateway, wait for the old version to drain (about 80 ms), tighten payment; in the chart the gateway switches first and every dot stays green.
4. Optional: **"Naive: random arrival"** once or twice, to show that the harm depends on luck.
5. Optional: click a rollout in "All rollouts" to show the plan, the parts each sidecar received (hash verified, acknowledged) and the retired old epoch.
6. Close with the E4 table (section 2.5).

**From a terminal (the same evidence):** `MSYS_NO_PATHCONV=1 scripts/test-e2e.sh 96` (suite 96) or `MSYS_NO_PATHCONV=1 experiments/c3/e4-consistent-rollout.sh` (E4, about 13 minutes).

**The pitch in one sentence:** "A resilience change is several settings across services that arrive at different times. C3 stamps the version into every request, so it knows when the old version is gone, and changes the services in a safe order, so the change itself never harms a request."

### 2.7 Honest limits, and answers to likely questions

| Question | Answer |
|---|---|
| Is this not just ordering the changes by hand? | A person cannot know when the requests of the old version have finished. The stamp gives the system that number, and the order is decided from the change itself (loosening or tightening), for any joint change. |
| Did you force the bad order? | The "payment first" run uses a fixed 4 s gap to show the case clearly. The random run shows it also happens by chance (1 of 5). Real fleets can lag longer, for example with ConfigMap-mounted configuration; that is an argument, not a measurement. |
| How big is the harm? | 2 to 3 of 24 requests per run in the demo, about 10 %, because one burst falls into the window. The requests are still served, late and marked cancelled (signal, never reject). |
| The deadline already travels in the request; what can be mixed? | On its own the deadline needs no consistency. Settings that two services must agree on do, and so does the **order** of a joint change. |
| Does the sidecar apply a policy per request? | It pins and counts per request. The settings it enforces today (route deadlines, rate) are applied on activation, so the protection is the order plus the drain gate. Per-request settings (retry limits, `replaySafe`) come with C2's retry phase. |
| Why not Istio or a ConfigMap? | They distribute configuration and converge eventually. As far as we know they do not stamp requests with a policy version or report when the old version has drained. **Check this against the Istio documentation before claiming it on a slide.** |
| Where does the joint change come from? | Today an admin creates it in the Admin Panel. C4's joint recommendation will arrive the same way once C4-1 to C4-4 are done. |
| Does it overlap C2 or C4? | C2 enforces, C4 decides. C3 decides only when and in what order an approved decision reaches the sidecars, and proves that it was consistent. No sidecar code was changed for the joint delivery. |
| Is it new? | The idea comes from per-packet consistent updates in software-defined networking (Reitblatt et al., SIGCOMM 2012, "Abstractions for Network Update"). The contribution is applying it to application-layer resilience policy in plain HTTP microservices, with the version carried in the request context. Say this openly. |

## 3. What has been done (other parts)

**Injection (FR1, FR2):** the mutating webhook injects and configures the sidecar; the coverage audit finds pods that run without it, and the re-inject action recreates them.

**Distribution (FR4, FR5, FR7):** approved policies arrive in PostgreSQL (append-only, approval required by the schema); the Operator detects them with LISTEN/NOTIFY and catches up after downtime from a checkpoint stored in PostgreSQL.

**Delivery (FR8, FR9):**
- A policy reaches only the sidecars its scope names: the chain deadline the gateway sidecars, a rate limit or retry ratio the target service's sidecars, a joint policy each of its services with its own part.
- Verified delivery: stage, check the hash the sidecar computed itself, activate, sidecar acknowledgement, drain, retire. Status shows desired vs observed and convergence.
- The hub takes one control per change (`chain-deadline`, `rate-limit`, `retry-ratio`) or a joint change (`changes`), and merges it into the service's current policy, as C4's bridge does; misdirected controls are refused.

**Sidecar hooks (C2's code, agreed with C2):** policy store per epoch, gateway stamping and `pe` fixed per chain, per-request pinning and counters, content hash, acknowledgements, and the rate of the inbound leaky bucket set from `leakyBucket.ratePerSec` on activation (rate only; never the queue capacity).

**Recovery and governance (FR10):** drift detection by content hash with automatic repair (oldest approval first, so the newest policy wins); Redis is disposable (lost effective policies are rebuilt from PostgreSQL); C3 never approves or changes a policy itself.

**Admin Panel:** Rollouts (consistency demo, single-control delivery with hash verification and acknowledgements, all rollouts with plan and order), Policies, Workloads (coverage audit, per-sidecar policy state).

**Tests (18 Oct 2026, kind cluster):**
- **Last runs:** 50 (8), 60 (5), 80 (36), 90 (31), 91 (15), 92 (33), 93 (21), 94 (17) and 96 (29) pass. Suites 00 to 40 passed before today's changes.
- **Unit tests:** Operator (including 5 joint-policy tests), hub (including the joint change and the demo input), sidecar (`PolicyRateTest` 6, `PolicyStoreTest` 8) and demo-app (stamped deadline).
- **E3 at 1 replica:** 8 deliveries, 100 % success, about 225 ms from approval to the last acknowledgement.
- **Not run after today's changes:** suites 00 to 40, 70 and 95, and a full run from a fresh cluster.

## 4. What was removed or replaced

| Removed or replaced | Why |
|---|---|
| Delivering a service policy to the gateway | It reset the gateway's chain deadlines (a bug); the scope now decides the targets |
| Applying `queueCapacity` from a policy | The sidecar owner allows the rate only |
| The Operator's catch-up position in Redis | Moved to PostgreSQL (migration 005) |
| The old E4 script and results (commit `d50b4b7`, independent policies) | Replaced by the joint-policy E4 of 18 Oct 2026 |
| The old version-skew view in the panel | Replaced by the consistency demo |
| Per-request consistency claimed for the independent controls | Claimed only for joint changes and settings two services must agree on (guide §5.1a) |

## 5. Status against the proposal commitments

| Commitment | State |
|---|---|
| Mutating webhook injects and configures the sidecar (FR1, FR2) | Done (suites 10, 20, 91) |
| Operator detects approved changes and distributes (FR5, FR7) | Done |
| PostgreSQL policy store with event notification (FR4) | Done (+ Redis fast path, disposable) |
| Version consistency and delivery status (FR8, FR9) | Done: hash-verified, acknowledged by the sidecar |
| Governance: never approves or modifies a policy itself (FR10) | Done in the schema; automatic halt and admin revert not built |
| **Novelty: per-request-consistent rollout** (ownership table A4) | **Done:** joint policy, ordered activation with the drain gate, demo, suite 96, E4 (section 2) |
| Verify delivery, repair drift, coverage | Done |
| Metrics: injection coverage 100 %, delivery success 100 % | Measured (1 replica for delivery) |
| Metric: propagation time, target p95 5 s at 50 pods | Not measured at scale (1 replica only) |
| Metric: manual configuration effort | Not done (E1) |
| Baselines: manual deployment, Istio | Not done (E1, E7) |
| Failure and recovery timings | Behaviour tested, times not measured (E5, E6) |
| Helm chart, one-command install | Not started |

## 6. What remains

| # | Item | State | Depends on | Needed for |
|---|---|---|---|---|
| 1 | Full e2e run on a fresh cluster, including 00 to 40, 70 and 95 | Open | none | PP1 |
| 2 | Helm chart (`charts/rpe`), one-command install | Not started | none | PP1 if possible, else final |
| 3 | E2 webhook safety (`Ignore` vs `Fail`) | Not started | none | PP1 |
| 4 | E6 drift detection and repair time | Not started | none | PP1 |
| 5 | Tell C2 what changed (sidecar hooks, `demo-app` fix); agree the joint `parts` format for the shared appendix with all four | Open | team | PP1 |
| 6 | Webhook admission-latency metric; E1 manual effort | Not started | none | final |
| 7 | Stub sidecar; E3 at 10 to 200 pods | Not started | none | final |
| 8 | E5 recovery timings | Not started | none | final |
| 9 | Halt (automatic, from C4 health) and admin revert; panel buttons | Not started | C4 health API, supervisor | final |
| 10 | Distribution study: push vs ConfigMap vs polling | Not started | C2 (file watcher, poller) | final |
| 11 | E7 footprint against Istio | Not started | none | final |
| 12 | Close-out: tick the guide's §4 and §15 with evidence, shared appendix, final clean run, rehearsal | Open | team | final |
| 13 | Optional: canary percent for multi-replica services | Not started | none | optional |

## 7. Next steps to follow

Rules for every step: unit tests, an e2e assertion, a run on the kind cluster, then update the guide's §2 and this file. Effort is in working days.

**Step 1: Fresh-cluster run (0.5 d).** `scripts/cluster-down.sh`, `scripts/cluster-up.sh`, `scripts/deploy.sh`, then `MSYS_NO_PATHCONV=1 scripts/test-e2e.sh` (all suites). Fix what fails. Tell C2's owner what changed in the sidecar and about the demo-app fix; show the team the contract notes in section 10. Done when: every suite is green on a fresh cluster.

**Step 2: Helm chart (3 d).** Build `charts/rpe` from `deploy/k8s/platform/*` and `deploy/k8s/demo/*`.
- Templates: namespaces, CRDs (`crds/`), webhook (Deployment, Service, MutatingWebhookConfiguration), Operator and RBAC, PostgreSQL with the migrations ConfigMap applied by a post-install Job, Redis, hub, intelligence, observability, demo (`demo.enabled`), e2e client and traffic (`tests.enabled`).
- Values: images, `webhook.failurePolicy`, `operator.driftIntervalMs`, toggles per component.
- TLS: `genCA` and `genSignedCert` with `lookup` so certificates survive upgrades; the control-token Secret the same way.
- Test: `helm lint`, `helm template | kubectl apply --dry-run=server`, then a fresh cluster with only `helm install rpe charts/rpe -n rpe-system --create-namespace` and the full e2e run. Keep `deploy/k8s` until the Helm install passes.
- Done when: one command installs the platform and all suites pass.

**Step 3: E2 and E6 (1.5 d).**
- E2 (`experiments/c3/e2-webhook-safety.sh`): webhook down with `Ignore` against `Fail`: pods missed vs pods blocked; time until the audit reports the gap (reuse suite 91 section 4).
- E6 (`e6-drift.sh`): tamper with a sidecar's policy and measure detection and repair time, at least 5 times; it should be within one drift interval.
- Done when: CSVs with mean, min, max, sd, p95.

**Step 4: Admission latency and E1 (1 d).** Add `rpe_webhook_admission_duration_seconds` and an admission counter to `k8s/admission-webhook/main.go` (metrics port, scraped by Prometheus). E1 (`e1-injection.sh`): hand-written sidecar YAML against the webhook: lines and steps per workload, pods left uncovered, admission latency p50/p95.

**Step 5: Stub sidecar and E3 at scale (3 d).** `experiments/c3/stub-sidecar/` (Go, implements the A3.5 control API with the same hash and acknowledgements, switches for delay and rejection), image `rpe/stub-sidecar:dev`, a `scale-svc` Deployment whose container is named `rpe-sidecar`; bound the Operator's concurrent pushes; run E3 at 10, 50, 100, 200 pods or as many as the laptop holds (say so). Done when: propagation p95 against the target (5 s at 50 pods) is met or the gap is explained.

**Step 6: E5 recovery timings (1 d).** Time back to the approved configuration after an Operator restart, a pod restart, a stub rejecting pushes and a PostgreSQL restart; missed updates must be 0.

**Step 7: Halt and revert (3 d).** A `Halted` phase and reason; `POST /api/k8s/rollouts/{name}/halt` and `/revert` (revert starts a new rollout of the previous approved version and needs an admin); automatic halt after two consecutive worse health readings from C4 (Prometheus error ratio as fallback); hub endpoints and panel buttons; e2e suite `97-halt-revert.sh`. Halting never changes a policy. Needs the supervisor's answer on automatic halt.

**Step 8: Distribution study (3 d, plus C2).** `distribution: push | configmap | poll` in the rollout spec; the Operator writes `rpe-policy-<service>`, the webhook mounts it, the sidecar watches it; polling of `GET /api/k8s/effective?service=` every 5, 15 or 30 s. Build both in the stub first; ask C2 for the real sidecar. Report on stubs if C2 cannot.

**Step 9: E7 footprint against Istio (3 d).** metrics-server in kind; RPE vs Istio's default profile on the same demo app: control-plane and per-sidecar CPU and memory, added p99 latency, components and CRDs, installation steps. Report honestly, including where Istio does more.

**Step 10: Close-out (2 d).** Tick the guide's §4 and §15 with links to evidence; apply the agreed appendix changes in all four guides together; final clean run; rehearse the demo (section 2.6) and record a backup video.

| Step | Effort | Needed for |
|---|---|---|
| 1 Fresh-cluster run | 0.5 d | PP1 |
| 2 Helm chart | 3 d | PP1 if possible, else final |
| 3 E2 and E6 | 1.5 d | PP1 |
| 4 Admission latency, E1 | 1 d | final |
| 5 Stub sidecar, E3 at scale | 3 d | final |
| 6 E5 | 1 d | final |
| 7 Halt and revert | 3 d | final |
| 8 Distribution study | 3 d + C2 | final |
| 9 E7 Istio | 3 d | final |
| 10 Close-out | 2 d | final |

About 20 working days in total; steps 1 to 3 (about 5 days) prepare PP1.

## 8. Waiting on other components

| Item | Owner | Effect on C3 |
|---|---|---|
| C2-2: the sidecar writes `pv` (the epoch it applied) into `X-RPE-Result` per hop | C2 (accepted) | The Request Inspector can show which version each hop applied; the demo works without it |
| C2-5: hop events to the hub | C2 (optional) | The mixed-policy count could come from hop events instead of the sidecars' counters |
| C4-1 to C4-4: a joint recommendation approved as one decision and delivered as one version with parts | C4 (open) | "C4 recommends, C3 delivers consistently" end to end; today an admin creates the joint change |
| C2's retry phase: retry limits and `replaySafe` per epoch | C2 (next phase) | A per-request setting where a mixed state is harmful by itself (Level C); retry-ratio enforcement |
| Supervisor: is Level A (ordered joint rollout with drain gate, no per-request setting in the sidecar) acceptable; automatic halt; propagation target; kind instead of Minikube | supervisor | Scope of steps 5 and 7 and of the novelty claim |

## 9. Risks

| Risk | Mitigation |
|---|---|
| Docker Desktop on the laptop crashed once during an image build | Build one image at a time; keep a recorded backup of the demo |
| Steps 8 and retries need changes in C2's sidecar | Ask early; build the stub first; say clearly when a result is on stubs |
| Laptop capacity for 200 pods (Docker 8 GB) | Tiny stub image; report the largest size that fits |
| Slow image downloads (postgres took almost 4 minutes) | Pre-pull and `kind load` the images before long runs |
| Istio on a laptop | Separate namespace or cluster, default profile, measure only the listed factors |
| Automatic halt may be read as autonomous modification | Halting changes no policy; revert needs an admin; confirm with the supervisor |
| The novelty claim compared with Istio | Check the Istio documentation; say "not provided as a built-in guarantee", never "impossible" |
| Shared appendix wording | Agree with the team first; change all four guides together |

## 10. Contract notes proposed to the team (not agreed)

These notes describe what C3 built in the sidecar and the Operator after the shared contract v0.2. They are **not** in the shared appendix, which must stay identical in all four guides. If the team accepts them, they are copied into the appendix of all four guides together and the contract version is bumped. Until then the shared appendix keeps the agreed text.

### A3.3 metrics table: replace the row for `rpe_mixed_policy_requests_total` with these rows

```
| `rpe_policy_inflight_requests` | gauge | `epoch` | In-flight inbound requests pinned to each epoch (C3 drains the previous epoch until 0) |
| `rpe_policy_requests_total` | counter | `epoch` | Inbound requests by the epoch they were served under |
| `rpe_policy_mismatch_total` | counter | | Inbound requests served under another version of the policy than the epoch they carry |
| `rpe_mixed_policy_requests_total` | counter | `mode` | Requests served under another version of a policy than the epoch they carry, during rollouts (exported by the C3 Operator from the sidecars' `rpe_policy_mismatch_total`). Counts requests that met more than one version of a policy along their chain during a rollout; whether that causes harm depends on the coupled settings of the policy (see the C3 guide, section 5.1a) |
```

### A3.4 "Implemented today": add these bullets

- **Joint policies (v0.4 proposal, 18 Oct 2026):** `POST /api/k8s/policies` also accepts `parts: [{ scope: { service, route? }, spec }]`: one approved decision for several services, stored as one version (`spec.parts`) with one epoch. Each target sidecar receives only the spec of its part, and its content hash is the hash of that part. A later request without `parts` for a policy whose latest version is joint keeps the other parts. Two-phase rollout of a joint version activates loosening parts first (the gateway first, so it stamps the new epoch), waits until no request stamped with the previous epoch is in flight on any target, then activates the tightening parts; `RPERollout.status` adds `plan` (per part: `service`, `kind` loosen or tighten, `step`, `changes`), `step`, `gateMs` and, per pod, `part` and `activeAt`. `POST /api/k8s/rollouts` also takes `order` (`services-first` or `gateway-first`, naive only). The hub takes a joint change as `changes` (a list of controls) on `POST /api/hub/policies`, and runs the consistency demo at `/api/hub/demos/consistency`.
- **Delivery targets (v0.3.1, 18 Oct 2026):** a policy is delivered only to the sidecars its scope names. A policy scoped to `gateway` (the chain deadline, `spec.routes[route].deadlineMs`) goes to the gateway sidecars only; a policy scoped to any other service (rate limit `spec.leakyBucket.ratePerSec`, retry ratio `spec.retry.budgetRatio`) goes to that service's sidecars only, never to the gateway. The three controls are independent versions. Delivery succeeds when the target sidecar holds the version with the approved content hash and has acknowledged it; enforcing the rate limit and the retry ratio is the sidecar's job (C2). The hub's `POST /api/hub/policies` takes `control` (`chain-deadline`, `rate-limit` or `retry-ratio`) and merges only that control into the service's current policy.
- **v0.3 (17 Oct 2026):** `POST /api/k8s/rollouts` also takes `skewMs` (0–10000: a random delay per proxy delivery that models propagation spread, the same for every mode). `RPERollout.status` adds `stagedAt`, `activatedAt`, `convergenceMs`, `drainMs`, `mixedPolicyRequests`, `settled` and, per pod, `hash`, `verified`, `confirmedAt`. Two-phase drains until no target has a request pinned to the previous epoch in flight. Naive drops the previous epoch on each proxy as it switches. Rollouts run one at a time.
- New: `GET /api/k8s/policies/{id}/status` (A3.4 shape), `GET /api/k8s/fleet` (coverage audit over namespaces labelled `rpe-injection=enabled`, per-proxy epochs and hashes, drift), `POST /api/k8s/fleet/reinject { namespace, pod }` (recreates a coverage-gap pod). Operator metrics on `rpe-policy-api.rpe-system:8081/metrics`: `rpe_injection_coverage_ratio`, `rpe_injection_gap_pods`, `rpe_drift_repairs_total`, `rpe_mixed_policy_requests_total{mode}`, `rpe_rollout_convergence_seconds{mode}`.

### A3.5 table: replace the row for `GET /rpe/v1/policy`

```
| `GET /rpe/v1/policy` | `{ epochs: [{ epoch, version, hash, state, inflight }], activeEpoch, inflight: { epoch: n }, served: { epoch: n }, mismatched }` (C3 drift checks, drain and mixed-policy count; `hash` is computed by the sidecar) |
```

### A3.5: add this block after the Ack paragraph

**Implemented today (v0.3, 17 Oct 2026; added by C3 in the sidecar's policy code, for C2 to review):**
- **Per-request pinning:** every inbound hop is served under the epoch the request carries (`pe`) when the sidecar holds it (staged or active), otherwise under the active epoch. The gateway serves the epoch it stamps.
- **`pe` is fixed per chain at the gateway proxy:** on the gateway sidecar's outbound call, `pe` is the epoch the proxy pinned when the request entered, replacing the value the co-located application stamped (an application learns the active epoch by polling, so it can lag; this made old-epoch requests appear after a drain). A3.1 already defines `pe` as immutable along the chain.
- **Content hash:** `hash` is computed by the sidecar: the first 16 hex digits of SHA-256 over the canonical JSON of the received `policy` member (keys sorted, no whitespace, integral numbers without a fraction). C3 computes the same value from the approved spec, so a match proves what the sidecar holds. A hash sent in the PUT body is ignored.
- **Acks** are sent asynchronously after a successful PUT, activate and DELETE (`state=retired`) to `RPE_POLICY_API_URL` (set by the webhook).
- **Mixed-policy hop:** a request served under another version of the same policy than the epoch it carries (epochs are named `<policyId>-v<n>`). It is counted as `mismatched` and `rpe_policy_mismatch_total`.
- **Rate limit is enforced (v0.3.2, 18 Oct 2026, with C2's agreement):** activating a policy whose `policy.leakyBucket.ratePerSec` is valid (above 0, at most 1000000) sets the release rate of the inbound leaky bucket. Only the rate is applied (the value the runtime update `POST :15090/backpressure/config` also sets); the queue capacity is never changed by a policy, and the bucket's mechanism is unchanged. A staged policy is not enforced, a policy without a valid rate leaves the current rate alone, and `queueCapacity` and `burst` in a policy are ignored. The sidecar acknowledges after it applied the rate; the effective rate is `rpe_backpressure_inbound_rate_per_second`.
- Apart from that rate, only the sidecar's policy store, control API and metrics changed; deadline and cancellation handling are untouched.


### A3.8 "Implemented today": replace the hub endpoints line

- `/api/hub/me|overview|chain|services|load`, `/api/hub/protocol/*`, `/api/hub/demos/*`, `/api/hub/sidecars`, `/api/hub/fleet`, `/api/hub/fleet/audit`, `POST /api/hub/fleet/reinject`, `/api/hub/policies`, `/api/hub/policies/{id}/status`, `/api/hub/rollouts`, `/api/hub/effective`.

### A3.9 Redis table: replace the checkpoint row, and add this paragraph after the table

| `rpe:operator:last-event-id` | string | Legacy only (read once to carry the position over): the Operator's catch-up checkpoint now lives in PostgreSQL, `policy.operator_state` |

**Redis is disposable (v0.3.3, 18 Oct 2026).** Redis runs without persistence and may be empty after a restart or a flush. Nothing correct depends on it: the catch-up position is in PostgreSQL, and the Operator rebuilds any `rpe:effective:<service>` entry that is missing from the newest approved active policy in PostgreSQL (without publishing a change message; it never replaces an entry that exists). Components that read `GET /api/k8s/effective` (the hub, C4's bridge) can therefore rely on it to build the next version of a service's policy.

