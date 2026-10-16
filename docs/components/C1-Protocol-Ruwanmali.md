# C1: Protocol & Specification Layer (A-to-Z Guide)

**Owner:** Ruwanmali K.H (IT23151642). **Project:** J26-SE-336 RPE. **Guide version:** 1.0 (15 Oct 2026).

> This file is self-contained. It holds everything about C1: the proposal commitments, groomed scope, novelty, design, contracts, existing code, plan, evaluation, UI, viva and checklists. The appendix at the end is the shared ecosystem reference, identical in all four component files.

---

## 0. How to use this file with Claude

From the repository root, start Claude Code and give it this file first:

```
claude "Read docs/components/C1-Protocol-Ruwanmali.md completely before doing anything. Then <task>."
```

Example tasks:
- `"...Then create spec/RPE-v1.md following §7.1–§7.9 with rule IDs, and stop for my review."`
- `"...Then do Phase 1 task P1.3 (rpe-go codec for X-RPE-Context) with tests, following §10."`
- `"...Then port the Go context guard to rpe-java (P1.6) and make both pass conformance-kit vectors."`
- `"...Then build the wire-format benchmark (P2.1) and produce the results table in experiments/c1/."`
- `"...Then implement the C1 console module per §12 against the APIs in Appendix A3.7."`
- `"...Then review my code against the Definition of Done in §15 and list what's missing."`

**Rules Claude must follow for C1 work:**
1. Obey **Appendix A8 (hard rules)**. Never remove a proposal commitment (§4).
2. C1 owns: `spec/`, `libs/rpe-go/`, `libs/rpe-java/`, `conformance-kit/`, `bench/`, `services/protocol-service/`, `experiments/c1/`, `console/src/modules/c1-protocol/`. Don't edit other components' folders. If a contract (A3) must change, write the proposal into `spec/CHANGES.md` and tell the user.
3. **Both libraries must stay semantically identical.** Every behaviour change needs a conformance vector added to `conformance-kit/vectors/`, and both runners must pass.
4. Every spec rule has an ID (`R-xxx`) used in code comments, test names and validation error messages.
5. Go: `go test -race ./...` and `go vet` must pass; gofmt. Java: `./mvnw -q verify`; Java 21 target; JUnit 5.
6. No new dependency unless it's justified in the PR description. Prefer the standard library in Go.
7. After finishing a phase task, update **§2 Status** in this file.

---

## 1. Identity and role

- **Component:** Protocol & Specification Layer (C1 in the ecosystem; the proposal calls it "Protocol and Specification Layer").
- **Role in the ecosystem:** *defines the rules.* C2 enforces them, C3 carries the libraries inside the sidecars it deploys, and C4 reads the fields and trail the protocol makes possible. If the spec is ambiguous, the implementations diverge, so precision is the job.
- **Research group:** Software Systems and Technologies. **Cluster:** Distributed Systems and Cloud Native Computing. **SDGs:** 9, 12, 13.

## 2. Status (update after every phase)

| Item | State (15 Oct 2026) |
|---|---|
| Go reference library | **Exists** in `_research/old_shits/protocol/` (package `rpe`, about 1,000 LOC + 300 LOC tests). `go test -race` passes, 74.6% coverage. Has: request/response context, parsing with duplicate/missing/range checks, monotonic `Deadline` with an offset bound, `CancellationToken`, `LogicalCallState` (terminal / retry-blocked), `RetryLedger` with allocation receipts (budget conservation), `ValidateSameInvocationTransition` / `ValidateChildTransition`, root/child context generation, topology trail (interned uint16 service IDs, registry version, incomplete flag, max 16 records / 1,400 B), HTTP helpers |
| Problems in the existing Go lib | Uses **~24 separate headers** (`Protocol-Version`, `Request-Id`, `Deadline`, …) instead of the single `X-RPE-Context`. Deadlines are absolute Unix ms (skew-sensitive). `SplitChildren` gives an arbitrary half of the pool. No fan-out mode, point of no return, `late` flag, child annotations, `rbu` or policy epoch |
| Java library | **Built (16 Oct 2026)**: `libs/rpe-java` (Java 21, no runtime dependencies, package `dev.rpe.protocol`): `RpeContext` (decode/encode/validate/newRoot/child), `ContextGuard` (R-T01..R-T06), `Hop`, `RpeResult`, `BudgetSplit`, `Ids` (trace binding), `ProtocolException` (code + rule); `http.Admission` (framework-neutral inbound step: trust boundary, R-D02/R-T04 rejection) and `http.RpeHttpClient` (`java.net.http` adapter that propagates the child context and bounds the call by the remaining deadline). Tests: 103 conformance vectors + 5,000-input differential corpus + adapter tests over a real HTTP server. **Next:** RestTemplate/WebClient/Feign/OkHttp adapters (P1.5); C2 migrates the sidecar onto it |
| **Built in the monorepo (15 Oct 2026)** | `libs/rpe-go`: legacy library seeded (module `github.com/J26-SE-336-Research/rpe/libs/rpe-go`) **plus the new header set**:
- `sfv.go`: an RFC 8941 dictionary subset.
- `xcontext.go`: `X-RPE-Context` decode/encode with unknown-member forwarding (R-V01); `Hop`; `Result`; `NewRoot`; `Child`; `ValidateContextTransition` R-T01..R-T06 with rule IDs on `ProtocolError.Rule`; W3C trace binding.
- `httpmw.go`: `Middleware` (gateway trust boundary R-S01; expired/cancelled rejected before the app, R-D02/R-T04; hop header stripped; request context deadline for cooperative cancellation) and `Transport` (child derivation; propagated deadline enforced, so a call that outlives it becomes a terminal 504); `SplitBudget` (R-F05).

