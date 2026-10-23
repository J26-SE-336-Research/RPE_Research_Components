# RPE v1: the Resilience Protocol Ecosystem context for HTTP

| | |
|---|---|
| **Status** | Draft v0.9 (PP1), 20 October 2026 |
| **Owner** | C1 Protocol & Specification Layer, Ruwanmali K.H. (IT23151642), project J26-SE-336 |
| **Reference implementations** | `libs/rpe-go` (Go), `libs/rpe-java` (Java 21) |
| **Conformance kit** | `conformance-kit/` (150 shared test cases; both implementations pass all of them) |
| **Supersedes** | the header contract notes in the component guides (Appendix A3.1) where they differ; see §17.3 |

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY and OPTIONAL are to
be interpreted as described in RFC 2119 and RFC 8174 when, and only when, they appear in capitals.

---

## 1. Introduction

In an HTTP microservice estate, four resilience signals are handled separately at every service: the **deadline** of
the user's request, the **retry budget**, **cancellation**, and **backpressure**. Because nothing carries them across
hops with shared rules, service B keeps working after A has given up, retries multiply exactly when a dependency is
slow, and a retried fan-out repeats side effects that were already committed (a card charged twice).

RPE v1 defines one versioned context that carries all four signals across HTTP hops, the rules every hop must obey
when it receives, derives and returns that context, and an optional in-band trail with which the request describes
its own path. The rules are numbered (R-xNN) so that implementations, tests and error messages can refer to them.

### 1.1 Design principles

1. **Signal, never reject.** RPE MUST NOT be the reason a request fails. When a chain's deadline passes, the request is
   marked (`cs=expired`) and keeps flowing; the application decides what to stop. The only request RPE refuses because
   of the deadline is a *retry* of a chain that is already cancelled or expired (§8).
2. **Relative time.** The deadline travels as time left (`ttl`), never as a timestamp, so clocks need not agree.
3. **Monotonic state.** The deadline never extends, the retry budget never grows, cancellation never clears.
4. **Same behaviour everywhere.** Every rule is executable: the conformance kit holds test cases for it and both
   reference implementations pass them.

### 1.2 Non-goals

- RPE provides **no atomicity and no compensation** across a fan-out (R-F04).
- RPE does not cancel or kill application work. It delivers the signal; the developer decides (R-C02).
- RPE does not choose policy values (deadlines, rates, budgets). Those come from configuration or from the
  intelligence layer through the approval workflow (C4, C3).
- RPE does not replace distributed tracing. The trail (§12) is a bounded, resilience-specific complement.

### 1.3 Roles in the ecosystem

| Role | Who | What it does with RPE |
|---|---|---|
| Gateway (trust boundary) | the edge service or its sidecar | creates the root context (R-S01) |
| Hop | every service, through its sidecar (C2) or the RPE library | validates, derives, propagates, returns |
| Application | the customer's service code | reads the signal and decides; uses the retry gate |
| Policy plane | C3 (delivery), C4 (recommendation), humans (approval) | supplies budgets and the policy epoch `pe` |

### 1.4 Terminology

- **Chain**: all HTTP calls made, directly or indirectly, to serve one request that entered at the gateway.
- **Hop**: one service handling one request of the chain.
- **Invocation**: one attempt of one call; a retry is a new invocation of the same logical call.
- **Original request**: the first attempt of a logical call. **Retry**: attempt 2 or later (`att >= 2`).
- **Budget**: the end-to-end deadline of the chain in milliseconds, fixed at the gateway.

---

## 2. Header set and scopes

| Header | Direction | Scope | Required | Purpose |
|---|---|---|---|---|
| `X-RPE-Context` | request | end-to-end | yes (inside the chain) | the four signals and identity (§3.1) |
| `X-RPE-Hop` | request | one hop | no | per-attempt data: attempt number, backpressure caller token (§3.2) |
| `X-RPE-Trail` | request and response | chain-internal | no | in-band per-hop trail (§3.3, §12) |
| `X-RPE-Result` | response | one hop | SHOULD | outcome, reason, terminal flag, backpressure, cancellation (§3.4) |

