# RPE project: design rules for Claude

## Hard rule: do not change the proxy's core behaviour

**Never change the deadline-propagation, cancellation-propagation or backpressure-handling behaviour of the proxy
(`netty-sidecar-proxy/`)**, even if it looks like an improvement or a test expects something else. This includes the
deadline timer and how `ttl` is turned into a local deadline and back, how `cs` is set and passed on, the leaky
bucket, the pressure signals, the adaptive concurrency and the circuit breaker. If a change seems needed, explain it to
the user and wait for an explicit decision; adapt tests, demo services or other components instead.

## Deadline propagation and cancellation propagation (authoritative design)

These rules are the intended design. Where existing code, tests or docs disagree (for example terminal 504 results,
`rsn=deadline` rejections, or "rejected" metrics for expired deadlines), the code and docs are outdated and must be
changed to match this section.

### Core principle: signal, never reject
- RPE must **not reject, fail or abort a request** because its chain deadline has been exceeded. A late request keeps
  flowing. Rejecting would make our product, not the customer's system, the cause of a failed request when it is
  integrated into an existing microservice estate.
- When the chain deadline is exceeded, RPE sets **`cancelled = true`** in the request-chain context (`X-RPE-Context`)
  and **signals** it to the services in the chain.
- The sidecar never cancels the work itself (no aborted handlers, no dropped requests, no 504 generated for the
  deadline).

### Why
- Goal: prevent cascading failures and reduce service overload. An expired deadline means the request is late, and
  lateness is a symptom of overload.
- Services receiving the signal can shed non-critical or expensive work and finish critical work, so overloaded
  services recover faster.

### Cancellation propagation
- The signal goes to **downstream and upstream** services in the chain. Downstream: carried in the context on
  outbound calls. Upstream: carried back in the response (result header), since a request header cannot reach services
  already waiting.
- The flag is **monotonic**: once `cancelled = true` it never returns to false, and every hop passes it on unchanged.
- A live push to calls that are already running is **not** required. Services learn of cancellation from the context on
  later calls and from the response path. This is an accepted limitation, not a gap.

- The application must propagate `Request_id` on its outbound HTTP calls (for example with a client interceptor). The
  sidecar then adds the saved chain context. This is an accepted design choice, not a gap.

### Scope boundary
- **In scope:** detect expiry, set and propagate `cancelled`, make it readable by the application (header, and a
  read-only helper in `rpe-go` / `rpe-java`), record metrics for signals sent.
- **Out of scope:** how the application cancels. The developer decides which processes to cancel (non-critical or
  expensive) and which must continue (critical, for example a payment commit). Our code only delivers the signal.

### Consequences for the code
- Expired deadline becomes `cancelled = true` in the context, not a terminal 504 / `rsn=deadline` outcome.
- Metrics: count cancel signals separately from rejections; an expired deadline is not a "rejected" request.
- Retries must stop once the chain is cancelled (retry storm prevention).
- Done (16 Oct 2026): `demo-app` follows this model (with the `rpe-go` signal helpers): it never answers 504 for a deadline; late
  or cancelled requests are served; `NON_CRITICAL=true` services (loyalty) skip work on a cancelled chain; critical
  work (payment) completes. e2e `30`, `35`, `95` assert the signal (`cs=expired`) instead of 504.
- Done (17 Oct 2026): `rpe-go` has signal-only developer helpers (`libs/rpe-go/signal.go`): `SignalMiddleware`,
  `SignalTransport`, `Cancelled`, `NonCritical`, `CallIfNotCancelled` (a skipped call is information, never an error).
- Still open: the older `rpe-go` `Middleware` / `Transport` keep the rejecting behaviour for existing users (C1 owner);
  the C1/C2 docs and rules (R-D05 and related) still describe it.

### Analogy
This mirrors how gRPC deadlines and context cancellation work for HTTP: the framework delivers the signal and the
application code decides when to stop. RPE brings that model to plain HTTP microservices.

## Backpressure handling (design from the user's implemented proxy)

Source: the user's `BACKPRESSURE_HANDLING_GUIDE.md` (netty-proxy, port 9090, `BACKPRESSURE_*` env vars).

- **Backpressure is exempt from "signal, never reject".** Load shedding is accepted here: a full inbound queue returns
  503 with `Retry-After: 1`, and an open circuit breaker fails calls fast. Only the deadline/cancellation rules above
  are signal-only.
- **Inbound leaky bucket:** per-sidecar FIFO queue released at a steady rate (default 100 req/s and queue capacity 200; on the gateway sidecar 300 req/s and 1000 since 21 Oct 2026, by the C2 owner).
- **Backpressure signals:** the receiver reports queue pressure at 50/65/80/95% full and a clear at 30% or less. Callers
  cut per-destination concurrency to `healthy limit x (100 - utilisation) / 100` (minimum 1) and recover gradually.
  Delivered by a direct `POST /backpressure/signal` to the caller sidecar and by response headers
  (`X-Proxy-Backpressure`, `X-Proxy-Queue-Utilization-Percent`) as a fallback.
