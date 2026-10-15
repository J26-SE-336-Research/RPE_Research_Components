# Netty Sidecar HTTP Proxy

A small educational HTTP sidecar proxy built with **Java 17, Maven, and Netty 4.1.115.Final**.

## Architecture

```text
Client / Service A
       |
       | HTTP request
       v
+-------------------------+
|  Netty TPROXY Sidecar   |
|       :8080             |
|                         |
|  HttpServerCodec        |
|  HttpObjectAggregator   |
|  ProxyHandler           |
+------------+------------+
             |
             | HTTP request
             | original destination + propagation headers
             v
+-------------------------+
|       Service B         |
|       :9000             |
+------------+------------+
             |
             | HTTP response
             v
       Proxy response
       + X-Proxy-Processed
             |
             v
           Client
```

The application continues to call Service B using its normal destination. In Kubernetes/Linux, TPROXY can redirect that traffic to the sidecar, which reads the original destination and forwards the request there.

## Current implementation

- Java 17
- Maven
- Netty 4.1.115.Final
- HTTP/1.1
- Listens on `0.0.0.0:8080`
- Reads the original destination from each intercepted TPROXY TCP connection
- Requires Linux Netty epoll and `IP_TRANSPARENT`
- Adds `X-My-Proxy: Netty-Sidecar-Proxy`
- Adds `X-Proxy-Processed: true` to backend responses
- Configurable through environment variables
- 5-second destination connection timeout
- 10 MB aggregated HTTP message limit

## Important learning point: TCP streams

TCP does not send "headers first" as a special TCP concept.

The HTTP bytes are written into a TCP byte stream. TCP divides that stream into segments and numbers them with sequence numbers. The receiver reorders segments when necessary and exposes an ordered byte stream to the HTTP layer.

Conceptually:

```text
HTTP bytes:
[headers][blank line][body]

TCP:
segment 1 -> bytes N..M
segment 2 -> bytes M+1..P
segment 3 -> bytes P+1..Q

Receiver:
TCP reassembles the ordered byte stream
                |
                v
HTTP decoder sees:
[headers][blank line][body]
```

The HTTP decoder (`HttpServerCodec`) is therefore working on an ordered stream of bytes, not on individual TCP packets.

## Build and run

The Dockerfile uses two stages: Maven builds the proxy JAR in the first stage, then the second stage copies the JAR into a Java 17 runtime image. You do not need to build the JAR on your computer first.

From this directory, build the image with Docker Compose:

```bash
docker compose build proxy
```

To start the Compose service after the image is built:

```bash
docker compose up proxy
```

The proxy still needs Linux TPROXY setup and the required transparent-socket permission at runtime.

You can also build and run the JAR directly with Maven:

```bash
mvn clean package
```

Run on Linux with Netty native epoll available and the required transparent socket permission:

```bash
java -jar target/netty-sidecar-proxy-1.0.0.jar
```

## Configuration

Linux:

```bash
export PROXY_LISTEN_HOST=0.0.0.0
export PROXY_LISTEN_PORT=8080
export DEFAULT_DEADLINE_MILLIS=5000
export REQUEST_CONTEXT_TTL_MILLIS=20000
export PROXY_DETECT_INBOUND_DEADLINE=true
export PROXY_ROLE=service
java -jar target/netty-sidecar-proxy-1.0.0.jar
```

### Backpressure and circuit breaker

Backpressure handling always runs on each sidecar. Set these environment variables on that Pod's `netty-proxy` container to configure its inbound request rate and queue:

```text
BACKPRESSURE_INBOUND_RATE_PER_SECOND=100
BACKPRESSURE_INBOUND_QUEUE_CAPACITY=200
BACKPRESSURE_OUTBOUND_QUEUE_CAPACITY=200
BACKPRESSURE_CIRCUIT_BREAKER_FAILURE_THRESHOLD=10
BACKPRESSURE_CIRCUIT_BREAKER_OPEN_MILLIS=5000
BACKPRESSURE_CONTROL_TOKEN=<optional-token-for-runtime-rate-updates>
```

The inbound leaky bucket is local to one sidecar Pod. It queues requests and releases them toward the application at the configured rate, spacing them out instead of passing a burst through at once. `BACKPRESSURE_INBOUND_QUEUE_CAPACITY` is the maximum number of requests waiting in that queue; it is a queue length, not a request rate. When the queue reaches the first built-in pressure threshold (50%; later thresholds are 65%, 80%, and 95%), responses carry `X-Proxy-Backpressure: slow-down` and `X-Proxy-Queue-Utilization-Percent: <0-100>`. The utilization header reports the current waiting-queue fill percentage. The response warning stays active until the queue drains to 30% capacity or less. If the queue is full, the proxy returns HTTP 503 with `Retry-After: 1` and reports 100% utilization. Configure the queue capacity and rate with each replica's capacity in mind; this is not one cluster-wide service limit.