- Header names are case-insensitive (HTTP). Each RPE header MUST appear at most once; a duplicated header is an error
  (`DUPLICATE_HEADER`).
- `X-RPE-Hop` MUST be removed before the request reaches the application and MUST NOT be forwarded to the next hop.
- `X-RPE-Trail` MUST NOT leave the chain: the gateway removes it from its response (R-P04).
- `traceparent` (W3C Trace Context) is bound to the context: `rid` equals its trace-id (§14.2).

---

## 3. Field definitions

### 3.1 `X-RPE-Context`

```
X-RPE-Context: v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000",
               ttl=287, bud=300, rb=3, cs=none, pe="e42", rt="POST /api/orders"
```

| Member | Type | Range | Required | Mutability | Meaning |
|---|---|---|---|---|---|
| `v` | integer | 1 .. 2^31 | yes | MUST NOT downgrade (R-T05) | protocol version; this document defines 1 |
| `rid` | string | 32 lowercase hex, not all zero | yes | immutable (R-T01) | request id = W3C trace-id |
| `iid` | string | 16 lowercase hex, not all zero | yes | fresh per invocation (R-T06) | this invocation |
| `pid` | string | 16 lowercase hex | yes | = parent's `iid` (R-T06) | parent invocation; all zero at the gateway |
| `ttl` | integer | -600000 .. 600000 ms | yes | never increases (R-T02) | time left when the message was sent; negative when late |
| `bud` | integer | 1 .. 600000 ms | yes | immutable (R-T01) | the chain budget set at the gateway |
| `rb` | integer | 0 .. 255 | yes | never increases (R-T03) | retry budget of this subtree |
| `cs` | token | `none`, `cancel`, `expired` | yes | monotonic (R-T04, R-C01) | cancellation state |
| `pe` | string | 1 .. 64 bytes | no | immutable (R-T01) | policy epoch stamped by the gateway |
| `rt` | string | 1 .. 128 bytes | yes | immutable (R-T01) | route key `METHOD /template` |

A context MUST be rejected as invalid when a required member is missing (`MISSING_HEADER`), a member has the wrong
type (`INVALID_SYNTAX`) or is out of range (`OUT_OF_RANGE`), `ttl > bud` (R-T02), or `v` is not supported (R-V02).
Members this implementation does not know MUST be kept and forwarded unchanged (R-V01).

### 3.2 `X-RPE-Hop`

```
X-RPE-Hop: cb="k3f9a2", att=2
```

| Member | Type | Range | Meaning |
|---|---|---|---|
| `att` | integer | 1 .. 255 | attempt number of the logical call; 1 (or absent) = original request |
| `cb` | string | 1 .. 64 bytes | backpressure caller token (reserved; see §11.2 for the token the C2 sidecar uses today) |

Unknown members are ignored.

### 3.3 `X-RPE-Trail` (verbose v1 encoding)

```
X-RPE-Trail: rv=1, inc=?1, h="1:0:41250:F:0;2:1:39870:F:2;3:2:8100:F:6;4:2:38110:X:6;7:2:900:U:6"
```

| Member | Type | Required | Meaning |
|---|---|---|---|
| `rv` | integer 1 .. 2^31-1 | yes | version of the service registry the ids belong to (R-P03) |
| `inc` | boolean | yes | the trail is known to be incomplete (R-P02, R-P05) |
| `h` | string | no | the entries, separated by `;` (absent or empty: no entries) |

Each entry is `svcId:parent:elapsedMicros:decision:consumedMs`:

| Field | Range | Meaning |
|---|---|---|
| `svcId` | 0 .. 65535 | the hop's service id in registry `rv`; 0 = not registered |
| `parent` | 0 .. index-1 | 1-based index of the calling hop's entry in the same trail; 0 = a root |
| `elapsedMicros` | -1 .. 999999999999999 | time the hop spent on the request including its callees; -1 while active |
| `decision` | one letter | what the hop did (table below) |
| `consumedMs` | 0 .. 1200000 | budget already used when the hop received the request (`bud - ttl`, at least 0) |