- **Outbound queue and circuit breaker:** per-destination concurrency limit and FIFO queue (capacity 1000, set by the C2 owner on 21 Oct 2026; was 200). The breaker
  opens after 10 consecutive counted failures (5xx, 429, transport errors), stays open 5 s, then allows one probe.
- State is local to each sidecar Pod, per destination host and port.

### Decisions and status
- Decided: pressure-marked 503/429 responses DO count toward opening the circuit breaker. This is the user's design.
- Decided (16 Oct 2026): the circuit breaker also counts 504 deadline responses as failures. Do not change the breaker;
  it is part of backpressure handling and works as intended.
- Decided: no maximum outbound concurrency; the user does not want it.
- Done: internal `X-Proxy-*` headers are stripped from application requests; metrics use `rpe_*` names; queue waiting
  time counts against the deadline (the timer starts before the request is queued).
- Done (18 Oct 2026, with the C2 owner's agreement): a rate-limit policy delivered by C3 sets the inbound
  leaky-bucket rate when it is activated (`policy.leakyBucket.ratePerSec` only; staged policies are not enforced, a
  policy without a valid rate leaves the rate alone). The queue capacity is never changed by a policy (a `queueCapacity`
  in a policy is ignored) and the bucket's mechanism is unchanged (`ActivePolicy`,
  `BackpressureController.applyPolicyRate`). Env vars and `/backpressure/config` still work.
- Decided (21 Oct 2026, the C2 owner): a runtime backpressure mode for the comparison experiment, `PUT
  /rpe/v1/backpressure/mode` on the 15020 control API. `full` (the default) is the complete mechanism; `basic` keeps
  only the inbound leaky bucket and the circuit breaker (no pressure signals sent or acted on, no per-destination
  limit or outbound queue). The Admin Panel's Backpressure experiment switches it and always sets `full` again.
  Per-destination metrics (`rpe_backpressure_peer_*`, `rpe_circuit_breaker_peer_state`) are read-only copies.

## Observability belongs to the proxy (user decision, 16 Oct 2026)

- RPE is the product; the microservices are the customer's applications. Exporting metrics (Prometheus) and traces
  (Tempo) is the sidecar's responsibility, never the services'. Do not add metrics or tracing code to the demo
  services (`demo-app` has none).
- Traces: every sidecar exports OTLP spans (`OTEL_EXPORTER_OTLP_ENDPOINT`, set by the webhook) with the `rpe.*`
  attributes C4 reads (`rpe.direction`, `rpe.chain.route`, `rpe.deadline.budget_ms`, `http.request.method`). The
  gateway sidecar's inbound span is the trace root; its trace-id is the request id.

## C2 sidecar: `netty-sidecar-proxy/` (the user's proxy; the old `sidecar/` skeleton was deleted)

- **Never change its deadline-propagation, cancellation-propagation or backpressure architecture.** Integration work
  only adapts interfaces (wire format, ports, env, metrics, control API, images, manifests).
- Wire format: `X-RPE-Context` on requests, the `cs` member of `X-RPE-Result` on responses, via `libs/rpe-java`.
  `RpeWire` translates at the edges; the proxy logic keeps its internal fields (`Request_id`, `deadlinevalue`,
  `deadlineExceded`, `cancellation_Triggered`). The deadline travels as `ttl` (time left), so clocks need not agree.
  `cs=expired` = deadline exceeded + cancelled; `cs=cancel` = cancelled only; never back to `none`.
- The co-located application gets `Request_id` (= `rid`); an application may send only `Request_id` on its outbound
  calls (accepted design) and the proxy adds the stored context.
- Requests without any context are forwarded unchanged by default (`PROXY_MISSING_CONTEXT=passthrough`, user decision
  16 Oct 2026: RPE must not be the reason a request fails), counted in `rpe_passthrough_requests_total` and logged at
  most once a minute. `reject` (HTTP 400) is the strict option. A `Request_id` with no stored context, or malformed
  chain state, still gets 400.
- Webhook (`RPE_*` env) is a fallback; the proxy's own env vars always win. Ports 15001 outbound, 15006 inbound
  (REDIRECT -> `RPE_APP_PORT`, TPROXY -> original port), 15020 control API (`/rpe/v1/...`), 15090 metrics + backpressure
  signal/config. Gateway route budgets: `resilience.rpe.dev/route-deadlines` annotation, overridden per route by the
  active C3 policy (`policy.routes["METHOD /path"].deadlineMs`).
- Build: `docker build -f netty-sidecar-proxy/docker/Dockerfile .` from the repo root (needs `libs/rpe-java`).
  Tests: `mvn -f netty-sidecar-proxy/pom.xml test` after installing rpe-java; the integration tests need Linux epoll.
- E2E on Windows Git Bash: run `scripts/test-e2e.sh` with `MSYS_NO_PATHCONV=1`, otherwise Git Bash rewrites in-pod
  paths such as `/tmp/body` and most suites fail for reasons unrelated to the product.

### Deferred to the next phase
- Retries and retry storm prevention (retry budget) are planned for the next phase and are intentionally absent from the
  current proxy. Do not count them as missing, and do not invent their definition. Keep the cancel flag readable where
  retries will later be decided (no retry once the chain is cancelled).
