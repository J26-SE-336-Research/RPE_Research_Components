# C4: Resilience Intelligence Layer (A-to-Z Guide)

**Owner:** R.M.S.B. Rathnayake (IT23289420). **Project:** J26-SE-336 RPE. **Guide version:** 1.0 (15 Oct 2026).

> This file is self-contained. It holds everything about C4: the proposal commitments, groomed scope, novelty, design, contracts, existing code, plan, evaluation, UI, viva and checklists. The appendix at the end is the shared ecosystem reference, identical in all four component files.

---

## 0. How to use this file with Claude

From the repository root:

```
claude "Read docs/components/C4-Intelligence-Rathnayake.md completely before doing anything. Then <task>."
```

Example tasks:
- `"...Then move _research/old_shits/intelligence-layer into intelligence/, delete the v1 analysis/ engine, and make all remaining tests pass (P1.1)."`
- `"...Then change the workflow so every recommendation requires human approval (P1.2) and update the tests."`
- `"...Then persist recommendations, decisions and audit in PostgreSQL (P1.3)."`
- `"...Then implement the joint feasibility-constrained recommender (P2.1) per §7.4 with unit tests."`
- `"...Then implement the impact preview and outcome verification (P2.2/P2.3) per §7.5–§7.6."`
- `"...Then build the C4 console module per §12 (keyboard accessible, status not colour-only)."`

**Rules Claude must follow for C4 work:**
1. Obey **Appendix A8**. Never remove a proposal commitment (§4). **Every recommendation requires human approval.** There is no automatic release mode by default; the existing `Decision.AUTOMATIC` path must go.
2. C4 owns: `intelligence/`, `experiments/c4/`, its pages in `admin-panel/web/src/pages/`. Don't edit other components' folders.
3. Java 21, Spring Boot 4.x (the existing app uses 4.1.1 and Jackson 3, `tools.jackson`). `./mvnw -q verify` must pass.
4. **Readability:** reformat the existing dense code (one statement per line, named methods, comments for each rule) so every line can be explained in the viva.
5. PromQL and TraceQL must use the metric and attribute names in **A3.3** exactly.
6. No recommendation without sufficient telemetry (NFR). Every recommendation carries evidence, the rule, the current and proposed values, and the preview.
7. After finishing a phase task, update **§2 Status** in this file.

---

## 1. Identity and role

- **Component:** Resilience Intelligence Layer (C4).
- **Role:** *decides.* Every minute it analyses Prometheus metrics and Tempo traces over a 5-minute window against an operational baseline, and classifies each service and chain as Healthy, Stressed or Overloaded. It then:
  - recommends **coordinated** changes to Leaky Bucket rate, retry budget and chain deadline, and detects cancellation problems;
  - shows the evidence and a predicted impact;
  - gets **human approval**;
  - sends approved policies to C3;
  - monitors the outcome and proposes gradual restoration.
- **Research group / cluster / SDGs:** Software Systems and Technologies; Distributed Systems and Cloud Native Computing; SDG 9, 12, 13.

## 2. Status (update after every phase)

| Item | State (16 Oct 2026) |
|---|---|
| Spring Boot app | **Exists** in `_research/old_shits/intelligence-layer/` (copied from `RPE_POC1` branch `component-4-integrated-v2`, commit `775fd0a`). Spring Boot 4.1.1, Java 17, about 4,600 LOC; **79 tests pass**; dashboard on port 8090 (`adaptive.html`/`adaptive.js`, and an older `index.html`/`dashboard.js`) |
| Good parts (v2 `adaptive/` package) | `AdaptiveEngine` (MAD baselines, persistence cycles, cooldowns, bounded change limits, gradual restore, chain deadlines), `RobustStatistics` (median/MAD), `DeadlineMath`, `PrometheusWindows`, `TempoWindows`, `AdaptiveCollector`, `PolicyWorkflow`, `PolicyDispatcher` (POSTs to C3 with `Idempotency-Key`), `MockScenarios` with a virtual clock, `ConnectionChecks`, `AdaptiveController` (`/api/v2/intelligence`) |
| Problems | (1) **Auto-release:** `PolicyWorkflow` marks Leaky Bucket and retry-budget changes `Decision.AUTOMATIC`, so only deadlines need approval. **This violates the proposal** (and C3 FR10). (2) **Two parallel engines:** the v1 `analysis/*` + `controller/*` (+ `index.html`/`dashboard.js`) and the v2 `adaptive/*`. (3) **Everything in memory** (`InMemoryRecommendationReviewStore`, engine state), so the audit log is lost on restart. (4) **Dense code style** (multiple statements per line), hard to defend line by line. (5) PromQL metric names aren't aligned with C2 (A3.3). (6) No joint constraints across policies, no impact preview, no outcome verification, no comparison of detectors |
| **Built in the monorepo (16 Oct 2026)** | `intelligence/` runs in **live mode** in `rpe-system` (87 tests; e2e 70 + 95).
- **P1.2 done: every recommendation needs approval.** `Decision.AUTOMATIC` is no longer used. A rejection holds for 30 minutes for the identical recommendation. A re-offered recommendation keeps its id and decision and refreshes its evidence. Only `APPROVED` and non-`MOCK` recommendations are delivered.
- **P1.3 done (audit):** `AuditLog` writes every proposal, decision and delivery to PostgreSQL `intel.audit`. Migration `003_intel_audit.sql` makes it append-only with a trigger. Read it with `GET /api/v2/intelligence/audit`.
- **P1.4 done:** PromQL uses the C2 contract `rpe_*`:
  - `rpe_original_requests_total`;
  - `rpe_requests_total{outcome}` (errors = `failed` only; 429/504 rejections are protection at work);
  - `rpe_request_duration_seconds`;
  - `rpe_retries_total`, `rpe_rejections_total`, `rpe_inflight_requests`;
  - `up{service,namespace}`.
  Tempo uses the `rpe.*` span attributes. Trace counts are cross-checked against the gateway's `rpe_chain_requests_total` / `rpe_chain_deadline_exceeded_total`. The current route deadline is the `rpe.deadline.budget_ms` the gateway stamped. Unmonitored hops (no sidecar) are reported, not blocking. Downstream spans that outlive the root count as orphan work instead of invalidating the trace.