| Code | Decision |
|---|---|
| `F` | served on time |
| `E` | answered before the application (an expired retry refused, R-R02) |
| `X` | served after the chain deadline (signalled, never rejected) |
| `C` | the chain was cancelled and the hop skipped its work |
| `R` | a retry attempt (`att >= 2`) that was served |
| `A` | active (request-side entries only; `elapsedMicros` is -1) |
| `P` | fan-out completed partially (R-F02) |
| `O` | shed by backpressure (429/503) |
| `U` | the callee returned no trail: a hop that does not speak RPE (R-P05) |

An entry MUST be in canonical form (no leading zeros, no `+`, no whitespace); `A` MUST have `elapsedMicros = -1` and
no other decision may. At most 16 entries and 1400 bytes (R-P02).

### 3.4 `X-RPE-Result`

```
X-RPE-Result: v=1, out=partial, rsn=deadline, term=?1, bp=2, bpu=78, rbu=1, pv="e42", late=?0, kids="inv:ok:80;loy:expired:90", cs=expired
```

| Member | Type | Default | Meaning |
|---|---|---|---|
| `v` | integer | 1 | protocol version |
| `out` | token | `unknown` | `ok`, `partial`, `rejected`, `stopped`, `failed`, `unknown` |
| `rsn` | token | `none` | `none`, `deadline`, `insufficient-time`, `cancelled`, `overload`, `circuit-open`, `invalid-context`, `unsupported-version`, `business`, `transport` |
| `term` | boolean | `?0` | terminal: the request MUST NOT be retried (R-R02) |
| `bp` | integer 0..3 | 0 | backpressure level of the responder |
| `bpu` | integer 0..100 | 0 | queue utilisation of the responder |
| `rbu` | integer 0..255 | 0 | retry budget used by the subtree (R-R03) |
| `pv` | string | absent | policy epoch applied |
| `late` | boolean | `?0` | completed after the deadline (point of no return passed, R-D05) |
| `kids` | string | absent | fan-out annotation `name:outcome:ms;...` (R-F02) |
| `cs` | token | `none` | cancellation state of the chain, carried upstream (R-C01) |

Unknown members are ignored. The response context is advisory for the caller and MUST NOT be trusted across the
trust boundary.

---

## 4. Encoding

1. The value of each RPE header is an **RFC 8941 Dictionary** restricted to integers, strings, tokens and booleans:
   no parameters, no inner lists, no decimals, no byte sequences (`INVALID_SYNTAX` otherwise).
2. A **duplicated member** is an error (`DUPLICATE_HEADER`). This is stricter than RFC 8941, which keeps the last value,
   because a duplicated resilience field is ambiguous.
3. Integers have at most 15 digits. Strings are printable ASCII with `\"` and `\\` escapes only.
4. **Canonical form**: known members in the order of the tables in §3, then unknown members sorted by key; members
   separated by `, `. Encoders MUST produce the canonical form; decoders accept any order and optional whitespace.
5. **Size limits**: `X-RPE-Context` at most 256 bytes (`CONTEXT_TOO_LARGE`); `X-RPE-Trail` at most 1400 bytes; all
   RPE headers together SHOULD stay under 4 KB.
6. The wire format is a study variable (E2, PP2): delimited key-value, RFC 8941, Base64-CBOR and one-header-per-field
   are compared for size, parse cost and HPACK/QPACK compression. Field semantics do not depend on the outcome.

---

## 5. Processing model

```
receive ─▶ validate ─▶ derive local deadline ─▶ application work ─▶ for each call: derive child ─▶ send
   ▲                                                                              │
   └──────────── merge child result (cs, trail) ◀── receive child response ◀──────┘
                 │
                 └─▶ write own result (out, rsn, term, late, cs, trail) ─▶ respond
```

1. **Receive.** Remove `X-RPE-Hop` (keep `att`); read `X-RPE-Context`. At the gateway, create a root context (R-S01).
2. **Validate.** An unreadable context is answered with 400 `rsn=invalid-context` and is the only rejection of an
   original request. A request without any context is forwarded unchanged by default (passthrough).
