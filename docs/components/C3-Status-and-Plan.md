# C3 Kubernetes Enforcement Layer: status and development plan

**Owner:** Kalhara D.M.R (IT23139626). **Date:** 18 Oct 2026. **Branch:** Kubernetes_Enforcement_Layer_V2. The A-to-Z guide is [C3-K8s-Enforcement-Kalhara.md](C3-K8s-Enforcement-Kalhara.md); this file is the short, current view: what was removed, what changed, what remains, and the order to build it.

## 1. What has been removed

| Removed | Why |
|---|---|
| The claim "per-request-consistent rollout" as C3's contribution (guide §3, §5, phases, E4, console tab, viva, glossary, pitch) | The three controls from C4 (chain deadline, rate limit, retry ratio) are independent versions for different sidecars. The deadline and the retry allowance travel in the request, and the rate limit is local to one sidecar, so there was nothing for the claim to protect |
| The "mixed-policy requests" / version-skew display in the Admin Panel | It counted version overlap, not a wrongly handled request |
| Experiment E4 (naive vs two-phase) and `e4-consistent-rollout.sh` with its results | Replaced by E3 (propagation time and delivery success) |
| Delivering a service policy to the gateway | It reset the gateway's chain deadlines to the static configuration (a bug); the policy's scope now decides the targets |
| Applying a `queueCapacity` from a policy in the sidecar | The sidecar owner allows the rate only |
| The Operator's catch-up position in Redis | Moved to PostgreSQL |

Kept but inert (tested, not shown): per-request epoch tracking and the mismatch counter in the sidecar and Operator. They can be deleted after PP1 if wanted.

## 2. What has changed

**Delivery**
- A policy reaches only the sidecars its scope names: the chain deadline the gateway sidecars; the rate limit and retry ratio the target service's sidecars.
- Verified delivery: stage, check the content hash the sidecar computed itself, activate, sidecar acknowledgement, drain the previous version, retire. Status shows desired vs observed and convergence.
- The hub takes one control per policy (`chain-deadline`, `rate-limit`, `retry-ratio`) and merges it into the service's current policy, as C4's bridge does; misdirected controls are refused.