- **`Component3Bridge`** replaces `PolicyDispatcher`:
  - an approved recommendation becomes the next version of the target's current C3 policy, with `recommendationId` and `approvedBy` = the reviewer;
  - chain deadlines become `spec.routes[route].deadlineMs` on the gateway policy;
  - it starts a two-phase rollout; ACCEPTED becomes **APPLIED** only when the rollout completes;
  - C3's effective policies are synced back as the engine's current reference.
- **Live tuning for the demo cluster:** 1-minute window, 15-second cycle, `rrpe.adaptive.evaluation-delay=20s` (Tempo ingestion), baseline 5 samples.
- **Fixes found on live data, all with tests:**
  - **MAD floor.** Near-zero MAD from steady traffic turned noise into breaches; the practical floor now always applies, in both `AdaptiveEngine` and `DeadlineHealth`.
  - **Censoring-aware deadline increase** (`DeadlineMath.increaseCensored`). Observed p99 is cut at the deadline during deadline failures, so the uncensored baseline p99 × 1.10 is a lower bound.
  - **Bounded steps** (`DeadlineMath.clamp`). A large increase becomes a +50% step instead of being dropped.
  - **Re-learn baselines** (`POST /baselines/reset`) after an intentional change.
- Header renamed `X-RPE-Control-Token` (the hub sends it; the reviewer is always the signed-in user).

**Admin Panel:** Recommendations → approval queue (review drawer with evidence and measurements, Approve / Reject with a reason), Health, Reviewed (delivery status), Audit trail, Simulation.

**Next:** P1.1 (delete v1, rename packages, reformat); then the novelty: joint recommender, impact preview, outcome verification, detector comparison. |
| Verdict | **Keep the v2 engine (about 60% reuse)**, delete v1, refactor for readability, then add the novelty |

## 3. Problem and research gap

Timeouts, retry controls, rate limits and scaling rules are configured independently, at the level of an individual service, app or route. Without coordination, they cause overload, extra retries, deadline failures, wasted resources and slow recovery.

- Anomaly-detection research (ServiceAnomaly, trace/LSTM approaches) detects and explains problems but **doesn't recommend coordinated policy changes**.
- MAPE-K is a general loop with no RPE-specific rules or human review.
- Products (Resilience4j, Istio, HPA, Prometheus/Tempo/Grafana) provide mechanisms and observability, but **don't decide** coordinated, explainable policies across a whole request chain.

**Gap (proposal):** it's unclear whether a rule-based, sliding-window, chain-aware intelligence layer can produce **correct, timely and stable** recommendations for deadline, backpressure (Leaky Bucket), retry budget and cancellation, with **enough evidence for a user to approve them safely**. The proposal itself states that decisions about deadlines, backpressure, retry budgets and cancellation **should be made together**.

## 4. Proposal commitments (MUST KEEP; this is the checklist)

Source: the C4 proposal PDF (IT23289420, September 2026).

**Abstract:**
- [ ] Use **Prometheus metrics and Tempo traces**.
- [ ] Compare a **5-minute window** with an operational baseline **every minute**.
- [ ] Classify services as **Healthy / Stressed / Overloaded**.
- [ ] Suggest changes to **Leaky Bucket rates, retry budgets and chain-specific deadlines**.
- [ ] Spot **cancellation-related problems**.
- [ ] **Every suggestion contains supporting evidence** and **needs approval from an authorised user**.
- [ ] DSR with quantitative evaluation; iterative prototyping.
- [ ] Tested in a controlled Kubernetes environment against **static policies**.
- [ ] Evaluation metrics: classification and suggestion accuracy, detection time, p95/p99, error rate, retry amplification, successful throughput, deadline failures, recovery time, **component overhead**.
- [ ] C3 spreads approved policies, C2 enforces them, and C4 watches the results and helps restore policies gradually.