3. **Derive the local deadline** from `ttl` and the local monotonic clock (R-D01).
4. **Application work** reads the signal (`cs`) and decides what to skip (R-C02).
5. **Derive each child** with a fresh `iid`, `pid` = own `iid`, `ttl` reduced by the locally measured elapsed time and
   the reserve (R-D07), `rb` = the grant (R-T03, R-F05), `cs` carried (R-T04).
6. **Respond** with `X-RPE-Result`, carrying the chain state `cs` upstream.

---

## 6. Transition rules (context guard)

These rules relate a received context (parent) to a context the hop sends (child). Both reference implementations
expose them as a guard that returns every violated rule (`validateTransition` / `ValidateContextTransition`).

- **R-T01** `rid`, `bud`, `rt` and `pe` MUST be identical along the chain.
- **R-T02** `ttl_child` MUST be at most `ttl_parent` minus the locally measured elapsed time (the deadline never
  extends), and `ttl` MUST NOT exceed `bud`.
- **R-T03** `rb_child` MUST NOT exceed the parent's unallocated budget; the grants of all children MUST sum to at most
  the parent's `rb` minus its reserve (**budget conservation**).
- **R-T04** If `cs_parent` is not `none`, `cs_child` MUST equal `cs_parent`.
- **R-T05** `v` MUST NOT downgrade.
- **R-T06** `pid_child` MUST equal `iid_parent`; every child and every attempt MUST have a fresh `iid`.

A transition that violates a rule is reported with the rule id and error code `INVALID_TRANSITION`.

---

## 7. Deadline and expiry (signal mode)

- **R-D01 Relative time.** The receiver computes `local_expiry = receive_time + ttl` on its own monotonic clock. All
  later checks use that clock. No absolute timestamp is ever compared across machines.
- **R-D02 Expired on arrival (signal).** A request whose `ttl <= 0` on arrival MUST NOT be rejected because of the
  deadline. The receiving RPE component (sidecar, or the library when there is no sidecar) MUST set `cs=expired` in the
  context passed to the application and in every child context. **Exception:** a retry (`att >= 2`) of a chain that is
  cancelled or expired MUST be refused before the application with 504, `out=rejected`, `rsn=deadline` (or
  `cancelled`), `term=?1` and `cs` (R-R02).
- **R-D03 Insufficient time (SHOULD, signal).** When the time left is less than the expected cost of the route, a hop
  SHOULD signal it (`rsn=insufficient-time` on its result) so that the application can shed optional work. The cost
  estimate comes from policy or C4. The decision function that uses it belongs to C2.
- **R-D04 Expired mid-flight.** When the deadline passes while a hop is working, the hop MUST carry `cs=expired` on
  every later child call and on its response. Calls already running learn of it from their own deadline or from the
  response path; a live push to them is not required.
- **R-D05 Point of no return.** RPE never abandons work. Before its commit stage an application MAY stop when it sees
  the signal; after the commit stage it MUST finish and SHOULD report `late=?1`. The commit stage is the application's
  knowledge (declared per route in policy as `commitStage`).
- **R-D06 Grace.** In absolute-deadline mode (experiments only) or when a transit allowance is applied, a request is
  considered expired only when `ttl <= -grace`, with `grace` the measured skew or transit bound (default 5 ms).
- **R-D07 Sequential and parallel children.** A parallel child gets `ttl_remaining - reserve`; a sequential child gets
  the time remaining at its dispatch minus `reserve`. `reserve` is the parent's own post-processing allowance.

> **Warning for application developers.** In signal mode nothing stops an expired *original* request. If the
> application does not read the signal (`cs`, or the library helpers `Cancelled`, `NonCritical`,
> `CallIfNotCancelled`), its work runs to completion after the deadline. `cs=expired` protects against **retries**;
> what to stop in the original request is the application's decision.

