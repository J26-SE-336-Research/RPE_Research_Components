# rpe-java (C1)

Java 21 implementation of the RPE v1 header set, with no runtime dependencies. It behaves identically to `rpe-go`:
it passes the shared conformance vectors and agrees with `rpe-go` on a 5,000-input differential corpus
(see `conformance-kit/`).

| Class | Purpose |
|---|---|
| `RpeContext` | `X-RPE-Context`: `decode`, `encode` (canonical), `validate`, `newRoot` (gateway, R-S01), `child` (R-T02, R-T03, R-D07) |
| `ContextGuard` | `validateTransition(parent, child, elapsedMs)` returns the violated rules R-T01..R-T06 |
| `RpeResult`, `Hop` | `X-RPE-Result` and the hop-scoped `X-RPE-Hop` |
| `BudgetSplit` | Retry-budget split for fan-out (R-F05) |
| `Ids` | Random ids, W3C trace binding |
| `ProtocolException` | Error `code()` and spec `rule()` |
| `RetryGate` | Retry gate R-R02: `decide(check)` says whether one more attempt may be sent, and why not |
| `Trail` | `X-RPE-Trail` (verbose v1): `decode`, `encode`, `append` (response-side subtree merge, R-P01..R-P03) |
| `http.Admission` | Framework-neutral inbound step: trust boundary; signal mode (R-D02): an expired original request is admitted with `cs=expired`, only a retry of an expired or cancelled chain is refused |
| `http.RpeHttpClient` | `java.net.http` adapter: propagates the child context; never refuses a first attempt or cuts a call off; a retry (`attempt >= 2`) of an expired chain is not sent (R-R02) |

**Warning for application developers:** nothing stops an expired original request. Read the signal
(`inbound().context().cancel()`) and decide what to skip; use `RetryGate` before every retry.

```java
// inbound (servlet filter, Spring interceptor, JDK HttpServer, ...)
Admission.Decision d = Admission.admit(Admission.Config.service(), method, path, headers);
if (!d.admitted()) { respond(d.status(), RpeHeaders.RESULT, d.resultHeader(), d.message()); return; }
if (d.inbound() != null && d.inbound().context().cancel() != CancelState.NONE) { /* late: skip optional work */ }

// outbound
var client = new RpeHttpClient(HttpClient.newHttpClient(), 5 /* reserve ms */);
var resp = client.send(HttpRequest.newBuilder(URI.create("http://payment-svc:8080/pay")), d.inbound(), 1 /* grant */,
        HttpResponse.BodyHandlers.ofString());
```

Planned adapters (proposal scope): RestTemplate, WebClient, Feign and OkHttp interceptors built on `Admission` and
`RpeContext.child`.

```bash
./mvnw -q test
```