`go test -race` passes, 77.9% coverage. The shared demo app uses these adapters, and e2e suites 30/35/40 verify them on Kubernetes.

`Transport` keeps an existing same-trace `traceparent` (tracing client spans), so the OpenTelemetry trace tree stays intact.

**Next:** the spec doc (P0.1/P1.1), framework adapters for `rpe-java`, the trail. |
| Spec document | Only the scope notes in `RPE-M1-Scope-Ruwanmali.md` (same as `_research/old_shits/protocol/RPE-M1.md`). No RFC-style spec yet |
| Conformance kit | **Built (16 Oct 2026)**: `conformance-kit/vectors/*.json`, 103 cases: decode 46, encode 5, transition 15, child 6, split 7, result 10, hop 7, traceparent 7. Errors are compared by code and rule ID. There is a Go runner (`conformance-kit/go`), and `rpe-java` runs the same files. **Both pass 100%.** `cmd/genvectors` regenerates the vectors from the reference; any change is a spec change. `cmd/difffuzz` writes a deterministic mutation corpus (5,000 inputs, about 1,100 still valid) with Go verdicts, which `rpe-java` replays with 0 divergences. Both run in `make test-unit` |
| Wire-format benchmark, topology rebuilder, protocol service | **Missing** |
| Integration | The demo services use `rpe-go` end to end, verified on Kubernetes. The new C2 sidecar strips `X-RPE-Hop` but does not parse the context yet; it migrates to `rpe-java` in C2 Phase 1. The admin panel's Protocol Tools and Request Inspector run on `rpe-go` |

## 3. Problem and research gap

HTTP microservices handle deadlines, retry limits, backpressure and cancellation separately at each service:
- Resilience4j and Hystrix keep state inside one service.
- Istio and Envoy act at the proxy, but carry no cross-hop resilience state.
- gRPC propagates deadlines and cancellation, but only inside gRPC, and has no cross-hop retry budget or backpressure context.
- W3C Trace Context carries identity, not resilience semantics.
- Jaeger and Tempo need a separate backend and use sampling, so rare paths are lost.

**Gap (from the proposal):** there is no common protocol that carries deadline, retry budget, backpressure and cancellation *together* as coordinated cross-hop state, with well-defined transition, expiry, terminal-state and fan-out semantics. There is also no protocol that lets the same context describe the request path, with no sampling.

## 4. Proposal commitments (MUST KEEP; this is the checklist)

Source: the C1 proposal PDF (IT23151642, September 2026). Nothing here may be removed or weakened.

**Abstract-level commitments:**
- [ ] A versioned context header (proposal name `X-RRPE-Context`, now `X-RPE-Context`).
- [ ] Formal propagation and validation rules.
- [ ] Java **and** Go reference libraries.
- [ ] A compact per-hop history mechanism for request-path visibility.
- [ ] Design Science Research with quantitative benchmarking and iterative prototyping.
- [ ] Candidate wire formats compared on **header size, parsing latency, allocation overhead, compression behaviour**.
- [ ] Evaluate **cross-language conformance, deadline behaviour, topology reconstruction accuracy**.
- [ ] Act as the protocol foundation used by the interceptor, Kubernetes enforcement and observability layers.

**Main objective:**
- [ ] Design, specify, implement and validate a single resilience protocol carrying deadline, backpressure, retry budget and cancellation across hops, with clear expiry and fan-out semantics.
- [ ] Behaviour consistent across programming languages.
- [ ] Use the same request flow to gain visibility.
- [ ] Compact history snapshots per hop support request-path and topology reconstruction.
- [ ] The history can be used to understand request behaviour and **monitored via a dashboard**.

**Specific objectives:**
1. **Design and specify the context header:**
   - [ ] RFC-style specification.
   - [ ] Propagation rules for all fields.
   - [ ] Unknown-field handling.
   - [ ] Validated against a written conformance test.
2. **Determine the most efficient wire encoding:**
   - [ ] Compare delimited key-value pairs.
   - [ ] Compare RFC structured field values.
   - [ ] Measure memory-allocation overhead.
   - [ ] Compare HPACK/QPACK compressibility.
   - (Base64-CBOR is also named in the baselines.)
3. **Formalise deadline and expiry semantics:** [ ] rules for sequential and parallel deadlines.
4. **Java and Go library:**
   - [ ] Parser and serialiser.
   - [ ] Transition validation.
   - [ ] Differential testing to verify both behave the same.
5. [ ] **Design and evaluate in-band, unsampled topology reconstruction.**

**Methodology:**
- [ ] Design science plus quantitative experimental evaluation.
- [ ] The final wire format is selected from performance and compression results.
- [ ] The Java and Go libraries are tested with identical inputs.
- [ ] Iterative development; experiment findings refine protocol versions.

**Experimental inputs:**
- [ ] Different service-chain lengths.
- [ ] Sequential and parallel requests.
- [ ] Different deadlines.
- [ ] Expired and near-expired requests.
- [ ] Different retry budgets, cancellations and backpressure values.
- [ ] Different wire encodings.
- [ ] No personal data.