**Why signal and not reject.** RPE runs inside a customer's estate. If it rejected late requests, RPE itself would
become the cause of failed requests. Lateness is a symptom of overload; the signal lets services shed optional,
expensive work (and finish critical work) so that the overloaded service recovers. This mirrors gRPC: the framework
delivers the deadline and the cancellation, the application decides.

---

## 8. Retries (retry safety)

- **R-R01** A retry is a new invocation: fresh `iid`, the same `pid`, `X-RPE-Hop: att=n` with `n >= 2`, and it spends
  one unit of the caller's retry budget.
- **R-R02 Retry gate.** A retry MUST NOT be sent, checked in this order, when:
  1. the chain is expired (`cs=expired`, reason `deadline`) or cancelled (`cs=cancel`, reason `cancelled`), from the
     context or from `cs` reported on a response;
  2. the last response carried `term=?1` (reason `terminal`);
  3. the operation is not replay-safe (reason `not-replay-safe`);
  4. the remaining time is 0 or less (reason `deadline`);
  5. the remaining time is less than the backoff plus the expected time of one attempt (reason `insufficient-time`);
  6. the retry budget of the subtree is 0 (reason `budget`);
  7. the caller's own attempt limit is reached (reason `attempts`).

  The fixed order makes every implementation report the same reason (`retry-gate.json`). A receiver MUST refuse a
  retry that arrives with `cs != none` or `ttl <= 0` (R-D02 exception). A refusal MUST be terminal (`term=?1`) for the
  caller's own caller too, so that the refusal is not undone one hop up.
- **R-R03** The budget a child receives covers its whole subtree. A response reports `rbu` (budget used) so that a
  sequential fan-out can deduct it.

The retry gate is a pure decision: it never sends a request. Libraries offer it as `DecideRetry` / `RetryGate.decide`
and as a retry helper (`DoWithRetry` in Go) that applies the caller's own policy (attempts, per-attempt timeout,
backoff) and asks the gate before every retry.

> **Phase note.** Retry *execution* in the C2 sidecar (retry budget enforcement, exponential backoff and jitter) is
> planned for the next phase and is intentionally absent from the current proxy. The retry gate is used at library and
> application level today. The cancellation flag stays readable and is never reset, so the sidecar can apply R-R02
> later without changing how the flag travels.

---

## 9. Cancellation

- **R-C01** `cs` only moves `none → cancel` or `none → expired`, and never back (terminal). Every hop passes it on
  downstream (in child contexts) and upstream (in `X-RPE-Result`).
- **R-C02** Cancellation is a **notification**. Implementations MUST NOT kill work, drop connections or answer 504
  because a chain is cancelled or expired. Delivery is the header flag, the response member, and a library hook for
  cooperative code (`NonCritical` stops optional work when the signal arrives).

---

## 10. Fan-out

- **R-F01** Each route declares `fanout = partial | all`.
- **R-F02** With `partial`, the parent answers `out=partial` when every required child succeeded; optional children may
  be skipped, expired or failed. Every child is annotated in `kids` as `name:outcome:ms`.
- **R-F03** With `all`, any failed child fails the parent (`out=failed`); if that failure is a deadline failure the
  response MUST be terminal (`term=?1`), so that a retry does not repeat the children that already committed.
- **R-F04 Non-goal.** RPE provides no atomicity guarantee across a fan-out and performs no compensation. Partial side
  effects may persist after a fan-out failure; reconciling them is the application's responsibility.
- **R-F05 Splitter.** Parallel: each of `n` children gets `floor((rb - reserve) / n)` and the remainder goes to the
  first children in a stable order. Sequential: each child may use the whole unallocated pool at dispatch and the
  parent deducts the `rbu` it reports.

---

## 11. Backpressure

### 11.1 Response fields

- **R-B01** `bp` (0..3) and `bpu` (0..100) report the responder's pressure. A level is derived from utilisation by
  thresholds from configuration.
- **R-B02** A responder that sheds load answers 429 or 503 with `out=rejected`, `rsn=overload` (or `circuit-open`),
  `term=?0`, `bpu`, `Retry-After` and the chain's `cs`. Backpressure shedding is load shedding, not a deadline
  decision, and is therefore exempt from "signal, never reject".