The leaky-bucket rate limiter applies only to inbound requests. Outbound calls are not rate-limited. Instead, the caller sidecar keeps a per-peer limit on concurrent calls. When a downstream queue crosses configured utilization levels (default 50%, 65%, 80%, and 95%), its sidecar immediately sends an authenticated `POST /backpressure/signal` to each recently observed caller Pod at port 9090. The caller reduces that peer's concurrency using the reported queue percentage. As the queue drains back across levels, the receiver sends lower utilization updates; at 30% or less it sends a clear signal. A clear signal stops pressure-based reductions; successful responses then raise concurrency gradually toward the learned healthy limit. Repeated reports at the same utilization do not repeatedly reduce the limit. Responses still carry the pressure headers as a fallback if direct notification cannot reach the caller. The caller proxy adds `X-Proxy-Peer-Key` with the host-and-port key it tracks and a short-lived random callback token. The receiver uses the observed private source Pod IP as the notification target and removes the internal headers before the request reaches the application.

These percentage thresholds use proxy defaults, so direct notifications need no shared Secret or manual Kubernetes configuration. For each tracked destination, the caller sidecar creates a random callback token, reuses it for requests to that destination, and refreshes its five-minute expiry while calls continue. It adds the token and destination key to each request. The receiver remembers the caller Pod IP and that token. On a pressure change, it posts the utilization and active/clear status to the caller Pod IP on port 9090. The caller accepts the update only while that token is registered locally, then maps it to the original destination. The receiver removes the internal headers before forwarding the request to the application. The proxy only registers private or loopback source addresses for push notifications. Restrict port 9090 to trusted cluster workloads; the token is a short-lived capability for one caller-to-destination pair. If direct notification cannot reach the caller, the response headers remain as a fallback.

Other 5xx responses and connection failures also halve the outbound concurrency limit. Successful 2xx/3xx responses raise the limit by one; if real queued demand exists, the learned healthy baseline can increase without a configured maximum. Excess calls wait in a bounded per-peer queue and resume as capacity becomes available. If the outbound queue is full, the caller sidecar returns HTTP 503 with the pressure header. `BACKPRESSURE_OUTBOUND_QUEUE_CAPACITY` sets that waiting queue's maximum length. Repeated server or connection failures can open the circuit breaker; while open, calls fail fast, then one probe is allowed after the open interval. The proxy does not retry calls. Peer state is kept in a bounded table; if that table fills, calls to new peers do not get adaptive concurrency or circuit-breaker tracking.

The separate Intelligence Service can update the inbound release rate and queue capacity at runtime by calling each target sidecar Pod's metrics/control port:

```text
POST http://<pod-ip>:9090/backpressure/config
X-Backpressure-Token: <BACKPRESSURE_CONTROL_TOKEN>
Content-Type: text/plain

rate=50
queue_capacity=100
```

The update endpoint is available when a non-empty control token is configured. Store the token in a Kubernetes Secret and expose the control port only to trusted cluster workloads. Each replica must receive its own update. The inbound rate, queue capacity, inbound queue depth, outbound circuit-breaker rejections, and observed downstream pressure signals are exposed as Prometheus metrics.

These controls do not change the proxy's deadline or cancellation headers and timers. Pressure rejection does not mark a request's deadline as exceeded. Backpressure always runs, using the configured rate and queue settings (or their defaults).

Inbound requests use Linux TPROXY: the proxy enables Netty's epoll transparent socket option and forwards to the original destination preserved on the accepted socket. Outbound HTTP requests are redirected by the pod's `OUTPUT` NAT rules; because that redirection changes the accepted socket's local address, the proxy resolves the outbound destination from the HTTP `Host` header (or an absolute `http://` request URI). The manifests run the application as UID `10001` and the proxy as UID `0`, dropping all proxy capabilities except `NET_RAW` for the transparent socket. They exclude UID `0` from egress redirection so the proxy's backend connections do not loop back through itself. If the proxy identity changes, keep the bypass UID aligned with its `runAsUser`.