**Baselines:**
- [ ] A protocol-overhead baseline (latency, processing, memory, network).
- [ ] A wire-format benchmark against RFC 8941 and Base64-CBOR.
- [ ] OpenTelemetry with Jaeger/Tempo as the reference for request-path visibility.

**Evaluation metrics (table in the proposal):**

| Metric | Target |
|---|---|
| Header size / wire overhead | < ~200 bytes |
| Parse latency | p99 < 100 µs (the proposal wrote "<100" without a unit; µs is the intended unit) |
| Serialisation latency | Compare with RFC 8941 and CBOR |
| HPACK/QPACK compressibility | Compare all wire formats |
| Cross-language semantic agreement | 100% on conformance vectors |
| Fan-out policy | 100% correct partial/whole decisions across the test matrix |
| Topology precision/recall | Against ground-truth edges at the chosen encoding size |

**Validation:**
- [ ] The conformance suite has valid, invalid and boundary cases; both libraries get the same inputs and must produce the same outputs.
- [ ] Performance: header size, runtime overhead, parse latency.
- [ ] Reconstruction compared against a known topology.

**Responsibility table:**
- **Own:** the header spec (fields, wire-format selection, validation and conformance rules).
- **Shared:** the conformance test kit and integration support for header creation and validation; the interceptor (with C2); exposing the per-hop trail and resilience fields for operational metrics (with C4); integration and end-to-end validation (all).

**Functional requirements:**
1. [ ] Propagate the resilience context at each hop.
2. [ ] Java and Go produce the same behaviour for identical inputs.
3. [ ] Follow the defined rules.
4. [ ] Maintain compact per-hop history so the request path can be identified or reconstructed.
5. [ ] Configurable fan-out behaviour when only some downstream calls succeed.
6. [ ] Capture request details through each request chain.
7. [ ] Encode and decode the header according to the wire format.

**Non-functional requirements:**
- [ ] **Performance:** p99 parse < 100 µs.
- [ ] **Scalability:** per-hop header size must not grow over long chains.
- [ ] **Reliability:** invalid values are detected.
- [ ] **Usability:** developers can handle the header manually.
- [ ] **Interoperability:** Java and Go behave equivalently.

**Users:**
- Software developers, DevOps teams, platform engineers, and organisations running microservices (requirements discussed with a domain expert).
- They need a lightweight, easy-to-integrate mechanism with minimal overhead and visibility into how resilience state changes.

**Commercialisation, budget, WBS and Gantt:** the commercialisation plan and budget are shared across the team: SaaS / enterprise licence / premium / professional services, with Community / Professional / Enterprise tiers. Budget: no hardware, open-source tools, cloud testing cost only. Keep these consistent in reports.

## 5. Novelty (groomed and defensible)

### 5.1 Primary novelty: the RPE protocol itself

One versioned, validated HTTP context that carries **deadline, retry budget, backpressure and cancellation together** across hops. Its semantics are formally specified, it is implemented identically in Java and Go, and it **describes its own path** (the in-band trail).

**Research question.** Can one cross-hop context carry all four resilience signals with rules precise enough that:
- (a) independent implementations agree exactly;
- (b) the rules prevent the failure modes caused by uncoordinated signals (retry amplification, duplicate side effects, wasted work on expired requests);
- (c) it stays within a strict per-request overhead budget;
- (d) the same context also rebuilds request paths without a separate tracing backend?