### 11.2 C2 sidecar profile (informative; documents the proxy as implemented)

The C2 sidecar (`netty-sidecar-proxy`) implements push-and-piggyback backpressure between sidecars. These messages are
**internal between sidecars**: they are not part of the application contract, they are removed before a request
reaches the application, and they are not moved into `X-RPE-Context` (which would change the proxy and exceed the
256-byte limit). C1 names and references them; their definition, names and thresholds belong to C2.

| Message | As implemented |
|---|---|
| Shedding response | HTTP 503, `Retry-After: 1`, `X-Proxy-Backpressure: slow-down`, `X-Proxy-Queue-Utilization-Percent`, plus `X-RPE-Result` (`out=rejected`, `rsn=overload` or `circuit-open`, `bpu`, `cs`) |
| Caller registration | on outbound sidecar calls: `X-Proxy-Peer-Key` (`host:port`) and `X-Proxy-Caller-Token` (random UUID); stripped before the application |
| Direct signal | `POST /backpressure/signal` on the caller sidecar (port 15090) with `X-Backpressure-Caller-Token`, `X-Backpressure-Utilization-Percent`, `X-Backpressure-Active` |
| Levels | pressure at 50 / 65 / 80 / 95 % of the inbound queue, clear at 30 % or less (defaults, `BACKPRESSURE_*`) |
| Caller reaction | per-destination concurrency `healthy limit x (100 - utilisation) / 100` (minimum 1), then gradual recovery |
| Circuit breaker | opens after 10 consecutive counted failures (5xx, 429, transport errors), open 5 s, then one probe |

Security requirements: a signal is sent only to the caller's observed source IP, never to an address taken from a
header, and the caller proves its identity with the token it registered on its own outbound call.

---

## 12. Trail

The trail lets a request describe its own path: every RPE hop adds one bounded entry, and the gateway ends up with the
complete call tree of that request, including how long each hop took and how much of the budget had already been used
when it started. From trails alone a collector rebuilds the service graph and attributes the deadline to hops, for
every request (no sampling) and with no tracing backend.

- **R-P01 One entry per hop.** Each RPE hop appends exactly one entry (§3.3). On the **request** side the trail carries
  the path from the gateway to the caller (entries active, `A`, elapsed -1); the hop's own entry's `parent` is the index
  of the caller's entry. On the **response** side a hop returns its finished **subtree**: its own entry first
  (`parent = 0`, decision and elapsed time set) followed by the subtrees its callees returned, re-indexed under it.
  The caller merges each callee's subtree under its own entry.
- **R-P02 Bounded.** A trail MUST NOT exceed 16 entries or 1400 bytes. When a merge would exceed a bound, the entries
  that do not fit are dropped together with their descendants and `inc=?1` is set.
- **R-P03 Interned names.** Service names never travel. Ids come from a versioned registry (`rv`, served by the
  protocol service at `GET /api/protocol/registry`). A subtree recorded with another registry version MUST NOT be
  merged (its ids mean other services); the result is marked `inc=?1`. A hop that does not know its own id (registry
  not loaded) does not record a trail. A service the registry does not list registers itself
  (`POST /api/protocol/registry/register`) and gets the next free id. Ids never change once given. The seeded services
  keep version `REGISTRY_VERSION`; once a service has been added, the version is a checksum of the whole name → id
  mapping, so the same mapping always has the same version and two different mappings never share one.
- **R-P04 Never leaves the chain.** The gateway MUST remove `X-RPE-Trail` from its response to the client and MUST
  ignore a trail sent by a client (trust boundary). It MAY report the completed trail to a collector
  (`POST /api/protocol/trails`).
- **R-P05 Unknown edges.** When a callee answers without a trail (it does not speak RPE) or does not answer, the
  caller records an entry for it with decision `U` (service id from the registry by host name, 0 if unknown) and sets
  `inc=?1`. A hop that receives a context below the gateway (`pid` not zero) without a trail sets `inc=?1`: an upstream
  hop did not carry it. Calls made *behind* a non-RPE hop are invisible to the trail; `inc=?1` states that the picture
  is incomplete instead of silently missing it.