**Main objective:** [ ] design, implement and evaluate a chain-aware intelligence layer that uses runtime metrics and traces to generate **coordinated, human-approved** recommendations for deadline, backpressure, retry budget and cancellation policies in REST microservices.

Prometheus collects every 15 s, and C4 analyses every 1 min with a recent 5-min window and a longer baseline. Each recommendation includes:
- recent and baseline measurements;
- the affected service or chain;
- the triggered rule;
- the current policy and the proposed value.

**Specific objectives:**
1. **Telemetry collection and analysis:**
   - [ ] retrieve service and RPE metrics from Prometheus;
   - [ ] retrieve request and span information from Tempo;
   - [ ] analyse every minute over a 5-min window;
   - [ ] compare with a longer baseline window;
   - [ ] validate that the required metrics and traces are available.
2. **Request-chain integration:**
   - [ ] retrieve reconstructed request paths and span information from Tempo;
   - [ ] **use the RPE path information defined by C1** as extra chain evidence;
   - [ ] identify the services and fan-out branches affected;
   - [ ] associate Prometheus metrics with the relevant chain;
   - [ ] validate association with the correct known chain.
3. **Health classification:**
   - [ ] define the rules for Healthy/Stressed/Overloaded;
   - [ ] evidence: latency, error rate, request rate, rejected requests, retries, deadline failures, cancellations, in-flight requests;
   - [ ] detect changes against the baseline;
   - [ ] test under load spikes, service delays, retry storms and recovery;
   - [ ] measure accuracy with labelled scenarios.
4. **Recommendation engine:**
   - [ ] Leaky Bucket rate recommendations under overload or backpressure;
   - [ ] retry-budget recommendations when retry amplification is detected;
   - [ ] **chain-specific deadlines from observed p99 + safety margin**;
   - [ ] detect cancellation problems (downstream processing continuing after cancellation);
   - [ ] reason, measurements, current policy and proposed value with every recommendation;
   - [ ] measure accuracy against predefined expected responses.
5. **Review and approval dashboard:**
   - [ ] show current service health and affected chains;
   - [ ] present recommendations in plain language with evidence;
   - [ ] approve/reject every recommendation;
   - [ ] record recommendations, decisions, timestamps and policy versions for auditing;
   - [ ] **send approved recommendations to C3 through an integration API**;
   - [ ] display the distribution and enforcement status received from C3 and C2.
6. **Evaluate:**
   - [ ] simulate normal traffic, spikes, downstream delays, retry storms, deadline failures, cancellation problems and recovery;
   - [ ] compare static policies with approved adaptive recommendations;
   - [ ] measure chain-association accuracy, classification accuracy and recommendation accuracy;
   - [ ] measure detection time, p95/p99, error rate, retry amplification, successful throughput and recovery time;
   - [ ] **count unnecessary or repeated policy changes** (stability);
   - [ ] monitor recovered services; verify temporary policies are **restored gradually without oscillation**.

**Methodology:**
- [ ] DSR (Hevner) plus controlled quantitative experiments.
- [ ] Test conditions: normal, increasing traffic, spikes, downstream delays, retry storms, unsuitable deadlines, cancellation problems, recovery.
- [ ] Static vs adaptive with the same workload, resources, introduced problem and duration.

**Data:**
- [ ] Controlled Kubernetes environment; no personal data; no external participants; an authorised test account approves or rejects.
- [ ] Data from C2 sidecars, Prometheus and Tempo.
- [ ] **Exclude start-up/warm-up data.**
- [ ] Group by condition and service.
- [ ] 5-min windows.
- [ ] **Exclude windows with missing or insufficient samples** from accuracy calculations.

**Baselines:**
- [ ] An **operational baseline**: stable traffic with no failures gives normal ranges for latency, errors, retries, rejections, cancellations, in-flight requests and throughput.
- [ ] The recent window is the last 5 min, analysed each minute; a minimum sample count is required.
- [ ] The **static policy configuration** (fixed Leaky Bucket rates, retry budgets, deadlines) vs human-approved adaptive recommendations, with the same initial values, workload, resources, problem and duration.

**Metrics table:**

| Metric | Target |
|---|---|
| p95/p99 latency | Lower than static under failures |
| Error rate | Lower than static |
| Deadline | Recommended deadline within the safety range; fewer deadline-exceeded requests than the static deadline |
| Detection time | ≤ 2 analysis cycles (120 s) |
| Recovery time | Lower than the fixed policy |
| Health classification accuracy | ≥ 80% against known conditions |
| Recommendation accuracy | ≥ 80%, with **no** recommendation beyond the safety boundaries |
| Successful throughput | Higher during overload, not reduced during normal operation |

**Validation:**
- [ ] Health classification: accuracy and detection time against known situations.
- [ ] Recommendations: the right policy picked, the right direction, within safety limits, deadline suggestions giving fewer exceeded requests than the fixed deadline, evidence attached.
- [ ] Workflow and integration: Prometheus/Tempo connectivity; the dashboard with a test account; **approved → sent to C3; rejected → not sent**.
- [ ] Recovery: static vs adaptive on latency, errors, retry amplification, throughput, deadline failures and time to recover; detects improvement and suggests restoration **without extra or conflicting suggestions**.