**Sidecar (C2's code, agreed with C2)**
- A delivered `leakyBucket.ratePerSec` sets the inbound leaky-bucket rate when the policy is activated (rate only; staged policies are not enforced; a policy without a valid rate leaves the rate alone). The sidecar acknowledges after applying it.
- The sidecar computes the policy content hash and acknowledges stage, activate and retire to the policy API.

**Recovery and governance**
- Coverage audit over `rpe-injection=enabled` namespaces, a re-inject action for pods without the sidecar, drift detection by content hash with automatic repair (oldest approval first, so the newest policy wins).
- Redis is disposable: the Operator's catch-up position is in PostgreSQL (migration 005), and lost effective policies are rebuilt from PostgreSQL. PostgreSQL stays the system of record (committed proposal item).
- Operator metrics on `:8081` (coverage ratio, drift repairs, rollout convergence).

**Admin Panel:** Rollouts "Policy delivery" (pick control and value, see the target sidecars, steps, hash verified, sidecar-acknowledged, convergence); Policies form with the same controls; Workloads coverage audit and per-sidecar policy state.

**Tests and evidence:** e2e suites 91 (recovery), 92 (targeted delivery), 93 (rate-limit enforcement), 94 (Redis disposable) are new; 60, 80, 90 adjusted. Last runs pass: 60 (5), 80 (36), 90 (31), 91 (15), 92 (33), 93 (21), 94 (17); 00 to 50 passed earlier (13, 14, 10, 22, 13, 5, 8). Unit tests: Operator, hub, sidecar (PolicyRateTest 6, PolicyStoreTest 8). Experiment E3 ran once at 1 replica: 8 deliveries, 100% success, about 225 ms from approval to the last sidecar acknowledgement.

**Not yet verified after the latest changes:** suites 70 and 95 (C4 live), and a full run from a fresh cluster.

## 3. Status against the proposal commitments

| Commitment | State |
|---|---|
| Mutating webhook injects and configures the sidecar (FR1, FR2) | Done (suites 10, 20, 91) |
| Operator detects approved changes and distributes (FR5, FR7) | Done |
| PostgreSQL policy store with event notification (FR4) | Done (+ Redis fast path) |
| Version consistency and delivery status (FR8, FR9) | Done: hash-verified, acknowledged by the sidecar |
| Governance: never approves or modifies a policy itself (FR10) | Done in the schema; automatic halt and admin revert not built |
| Verify delivery, repair drift, coverage | Done |
| Metrics: injection coverage 100%, delivery success 100% | Measured (1 replica for delivery) |
| Metric: propagation time, target p95 5 s at 50 pods | Not measured at scale (1 replica only) |
| Metric: manual configuration effort | Not done (E1) |
| Baselines: manual deployment, Istio | Not done (E1, E7) |
| Failure and recovery timings | Behaviour tested, times not measured (E5, E6) |
| Helm chart, one-command install | Not started |

## 4. What remains

| # | Item | State | Depends on |
|---|---|---|---|
| 1 | Housekeeping: commit and push all uncommitted work, full e2e run on a fresh cluster including 70 and 95 | Open | none |
| 2 | Helm chart (`charts/rpe`) | Not started | none |
| 3 | Webhook admission-latency metric; experiments E1 and E2 | Not started | none |
| 4 | Stub sidecar for scale; E3 at 10 to 200 pods | Not started | none |
| 5 | E5 recovery timings and E6 drift timings | Not started | none |
| 6 | Regression halt from C4 health, halt and revert endpoints, panel buttons | Not started | C4 health API |
| 7 | Distribution study: ConfigMap and polling vs push | Not started | C2 (file watcher, poller) |
| 8 | E7 footprint against Istio | Not started | none |
| 9 | Optional: canary percent; ordered rollout (needs C1 topology API, which does not exist) | Not started | C1 |
| 10 | Retry-ratio enforcement | Waits for the proxy's retry phase | C2 |
| 11 | Tick the guide's §4 and §15 checklists with evidence; update the shared appendix wording | Open | team agreement |
| 12 | Supervisor questions (guide §16): positioning, automatic halt, target time, kind instead of Minikube | Open | supervisor |

## 5. Development plan

Rules for every step: unit tests, an e2e assertion, run on the kind cluster, then update the guide's §2 status. Effort is in working days. Order is by value and risk.

**Step 0: Housekeeping (0.5 d).** Commit in logical commits and push; a fresh-cluster run of all suites including 70 and 95; tell C2's owner what changed in the sidecar (policy store, acks, hash, rate application, `pe` kept per chain at the gateway); agree with the team how to reword the shared appendix (the old novelty row, the A1 role summary, the `pe` description). Done when: everything is pushed and the full suite list is green on a fresh cluster.

**Step 1: Helm chart (3 d).** Build `charts/rpe` from `deploy/k8s/platform/*` and `deploy/k8s/demo/*`.
- Templates: namespaces, CRDs (`crds/` directory), webhook (Deployment, Service, MutatingWebhookConfiguration), operator and RBAC, PostgreSQL with the migrations ConfigMap applied by a post-install Job, Redis, hub, intelligence, observability, demo (`demo.enabled`), e2e client (`tests.enabled`).
- Values: images, `webhook.failurePolicy`, `operator.driftIntervalMs`, toggles per component.
- TLS: `genCA` and `genSignedCert` with `lookup` so certificates survive upgrades; the control-token Secret the same way.
- Test: `helm lint`, `helm template | kubectl apply --dry-run=server`, then a fresh cluster with only `helm install rpe charts/rpe -n rpe-system --create-namespace` followed by the full e2e run. Keep `deploy/k8s` until the Helm install passes.
- Done when: one command installs the platform and all suites pass.

**Step 2: Admission latency, E1 and E2 (2 d).**
- Add `rpe_webhook_admission_duration_seconds` and an admission counter in `k8s/admission-webhook/main.go` (separate HTTP metrics port, scraped by Prometheus).
- E1 (`experiments/c3/e1-injection.sh`): manual deployment (hand-written sidecar and init container YAML: lines and steps counted, forgotten pods uncovered) against the webhook; coverage %, manual lines per workload, admission latency p50/p95 over N pod creations.
- E2 (`e2-webhook-safety.sh`): webhook down with `Ignore` against `Fail`: pods missed vs pods blocked; time until the audit reports the gap (reuse the steps of suite 91 section 4).
- Done when: CSVs with mean, min, max, sd, p95 for both.

**Step 3: Stub sidecar and E3 at scale (3 d).**
- `experiments/c3/stub-sidecar/` (Go): implements the A3.5 control API (GET/PUT policy, activate, delete), computes the same canonical hash, acknowledges to the policy API, with switches to add delay or reject pushes. Image `rpe/stub-sidecar:dev` (case in `scripts/build-images.sh`).
- A Deployment `scale-svc` with N replicas whose container is named `rpe-sidecar`, so the Operator treats the pods as targets.
- Operator: bound the concurrency of pod pushes (it starts one goroutine per pod today).
- Extend `e3-convergence.sh` to `REPLICAS="10 50 100 200"` on `scale-svc`. Check Docker memory (8 GB): stop at the size the laptop can hold and say so.
- Done when: propagation mean, min, max, sd, p95 and success rate at 10 to 200 pods; the target (p95 5 s at 50 pods) is met or the gap is explained.

**Step 4: E5 and E6 (1.5 d).**
- E5 (`e5-recovery.sh`): time back to the approved configuration after an Operator restart, a pod restart, a stub rejecting pushes, and a PostgreSQL restart; missed updates must be 0.
- E6 (`e6-drift.sh`): tamper with a sidecar's policy and measure detection and repair time, repeated at least 5 times; it should be within one drift interval.
- Done when: CSVs and a table for each.

**Step 5: Halt and revert (3 d).**
- Operator: a `Halted` phase and a reason (`policy.rollouts.halted_reason` exists); `POST /api/k8s/rollouts/{name}/halt` stops further work and keeps the state; `POST /api/k8s/rollouts/{name}/revert` starts a new rollout of the previous approved version (records the requester and an event). Halting never changes a policy.
- Automatic halt: while a rollout is activating or draining, read the target services' health from C4 (`GET /api/v2/intelligence`, via the hub or directly) and halt after two consecutive worse readings; Prometheus error ratio as the fallback. Revert always needs an admin.
- Hub: admin-only endpoints. Panel: Halt and Revert buttons and the Halted state.
- Test: unit tests of the state machine; new suite `96-halt-revert.sh` (slow delivery, halt, nothing more is activated, revert restores the previous version).
- Done when: the demo "inject a fault during a rollout, it halts, an admin reverts" passes. Needs the supervisor's answer on automatic halt (§16 Q4).

**Step 6: Distribution study (3 d, plus C2).**
- Add `distribution: push | configmap | poll` to the rollout spec and API (the column exists).
- configmap: the Operator writes `rpe-policy-<service>`; the webhook mounts it as an optional volume; the sidecar watches the file. poll: `GET /api/k8s/effective?service=` (add the filter) polled by the sidecar every 5, 15 or 30 s.
- Build both modes in the stub sidecar first so the study can run; ask C2 to add them to the real sidecar so the result is credible.
- Done when: E3 shows push against configmap and poll at 10 to 200 pods. If C2 cannot, report the study as done on stub sidecars and say so.

**Step 7: E7 footprint against Istio (3 d).** Install metrics-server in kind. Compare RPE with Istio's default profile on the same demo app: control-plane and per-sidecar CPU and memory (idle and under load), added p99 latency (k6 is not installed here; use `hey` or install k6), number of components and CRDs, installation steps. Report honestly, including where Istio does more.

**Step 8: Optional (decide after step 5).** Canary percent for multi-replica services (activate a share of the pods, check health, then the rest). Ordered rollout only if C1 delivers the topology API. Retry-ratio enforcement belongs to C2.

**Step 9: Close-out (2 d).** Tick guide §4 and §15 with links to evidence; apply the agreed appendix changes in all four guides; final `cluster-down` and `deploy` (or Helm) run with every suite green; rehearse the demo script (guide §13); decide whether to delete the inert per-request tracking code.

| Step | Effort | Needed for |
|---|---|---|
| 0 Housekeeping | 0.5 d | PP1 |
| 1 Helm chart | 3 d | PP1 if possible, else final |
| 2 E1 and E2 | 2 d | PP1 (E2) / final |
| 3 Stub sidecar, E3 at scale | 3 d | final |
| 4 E5 and E6 | 1.5 d | PP1 (E6) / final |
| 5 Halt and revert | 3 d | final |
| 6 Distribution study | 3 d + C2 | final |
| 7 E7 Istio | 3 d | final |
| 8 Optional | 2 d | optional |
| 9 Close-out | 2 d | final |

About 20 working days in total.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Steps 6 and 10 need changes in C2's sidecar | Ask early; build the stub first; state clearly when a result is on stubs |
| Laptop capacity for 200 pods (Docker 8 GB) | Tiny stub image; report the largest size that fits |
| Slow image downloads (postgres took almost 4 minutes) | Pre-pull and `kind load` the images before long runs |
| Istio on a laptop | Separate namespace or cluster, default profile, measure only the listed factors |
| Automatic halt may be read as autonomous modification | Halting changes no policy; revert needs an admin; confirm with the supervisor |
| Shared appendix wording | Agree with the team first; change all four guides together |