The Kubernetes manifests configure inbound TPROXY rules and policy routing in an init container, plus optional outbound redirection. Each Pod template declares `resilience.rrpe.dev/inbound-ports`, `resilience.rrpe.dev/outbound-ports`, `resilience.rrpe.dev/excluded-outbound-ports`, and `resilience.rrpe.dev/proxy-port` annotations. Port values use iptables multiport syntax: comma-separated ports and ranges such as `8081:8084`. The init container reads these values through the Downward API, rejects non-port characters, and only installs an egress redirect when an outbound allowlist is non-empty. The gateway listens on port `15001`; the service proxies listen on `8080`.

For this demo, the gateway intercepts inbound port `8080` and outbound ports `8081:8084`; Order intercepts inbound `8081` and outbound `8082:8084`; Inventory, Payment, and Notification intercept their own inbound ports and have no outbound allowlist. Excluded outbound ports are placed before the redirect rule. Keep health-check and control-plane ports out of the inbound allowlist. This proof of concept handles plaintext HTTP/1.1 only; it does not intercept HTTPS/TLS, HTTP/2, or arbitrary TCP protocols. The shaded build includes Netty native epoll runtimes for Linux x86_64 and ARM64.

### Initial adoption scope

Treat this proxy as an opt-in Kubernetes prototype for Linux Pods whose HTTP/1.1 ports are explicitly declared with the Pod annotations above and whose cluster permits a root init container with `NET_ADMIN` plus a root proxy container with only `NET_RAW`. Before broad adoption, define per-workload port allowlists and exclusions, keep health-check and control-plane ports out of inbound interception, test DNS and service routing on the target CNI, and verify Pod Security Admission permits the routing setup. The current annotations make port routing configurable, but do not make HTTPS/TLS, HTTP/2, or arbitrary TCP protocols supported.

For the Minikube Docker driver, build and load the image referenced by the manifests before applying them:

```bash
docker build -f netty-sidecar-proxy/docker/Dockerfile \
  -t 2002rusira/netty-sidecar-proxy-rpe-implementation:v13 \
  netty-sidecar-proxy
minikube image load 2002rusira/netty-sidecar-proxy-rpe-implementation:v13
```

For a single-node Minikube development setup, apply `K8s/11-tempo.yaml` to install Tempo with local persistent storage and an OTLP/HTTP receiver at `tempo:4318`. Apply `K8s/10-otel-collector.yaml` to receive proxy spans over OTLP/HTTP on port `4318` and forward them over OTLP/HTTP to Tempo. Then apply the API gateway and backend service manifests. Their init containers install the pod-local routing rules before the application and proxy start.

## Project structure

```text
netty-sidecar-proxy/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   └── java/
    │       └── com/example/proxy/
    │           ├── ProxyConfig.java
    │           ├── ProxyServer.java
    │           ├── ProxyInitializer.java
    │           ├── ProxyHandler.java
    │           ├── BackendInitializer.java
    │           └── BackendResponseHandler.java
    └── test/
        └── java/
            └── com/example/proxy/
                └── ProxyConfigTest.java
```

## Deadline and cancellation propagation (proof of concept)

For the simple-English end-to-end flow, example TPROXY rule shape, pod permission notes, and rollout checklist, see [DEADLINE_TPROXY_GUIDE.md](DEADLINE_TPROXY_GUIDE.md).

The Netty sidecar beside the API gateway creates one chain identity and absolute Unix epoch deadline for a new outbound request when those headers are absent. It preserves existing values when a request already belongs to a chain, sends the headers to the original destination, and echoes the chain metadata on its response:

| Header | Meaning |
| --- | --- |
| `deadlinevalue` | Absolute deadline in epoch milliseconds, shared by every hop |
| `deadlineExceded` | `true` when the deadline has elapsed |
| `cancellation_Triggered` | `true` when cancellation has been signalled |
| `Request_id` | UUID identifying the request chain |

Configure `DEFAULT_DEADLINE_MILLIS` on the gateway sidecar (default `5000`). It is the fallback budget for a new inbound route that has no matching rule. `GATEWAY_DEADLINE_RULES` optionally overrides that budget by HTTP method and path, with semicolon-separated entries such as `GET /api/inventory=500;POST /api/orders=5000;GET /api/orders/{id}=2000`. Values are milliseconds. Query strings are ignored, trailing slashes are normalized, and `{name}` matches one non-empty path segment. Unmatched routes use the default. The selected duration is converted to an absolute deadline once at gateway ingress; downstream hops must keep forwarding that deadline. Each sidecar detects expiry for the inbound request it owns, and outbound hops reuse the saved chain context so they carry the same absolute deadline and status.