**Why a response-side merge.** The request side alone can only describe the path to the current hop; siblings of a
fan-out never see each other. Returning finished subtrees gives the gateway the whole tree, with final elapsed times,
at the cost of one bounded header per response.

**Deadline attribution.** For an entry, `consumedMs` is where in the budget the hop started and `elapsedMicros` how long
it took including its callees. A hop's **own time** is its elapsed time minus the union of its callees' intervals. The
hop with the largest own time is the one that used the deadline.

**Size–fidelity (study, PP2).** v1 is the verbose encoding. Ring-buffer, interned-delta and Bloom-sketch encodings,
and their size versus reconstruction accuracy against sampled tracing (Tempo, 1 % head and tail sampling), are
evaluated in experiment E6.

---

## 13. Versioning and forward compatibility

- **R-V01** Unknown members of `X-RPE-Context` MUST be preserved and forwarded unchanged; they are serialized after the
  known members, sorted by key. Unknown members of the other headers are ignored.
- **R-V02** A receiver that does not support `v` rejects the context as invalid (400, `rsn=unsupported-version`,
  error rule R-V02).
- **R-V03** No silent downgrade: a hop MUST NOT send a lower `v` than it received (R-T05).

---

## 14. Security and privacy

### 14.1 Trust boundary

- **R-S01** The gateway MUST create a fresh root context for every external request and MUST NOT trust a
  client-supplied `ttl`, `bud`, `rb`, `pe` or trail.
- **R-S02** (OPTIONAL, future) An HMAC signature over the immutable members (`sig`) may protect the context between
  trust domains.
- **R-S03** The trail is stripped at egress (R-P04); it reveals internal topology and timing.

### 14.2 Trace binding

`rid` is the W3C trace-id: the gateway binds `rid` to an incoming `traceparent` when one is present and valid, and
every hop sends a `traceparent` whose trace-id equals `rid`, so RPE data and traces can be joined.

---

## 15. Error codes and status codes