**Development:** iterative prototyping with Agile practices; cycles checked against requirements; feedback from the supervisor and team.

**Users and requirements:**
- *DevOps/SRE:*
  - [ ] see Healthy/Stressed/Overloaded;
  - [ ] see the service and chain information;
  - [ ] see recommendations for Leaky Bucket, retry budget, chain deadlines and cancellation problems;
  - [ ] see the supporting metrics and the reason;
  - [ ] monitor the outcome and recovery.
- *Authorised platform admin:*
  - [ ] approve/reject;
  - [ ] only approved recommendations go to C3;
  - [ ] review recommendations and decisions;
  - [ ] see the status of each;
  - [ ] **set classification thresholds and policy safety limits**.

**Functional requirements:**
- [ ] Get metrics from Prometheus and traces from Tempo.
- [ ] Analyse against the baseline every minute.
- [ ] Classify Healthy/Stressed/Overloaded.
- [ ] Link the condition with the service and chain.
- [ ] Produce evidence-supported recommendations.
- [ ] Let an authorised user approve or reject.
- [ ] Send approved recommendations to C3.
- [ ] Record recommendations, decisions and policy history.
- [ ] Detect recovery and suggest gradual restoration.

**Non-functional requirements:**
1. [ ] **Response time:** detect within 2 cycles / 120 s.
2. [ ] **Accuracy:** ≥ 80%.
3. [ ] **Security:** only authorised users approve or reject.
4. [ ] **Usability:** clearly show the service, health, recommendation and evidence.
5. [ ] **Reliability:** no recommendation when telemetry is missing or insufficient.
6. [ ] **Scalability:** analysis finishes within each 1-min interval.
7. [ ] **Accessibility:** keyboard-usable; health **not conveyed by colour alone**.
8. [ ] **Auditability:** every recommendation and decision saved with date, time and status.

