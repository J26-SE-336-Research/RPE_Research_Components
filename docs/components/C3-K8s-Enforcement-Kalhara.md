# C3: Kubernetes Enforcement Layer (A-to-Z Guide)

**Owner:** Kalhara D.M.R (IT23139626). **Project:** J26-SE-336 RPE. **Guide version:** 1.0 (15 Oct 2026).

> This file is self-contained. It holds everything about C3: the proposal commitments, groomed scope, novelty, design, contracts, existing code, plan, evaluation, UI, viva and checklists. The appendix at the end is the shared ecosystem reference, identical in all four component files.

---

## 0. How to use this file with Claude

From the repository root:

```
claude "Read docs/components/C3-K8s-Enforcement-Kalhara.md completely before doing anything. Then <task>."
```

Example tasks:
- `"...Then move _research/old_shits/admission-webhook into k8s/admission-webhook and apply the fixes in §9 (UID 1337, annotations, tests)."`
- `"...Then create the PostgreSQL schema + NOTIFY triggers in k8s/policy-store per §7.3."`
- `"...Then scaffold rpe-operator with controller-runtime and the RPEPolicy/RPERollout CRDs per §7.4."`
- `"...Then implement the two-phase delivery (P3.1) per §7.5 with envtest tests."`
- `"...Then write the Helm chart charts/rpe per §7.8."`
- `"...Then build the C3 console module per §12."`

**Rules Claude must follow for C3 work:**
1. Obey **Appendix A8**. Never remove a proposal commitment (§4). In particular: **PostgreSQL + event notification + Operator**, and **the layer never approves or modifies a policy on its own**. Automatic actions may only *halt* a rollout; reverting to a previous approved version needs an admin action.
2. C3 owns: `k8s/admission-webhook/`, `k8s/operator/`, `k8s/policy-store/`, `charts/rpe/`, `experiments/c3/`, `console/src/modules/c3-enforcement/`. Don't edit other components' folders.
3. Go 1.24+, controller-runtime, `go test ./...` with envtest for controllers. SQL migrations are versioned (golang-migrate or plain numbered files).
4. Contracts A3.2, A3.4 and A3.5 are the integration surface; to change them, agree with C2 and C4 and update all four guides.
5. After finishing a phase task, update **§2 Status** in this file.

---

## 1. Identity and role

- **Component:** Kubernetes Enforcement Layer (C3).
- **Role:** *deploys and distributes.*
  - Injects the RPE sidecar (C2) into application pods.
  - Stores human-approved resilience policies in PostgreSQL.
  - Detects approved changes through event notification.
  - Rolls each policy out to exactly the sidecars it concerns, with **per-request consistency** and verified, acknowledged delivery.
  - Verifies delivery and repairs drift.
  - Packages the platform with Helm.
- **Research group / cluster / SDGs:** Software Systems and Technologies; Distributed Systems and Cloud Native Computing; SDG 9, 12, 13.

## 2. Status (update after every phase)

| Item | State (15 Oct 2026) |
|---|---|
| Admission webhook | **Exists** in `_research/old_shits/admission-webhook/` (Go, 424 LOC + `cmd/certgen` 103 LOC). Builds and vets; **no tests**. Annotation opt-in (`resilience.rrpe.dev/inject=enabled`), roles gateway/service, port validation, an init container that sets the iptables TPROXY (inbound) and REDIRECT (outbound) rules with `NET_ADMIN`, a proxy container with probes, an idempotency annotation, graceful shutdown, and a scratch image running as a non-root user |
| Webhook problems | (1) **Bug:** the egress rule excludes `--uid-owner 0`, and the proxy runs as root. Any app container running as root (most images) bypasses outbound interception, silently. (2) No MutatingWebhookConfiguration manifest or namespace selector in the repo. (3) Hard-coded image `2002rusira/netty-sidecar-proxy-rpe-implementation:v9`. (4) Single proxy port (no separate inbound/outbound/admin ports). (5) Hand-rolled structs instead of `k8s.io/api`. (6) No tests |
| PostgreSQL policy store, NOTIFY, Operator, policy API, acks, drift, rollout, Helm | **Missing.** This is about 80% of the committed scope |
| **Built in the monorepo (15 Oct 2026)** | `k8s/admission-webhook`: moved and **hardened**:
- the egress exclusion is UID **and** GID 1337 (the root bypass is fixed and e2e-tested);
- REDIRECT is the default inbound mode, with TPROXY as an opt-in annotation;
- separate ports 15001/15006/15020/15090;
- `resilience.rpe.dev/*` annotations;
- namespace-label scope with pod opt-out;
- inbound ports auto-detected from containerPorts;
- validation of reserved ports and annotations;
- a configurable image;
- certgen parameterised.

Unit tests cover 73.4%. `k8s/policy-store/migrations/001_init.sql` creates the `policy` schema (policies/rollouts/deliveries/policy_events). Approval is enforced (NOT NULL `approved_by`), policies are append-only (trigger), and the approval trigger does `pg_notify('rpe_policy_approved')` and writes `policy_events`.

The reference deployment is in `deploy/k8s` + `scripts/deploy.sh`, with MutatingWebhookConfiguration `failurePolicy: Fail`. E2e suites 10/20/60 pass on kind.

**Next:** the policy API (P2.2), the Operator with LISTEN/catch-up and CRDs (P2.3), the Helm chart (P2.5). |
| **Operator + policy API + Redis (15 Oct 2026)** | `k8s/operator` (Go, controller-runtime v0.20):
- the **policy API** on `rpe-policy-api` (human approval enforced → 422; idempotent on `recommendationId`; waits for and returns the NOTIFY);
- approvals mirrored as **RPEPolicy** CRs, with catch-up from `policy_events` using a Redis checkpoint;
- **RPERollout** CRs drive **two-phase** rollouts (Staging → Activating, gateways first → Draining with `DRAIN_MS` → Complete with the previous epoch retired) and **naive** rollouts (all proxies at once);
- deliveries are recorded in `policy.deliveries`, and policy status is updated (rolling-out/active/superseded/rollout-failed);
- **drift repair** every `DRIFT_INTERVAL_MS` re-delivers active policies to restarted pods;
- **Redis** fast path per A3.9.

Admin panel: Policies (roll out / restore) and **Rollouts** pages. Migration `002_rollout_identity.sql` adds rollout name/requester. The e2e suite 90 has 24 checks, all passing.