| Error code | When |
|---|---|
| `MISSING_HEADER` | a required member (or the trail's `rv`/`inc`) is missing |
| `DUPLICATE_HEADER` | a header or a member appears twice |
| `INVALID_SYNTAX` | the value is not the allowed RFC 8941 subset, a member has the wrong type, a trail entry is malformed or not canonical |
| `OUT_OF_RANGE` | a value is outside its range |
| `UNSUPPORTED_VERSION` | `v` is not supported (rule R-V02) |
| `INVALID_TRANSITION` | a transition rule is violated (rule R-T01..R-T06) |
| `CONTEXT_TOO_LARGE` | `X-RPE-Context` over 256 bytes or `X-RPE-Trail` over 1400 bytes |

| Situation | Status | `X-RPE-Result` |
|---|---|---|
| Original request, deadline passed | **served** (status of the application) | `cs=expired`, `late=?1` when committed late |
| Retry of a cancelled or expired chain | 504 | `out=rejected, rsn=deadline\|cancelled, term=?1, cs=…` |
| Unreadable context | 400 | `out=rejected, rsn=invalid-context, term=?1` |
| Overload (load shedding) | 503 or 429, `Retry-After` | `out=rejected, rsn=overload, term=?0, bpu, cs` |
| Circuit open | 503 | `out=rejected, rsn=circuit-open, term=?0` |

---

## 16. Conformance

An implementation conforms to RPE v1 when it passes every case of the conformance kit (`conformance-kit/vectors`):

| File | Cases | Covers |
|---|---|---|
| `context-decode.json` | 46 | §3.1, §4, R-T02, R-V01, R-V02 |
| `context-encode.json` | 5 | canonical form (§4) |
| `context-transition.json` | 15 | R-T01..R-T06 |
| `context-child.json` | 6 | child derivation, R-T03, R-D07 |
| `budget-split.json` | 7 | R-F05 |
| `result.json` | 10 | §3.4 |
| `hop.json` | 7 | §3.2 |
| `traceparent.json` | 7 | trace binding (§14.2) |
| `retry-gate.json` | 16 | R-R02 and its reason order |
| `trail.json` | 25 | §3.3 |
| `trail-merge.json` | 6 | R-P01..R-P03 response-side merge |

Errors are compared by **code** and **rule**, never by message. Cases are generated once from the reference
implementation (`go run ./cmd/genvectors`), reviewed and committed; a case change is a specification change and is
recorded in §17.2. A differential corpus (5,000 mutated headers, `cmd/difffuzz`) checks that the Java and Go decoders
agree on inputs nobody wrote by hand.

---

## 17. Appendix

### 17.1 Example: one request, hop by hop (demo shop, 1000 ms budget)

| Hop | Received `X-RPE-Context` (abridged) | Trail entry on the response |
|---|---|---|
| gateway | creates `ttl=1000, bud=1000, rb=3, cs=none, pid=0…0` | `1:0:180000:F:0` |
| order-svc | `ttl=996, rb=3, pid=<gateway iid>` | `2:1:172000:F:4` |
| inventory-svc | `ttl=990, rb=1` (split of 3 over 4 children) | `3:2:9000:F:10` |
| payment-svc | `ttl=990, rb=1` | `4:2:150000:F:10` |
| loyalty-svc | `ttl=990, rb=1` | `5:2:7000:F:10` |
| legacy-svc (no RPE) | context ignored | recorded by order-svc as `7:2:12000:U:10`, `inc=?1` |

If payment takes 1500 ms instead, the request is still served; the sidecars mark the chain `cs=expired`, payment's
entry becomes `X`, loyalty (non-critical) skips its work, and a retry of the payment call is refused by the gate.

### 17.2 Change log

| Version | Date | Change |
|---|---|---|
| 0.9.1 | 23 Oct 2026 | R-P03: automatic registration of new services and the checksum registry version. No change to any header or test case. |
| 0.9 | 20 Oct 2026 | First complete draft. Signal mode for deadlines (R-D02, R-D04, R-D05 rewritten; only expired retries are refused). Retry gate R-R02 with a fixed reason order and its conformance cases. Trail v1 (R-P01..R-P05), verbose encoding, response-side subtree merge, registry, conformance cases. `cs` member of `X-RPE-Result`. C2 backpressure sidecar profile documented. |

### 17.3 Differences from the guides' Appendix A3.1 (v0.2)

- A3.1 lists "deadline expired or insufficient time → 504 with `term=?1`" as the status mapping. In v0.9 an expired
  **original** request is served and signalled (`cs=expired`); 504 is used only for a refused **retry** (§7, §15).
- A3.1 describes the backpressure notification as a future `POST /rpe/v1/backpressure` JSON message. v0.9 documents the
  messages the C2 sidecar actually uses (§11.2); any change to them is C2's decision.
- The trail entry format of A3.1 is kept; v0.9 defines `parent` (1-based, 0 = root), `consumedMs`, decision `U` and the
  response-side merge.

### 17.4 Implementation status (PP1)

| Part | rpe-go | rpe-java | Notes |
|---|---|---|---|
| Context, Hop, Result codec; guard; splitter | yes | yes | conformance 100 % |
| Signal-mode inbound and outbound adapters | `SignalMiddleware`, `SignalTransport`, `Middleware`, `Transport` | `http.Admission`, `http.RpeHttpClient` | only expired retries refused |
| Retry gate (R-R02) | `DecideRetry`, `DoWithRetry`, `WithAttempt` | `RetryGate` | conformance 100 % |
| Trail codec and merge | `DecodeTrail`, `Trail.Append` | `Trail.decode`, `Trail.append` | conformance 100 % |
| Trail recording by the adapters, registry client, collector | yes | planned (PP2, with the Java client adapters) | demo shop uses rpe-go |
| Protocol service (A3.7) | `services/protocol-service` | Java side for validation and conformance | trails, topology, attribution, registry |