**Four defensible points (what's new):**
1. **Coordinated semantics, not just fields.** A state machine with these invariants:
   - the deadline never extends;
   - the retry budget never increases;
   - **the retry budget is conserved across fan-out**;
   - cancellation is terminal;
   - **a deadline-expired response is terminal and non-retryable**;
   - point of no return;
   - fan-out `partial | all` with a per-child annotation.

   The context guard (`validateTransition`) enforces it in both languages.
2. **Skew-tolerant deadline semantics.** A relative TTL with locally measured elapsed time; the grace threshold comes from the measured skew bound.
3. **A self-describing, resilience-aware trail.** Bounded per-hop entries (interned service ID, delta timing, decision, deadline consumed) rebuild the path, per-edge latency and *which hop consumed the deadline*, from every request.
4. **An interoperable ecosystem.** A conformance kit (vectors plus a runner any implementation can execute) and Java↔Go differential fuzzing.

### 5.2 Wording to USE and AVOID

- **USE:** "No existing system combines all four signals, formal transition/terminal semantics, cross-language conformance and in-band path reconstruction in one REST-native context." "The trail brings the idea of bounded in-band telemetry (INT/PINT in networking) to the L7 resilience context."
- **AVOID (these are false):**
  - "Envoy carries no cross-hop resilience context at all": Envoy sends `x-envoy-expected-rq-timeout-ms` and accepts `x-envoy-max-retries`.
  - "Predictive deadline admission does not exist in any current system": Envoy's expected-timeout header exists so upstream services can exit early, and patents exist.
  - "First to reconstruct topology without tracing": X-Trace/Pivot Tracing baggage, Hindsight and Mint exist.

### 5.3 Prior art and how to answer it

| Examiner says | Answer |
|---|---|
| "gRPC already propagates deadlines and cancellation." | Only inside gRPC. It has no cross-hop retry budget or conservation, no terminal semantics in a propagated context, and no backpressure field. RPE is REST-native and covers all four signals. |
| "Linkerd/Finagle propagate deadlines over HTTP." | One signal, without validated transition rules, budget conservation or conformance across implementations. |
| "Just put it in W3C Baggage." | Baggage is opaque key-values with no semantics, validation or invariants. RPE *binds* `rid` to `traceparent` and adds the semantics. |
| "Hindsight and Mint already catch rare traces." | They need agents, buffering and collectors. The RPE trail travels with the request at zero collection cost, and it carries resilience decisions (budget, deadline consumed), not just spans. |
| "Isn't the trail just tracing in a header?" | It's bounded (constant size) and resilience-aware. The study measures the size vs fidelity trade-off, like PINT in networking. |
| "What about clock skew?" | Relative TTL plus local elapsed time. The drift curve under injected skew shows the bound, and the grace threshold is derived from it. |

### 5.4 60–90 second pitch

> "In HTTP microservices, deadline, retries, cancellation and backpressure are handled separately at each service, so service B keeps working after A gave up, retries pile up, and a failed fan-out charges the card twice. RPE is one header, `X-RPE-Context`, that carries all four signals with rules every hop must obey: the deadline only shrinks, the retry budget only shrinks and is split, never duplicated, across parallel calls, cancellation can't be undone, and an expired response is terminal and never retried. The Java and Go libraries enforce the same rules, which we prove with a shared conformance kit and differential fuzzing. And because the context visits every hop, a bounded trail inside it rebuilds the request path and shows which hop used up the deadline, with no tracing backend and no sampling. Live: with the terminal rule off the card is charged twice; with it on, once."

## 6. Scope boundary

| C1 does | C1 does NOT do |
|---|---|
| Defines fields, encodings, rules (MUST/SHOULD/MAY), error codes, status codes, the notification message format | Enforce at runtime (C2), deploy (C3), decide policy values (C4) |
| Provides the libraries: codec, context guard, deadline type, splitter, retry ledger, trail, client adapters, trace binding | Retry execution, rate limiting or proxying (C2) |
| Provides the conformance kit, benchmarks and protocol service (validate / transition / split / trails / topology / registry / attribution) | Health classification (C4) |
| Defines the "insufficient time" rule as a SHOULD with its fields | Owns the decision-function novelty (C2) |
| Defines the policy epoch field `pe` | Rollout logic (C3) |

## 7. Detailed design

### 7.1 Spec structure (`spec/RPE-v1.md`)

1. Introduction, terminology (RFC 2119 keywords), non-goals.
2. Header set and scopes: Context (end-to-end), Hop (one hop), Trail (optional), Result (response), plus the notification message.
3. Field definitions (type, range, default, mutability). See Appendix A3.1.
4. Encoding: canonical encoding (decided by §7.7), size limits (`X-RPE-Context` ≤ 256 B; total RPE headers ≤ 4 KB), case-insensitive names, rejection of duplicate headers.
5. Processing model: receive → validate → derive the local deadline → (application work) → child derivation → send; then response handling.
6. Transition rules (context guard): R-T01…
7. Deadline and expiry semantics: R-D01…
8. Retry semantics and budget conservation: R-R01…
9. Cancellation and terminal states: R-C01…
10. Fan-out semantics and splitter: R-F01…
11. Backpressure fields and the notification message: R-B01…
12. Trail: R-P01…
13. Versioning, unknown fields and forward compatibility: R-V01…
14. Security and privacy: R-S01…
15. Error codes and status-code mapping.
16. Conformance requirements (what a conformant implementation must pass).
17. Appendix: examples and test-vector format.

### 7.2 Core rules (initial set; extend as needed)

**Transitions (context guard `validateTransition(incoming, outgoing)`):**
- **R-T01** `rid`, `bud`, `rt` and `pe` MUST be identical along the chain.
- **R-T02** `ttl_out` MUST be ≤ `ttl_in` − locally measured elapsed time (never extends).
- **R-T03** `rb_out` (to one child) MUST be ≤ the parent's unallocated budget, and Σ children grants ≤ parent `rb` minus the parent's reserve (**conservation**).
- **R-T04** If `cs_in ≠ none`, then `cs_out` MUST equal `cs_in`, and new child work MUST NOT start.
- **R-T05** `v` MUST NOT silently downgrade.
- **R-T06** A child's `pid` MUST equal the parent's `iid`, and each child or attempt MUST have a fresh `iid`.

**Deadline and expiry:**
- **R-D01** The receiver computes `local_expiry = receive_time_monotonic + ttl_in`. All later checks use the local monotonic clock.
- **R-D02 Expired on arrival:** `ttl_in ≤ 0` → MUST reject before application logic: 504, `rsn=deadline`, `term=?1`.
- **R-D03 Insufficient time (SHOULD):** if `ttl_remaining < est_cost(route) × α` (default α = 1.0, with `est_cost` = the route's p50 or p95 from configuration or C4), the receiver SHOULD reject: 504, `rsn=insufficient-time`, `term=?1`. C1 defines the rule; C2 implements it in its decision function.
- **R-D04 Expired mid-flight:** the receiver MUST propagate `cs=expired` to the children it has already dispatched. Its own work follows the point-of-no-return rule.
- **R-D05 Point of no return:** before work starts, reject freely. During read-only work, abandon freely. After the commit stage begins, MUST finish and return with `late=?1`. Routes declare `commitStage` in policy.
- **R-D06 Grace threshold:** in absolute-deadline mode, or when a transit allowance is applied, a request is considered expired only if `ttl ≤ −grace`, where `grace` = the measured skew/transit bound (configurable, default 5 ms).
- **R-D07 Sequential vs parallel:** each child in parallel mode gets `ttl_child = ttl_remaining − reserve_ms`. In sequential mode, each child gets the remaining time at dispatch, minus `reserve_ms`. `reserve_ms` is the parent's own post-processing allowance (policy, default 5% of `ttl`).

**Retries:**
- **R-R01** A retry is a new attempt with a fresh `iid`, `att+1`, and it consumes one unit from the parent's ledger.
- **R-R02** A retry MUST NOT be sent if `term=?1` was received, if `cs≠none`, if the remaining ttl is less than backoff plus the estimated attempt time, if the operation is not replay-safe, or if the ledger is empty.
- **R-R03** The budget a child receives covers its whole subtree. The response reports `rbu` (budget used), so sequential fan-out can deduct it.

**Cancellation:**
- **R-C01** `cs` transitions are only `none → cancel` and `none → expired`, and the state is terminal.
- **R-C02** Cancellation is a notification. Implementations MUST NOT forcibly kill work; delivery tiers are the header flag, connection close (opt-in) and a library hook.

**Fan-out:**
- **R-F01** Per route, `fanout = partial | all`.
- **R-F02** With `partial`: the parent returns `out=partial` if every *required* child succeeded. Optional children may be expired or cancelled, and each child is annotated in `kids`.
- **R-F03** With `all`: if any child fails, the parent returns `out=failed`, and if the failure is a deadline failure the response MUST be `term=?1`. This prevents the **duplicate-charge trap**: a retried fan-out would repeat the already-committed side effects.
- **R-F04 Non-goal (MUST appear in the spec):** *"RPE provides no atomicity guarantee across fan-out and performs no compensation. Partial side effects may persist after a fan-out failure. Reconciling them is the application's responsibility."*
- **R-F05 Splitter arithmetic.** In parallel mode with n children: `reserve = policy.parentReserve` (default 1). Grants are `floor((rb − reserve)/n)`, and the remainder goes to the first children in a stable order. In sequential mode, each child gets `rb_unallocated − reserve` at dispatch, and its `rbu` is deducted on return.

**Backpressure:**
- **R-B01** `bp` (0–3) and `bpu` (0–100) are response fields. A level is derived from utilisation using the policy thresholds.
- **R-B02** The notification message format and its rules are as in A3.1. The receiver MUST use only the observed source IP.

**Trail:**
- **R-P01** Each RPE hop appends one entry: `svcId`, `parentIdx`, `elapsedMicros` (−1 while active, finalised on the response path if the response-side trail is enabled), `decision`, `ttlConsumedMs`.
- **R-P02** The trail is bounded: at most K entries or B bytes. When it would exceed that, the encoding-specific policy applies (ring drop-oldest, sketch, and so on) and `inc=?1` is set.
- **R-P03** Service names are interned to 2-byte IDs through the registry (`rv`). Names never appear on the wire.
- **R-P04** The trail MUST be stripped at egress (the gateway's response to the external client).
- **R-P05** If a hop doesn't speak RPE, the next RPE hop detects that `pid` doesn't match a known parent entry, records an **unknown edge** and sets `inc=?1`.
- **Direction decision:** the request side carries entries, and the response side finalises elapsed times. Justify this choice in the spec (richer durations at small cost).

**Versioning:**
- **R-V01** Unknown fields MUST be preserved and forwarded, unchanged, by intermediaries implementing the same major version.
- **R-V02** A v1 receiver getting `v=2` processes the known fields if the major version is compatible; otherwise it rejects with `rsn=unsupported-version`.
- **R-V03** No silent downgrade.

**Security and privacy:**
- **R-S01** The gateway MUST regenerate the context for external requests (trust boundary) and never trust a client-supplied `ttl`/`rb`.
- **R-S02** Optional HMAC signature (`sig`) over the immutable fields. This is a *nice-to-have*.
- **R-S03** The trail is stripped at egress.

### 7.3 Library API (identical semantics in Go and Java)

```
RpeContext   parse(headers) / serialize() / Validate()            // codec + field validation (rule IDs in errors)
ContextGuard validateTransition(incoming, outgoing) -> violations   // R-T01..R-T06
Deadline     fromContext(ctx, receiveTimeMonotonic); remaining(); isExpired(); hasTimeFor(estimate, alpha)
RetryLedger  newFor(ctx); allocateChild(iid, grant) -> receipt; spendRetry(); snapshot()
Splitter     split(mode=SEQUENTIAL|PARALLEL, ctx, n, reserve) -> []ChildAllocation   // R-F05, R-D07
ResultCtx    parse/serialize X-RPE-Result; terminal(); childAnnotations()
Trail        append(entry); finalize(elapsed); encode(encoding); decode(); registryVersion()
HopCtx       parse/serialize X-RPE-Hop (cb, att); strip()
Notification parse/serialize/validate backpressure message
TraceBinding ridFromTraceparent(); ensureTraceparent(rid)
Adapters     Go: http.RoundTripper wrapper; Java: RestTemplate interceptor, WebClient filter, Feign RequestInterceptor, OkHttp interceptor
```

The adapters make outbound propagation automatic: they read the current inbound context (thread-local or Reactor context in Java; `context.Context` in Go), derive the child context and set the headers. This is what makes "no code changes" true for apps that use the adapters. The sidecar (C2) uses the same library for apps that don't.

### 7.4 Reusing the existing Go code (`_research/old_shits/protocol/`)

**Keep (move into `libs/rpe-go/` and refactor):**
- `deadline.go`: the monotonic `Deadline` type; keep the offset-bound idea for R-D06.
- `state.go`: `CancellationToken`, `LogicalCallState`.
- `ledger.go`: `RetryLedger` with receipts (conservation). Replace `SplitChildren`'s "half the pool" with R-F05.
- `transition.go`: becomes `ContextGuard`; add R-T05 and the `pe` immutability check.
- `generation.go`: root and child context creation.
- `scalar.go`, `errors.go`: parsing helpers; add rule IDs to `ProtocolError`.
- `topology.go`: trail records. Re-encode into `X-RPE-Trail` and add `ttlConsumedMs` and the decision code.
- `retry.go`: `AuthorizeRetry` logic (deadline-aware retry gate) moves to R-R02. C2 calls it.

**Rewrite:**
- `codec.go` and `headers.go`: replace the ~24 headers with `X-RPE-Context` / `-Hop` / `-Trail` / `-Result`.
- Keep the old multi-header codec as the **"one header per field" candidate** in `bench/` only.
- Switch absolute `DeadlineUnixMillis` to relative `ttl` + `bud`. Keep an absolute mode in `bench/`/experiments for the skew study.

**Add:** `pe`, `att`, `cb`, `rbu`, `late`, `kids`, `bpu`, fan-out mode, point-of-no-return helpers, and the notification message.

### 7.5 Conformance kit (`conformance-kit/`)

- **Vector format** (`vectors/*.json`):
  ```json
  { "id": "R-T03-001", "rule": "R-T03", "kind": "transition", "input": { "incoming": "...", "outgoing": "..." },
    "expect": { "valid": false, "violations": ["R-T03"] } }
  ```
  Kinds: `parse`, `serialize`, `transition`, `split`, `deadline`, `retry-gate`, `fanout-result`, `trail`, `notification`.
- **Coverage:** valid, invalid and boundary cases for every rule ID, at least 3 vectors each.
- **Runners:** `runner-go` (`go test ./conformance-kit/...`) and `runner-java` (JUnit, parameterised). Both print a matrix (vector × implementation).
- **Differential fuzzer:** a generator produces random contexts and transitions, both libraries process them (the Java side through a small CLI or JSON-over-stdin harness), and the outputs are diffed. Any difference is logged as a finding.
- **Property tests:** invariants hold across random sequences (budget conservation, no TTL increase, terminal cancellation).

### 7.6 Protocol service (`services/protocol-service/`, Go)

This implements the API in Appendix A3.7. It wraps `rpe-go` directly and calls a `rpe-java` sidecar CLI or microservice for the "both" mode.
- **Trail collector:** stores completed trails (in-memory ring plus optional PostgreSQL `protocol` schema).
- **Topology rebuilder:** builds a directed graph of edges `(svcA → svcB)` with counts and latency distributions for each encoding.
- **Compare endpoint:** pulls the Tempo service graph (Tempo search API / metrics-generator) and the demo app's ground truth (`demo-app/topology.json`), then computes edge precision/recall and latency error.
- **Registry:** maps service name ↔ uint16 with a version. Sidecars fetch it at start and on change (exposed in the console's Service Registry tab).

### 7.7 Wire-format study (`bench/`)

- **Candidates:**
  - (a) delimited KV (`v=1;rid=…;ttl=…`);
  - (b) RFC 8941 Dictionary;
  - (c) Base64-CBOR;
  - (d) one header per field (the old Go codec).
- **Measures:**
  - bytes on the wire for header name + value, typical and worst case;
  - parse latency p50/p99;
  - serialise latency;
  - allocations per op (Go `-benchmem`, JMH `-prof gc`);
  - **HPACK/QPACK compressibility.** Simulate a connection carrying 10,000 realistic requests from the demo app's traces through an HPACK encoder (`golang.org/x/net/http2/hpack`) and a QPACK encoder (static + dynamic table), and report the average bytes per request after compression. Stable fields such as `v`, `rt` and `pe` compress well; per-request fields such as `rid` and `ttl` don't. The study shows how each layout interacts with this.
- **Overhead budget:** < 200 B on the wire and p99 parse < 100 µs. The selection rule is the best compressed size among candidates within the parse budget, with a written rationale.
- **Output:** `experiments/c1/wire-format/results.csv` and charts.

### 7.8 Trail-encoding study

- **Encodings:**
  - full (verbose);
  - ring buffer of the last N;
  - interned 2-byte IDs + delta-varint timing;
  - Bloom/sketch of the path (constant size; membership and approximate structure);
  - optionally a hybrid.
- **Workload:** the demo app (5–7 services, including fan-out and one non-RPE hop) plus a synthetic chain generator (lengths 3–20, fan-out 1–4), with **injected rare paths** (for example 0.5% of requests take a rare branch).
- **Measured:**
  - bytes per request;
  - edge precision/recall against ground truth;
  - per-edge latency estimation error;
  - per-hop deadline attribution accuracy against injected delays;
  - rare-path detection rate vs Tempo with 1% head sampling and with tail sampling.
- **Output:** size–fidelity curves, the main thesis figure.

### 7.9 Clock-skew study

Inject offsets with libfaketime or the demo app's `clockOffsetMs` knob (0–200 ms) on one or more services, over N hops (2–10). Compare:
- absolute-deadline mode vs relative-TTL mode;
- grace off vs on.

**Measured:** deadline drift (error in the remaining-time estimate) and false rejections. **Output:** a drift curve.

## 8. Contracts C1 provides and consumes

- **Provides:**
  - A3.1, the header contract, including the notification message format;
  - the field `pe` used by C3;
  - A3.7, the protocol service API;
  - the libraries;
  - the registry.
- **Consumes:**
  - C2 sidecars' trails (`POST /api/protocol/trails`) and hop events;
  - Tempo (for comparison);
  - per-route latency estimates (from C4 or Prometheus) for R-D03 examples;
  - the demo app's ground-truth topology.

## 9. Legacy code guide

| Path | Verdict | Notes |
|---|---|---|
| `_research/old_shits/protocol/*.go` | **Keep ~70%** (§7.4) | Good quality; reusable rules and ledger |
| `_research/old_shits/protocol/RPE-M1.md`, `RPE-M1-Scope-Ruwanmali.md` | Source of the expanded scope | All of its content is integrated into §4–§7. Rename `X-RRPE` → `X-RPE` |
| `_research/old_shits/netty-sidecar-proxy` | Not C1, but must adopt `rpe-java` | Its headers (`Request_id`, `deadlinevalue`…) are replaced |

## 10. Implementation plan (phases → tasks → acceptance)

Milestones align with SLIIT checkpoints: **PP1** (progress presentation 1), **PP2**, **Final**.

**Phase 0: Contracts first (week 1–2, blocks the team)**
- P0.1 Freeze A3.1 v0.1 field semantics with the team, and publish `spec/RPE-v1.md` §1–§3 and rule IDs.
- P0.2 Create the monorepo folders (A5), and move `_research/old_shits/protocol` into `libs/rpe-go` with history preserved.
- **Acceptance:** C2 and C3 confirm that they can build against the contract.

**Phase 1: Libraries MVP (→ PP1)**
- P1.1 Spec §4–§10 drafted with all R-rules.
- P1.2 `conformance-kit` vector format plus ≥ 60 vectors covering every R-rule.
- P1.3 `rpe-go`: `X-RPE-Context` / `-Hop` / `-Result` codec (default RFC 8941), relative TTL, `Deadline`, guard, ledger, splitter (R-F05), fan-out result, notification message.
- P1.4 `rpe-go` `http.RoundTripper` adapter plus trace binding.
- P1.5 `rpe-java`: the same API (Maven module, Java 21). Port from Go and run against the same vectors.
- P1.6 Java adapters: RestTemplate, WebClient, Feign, OkHttp.
- P1.7 Hand both libraries to C2 (sidecar) and the demo app.
- **Acceptance:** both runners pass 100% of vectors; C2's sidecar parses and emits `X-RPE-Context`; the demo app propagates context through order → payment.

**Phase 2: Studies and trail (→ PP2)**
- P2.1 Wire-format benchmark (§7.7) and the selection decision recorded in the spec.
- P2.2 Trail implementation (verbose) plus the protocol service (trail collector, rebuilder, registry, compare).
- P2.3 Trail encodings (ring, interned-delta, bloom) plus the size–fidelity experiment (§7.8).
- P2.4 Differential fuzzer plus property tests.
- P2.5 Clock-skew study (§7.9).
- **Acceptance:** results CSVs and charts exist; the chosen encoding is justified; topology precision/recall is reported.

**Phase 3: Semantics evaluation and UI (→ Final)**
- P3.1 Duplicate-charge and retry-amplification experiments: terminal rule off vs on, fan-out `all` (demo app counters).
- P3.2 Fan-out decision matrix test (100% correct partial/whole).
- P3.3 Console C1 module (§12) plus the Service Registry tab.
- P3.4 Spec v1.0 final, conformance kit README ("any implementation can run it").
- P3.5 Thesis chapters, figures.
- **Nice-to-have (drop first):** TLA+/Alloy model of the invariants; HMAC `sig`; AMQP binding.

## 11. Evaluation plan

| ID | Experiment | Baseline | Metric | Target / output |
|---|---|---|---|---|
| E1 | Conformance and differential fuzzing | n/a | Agreement %, divergences | 100% agreement; 0 invariant violations |
| E2 | Wire-format study | 4 candidates | Bytes, p50/p99 parse and serialise, allocations, HPACK/QPACK bytes per request | < 200 B, p99 < 100 µs; chosen format justified |
| E3 | Protocol overhead end-to-end | Demo app without RPE headers | Added latency, CPU, memory, bytes | Reported; within budget |
| E4 | Terminal and fan-out semantics | Protocol semantics off | Retry amplification, duplicate side effects (cards charged), wasted retries | ×2 → ×1 charges; fewer retries |
| E5 | Fan-out decision matrix | n/a | Correct partial/whole decisions | 100% |
| E6 | Trail size–fidelity | Tempo with 1% head sampling and with tail sampling | Bytes/request, edge P/R, latency error, attribution accuracy, rare-path detection | Curves; rare paths detected by the trail and missed by sampling |
| E7 | Clock-skew drift | Absolute mode, grace off | Drift ms over N hops, false rejections | Curve; grace removes false rejections |

Repeat each experiment ≥ 5 times. Report mean, standard deviation, p50/p95/p99 and confidence intervals where relevant. Exclude a 60 s warm-up.

## 12. Admin Console: C1 tab and Operate contributions

**Operate side (C1-owned settings):**
- *Services & Routes:* deadline budget, `fanout`, `split`, `replaySafe`, `commitStage`, parent reserve.
- *Service Registry:* name ↔ ID table and version.

These are saved as policies through C3's API, so they are real configuration.

**C1 Protocol tab (Demonstrate).** The main novelty demo comes first.

| Sub-tab | Act (real control) → Observe (real data) |
|---|---|
| **Main: Terminal rule and duplicate charge** | Set the order route to `fanout=all` and make fraud-check slow (Scenario Lab), with the terminal rule off and then on → the live hop timeline shows the header changing per hop (ttl shrinking, rb split, cs propagated), and payment's *cards charged* counter shows **×2 vs ×1** |
| Header Inspector | Live capture of the `X-RPE-*` headers at every hop of a chosen request (hub stream). Paste or edit a header → `/api/protocol/validate` → Java and Go results side by side with rule IDs |
| Context Guard | Edit an outgoing header illegally (raise ttl or rb, clear cs, downgrade v) → both libraries reject it with R-T rule IDs |
| Expiry Cases | Send real requests with crafted budgets: expired on arrival, insufficient time, expired mid-flight → timeline with the reject point and cancelled children |
| Point of No Return | Expire payment during validate (abandoned) vs after commit (completed, `late=?1`) |
| Deadline Attribution | Waterfall per request: "inventory used 80 ms, payment 210 ms of 300 ms" (`/api/protocol/attribution/{rid}`) |
| Fan-out | Toggle `partial`/`all` for loyalty vs fraud-check → result, per-child annotations, side-effect counters (shows the non-goal) |
| Splitter | Enter ttl, rb, n and mode → allocations, then run it live and compare with what the children actually received |
| Topology | Graph rebuilt from trails vs Tempo vs ground truth; encoding selector → bytes vs P/R; run the rare-path scenario → the trail sees it and sampled tracing misses it; legacy-svc shown as an unknown edge |
| Clock Skew | Slider for the clock offset on one service → false rejections with grace off/on; drift curve |
| Wire Format and Conformance | Benchmark table and charts against the budget lines; vector × implementation matrix; fuzzing counters |

## 13. Viva and demo script

1. **Pitch** (§5.4, 90 s), with **one diagram**: the header changing hop by hop (ttl 300 → 287 → 250, rb 3 → 1/1, cs none → expired, term flag on the response).
2. **Demo 1 (main):** duplicate charge ×2 → ×1 (2 min).
3. **Demo 2:** topology/trail with the rare path and the encoding selector (2 min).
4. **Demo 3:** Java vs Go side-by-side validation and the conformance matrix (1 min).
5. **Results slides:** E2 table, E6 curve, E7 curve.

**Likely questions:**
- "Why not gRPC?"
- "Why not Baggage?"
- "Skew?"
- "Why RFC 8941?"
- "What if a hop doesn't speak RPE?" (unknown edge, `inc=?1`)
- "Atomicity?" (the explicit non-goal)
- "504 vs a custom code?" (504 + `rsn`, matching gateways, with the reason distinguishing the caller's deadline)
- "Overhead?" (E2/E3)

## 14. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Overscope | Priorities: libraries, conformance and the wire study first; trail second; TLA+/HMAC/AMQP last |
| Java/Go drift | Vectors before code; CI runs both runners; differential fuzzer |
| Team blocked on C1 | Phase 0 contracts in 2 weeks; publish v0.1 libraries early |
| Topology claim challenged | Use the §5.2 wording; compare with sampling *and* tail sampling; cite Hindsight, Mint, PINT |
| Absolute vs relative deadline debate | Both modes measured in E7 |

## 15. Definition of Done (C1)

- [ ] Every §4 commitment is ticked, with evidence (link to code, test or result).
- [ ] `spec/RPE-v1.md` v1.0 has rule IDs, a non-goal statement, privacy, versioning and status codes.
- [ ] `rpe-go` and `rpe-java` pass 100% of vectors; fuzzer finds 0 divergences in a 1M-case run.
- [ ] Adapters work in the demo app (Go and Java services).
- [ ] E1–E7 are completed, with CSVs and charts in `experiments/c1/`.
- [ ] Protocol service is deployed in the Helm chart; console C1 tab works on live data.
- [ ] C2 sidecar uses `rpe-java`; no legacy headers remain anywhere.
- [ ] Thesis figures: header evolution diagram, E2 table, E6 curves, E7 curve, conformance matrix.

## 16. Open questions for the supervisor

1. Approve the rename `X-RRPE-Context` → `X-RPE-Context`. It is a name change only, noted in PP1.
2. Approve the added header scopes (`-Hop`, `-Trail`, `-Result`) under the single-protocol commitment.
3. Confirm that the TAF can be amended to show the topology/trail contribution in the novelty column (RPE-M1 §10).
4. Ask whether formal verification (TLA+) is expected, or whether property tests and fuzzing are enough.

## 17. Glossary

- **TTL:** remaining deadline in ms.
- **Budget (`bud`):** original deadline.
- **`rb`:** retry budget.
- **Terminal:** a response that must not be retried.
- **Point of no return:** the commit stage, after which work is finished rather than abandoned.
- **Trail:** bounded per-hop history in `X-RPE-Trail`.
- **Unknown edge:** a gap caused by a non-RPE hop.
- **Epoch (`pe`):** the policy version a request is pinned to.
- **Conformance vector:** a JSON test case every implementation must pass.

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