**C4 integration (16 Oct 2026):** C4's `Component3Bridge` posts approved recommendations to the policy API, with `source=c4-recommendation`, `recommendationId` and `approvedBy` = the human reviewer. It then starts a two-phase rollout and polls it to Complete. Suite 95 verifies this end to end on Kubernetes. Migration `003_intel_audit.sql` adds C4's append-only audit table in the same database.

**Superseded "Next" items (done 17 Oct 2026):** request-level epoch pinning, the per-epoch in-flight gauge for exact draining. |
| **Verified, targeted delivery (17-18 Oct 2026)** | **Sidecar hooks** (in `netty-sidecar-proxy`, policy store, control API and metrics only; deadline, cancellation and backpressure untouched; for C2 to review, see A3.5 v0.3):
- every inbound hop is pinned to the epoch the request carries (`pe`) when held, else the active epoch;
- the sidecar computes the content hash of the policy it holds (SHA-256 of canonical JSON, the same as the Operator's `specHash`; shared test vectors in `PolicyStoreTest` and `TestSpecHashVectors`);
- it sends acks (`POST /api/k8s/acks`) after stage, activate and retire;
- it counts in-flight requests per policy version (`rpe_policy_inflight_requests`, also in `GET /rpe/v1/policy`), used to drain the previous version before it is retired.

**Operator:**
- deliveries are verified by hash and the proxies' own acks are recorded (migration `004`: `confirmed_hash/state/at`);
- two-phase drains until no target has a request pinned to the previous epoch in flight (`DRAIN_MAX_MS` is only a cap);
- naive drops the previous epoch on each proxy as it switches (the Istio-like baseline);
- `skewMs` is an optional delivery delay per sidecar, used to watch a slow delivery and to interrupt it in the recovery demo;
- the convergence and drain times go to `RPERollout.status` and `policy.rollouts`;
- rollouts run one at a time;
- **coverage audit** over every `rpe-injection=enabled` namespace (`GET /api/k8s/fleet`, `rpe_injection_coverage_ratio`, `coverage-gap` events, admin **re-inject**);
- **drift by content hash** with repair (`rpe_drift_repairs_total`; skipped while a policy is rolling out);
- `GET /api/k8s/policies/{id}/status`;
- the RPEPolicy status shows desired/observed/convergence/drift;
- Operator metrics on `:8081`, scraped by Prometheus.

**Targeted delivery (18 Oct 2026):**
- The per-request epoch tracking (the gateway stamps `pe`, each sidecar serves a request under the epoch it carries), the mismatch counter and the two-phase drain exist in the sidecar and the Operator and are tested. The consistency demo needs a joint policy that spans the gateway and a service, because a service policy no longer reaches the gateway (§5.1a).
- The three controls C4 produces are independent versions: the **chain deadline** goes to the **gateway** sidecars only (scope `gateway`; the gateway creates every request's budget), the **rate limit** and the **retry ratio** go to the **target service's** sidecars only. A service policy is **never** delivered to the gateway: activating one there used to reset the gateway's chain deadlines to the static configuration (`resolveTargets` now follows the scope; drift expectations too).
- A delivery succeeds when the target sidecar holds the version with the approved content hash and has acknowledged it itself. The **rate limit is enforced** by the sidecar (18 Oct 2026, below). The retry ratio is delivered and acknowledged but cannot be enforced yet: the proxy has no retries (a later phase).
- **Rate-limit enforcement (18 Oct 2026, agreed with C2):** activating a policy with `leakyBucket.ratePerSec` sets the rate of the sidecar's inbound leaky bucket (A3.5 v0.3.2); the effective rate is the sidecar metric `rpe_backpressure_inbound_rate_per_second`. Suite 93 shows a burst of chain requests passing quickly at 200 requests/s and being shaped at 2 requests/s, the change taking effect at runtime in both directions, and a restarted sidecar getting its rate back from drift repair (restored policies are delivered oldest approval first, so the newest approved policy wins).
- The hub takes one control per policy (`control` = `chain-deadline | rate-limit | retry-ratio`) and merges it into the service's current policy, as C4's bridge does.

**Console:**
- Rollouts: **Policy delivery** (pick the control and value, see which sidecars it goes to, phase steps, hash verified and sidecar-acknowledged counts, per-sidecar grid, convergence time);
- Policies: the new-policy form uses the same controls;
- Workloads: **Coverage audit** with re-inject, and **Policy enforcement** per proxy (held versions, hashes, in-flight, drift).

**Tests:**
- Operator state-machine tests with a fake proxy (targets by scope, the gateway never contacted by a service policy and vice versa, stage and verify before activate, drain, naive retire, hash mismatch → Failed, one at a time, acks);
- hub test for the single-control policies (scope, merge, validation);
- e2e **92 targeted delivery** (chain deadline on the gateway only, rate limit and retry ratio on the service only, hash verified and sidecar-acknowledged, the gateway's chain deadline unchanged after a service rollout, misdirected controls refused), **91** (Operator restart catch-up and mid-rollout resume, tamper repair, coverage gap and re-inject); 80 and 90 adjusted;
- experiment `experiments/c3/e3-convergence.sh` (propagation time and delivery success per replica count → CSV and summary).

**Redis made disposable (18 Oct 2026; supervisor asked why PostgreSQL and not Redis):**
- Decision: keep **both**. PostgreSQL stays the system of record (a committed proposal item; only its schema refuses a policy without an approver and any in-place edit). Redis stays the fast path (current policies per service, events, acknowledgements) and was made **disposable**, because it runs without persistence and two things were trusted to it.
- The Operator's catch-up checkpoint moved to PostgreSQL (`policy.operator_state`, migration `005`; the old Redis value is carried over once), so losing Redis no longer replays old approvals.
- The effective policies (`GET /api/k8s/effective`, the base the hub and C4 use to build the next version) are rebuilt from PostgreSQL when Redis lost them (`rehydrate.go`); an existing entry is never replaced. Without this, a flush made the next change start from an empty policy and silently drop the service's other settings.
- Test: e2e **94** (flush Redis, effective policies come back; flush and restart the Operator, nothing is replayed and the checkpoint does not move; a change after a flush keeps the other control) plus an Operator unit test.

**Verified on the cluster (18 Oct 2026):** suites 60, 80, 90, 91, 92, 93 and 94 pass.

**Next:**
- joint policy delivery and the consistency demo (step 0b in `C3-Status-and-Plan.md`);
- run the full e2e set and `e3-convergence.sh`, record E3 results;
- regression halt using C4 health and admin revert (P3.4);
- ordered rollout (P3.2);
- the push/ConfigMap/poll study (P3.5);
- the Helm chart (P2.5);
- the Istio comparison (E7). |
| Proposal note | The budget section mentions Node.js. The plan uses Go (consistent with the existing webhook and controller-runtime). Node.js isn't a functional commitment |

## 3. Problem and research gap

Adding a resilience runtime to Kubernetes workloads needs two things:
- (a) injecting the sidecar into every relevant pod without asking developers to edit manifests;
- (b) getting *approved* policy changes to the running sidecars quickly and consistently.

Policy engines (Gatekeeper, Kyverno) govern Kubernetes resources, not a runtime's policies. Full service meshes (Istio) do both, but bring a large footprint and complexity beyond what RPE needs.

**Gap (proposal):** there is no focused, Kubernetes-native enforcement mechanism that combines automatic RPE sidecar deployment with reliable distribution and synchronisation of approved resilience policies, without a full service mesh.

**Groomed gap (new):** during any policy rollout, pods along one request chain briefly run *different* policy versions. A single request can then be governed by inconsistent rules (for example the caller assumes retry budget 3 while the callee enforces 1, or deadlines disagree). No existing mesh or rollout tool guarantees **per-request policy consistency** for resilience policies.

## 4. Proposal commitments (MUST KEEP; this is the checklist)

Source: the C3 proposal PDF (IT23139626, September 2026).

**Abstract:**
- [ ] A lightweight, Kubernetes-native enforcement layer.
- [ ] A **Mutating Admission Webhook** injects and configures the RPE sidecar at pod admission.
- [ ] A **Kubernetes Operator** detects policy changes, resolves affected workloads and distributes updated policies to running RPE-enabled pods.
- [ ] A **PostgreSQL-based policy store with event-driven notification** for near-real-time propagation.
- [ ] Proof-of-concept first: validate HTTP deadline propagation first, then add enforcement and policy management.
- [ ] Evaluation: functional testing, **sidecar coverage**, **policy convergence time**, and comparison of adoption and operational complexity against service meshes.

**Main objective:** [ ] automatically deploy, inject, configure and manage the RPE runtime across Kubernetes workloads, with **consistent and real-time distribution of approved resilience policies** to running RPE-enabled pods, with minimal application changes.

**Specific objectives:**
- [ ] **Automate RPE sidecar deployment:** the webhook injects and configures the sidecar into applicable pods.
- [ ] **Kubernetes workload enforcement:** identify and manage workloads needing RPE; keep sidecar deployment consistent across running pods.
- [ ] **Dynamic policy distribution:** policy storage plus an event-driven mechanism that detects approved changes and delivers them to the relevant running sidecars with minimal delay.
- [ ] **Controlled policy governance:** policies come only from human-approved changes; **the layer does not autonomously modify resilience policies**.
- [ ] **Verify policy application:** monitor and verify delivery and application to the intended workloads.
- [ ] **Evaluate:** functional correctness, coverage, propagation and convergence time, operational adoption characteristics, in Kubernetes microservice environments.

**Methodology:**
- [ ] Applied experimental research with a proof-of-concept design; controlled experiments; no human participants.
- [ ] Order: environment and sidecar integration → webhook → policy store → change detection → Operator → distribution → evaluation against baselines.

**Experimental inputs:**
- [ ] Pod and Deployment configurations.
- [ ] Containerised microservices.
- [ ] Sidecar configuration.
- [ ] Policy configurations.
- [ ] Approved and updated policy versions.
- [ ] Different numbers of pods and replicas.
- [ ] Policy change events.
- [ ] Runtime status and policy-application info reported by sidecars.
- [ ] Environment: **Minikube** (kind is acceptable as an equivalent local cluster).

**Baselines:**
- [ ] **Manual deployment** (no automatic injection).
- [ ] **Full service mesh (Istio)** on infrastructure and operational requirements.
- [ ] Comparison factors: manual configuration needed, automatic sidecar deployment, runtime policy-update capability, Kubernetes components required, operational complexity, resource requirements.

**Metrics:**

| Metric | Target |
|---|---|
| Sidecar injection coverage | 100% of application pods in the test environment |
| Policy propagation time | Lower is better |
| Policy delivery success rate | 100% under normal conditions |
| Policy consistency | All sidecars converge to the expected version |
| Manual configuration effort | Compare manual vs automatic |

**Validation:**
- [ ] **Functional:** admission requests received; pod specs mutated; sidecars present after creation; configuration available; recreated pods get the sidecar.
- [ ] **Policy distribution:** an approved change in the store is detected by the Operator and distributed.
- [ ] **Repeated trials** across pod and replica counts; mean, min, max and standard deviation.
- [ ] **Failure and recovery:** temporary delivery failure, Operator restart, pod restart → eventual return to the expected configuration.

**Development iterations:**
1. [ ] Core runtime integration.
2. [ ] Automatic sidecar injection.
3. [ ] Policy management (PostgreSQL + notification).
4. [ ] Kubernetes Operator.
5. [ ] End-to-end integration.
6. [ ] Evaluation and refinement.

**Responsibility table:**
- **Own:** sidecar injection; updated policy distribution.
- **Shared:** integration with the protocol layer for header creation and validation (C1); interceptor (C2); sending operational metrics to C4; end-to-end integration (all).

**Functional requirements** (the proposal numbering skips 6):
1. [ ] Automatic injection into every application pod admitted to the configured cluster scope.
2. [ ] Sidecar configuration (container config and runtime parameters) added at admission.
3. [ ] Runtime management of RPE components; maintain the configuration of RPE-enabled pods.
4. [ ] Policy information kept in a **centralised policy store**.
5. [ ] Approved policy change detection.
7. [ ] Policy distribution from the store to running sidecars.
8. [ ] Policy version consistency: a mechanism to determine the version delivered to each sidecar.
9. [ ] Policy delivery status recorded or exposed (success and failure).
10. [ ] Integration with the governance workflow: changes accepted only through the approved workflow; never independently approved or modified.

**Non-functional requirements:**
- [ ] **Reliability:** 100% of eligible pods injected.
- [ ] **Policy reliability:** delivered to all required sidecars.
- [ ] **Consistency:** all sidecars report the expected version.
- [ ] **Performance:** propagation within a defined target time. **Define it:** target p95 convergence ≤ 5 s for 50 pods with push. Measure it.
- [ ] **Scalability:** behaviour holds as pods and replicas increase.

**Requirements elicitation:** team discussions, interface analysis, study of Kubernetes mechanisms.

**Commercialisation and budget:** shared team plan; LKR 15,000 cloud testing budget; Minikube locally; no hardware or licence costs.

## 5. Novelty (groomed and defensible)

### 5.1 Primary novelty: per-request-consistent rollout of resilience policies across call chains

**Mechanism: two-phase epoch stamping**, adapted from consistent updates in networking (Reitblatt et al., SIGCOMM'12):
1. **Stage:** the Operator pushes the new policy version to every affected sidecar as **staged** (inactive) and waits for acks from all of them.
2. **Flip at the ingress:** the Operator tells the gateway sidecars to start stamping new requests with the new epoch (`pe` in `X-RPE-Context`).
3. **Apply by epoch:** every sidecar applies the policy matching the request's `pe`. In-flight requests finish under the old epoch, so **no request ever sees a mix of old and new rules**.
4. **Drain and retire:** when `rpe_inflight_requests{epoch=old}` reaches 0 everywhere, the old epoch is retired.

**Fallback and comparison, a topology/direction-ordered rollout** (when two versions can't be held at once):
- Use the dependency graph from C1's topology API.
- Loosening changes (longer deadline, higher rate or budget) go **callee-first**.
- Tightening changes go **caller-first**.
- This minimises unsafe mixed states.

**Safety under governance:**
- If C4's health shows a regression on the target services during the rollout, the Operator **halts** it. Halting creates no new policy state.
- **Reverting** to the previous approved version is a one-click *admin* action.
- This satisfies "the layer does not autonomously modify policies".

**Research question.** Can per-request-consistent (two-phase, epoch-stamped) rollout eliminate the mixed-policy states that naive rollout causes in microservice call chains, and at what cost in convergence time compared with naive, ordered, ConfigMap and polling distribution?

### 5.1a Scope and honest limits (added 18 Oct 2026)

The claim applies to **settings that two services must agree on**. The policy contract (appendix A3.4) puts such settings in one versioned policy: `fanout`, `replaySafe`, `commitStage`, the retry limits, the backpressure mode, the cancellation tiers and the mechanism toggles. C4's recommendation is **joint** (deadline, retry budget and rate are computed together so that they never contradict each other). When one policy version changes several of these across services, a request that meets the old version at one service and the new one at the next can be governed by rules that contradict each other. Stamping the epoch at the gateway and applying the policy of that epoch at every hop removes that state.

What is true today:
- The mechanism is built and tested: staged delivery, the gateway stamps `pe`, each sidecar serves a request under the epoch it carries, the previous epoch is drained by in-flight count, and mixed-policy hops are counted (`rpe_policy_mismatch_total`).
- The sidecar reads only two things from a policy today: the gateway route deadlines and the leaky-bucket rate. No sidecar code reads the coupled settings yet (retries are a later C2 phase). So the demo shows versions overlapping and being separated, not yet a harmful outcome. The panel will ask "what goes wrong in a mixed state?", and the answer has to be shown with settings that are enforced (the example below).
- Measurement of 17 Oct 2026 (commit `d50b4b7`): at a 2000 ms propagation spread, naive delivery produced 8 to 10 mixed-policy requests per run and two-phase produced 0 (3 runs each). It predates targeted delivery (a service policy no longer reaches the gateway), so it must be **re-run with a joint policy that spans the gateway and a service** before it is quoted.
- Example of harm with settings enforced today: one joint change, chain deadline 1000 to 4000 ms (gateway) and payment rate 200 to 2 requests/s. If the rate drops first, requests wait up to 3.5 s in payment's queue against the old 1000 ms deadline and expire (`cs=expired`, deadline-exceeded counts); with the deadline first they do not.
- Open design question: a leaky-bucket rate cannot be chosen per request, so the rate part of a joint policy needs either ordered activation (loosening first) or a behaviour agreed with C2.
- Development step 0b in `C3-Status-and-Plan.md` covers the joint policy delivery and the re-run.

### 5.2 Supporting contributions

1. **Distribution-mechanism study:** PostgreSQL LISTEN/NOTIFY push (the committed design) vs ConfigMap volume mount vs sidecar polling. Measures convergence time and delivery success at 10 → 200 pods.
2. **Delivery verification and drift repair** (delivers "verify policy application"):
   - acks carry version and hash;
   - the `RPEPolicy` CR `.status` shows desired vs observed and convergence %;
   - a periodic drift check repairs mismatches.
3. **Coverage auditing:** detect pods in enabled scopes that bypassed admission (created before the webhook, webhook down with `failurePolicy: Ignore`), and report the gap. Webhook safety tests (latency, failure modes).
4. **Footprint comparison with Istio** (committed baseline): CPU, memory, added p99, number of Kubernetes components/CRDs, installation steps.
5. **Helm chart** for the whole platform.
6. **Targeted delivery:** each control reaches exactly the sidecars its scope names (the chain deadline the gateway sidecars; the rate limit and the retry ratio the target service's sidecars), staged and checked against the content hash the sidecar computed itself, then acknowledged by the sidecar.
7. **Recovery:** after an Operator restart, a pod restart or a tampered sidecar the fleet returns to the approved configuration; Redis is disposable and rebuilt from PostgreSQL, the system of record.

### 5.3 Wording to USE and AVOID

- **USE:** "Per-request policy consistency for resilience policies across a microservice call chain." "Bringing consistent-update guarantees from network updates (two-phase, version stamping) to L7 resilience policies."
- **AVOID (these are taken):**
  - "Sidecars acknowledging the config version is new": Istio xDS ACK/NACK, `istioctl proxy-status`.
  - "Canary config rollout is new": Facebook Configerator, Flagger.
  - "Lightweight injection is new": Istio, Linkerd.
  - "Per-request consistency" for the independent controls alone: the deadline and the retry allowance travel in the request and a rate is local to one sidecar. Claim it for settings two services must agree on (5.1a).

### 5.4 Prior art and how to answer it

| Examiner says | Answer |
|---|---|
| "Istio already pushes config and tracks sync." | Yes, per proxy. It doesn't guarantee that a *request* traversing several proxies sees one consistent policy version, and it isn't specific to resilience semantics. We measure mixed-policy requests: naive (Istio-like) vs ours = 0. |
| "This is just Reitblatt's consistent updates." | It's the same principle applied to a new domain: L7 resilience policies, with the epoch carried in the protocol context (C1's `pe`), sidecars holding staged versions, and in-flight-based draining. We cite it as the foundation. |
| "Why PostgreSQL rather than Redis (or etcd/CRDs)?" | It is the proposal's committed design (an audit-friendly relational store with LISTEN/NOTIFY), shared with C4's audit log. Redis is the fast path and is disposable: the Operator rebuilds it from PostgreSQL. CRDs mirror the policies for a Kubernetes-native view. |
| "Halting automatically is autonomous modification." | Halting doesn't change any policy; it pauses distribution. Revert needs an admin. |
| "If the deadline travels in the request, what can be mixed?" | Only settings two services must agree on: the coupled fields of the policy contract and C4's joint recommendation (5.1a). We show it with one joint change of chain deadline and rate. |
| "Does the rate limit actually take effect?" | The target sidecar applies the delivered rate to its inbound leaky bucket (the queue capacity is never changed by a policy). C3 proves the sidecar holds the approved content and acknowledged it. |

### 5.5 45-second pitch

> "When a new resilience policy rolls out, a single request can hit a pod on the old policy and then a pod on the new one, so the caller and callee disagree on deadline or retry budget mid-request. Our Operator first stages the new policy on every sidecar, then the gateway stamps new requests with the new version, and every sidecar applies the version the request carries. One request always sees one policy. During a live rollout, the naive approach produces many mixed-policy requests; ours produces zero, and convergence stays within seconds."


## 6. Scope boundary

| C3 does | C3 does NOT do |
|---|---|
| Webhook injection (sidecar + init container + ports + UID), namespace and pod opt-in, coverage audit | Implement the sidecar or its mechanisms (C2) |
| PostgreSQL policy store, NOTIFY triggers, policy API (intake from C4/admin with an approval record), Operator, CRDs, rollouts (two-phase, ordered, naive), acks, drift repair, status | Approve policies or compute values (C4 / admin) |
| Helm chart for the platform; platform install docs | Define header fields (C1); `pe` semantics are C1's, the rollout logic is C3's |

## 7. Detailed design

### 7.1 Admission webhook (`k8s/admission-webhook/`)

- **Selection:** `MutatingWebhookConfiguration` with `namespaceSelector: rpe-injection=enabled` and object selector override `resilience.rpe.dev/inject=enabled|disabled`. `failurePolicy: Ignore` (default, studied) or `Fail` (configurable). `sideEffects: None`, `reinvocationPolicy: Never`, `timeoutSeconds: 5`.
- **Mutation:**
  - the init container `rpe-init` sets the iptables rules (NET_ADMIN only on init): inbound TPROXY → 15006; outbound REDIRECT → 15001; **exclude `--uid-owner 1337`**; exclude the ports 15020 / 15090 and `excluded-outbound-ports`;
  - the sidecar container `rpe-sidecar` runs as UID/GID 1337, with ports 15001/15006/15020/15090, env (role, service name from labels, policy API URL, hub URL, OTel endpoint), readiness on `/rpe/v1/health`, resource requests and limits;
  - the annotation `resilience.rpe.dev/injected=true` is added.
- Uses typed `k8s.io/api/core/v1` and the JSON patch.
- **Image** from `--sidecar-image` / Helm values. Remove the hard-coded image.
- **Certificates:** keep `cmd/certgen` (Helm job) or cert-manager.
- **Tests:** table tests for mutate (opt-in/out, roles, ports, idempotency, existing init containers); an envtest e2e admitting a pod.

### 7.2 Workload identification

- **Scope:** namespaces labelled `rpe-injection=enabled`; workloads opt in or out by annotation.
- **Mapping:** the Kubernetes Service selector maps pods to the RPE `service` name (label `app.kubernetes.io/name` or annotation `resilience.rpe.dev/service`).
- **Coverage auditor:** on each reconcile, list pods in enabled scopes; any without an `rpe-sidecar` container goes into `coverage.gaps`, exposed through the API and the metric `rpe_injection_coverage_ratio`.

### 7.3 Policy store (`k8s/policy-store/`, PostgreSQL 16, schema `policy`)

```sql
create table policies (
  policy_id text not null, version int not null, epoch text not null unique,
  scope jsonb not null, spec jsonb not null,
  source text not null check (source in ('c4-recommendation','admin')),
  recommendation_id text, approved_by text not null, approved_at timestamptz not null,
  status text not null default 'approved-pending-rollout', created_at timestamptz default now(),
  primary key (policy_id, version));
create table rollouts (
  rollout_id bigserial primary key, policy_id text, version int, mode text check (mode in ('two-phase','ordered','naive')),
  distribution text check (distribution in ('push','configmap','poll')) default 'push',
  canary_percent int default 100, state text, started_at timestamptz, finished_at timestamptz, halted_reason text);
create table deliveries (
  pod text, service text, epoch text, version int, hash text, state text check (state in ('staged','active','retired','failed')),
  acked_at timestamptz, error text, primary key (pod, epoch));
create table policy_events (id bigserial primary key, policy_id text, version int, kind text, payload jsonb, at timestamptz default now());
-- trigger: after insert on policies -> pg_notify('rpe_policy_approved', json_build_object('policyId',policy_id,'version',version)::text)
```

**Rules:**
- Insert requires a non-null `approved_by`/`approved_at`. The API refuses policies without an approval record (governance FR10).
- Policies are append-only: never update `spec` in place; a new version is a new row.
- **Roles of PostgreSQL and Redis.** PostgreSQL is the system of record: approvals, immutable versions, rollouts, deliveries, the Operator's catch-up position (`policy.operator_state`) and the audit shared with C4. Redis is the fast path (current policies per service, events, acknowledgements, change messages) and is disposable: it runs without persistence, and the Operator rebuilds a missing effective-policy entry from PostgreSQL. A policy can only enter through the PostgreSQL schema, which refuses a row without an approver and any in-place edit.

### 7.4 Policy API and Operator (`k8s/operator/`, Go, controller-runtime)

- **Policy API** (an HTTP server in the operator binary, or a separate `policy-api` deployment):
  - `POST /api/k8s/policies` (from C4 or the admin through the hub; idempotent by `Idempotency-Key`);
  - `POST /api/k8s/rollouts`;
  - `GET /api/k8s/policies/{id}/status`;
  - `POST /api/k8s/acks` (from sidecars);
  - `GET /api/k8s/fleet` (pods, sidecar and policy versions, drift, coverage);
  - `POST /api/k8s/rollouts/{id}/revert` (admin only; re-applies the previous approved version as a new rollout).
- **CRDs:**
  - `RPEPolicy`: a mirror of the approved policy (spec) plus `.status {desiredEpoch, observed: [{pod, epoch, hash, state}], convergencePct, driftPods}`;
  - `RPERollout`: `{policyRef, mode, distribution, canaryPercent}` plus `.status {phase: Staging|Activating|Draining|Complete|Halted, timestamps, mixedPolicyRequests}`.
- **Change detection:** a `LISTEN rpe_policy_approved` loop, with reconnect and catch-up from `policy_events` after downtime (covers the Operator-restart test). On notify: upsert `RPEPolicy` and, if auto-rollout is configured for admin-approved policies, create an `RPERollout`; otherwise wait for an explicit rollout request.
- **Reconciler:** resolve target pods by the policy's scope (the gateway sidecars for a gateway-scoped chain deadline, the service's sidecars for a rate limit or retry ratio; never the gateway for a service policy) → run the rollout state machine (§7.5) → update the status. Periodic drift check: `GET :15020/rpe/v1/policy` per pod every 30 s; on a mismatch, re-push and count `rpe_drift_repairs_total`. A joint policy spans several scopes (§5.1a).
- **Failure handling:** retries with backoff for each pod push; delivery failures are recorded in `deliveries` with the error; recovery is tested.

### 7.5 Rollout state machine

**Two-phase (default):**
1. **Staging:** `PUT /rpe/v1/policy {state: staged}` to all target pods (parallel, bounded concurrency) → wait for 100% acks (timeout → retry; persistent failure → halt with a reason).
2. **Activating:** `POST /rpe/v1/policy/activate {epoch}` to the **gateway** sidecars (and mark it active on service sidecars). New requests now carry the new `pe`.
3. **Draining:** watch `rpe_inflight_requests{epoch=old}` (Prometheus or the sidecar API) until it is 0 everywhere, or until a max drain time (= the max deadline budget) passes.
4. **Complete:** `DELETE /rpe/v1/policy/{oldEpoch}`; the policy status becomes `active`.

**Ordered:** compute the DAG from `GET /api/protocol/topology`; direction = compare the new spec with the old (deadline↑, rate↑ and budget↑ are loosening). Loosening: reverse topological order (callees first). Tightening: topological order (callers first). Push active in waves; wait for acks per wave.

**Naive:** push active to all pods concurrently (the Istio-like baseline).

**Canary %:** stage and activate on N% of the target pods' gateways or routes first (optional).

**Regression halt:** during Activating and Draining, poll `GET /api/intel/health` for the target services. If health is worse than before the rollout for 2 cycles → **Halted**: stop the remaining waves, keep the state, notify the admin. Revert only through the admin endpoint.

**Mixed-policy measurement:** the hub computes, from hop events, the requests whose hops reported more than one `policyEpoch`. Exposed as `rpe_mixed_policy_requests_total` and in the rollout status. (Not built yet: today the count comes from the sidecars' own counters; the hub variant needs the hop events.)


### 7.6 Distribution modes (for the study)

- **push:** the Operator calls the sidecar API on NOTIFY (the committed design).
- **configmap:** the Operator writes a ConfigMap per service; the sidecar watches the mounted file (kubelet sync delay applies).
- **poll:** the sidecar polls `GET /api/k8s/policies/effective?service=` every N s (N = 5/15/30).

The mode is selected per rollout (`distribution`) and in the sidecar config (needs a small C2 feature: a file watcher and a poller).

### 7.7 Acks, consistency and status

- Acks: `{pod, service, epoch, version, hash, state, ts}` → the `deliveries` table → CR status.
- **Version consistency (FR8):** `GET /api/k8s/fleet` shows, per pod, its staged and active epochs and hash.
- **Delivery status (FR9):** `deliveries.state` and `error`.

### 7.8 Helm chart (`charts/rpe/`)

**Sub-components (each with values toggles):**
- namespace `rpe-system`;
- webhook (Deployment, Service, MutatingWebhookConfiguration, certgen Job);
- operator and policy API (Deployment, RBAC, CRDs);
- PostgreSQL (bitnami dependency or a simple StatefulSet);
- protocol-service (C1);
- intelligence (C4);
- hub and console;
- observability (Prometheus, Tempo, OTel collector, Grafana; optional);
- the demo app (optional, `demo.enabled`).

**RBAC:** the operator gets get/list/watch pods, services, namespaces; CRUD on RPEPolicy/RPERollout and ConfigMaps; nothing more.

## 8. Contracts C3 provides and consumes

- **Provides:**
  - A3.2 (ports, UID, annotations);
  - the A3.4 policy and rollout APIs;
  - the fleet and coverage API;
  - `rpe_injection_coverage_ratio`, `rpe_drift_repairs_total`, rollout status;
  - the Helm chart.
- **Consumes:**
  - the A3.5 sidecar API (C2);
  - acks (C2);
  - the topology API (C1, for ordered mode);
  - the health API (C4, for regression halt);
  - hop events via the hub (for the mixed-policy metric);
  - approved policies from C4/admin (A3.4).

## 9. Legacy code guide

| Path | Verdict | Fixes |
|---|---|---|
| `_research/old_shits/admission-webhook/main.go` | **Keep and harden** | Change the egress exclusion from `--uid-owner 0` to **1337** and run the sidecar as 1337; separate ports 15001/15006/15020/15090; rename annotations `resilience.rrpe.dev/*` → `resilience.rpe.dev/*`; namespace label selection; configurable image (no `2002rusira/...:v9`); use `k8s.io/api` types; add tests; add the webhook configuration manifest |
| `_research/old_shits/admission-webhook/cmd/certgen/main.go` | Keep | Run as a Helm pre-install Job |
| `_research/old_shits/admission-webhook/Dockerfile` | Keep | Bump the Go version; multi-stage scratch image is fine |
| `_research/old_shits/netty-sidecar-proxy/docs`, `DEADLINE_TPROXY_GUIDE.md` | Read | How the sidecar uses TPROXY; check the iptables rules against it |

## 10. Implementation plan

**Phase 0: Contracts (weeks 1–2):** agree A3.2/A3.4/A3.5 with C2 and C4; set up the kind cluster scripts (`make cluster`).

**Phase 1: Injection (→ PP1):**
- P1.1 Move and harden the webhook (all §9 fixes), unit tests and envtest.
- P1.2 MutatingWebhookConfiguration, certgen Job, namespace opt-in.
- P1.3 Functional validation per §4 (admission received, mutation, sidecar present, recreated pods injected).
- P1.4 Coverage auditor (first version) and the metric.
- **Acceptance:** 100% injection on the demo app; root containers are intercepted (bug fixed); a passthrough request works.

**Phase 2: Store + Operator + distribution (→ PP2):**
- P2.1 PostgreSQL schema, migrations, NOTIFY trigger.
- P2.2 Policy API with approval enforcement; idempotency.
- P2.3 Operator: LISTEN loop with catch-up, CRDs, reconciler, naive push, acks, status.
- P2.4 Drift check and repair; Operator-restart and pod-restart recovery.
- P2.5 Helm chart v1 (platform install in one command).
- **Acceptance:** C4/admin-approved policy → delivered to all target sidecars → status 100%; recovery tests pass.

**Phase 3: Consistent rollout (main novelty) (→ PP2/Final):**
- P3.1 Two-phase epoch rollout (needs the C2 epoch support), drain detection. Built and tested (staged delivery, gateway stamping, per-request pinning, drain by in-flight count); the demo needs a joint policy (§5.1a).
- P3.2 Ordered rollout using the C1 topology.
- P3.3 Mixed-policy metric. The counter is built in the sidecar and the Operator; the hub-computed variant (from hop events) is not built.
- P3.4 Regression halt plus admin revert.
- P3.5 ConfigMap and poll distribution modes (with C2).
- P3.6 Console C3 module (§12).
- **Acceptance:** live demo, naive (mixed > 0) vs two-phase (mixed = 0) with a joint policy; and targeted, verified delivery of each control to its own sidecars (§5.1a, step 0b in `C3-Status-and-Plan.md`).

**Phase 4: Evaluation (→ Final):** E1–E7; Istio installed in a separate namespace or cluster for the comparisons; thesis.

## 11. Evaluation plan

| ID | Experiment | Compared | Metrics | Target / output |
|---|---|---|---|---|
| E1 | Injection coverage | Manual deployment vs webhook | Coverage %, manual steps/lines of YAML per workload, admission latency | 100%; manual effort reduced (quantified) |
| E2 | Webhook safety | failurePolicy Ignore vs Fail, webhook down | Pods missed (Ignore) vs blocked (Fail); auditor detection | Gaps detected 100% |
| E3 | Propagation and convergence | Push (LISTEN/NOTIFY) vs ConfigMap vs poll(5/15/30 s); pods 10/50/100/200 | Time from approval to 100% acked (mean, min, max, sd, p95), delivery success | Push fastest; target p95 ≤ 5 s at 50 pods |
| E4 | **Consistent rollout** | Naive vs ordered vs two-phase, under steady traffic | Mixed-policy requests, deadline/retry violations during the transition, convergence time, drain time | Two-phase = 0 mixed; ordered < naive (re-run with a joint policy, §5.1a) |
| E5 | Failure and recovery | Operator restart, pod restart, temporary delivery failure, DB restart | Time to return to the expected configuration, missed updates | Eventual convergence 100% |
| E6 | Drift | Manual tampering of a sidecar's policy | Detection time, repair time | Repaired within 1 drift interval |
| E7 | Footprint vs Istio | RPE platform vs Istio (sidecar mode) on the same demo app | CPU/memory (control plane and per sidecar), added p99, number of components/CRDs, install steps | Smaller footprint for the RPE scope (reported honestly) |

Repeat ≥ 5 times per configuration; report mean, min, max, sd and p95.

## 12. Admin Console: C3 tab and Operate contributions

**Operate side (C3-owned):**
- *Fleet:* namespaces and workloads with injection on/off (labels and annotations via the API); pods with sidecar version, staged/active epochs, hash, drift; coverage % and gaps.
- *Policies & Approvals* (shared with C4): version history, start rollout (mode, distribution, canary %), live status, halt reason, **admin revert**.

**C3 Enforcement tab (Demonstrate).** The main novelty demo comes first.

| Sub-tab | Act → Observe |
|---|---|
| **Main: Consistent rollout** | With steady traffic (Scenario Lab "normal"), approve a policy change (for example payment deadline 800 → 1,200 ms, retry budget 3 → 1), choose **naive** or **two-phase** and roll out → live **mixed-policy requests counter** (many vs 0), per-pod epoch grid turning from old to staged to active, drain progress |
| Policy delivery | Approve a chain deadline (to the gateway) or a rate limit or retry ratio (to a service) → the target sidecars, per-sidecar staged → active, content hash verified, the sidecar's own acknowledgement and the convergence time appear live |
| Injection | Label a namespace, deploy a workload → sidecar appears; coverage % updates; create a pod while the webhook is down → the auditor flags the gap |
| Convergence | Choose push / ConfigMap / poll and a pod count → convergence-time chart per pod, with mean/p95 |
| Failure & Recovery | Buttons: kill the Operator, restart a pod, make a sidecar reject pushes → watch reconciliation and catch-up |
| Drift | Tamper with a sidecar's policy → drift detected and repaired |
| Footprint | RPE vs Istio resource and component comparison (from the E7 data) |

## 13. Viva and demo script

1. **Pitch** (§5.5, 45 s), with **one diagram**: old/new epochs and stamping at the gateway (stage → flip → drain).
2. **Demo 1 (main):** naive vs two-phase mixed-policy counter with a joint policy (2 min); available after step 0b (§5.1a).
3. **Demo 2:** targeted, verified delivery: the chain deadline to the gateway only, a rate limit or retry ratio to the service only, each acknowledged by the sidecar (3 min).
4. **Demo 3:** inject a new namespace live, then kill the Operator → recovery (2 min).
5. **Results slides:** E3 propagation time and delivery success per replica count, E4 mixed-policy table, E1/E2 coverage, E7 footprint.

**PP1 runbook (updated 18 Oct 2026).** Before the panel: `scripts/cluster-down.sh && scripts/cluster-up.sh && scripts/build-images.sh && scripts/deploy.sh` (fresh cluster, all suites green), then `scripts/console.sh` (http://localhost:8800, log in as `admin`).
1. **Injection and coverage (Workloads).** Audited coverage is 100%. Point at the proxy user 1337 and the `rpe-init` exit 0. *Optional live gap:* run the steps of `tests/e2e/91-recovery.sh` §4 (webhook down with `failurePolicy: Ignore`, create a pod), show **Pods without the proxy**, restore the webhook, click **Re-inject**.
2. **Targeted delivery (Rollouts → Policy delivery).**
   - **Chain deadline** (route `GET /api/orders`, for example 1100 ms): the table shows it delivered to **the gateway sidecars** only; the hash is **verified** and the sidecar's acknowledgement time is shown.
   - **Rate limit** for `payment-svc` (for example 150): delivered to **the payment-svc sidecars** only. Workloads → Policy enforcement shows the gateway still holds its own chain-deadline version, untouched.
   - **Retry ratio** for `payment-svc`: a new version that keeps the rate limit, again only on the payment-svc sidecars.
   - Say plainly: enforcing the rate limit and the retry ratio is the sidecar's job (C2); C3 delivers, verifies and records the acknowledgement.
3. **Recovery.** Start a delivery with **Delivery delay** 4000 ms and run `kubectl --context kind-rpe -n rpe-system delete pod -l app=rpe-operator` while it runs: the rollout resumes and completes. Tamper with a sidecar (Proxies, stage a different policy under the active epoch): **Policy enforcement** shows the hash mismatch, then **In sync** within `DRIFT_INTERVAL_MS`.
4. **Evidence.** The output of `experiments/c3/e3-convergence.sh` (CSV and summary: success rate, mean, min, max, sd and p95 of the propagation time per replica count).
5. **Consistent rollout (after step 0b).** Roll out a joint change (chain deadline 1000 to 4000 ms and payment rate 200 to 2 requests/s) as naive and as two-phase under steady traffic: the mixed-policy counter and the expired requests rise with naive and stay at 0 with two-phase.

**Likely questions:**
- "Istio does this?" (§5.4)
- "Why PostgreSQL?"
- "Is halting autonomous modification?" (no)
- "What's the target time?" (p95 ≤ 5 s at 50 pods)
- "Industry uses Redis for this, why PostgreSQL?" (Both are in the design. Redis is the fast path for the current policies, events and acknowledgements. PostgreSQL is the record because an approval must be durable and constrained: no policy without an approver, no edit in place, atomic version numbers, and the audit shared with C4. Redis cannot enforce those. Redis is disposable: we flush it in a test and the system rebuilds it. Both notification paths take milliseconds, so the choice is about guarantees, not speed.)
- "Reitblatt?" (cite it as the foundation)
- "What if a sidecar can't hold two versions?" (the ordered fallback)
- "If the deadline travels in the request, what can be mixed?" (Only settings two services must agree on: the coupled fields of the policy contract and C4's joint recommendation. See 5.1a; we show it with a joint change of chain deadline and rate.)
- "Why not also send a rate limit to the gateway?" (The gateway applies only the chain deadline. Activating a service policy on it used to reset its route deadlines to the static configuration, so the policy's scope decides the targets.)
- "Does the rate limit actually take effect?" (Enforcement is the sidecar's job (C2). C3 proves the target sidecar holds the approved content, by the hash the sidecar computed, and that it acknowledged it.)

## 14. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Depends on C2 epoch support | Agree A3.5 early; build against a stub sidecar (a small Go server implementing A3.5) for operator tests |
| Depends on C2 for what a delivered policy does | The rate limit is enforced by the sidecar (done, agreed with C2); the retry ratio waits for the proxy's retry phase. C3 guarantees delivery, hash verification and the acknowledgement |
| TPROXY/iptables issues on kind | Use the legacy script as the base; test with Docker Desktop Linux VM / kind nodes |
| Istio comparison effort | Use Istio's default profile and the same demo app; measure only the listed factors |
| Scale tests on a laptop | 200 pods using tiny stub sidecars (the stub server) for the convergence experiments; real sidecars for E3 and E4 |

## 15. Definition of Done (C3)

- [ ] Every §4 commitment is ticked, with evidence.
- [ ] Hardened webhook with tests; UID-1337 fix verified with a root app container.
- [ ] PostgreSQL store + NOTIFY + policy API (approval enforced) + Operator + CRDs + acks + drift + coverage.
- [ ] Two-phase, ordered and naive rollouts; push, ConfigMap and poll distribution; halt + admin revert.
- [ ] Helm chart installs the whole platform in one command.
- [ ] E1–E7 done with CSVs and charts in `experiments/c3/`.
- [ ] Console C3 tab and Fleet/Policies (Operate) work on live data.

## 16. Open questions for the supervisor

1. Approve the new main novelty (per-request-consistent rollout) as an *addition* to the committed scope. Supervisor feedback on 18 Oct 2026: applicable. Confirm that it may be demonstrated with a joint policy that spans the gateway and a service (§5.1a). Also confirm the positioning of the remaining contributions: targeted, hash-verified, sidecar-acknowledged and recoverable distribution of approved policies, plus automatic injection with a coverage audit, without a service mesh.
2. Approve kind as equivalent to Minikube.
3. Define the "target time" NFR: proposed p95 ≤ 5 s at 50 pods.
4. Is automatic halt on regression acceptable under the governance commitment (revert stays manual)?

## 17. Glossary

- **Epoch:** the policy version stamped into requests (`pe`).
- **Two-phase rollout:** stage everywhere, then flip at the ingress.
- **Drain:** wait until no in-flight requests use the old epoch.
- **Mixed-policy request:** a request whose hops used different epochs.
- **Drift:** a sidecar's policy differs from the desired one.
- **Coverage gap:** an eligible pod without a sidecar.
- **NOTIFY:** PostgreSQL's LISTEN/NOTIFY event mechanism.

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
- **Traces:** the RPE sidecars (not the services) export OTLP/HTTP to `rpe-tempo.rpe-system:4318` and sample every trace; the webhook passes the endpoint to every injected sidecar.
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
console/                      shared  React + TS (Vite); src/modules/{c1-protocol,c2-interceptor,c3-enforcement,c4-intelligence}
hub/                          shared  rpe-hub (auth, routing, SSE, experiments store)
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

**Admin Console** (`console/`, shared shell; each student owns their module):

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

**Admin Panel (built):** `console/` (React + TS, Vite) + `hub/` (Go rpe-hub: signed-cookie auth, roles admin/sre/viewer, least-privilege RBAC). `make console` serves it at http://localhost:8800. It is a function-organised operations console, **not per-student tabs**:
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