The gateway `/metrics` endpoint exposes only explicitly configured route budgets as `sidecar_gateway_deadline_budget_milliseconds{method="GET",route="/api/orders"} 2000`. These are gauges initialized from `GATEWAY_DEADLINE_RULES` at sidecar startup; Prometheus can scrape them to read each configured method/path budget. Unmatched routes use `DEFAULT_DEADLINE_MILLIS` for request handling but are not listed in this metric. The proxy does not export raw query values or concrete path IDs as labels.

The proxy exports the service-only latency histogram `sidecar_service_request_duration_seconds`; it is not split by route. The gateway additionally exports `sidecar_gateway_request_chain_duration_seconds`, a gateway-only histogram labeled by service, method, configured chain route, and deadline budget. It measures the time from gateway-sidecar ingress until the API gateway's response returns, which includes downstream calls the gateway waits for. Request counts, deadline detections, cancellations, and gateway chain outcomes also use bounded service, method, route, and budget labels. Request IDs are deliberately excluded from Prometheus labels. Container CPU and memory still need to come from Kubernetes resource monitoring (for example, kubelet/cAdvisor metrics); the proxy reports request behavior, not application-container resource usage.

When `OTEL_EXPORTER_OTLP_ENDPOINT` is set to the trace URL (for example, `http://otel-collector:4318/v1/traces`), each sidecar exports one OpenTelemetry span for each intercepted HTTP hop over OTLP/HTTP. A trace starts at the gateway, follows the W3C `traceparent` injected by the sidecars, and records the existing `Request_id`, configured chain route, deadline budget, service name, response status, and deadline-exceeded events. The same request ID therefore lets operators find the complete chain in the tracing backend. `SERVICE_NAME` should identify each sidecar; Kubernetes manifests read it from the Pod's `app` label. Apply [10-otel-collector.yaml](../K8s/10-otel-collector.yaml) to receive OTLP traces over HTTP port `4318` and forward them over HTTP to a Tempo service at `tempo:4318`. Tempo must have its OTLP/HTTP receiver enabled and be reachable at that address. Prometheus should scrape each sidecar's existing `/metrics` endpoint for aggregate route metrics. The intelligence layer can correlate route metrics with traces through the request ID; individual request IDs are not Prometheus labels because that would create unbounded time series.

`REQUEST_CONTEXT_TTL_MILLIS` sets how long a sidecar keeps each request context after its first creation (default `20000`, or 20 seconds). The timer is fixed from context creation and is not reset by later requests with the same `Request_id`. Responses and connection failures do not remove contexts early; expiry removes them. Set the same value on every sidecar so all services use the same cleanup window. The TTL is a cleanup limit, separate from the request deadline.

Set `PROXY_ROLE=gateway` only on the API gateway sidecar. That is the only role allowed to create a missing `Request_id` or `deadlinevalue`. Service sidecars default to `PROXY_ROLE=service`; they require the inbound chain headers or a matching saved context for an outbound request containing only `Request_id`. A service sidecar returns HTTP 400 instead of silently starting a new deadline if the chain context is missing.

The order service captures only `Request_id` for the lifetime of its synchronous inbound request and adds only that header to inventory, payment, and notification calls. Its sidecar looks up the saved inbound context by ID and fills in `deadlinevalue`, `deadlineExceded`, and `cancellation_Triggered`. A new chain with no ID gets its UUID and deadline from the gateway sidecar. The sidecar retains the inbound chain context until the owning inbound request completes; completing an outbound hop does not evict it. When the deadline arrives, the sidecar updates the stored flags so later outbound hops carry the cancellation signal.

Every service hop still needs to be routed through a proxy instance for proxy-side behavior to run. The proxy observes deadline expiry and propagates the resulting flags; it does not interrupt backend application work or send a separate asynchronous cancellation message to an already-running downstream request. Cancellation here is a propagated signal for application code to observe, consistent with this prototype's scope.

## Current limitations

This is a learning/reference implementation rather than a production transparent proxy.

The next engineering steps are:

1. Persistent backend connections / connection pooling.
2. Correct handling of HTTP keep-alive and connection lifecycle.
3. Read/write timeouts.
4. Bounded buffering and scheduled request shaping.
5. Streaming bodies instead of aggregating every request/response.
6. HTTPS/TLS interception where appropriate.
7. Health/readiness endpoints.
13. Separate asynchronous cancellation notifications to already-running downstream calls.
9. Integration tests with a real backend.

## Security note

Do not use transparent interception or TLS interception against systems you do not own or administer. In a real deployment, traffic-redirection rules and certificate handling must be designed for the environment and its authorization model.