**Literature commitments (cite):**
- ServiceAnomaly (Panahandeh et al., JSS 2024);
- Kohyarnejadfard et al. (J. Cloud Computing 2022, NLP/LSTM);
- Dean & Barroso, The Tail at Scale (CACM 2013);
- Huang et al., Metastable Failures in the Wild (OSDI'22);
- Rutten et al., MAPE-K feedback control (2017);
- Resilience4j; Istio; Kubernetes HPA; Prometheus/Tempo/Grafana;
- Hevner et al., DSR (MISQ 2004).

**Commercialisation and budget:** shared team plan. Competitive advantage: explainable, chain-aware, coordinates recommendations across policies, human approval, works with existing Prometheus/Tempo.

## 5. Novelty (groomed and defensible)

### 5.1 Primary novelty: protocol-informed, explainable **joint** recommendation with a **verified impact preview**

- **Joint:** deadline, retry budget and Leaky Bucket rate for a chain or service are computed **together, under feasibility constraints** (§7.4), so the recommendations never contradict each other. For example, a retry is only allowed if it fits inside the deadline, and retries count against capacity.
- **Protocol-informed:** the evidence includes cross-hop data that black-box tuners don't have:
  - per-hop deadline consumption (C1 trail);
  - retry-budget use and denial reasons;
  - push backpressure levels (C2);
  - cancellation delivery.
- **Explainable:** each recommendation shows the rule, the inputs, the constraint that bound it, and the current → proposed values.
- **Verified preview:** before approval, a predicted impact ("≈ X% fewer deadline failures, ≈ Y% shed, ≈ Z extra retries/s"). After rollout, C4 measures the actual impact and reports the **prediction error**.

**Research question.** Can jointly constrained, explainable recommendations with an impact preview give accurate, stable and *correctly predicted* improvements over static policies and simpler detectors, within the proposal's targets (≥ 80% accuracy, ≤ 120 s detection, no safety-limit violations)?

### 5.2 Supporting contributions

1. **Outcome verification and confidence.** Compare the windows before and after rollout. Mark each recommendation effective, neutral or ineffective; adjust a per-rule confidence score shown to the reviewer. An ineffective change becomes a **revert recommendation that still needs approval**.
2. **Detector comparison.** On the labelled dataset, compare the MAD-baseline classifier with a static threshold, z-score and Isolation Forest. This answers "why rule-based?" with data: comparable accuracy, explainable, no training.
3. **Orphan-work detection** from Tempo: spans that continue after the cancellation timestamp (delivers the "cancellation problems" commitment) plus C2's wasted-work metric.
4. **Anti-oscillation:** persistence cycles, cooldowns, maximum change per step, and hysteresis on restoration. *Measured:* oscillation count.

### 5.3 Wording to USE and AVOID

- **USE:** "Protocol-informed, explainable joint recommendation of deadline, retry budget and rate, with an impact preview whose accuracy is verified after approval."
- **AVOID (these are taken):**
  - "First to tune timeout and retry together": adaptive retry/timeout controllers (CMU self-adaptive circuit breaking and retry; Hanada & Ishibashi) and optimiser patents exist.
  - "First anomaly detection for microservices": ServiceAnomaly and many others.
  - "ML-based": it's deliberately rule-based and explainable.

### 5.4 Prior art and how to answer it

| Examiner says | Answer |
|---|---|
| "Rule-based thresholds aren't research." | The contribution is the *joint, constrained, verified* recommendation, not the thresholds. We still show the detector compares well with z-score and Isolation Forest (E3), and is explainable without training. |
| "Adaptive retry/timeout controllers already exist." | They tune one or two settings locally, as black boxes. We use cross-hop protocol evidence, enforce joint feasibility, keep a human in the loop, and *verify* predictions. |
| "Why human approval?" | Proposal commitment and governance. The preview and confidence score make approvals fast and safe. |
| "How accurate is the preview?" | E5 reports the prediction error per policy type. |
| "Most of this code existed before." | The existing code is the classification and single-policy base. This year's contributions are the joint recommender, preview, verification, persistence and evaluation (§2/§10). |

### 5.5 60-second pitch

> "Tuning one knob at a time causes conflicts. Raising retries when the deadline is tight just burns the remaining time, and raising a deadline during overload holds resources longer. Our intelligence layer watches Prometheus, Tempo and the RPE protocol's own evidence (which hop used the deadline, how much retry budget was used, how hard services are pushing back), classifies health every minute, and recommends deadline, retry budget and rate together under explicit feasibility rules. Each recommendation says why, and predicts what will happen. A human approves it, C3 rolls it out, and we then measure what actually happened against the prediction."

## 6. Scope boundary

| C4 does | C4 does NOT do |
|---|---|
| Collection, windows, baselines, classification, joint recommendation, preview, approval workflow, audit, dispatch to C3, outcome verification, restoration, orphan-work detection, settings (thresholds and safety limits) | Distribute or apply policies (C3, C2); define fields (C1) |
| Provide health to C3 (rollout regression halt) | Approve anything automatically |

## 7. Detailed design

### 7.1 Data collection (every 60 s; Prometheus scrape 15 s)

PromQL (A3.3 names), with `W=5m` recent and baseline from the stored baseline windows:

| Signal | PromQL |
|---|---|
| Original requests | `sum by (service,route) (increase(rpe_original_requests_total{direction="inbound"}[5m]))` |
| p95/p99 latency | `histogram_quantile(0.95, sum by (le,service) (rate(rpe_request_duration_seconds_bucket{direction="inbound"}[5m])))` |
| Error rate | `sum by(service)(increase(rpe_requests_total{outcome=~"failed\|rejected"}[5m])) / sum by(service)(increase(rpe_original_requests_total[5m]))` |
| Retry rate and effectiveness | `increase(rpe_retries_total{result="attempted"}[5m])`, `increase(rpe_retries_total{result="succeeded"}[5m])` |
| Rejections | `increase(rpe_rejections_total[5m])` by reason |
| Deadline failures | `increase(rpe_deadline_exceeded_total[5m])` |
| Cancellations | `increase(rpe_cancellations_total[5m])` |
| In-flight | `avg_over_time(sum by(service)(rpe_inflight_requests)[5m:])` |
| Successful throughput | `sum by(service)(rate(rpe_requests_total{outcome="ok"}[5m]))` |
| Backpressure | `max_over_time(rpe_backpressure_level[5m])`, `avg_over_time(rpe_backpressure_utilization_ratio[5m])` |
| Wasted work | `increase(rpe_wasted_work_seconds_total[5m])` |
| Chain p99 | Gateway `rpe_request_duration_seconds` by `route` (end-to-end) |

**Tempo:**
- TraceQL for chains: spans grouped by `rpe.rid`;
- ordered path per route;
- fan-out branches;
- orphan-work detection (`span.end > cancel_ts` for spans of requests with `rpe.cs != none`).

**C1:** `GET /api/protocol/attribution/{rid}` and `/topology` provide per-hop deadline consumption and the graph.

**Data validity:** `complete=false` when a series is missing, samples are below the minimum or the data is stale. No recommendation in that case (NFR5). Discard the first window after start-up (warm-up).

### 7.2 Health classification (keep the v2 MAD algorithm)

- **Per service:** learn the baseline median and MAD per signal (latency p95, errorRate, retryRate, rejectionRate, inFlight, throughput) from stable Healthy windows (the stability check in `learn()`).
- **Thresholds:** Stressed = median + 2×MAD; Overloaded = median + 3×MAD. The effective variation has a floor (to avoid MAD = 0). Throughput is inverted (a drop is bad).
- **Persistence:** a signal must breach for 2 consecutive cycles. Critical latency or error breaches at 3×MAD give Overloaded. One persistent signal gives Stressed; two or more give Overloaded.
- **States:** `LEARNING_BASELINE`, `BASELINE_READY`, `INSUFFICIENT_DATA`, `PENDING_BREACH`, `HEALTHY`, `STRESSED`, `OVERLOADED`.
- **Baseline refresh** after N healthy cycles.

### 7.3 Single-policy formulas (current design; keep as inputs to the joint step)

- **Chain deadline:** `candidate = ceil(recentChainP99 × (1 + margin))`. The margin is 10% (latency change ≤ 10%), 20% (10–25%) or 30% (> 25%). Restoration: `max(baselineP99 × 1.10, current × 0.90)`. A deadline increase is blocked during Overloaded.
- **Leaky Bucket:** `capacity = min(baselineThroughput, recentThroughput)`, `rate = capacity × 0.90` (Stressed) or `× 0.75` (Overloaded). Recovery: `+5% of the baseline` per step, capped at the healthy target.
- **Retry budget:** `effectiveness = successfulRetries / retryAttempts`, `ratio = maxRatio × effectiveness × healthFactor`. healthFactor is 1.0 (Healthy), 0.5 (Stressed) or 0.0 (Overloaded, retries disabled). Never increase during stress; +0.01 steps on recovery. A nonzero budget needs a replay-safe operation and enough remaining deadline.
- **Safety limits (settings):** min/max deadline, maximum relative change per step (deadline and rate), maximum retry ratio, cooldown per action.

### 7.4 Joint feasibility-constrained recommendation (main novelty)

Per chain *c* (route at the gateway) and its services *s*, combine the single-policy candidates into one **recommendation set** that satisfies all of these:

1. **Deadline–latency:** `D ≥ P99_c × (1 + m)`, using the margin rule above.
2. **Retry fits the deadline:**
   - `k_max = floor((D − P99_c) / (backoff_avg + P50_down))`;
   - retries per request ≤ `k_max`, and the retry ratio `r ≤ min(r_candidate, k_max / avgAttemptsPerReq)`;
   - if `k_max = 0`, then `r = 0`.
3. **Retries count against capacity:** `R × (1 + r) ≤ C_s × safety`, so the effective load including retries fits capacity. If it's violated, reduce `r` first (retries are the cheapest to remove), then `R`.
4. **No conflicting directions under overload:** if Overloaded, then `D` doesn't increase, `r` doesn't increase and `R` doesn't increase.
5. **Downstream-first protection:** use C1's per-hop attribution to find the hop consuming the most deadline (the bottleneck service). Rate and retry changes target the callers of the bottleneck; the deadline targets the chain.
6. **Safety limits:** every value is within the configured bounds and maximum step size. If the constraints can't all be met, emit **no recommendation**, with an explanation ("infeasible: …").

**Output:** `RecommendationSet { id, target(chain, services), health, items:[{policy, current, proposed, unit, rule}], constraintsApplied:[…], evidence:{recent, baseline, attribution, bp, retryStats, orphanWork}, preview:{…}, confidence }`.

### 7.5 Impact preview (computed before approval)

- **Deadline:** predicted deadline-failure rate = `P(latency_c > D_new)` from the recent chain latency histogram (Prometheus buckets for the gateway route). Compare with `P(latency_c > D_current)`.
- **Leaky Bucket:** predicted shed fraction = from the per-second arrival counts over the window, `Σ max(0, a_t − R_new) / Σ a_t`, with the burst taken into account.
- **Retry budget:** predicted extra load = `arrivals × failureProb × r_new`; predicted saved retries = `(r_current − r_new) × arrivals × failureProb`.
- **Combined:** the headline "≈ X% fewer deadline failures, ≈ Y% shed, ≈ Z retries/s fewer", with assumptions listed.
- Stored with the recommendation for later comparison.

### 7.6 Outcome verification

- **When:** after C3 reports rollout `complete` (`GET /api/k8s/policies/{id}/status`).
- **Measure:** the next 5-min windows vs the 5-min windows before. The actual deadline-failure rate, shed fraction and retries/s.
- **Prediction error:** `|predicted − actual|` per item, with relative error.
- **Effectiveness:**
  - effective if the target metric improved by ≥ θ (for example 10%) and no guard metric (p99, error rate, throughput) degraded by > θ;
  - neutral if neither;
  - ineffective otherwise.
- **Confidence:** a per-rule running score shown in the UI.
- **Ineffective** → create a revert recommendation (approval required).

### 7.7 Workflow, persistence and API

- **States:** `PROPOSED → PENDING_APPROVAL → APPROVED | REJECTED | WITHDRAWN | SUPERSEDED → DELIVERED (C3 accepted) → ROLLED_OUT → VERIFIED(effective|neutral|ineffective)`.
- **Remove `Decision.AUTOMATIC`.** All recommendations are `PENDING_APPROVAL`.
- **Optional guardrail-envelope mode:** **off by default**, only if the supervisor approves, and documented as an addition.
- **PostgreSQL schema `intel`:** tables `recommendations`, `items`, `evidence` (jsonb), `decisions` (user, role, comment, ts), `deliveries`, `outcomes`, `settings_history`, `audit` (append-only).
- **Dispatch:** the existing `PolicyDispatcher` → `POST /api/k8s/policies` with the approval record and `Idempotency-Key = recommendationId` (A3.4). Rejected recommendations are never sent.
- **API:** A3.6 (`/api/intel/*`). Authorisation: the hub passes the user and role; `decision` requires `admin` or `sre`.
- **Health for C3:** `GET /api/intel/health?services=` (used for the regression halt).

### 7.8 Labelled dataset and evaluation harness (`experiments/c4/`)

- **Dataset:** each Scenario Lab run stores a ground-truth timeline (fault start/end, expected health per service, expected recommendation type and direction). Windows overlapping warm-up or transitions are flagged.
- **Detectors:** MAD (ours), a static threshold, z-score (mean ± 3σ), and Isolation Forest (Python, scikit-learn, offline on exported window features). Scripts are in `experiments/c4/detectors/`.
- **Mock mode:** keep `MockScenarios` and the virtual clock for unit and regression tests.

## 8. Contracts C4 provides and consumes

- **Provides:**
  - A3.6 intelligence API;
  - the health API for C3;
  - policies to C3 (A3.4) with approval records.
- **Consumes:**
  - A3.3 metrics, traces and hop events (C2);
  - the C1 topology and attribution APIs (A3.7);
  - C3 rollout status (A3.4);
  - Scenario Lab ground-truth labels.

## 9. Legacy code guide

| Path (under `_research/old_shits/intelligence-layer/`) | Verdict | Notes |
|---|---|---|
| `src/main/java/org/rrpe/intelligence/adaptive/*` | **Keep** | Rename the package to `dev.rpe.intelligence`; refactor for readability; replace the single-policy dispatch with the joint set |
| `adaptive/PolicyWorkflow.java` | **Change** | Remove `Decision.AUTOMATIC` (lines ~86–90: `proposal.action() == Action.CHAIN_DEADLINE ? PENDING_APPROVAL : AUTOMATIC`) and the "Only deadlines require human review" guard (~line 107); persist |
| `adaptive/PolicyDispatcher.java` | Keep | Point it at `POST /api/k8s/policies`; include the approval record |
| `adaptive/PrometheusWindows.java`, `TempoWindows.java`, `AdaptiveCollector.java` | Keep, update | PromQL/TraceQL per §7.1 and A3.3 |
| `adaptive/MockScenarios.java` | Keep | Tests and offline demos |
| `analysis/*`, `controller/*` (v1), `static/index.html`, `dashboard.js/.css` | **Delete** | The legacy v1 engine and dashboard (the README calls `/api/v1` legacy) |
| `static/adaptive.html`, `adaptive.js` | Port | Becomes the console C4 module (React) |
| `src/test/...` | Keep the v2 tests | Delete the v1 tests together with the v1 code; add tests for the new features |
| `deploy/kubernetes.yaml`, `Dockerfile` | Replace | With the Helm subchart (C3's chart) and the updated Dockerfile |

## 10. Implementation plan

**Phase 0: Contracts (weeks 1–2):** agree A3.3 (metric names) with C2 and A3.4/A3.6 with C3; move the code into `intelligence/`.

**Phase 1: Clean base aligned with the proposal (→ PP1):**
- P1.1 Delete v1, rename the packages, reformat for readability, all v2 tests green.
- P1.2 **Approval for every recommendation** (remove AUTOMATIC); update the tests.
- P1.3 PostgreSQL persistence (schema `intel`), the audit log, policy history.
- P1.4 PromQL/TraceQL aligned to A3.3; connection checks; the insufficient-data rule.
- P1.5 Dispatch to the C3 API; show the C3/C2 status.
- **Acceptance:** a live demo app run → classification → recommendation → approve → C3 receives it; reject → nothing sent; restart keeps the audit.

**Phase 2: Main novelty (→ PP2):**
- P2.1 Joint feasibility-constrained recommender (§7.4) with unit tests per constraint.
- P2.2 Impact preview (§7.5).
- P2.3 Outcome verification and confidence (§7.6); revert recommendations.
- P2.4 Chain evidence from C1 attribution and C2 backpressure; orphan-work detection.
- **Acceptance:** each recommendation set shows its constraints, evidence and preview; after rollout it shows predicted vs actual.

**Phase 3: Evaluation + UI (→ Final):**
- P3.1 Labelled dataset from the Scenario Lab; detector comparison.
- P3.2 E1–E7.
- P3.3 Console C4 module (§12), accessibility (keyboard, non-colour status labels/icons).
- P3.4 Thesis.

## 11. Evaluation plan

| ID | Experiment | Compared | Metrics | Target / output |
|---|---|---|---|---|
| E1 | Classification | Labelled scenarios (normal, spike, slow-downstream, retry-storm, recovery) | Accuracy, precision/recall per class, detection time | ≥ 80%; ≤ 120 s |
| E2 | Recommendation correctness | Expected responses per scenario | Right policy, right direction, within safety limits, evidence present | ≥ 80%; 0 safety violations |
| E3 | Detector comparison | MAD vs static threshold vs z-score vs Isolation Forest | Accuracy, detection time, false alarms, explainability (qualitative) | MAD competitive and explainable |
| E4 | Static vs adaptive (approved) | Static policy vs joint recommendations vs single-policy recommendations (ablation of the joint constraints) | p95/p99, error rate, deadline failures, retry amplification, successful throughput, recovery time | Joint ≥ single > static |
| E5 | **Preview accuracy** | Predicted vs actual after rollout | Absolute and relative error per policy type | Small error, reported with CIs |
| E6 | Stability | Through recovery | Number of changes, repeated/unnecessary changes, oscillations | Gradual restore, 0 oscillation |
| E7 | Overhead | n/a | Analysis time per cycle, CPU/memory, query load | Analysis < 60 s per cycle |

Exclude warm-up windows and windows with insufficient data. Repeat each scenario ≥ 5 times.

## 12. Admin Console: C4 tab and Operate contributions

**Operate side (C4-owned):**
- *Policies & Approvals:* a queue of recommendation sets with evidence, constraints, preview, confidence, approve/reject (with a comment), status from C3/C2.
- *Settings:* thresholds (MAD multipliers, persistence cycles), safety limits, windows, Prometheus/Tempo endpoints.
- *Audit log.*

**C4 Intelligence tab (Demonstrate).** The main novelty demo comes first.

| Sub-tab | Act → Observe |
|---|---|
| **Main: Joint recommendation with verified preview** | Start the slow-payment (or retry-storm) preset → health timeline goes Healthy → Stressed → Overloaded (text labels and icons, not colour only) → a **joint recommendation set** appears (deadline + retry + rate) with the constraints applied, the evidence (recent vs baseline, C1 per-hop attribution, C2 backpressure) and the **predicted impact** → approve → C3 rollout status → after the windows, **predicted vs actual** with the prediction error |
| Health | Live per-service and per-chain states with evidence; detection-time markers vs ground truth |
| Restoration | Remove the fault → gradual restoration recommendations (approval each); oscillation counter |
| Cancellation Problems | Orphan-work findings from Tempo (spans after cancel), with links to the traces |
| Accuracy | Tables from E1–E3 (live, from the labelled runs) |
| Audit | Every recommendation, decision, user, timestamp and policy version |

## 13. Viva and demo script

1. **Pitch** (§5.5, 60 s), with **one diagram**: evidence (Prometheus + Tempo + C1 trail + C2 backpressure) → constraints → recommendation set + preview → approval → C3 → verification.
2. **Demo 1 (main):** the slow-payment preset, then joint recommendation → approve → predicted vs actual (3 min).
3. **Demo 2:** restoration without oscillation (1 min).
4. **Results slides:** E1/E2 accuracy, E3 detectors, E4 static vs joint, E5 preview error.

**Likely questions:**
- "Why not ML?" (E3, explainability, no training data needed)
- "Joint tuning exists?" (§5.4)
- "Why human approval?"
- "How do you avoid oscillation?"
- "What if telemetry is missing?" (no recommendation; INSUFFICIENT_DATA)
- "How did you get ground truth?" (Scenario Lab labels)

## 14. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Looks "already done" | Clear separation of the existing base vs this year's novelty (§2, §5.4); commit history |
| Metric-name mismatch with C2 | A3.3 contract; a connection-check page that verifies each query returns data |
| Preview inaccurate | Report it honestly (that's the research result); refine the estimators; show CIs |
| Not enough labelled data | Automate Scenario Lab runs (≥ 5 per scenario) with labels |
| Dense legacy code questioned | Refactor early (P1.1) |

## 15. Definition of Done (C4)

- [ ] Every §4 commitment is ticked, with evidence.
- [ ] No automatic release; every recommendation is approved by an authorised user; rejected → never sent.
- [ ] PostgreSQL persistence and audit; restart-safe.
- [ ] Joint recommender, preview, outcome verification, confidence, revert recommendations, orphan-work detection.
- [ ] PromQL/TraceQL aligned with A3.3; insufficient-data handling.
- [ ] E1–E7 with CSVs and charts in `experiments/c4/`.
- [ ] Console C4 tab and the Policies & Approvals / Settings / Audit pages work on live data; keyboard accessible; status not colour-only.

## 16. Open questions for the supervisor

1. Confirm that **all** recommendations need approval (the earlier RPE_Components.md said Leaky Bucket and retry would be automatic, which contradicts the proposal). Is an optional pre-approved "guardrail envelope" mode acceptable as a documented extension, off by default?
2. Approve the detector-comparison baselines (z-score, Isolation Forest) as additions.
3. Approve the preview-accuracy metric as an additional evaluation metric.

## 17. Glossary

- **MAD:** median absolute deviation.
- **Window:** the recent 5-min data, analysed every minute.
- **Baseline:** stable Healthy reference.
- **Recommendation set:** jointly constrained changes for one chain or service.
- **Preview:** the predicted impact before approval.
- **Outcome verification:** actual vs predicted after rollout.
- **Orphan work:** processing that continues after cancellation.
- **Persistence:** consecutive cycles needed before acting.
- **Cooldown:** minimum time between changes of the same action.

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
