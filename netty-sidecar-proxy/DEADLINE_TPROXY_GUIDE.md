# Deadline, cancellation, and TPROXY guide

This guide explains the current sidecar proxy in simple English. It is for the team that builds the proxy image and the team that configures Kubernetes traffic routing.

## The short version

- The API gateway sidecar starts a request chain. It creates one request id and one deadline for every new request.
- A service sidecar saves the chain state when the request enters that service.
- The service application passes only `Request_id` (or the whole `X-RPE-Context`) on its outgoing HTTP calls. Its sidecar finds the saved state and adds it to those calls.
- A sidecar can notice that the deadline has passed. It marks the chain as deadline exceeded and cancelled.
- The proxy passes that status on later calls and on responses. It does not stop application work or send a separate live cancellation message to a request that is already running.
- TPROXY rules and pod permissions send traffic through the proxy. They are deployment work, not configured by this Java program.

## The headers

Between services, the chain state travels in one header, `X-RPE-Context` (the Component 1 format, read and written with `libs/rpe-java`). Responses carry the state in the `cs` member of `X-RPE-Result`.

| Field | Example | What it means |
| --- | --- | --- |
| `rid` | `"4bf92f35..."` | One unique ID for the whole request chain (32 hex characters). The gateway proxy creates it for a new chain. It is also the trace-id. |
| `ttl` | `287` | Time left in milliseconds when the request was sent. Each sidecar turns it into its own local deadline when the request arrives, so the pods' clocks do not have to agree. |
| `bud` | `300` | The original budget of the chain. It never changes. |
| `cs` | `none` | `expired` after a sidecar detects that the deadline has passed (the deadline is exceeded and cancellation is signalled), `cancel` for a cancellation without an expired deadline. Once it is not `none` it never goes back. |

Inside the proxy the same state is kept in its internal fields `Request_id`, `deadlinevalue` (the local absolute deadline), `deadlineExceded` and `cancellation_Triggered`. They are translated at the edges and are not sent between services. The co-located application still receives `Request_id`, which equals `rid`.

The gateway sidecar uses `GATEWAY_DEADLINE_RULES` to choose a duration by HTTP method and path, and falls back to `DEFAULT_DEADLINE_MILLIS` when no rule matches. For example, `GET /api/inventory=500;POST /api/orders=5000` sets 500 ms for inventory reads and 5,000 ms for order creates. Rules are separated by semicolons; each is `METHOD /path=milliseconds`. Query strings are ignored, trailing slashes are normalized, and `{name}` in a path matches one non-empty segment. The route deadlines of an active Component 3 policy take precedence. The sidecar adds the selected duration to the current time once to create the chain's deadline; downstream hops only ever see the remaining time shrink.

The gateway sidecar exports only explicitly configured route budgets on `/metrics` using `rpe_gateway_deadline_budget_milliseconds{method="GET",route="/api/orders"} 2000`. Labels use configured route templates, not query values or concrete resource IDs. Unmatched routes use the default request budget but are not listed in this metric.

Every sidecar also exports route-and-service request counts, deadline detections, and cancellations. The service latency histogram is grouped by service only, not by route. The gateway additionally exposes `rpe_gateway_request_chain_duration_seconds`, a route-grouped histogram of time from gateway-sidecar ingress until the API gateway response returns. When `OTEL_EXPORTER_OTLP_ENDPOINT` points to an OpenTelemetry Collector, sidecars export spans with the shared request ID, chain route, budget, service name, response status, and deadline events. The gateway span begins the trace; each downstream proxy hop continues it using W3C `traceparent`. Prometheus stores aggregate metrics, while the trace backend stores the per-request path. Kubernetes obtains each `SERVICE_NAME` from the Pod's `app` label.

## One request, step by step

Imagine a client calls the API gateway, which calls Order, and Order calls Inventory.

1. The API gateway sidecar sees a new request. With `PROXY_ROLE=gateway`, it creates a request id and a deadline, with `cs=none`. Any chain state sent by the client is ignored.
2. TPROXY sends the request to its real destination. The proxy reads the original destination from the intercepted connection; the service does not have to change its destination to the proxy address.
3. The Order sidecar receives the request with `X-RPE-Context`. It saves a copy of the chain context in memory under the request id and starts checking the deadline for this inbound request.
4. Order's application makes its Inventory call with just `Request_id`. In this project, the Order `RestTemplate` interceptor does that part.
5. The Order sidecar sees the ID-only call, finds the saved context, and adds the same deadline and current flags before forwarding it to Inventory.
6. Inventory's sidecar saves the inbound context too. Inventory's application can read `cs` in `X-RPE-Context` when the request arrives. The current Inventory example logs a cancellation signal; it does not stop its critical work.
7. If the deadline passes while Order is still handling the request, Order's sidecar marks its saved context as exceeded and cancelled. A later outbound call from Order will carry `cs=expired`.
8. If Inventory returns a response with cancellation or deadline status set, Order's sidecar saves that status and adds it to Order's response. The Order sidecar removes its saved context when that inbound request finishes.
9. The response travels back through the upstream sidecars. Each response handler can attach the status it knows for that request to the response going upstream.

The service application does not send the original inbound request back. HTTP already pairs a response with its request on the connection. The sidecar handling that response has the request ID and context saved by its request handler.

## What “cancellation” means here

The current proxy uses cancellation as an HTTP status signal. It does not kill a thread, close an in-flight backend connection, or send a new cancellation request to the backend.

For example, if Inventory started work before the deadline passed, changing a header in Order's memory later cannot change the headers Inventory already received. Inventory can learn about cancellation if:

- its request arrives with `cs=expired` or `cs=cancel`, or
- a later request carries it, or
- its response path receives a cancellation status from a downstream service and passes it back in the response.

The application can choose to skip optional work when it sees the signal. Notification currently demonstrates skipping optional notification work. Work that is already running needs an additional design, such as polling a cancellation endpoint or a separate messaging channel, if it must learn about cancellation immediately.

## Required application change

The sidecar does the deadline and status propagation. The service application only needs to preserve `Request_id` on its outbound calls so the sidecar can find the right context.

In this project, Order does this with a request-scoped value and a `RestTemplate` interceptor. Do not copy a request ID into a global variable: concurrent requests would then use the wrong ID. Also make sure every HTTP client path used for downstream calls attaches the ID. A client that omits it will be rejected by a service sidecar when no matching context can be found.

Application cancellation handling is optional and specific to the work. A service can log the signal, skip optional work, or stop work that its own code can safely stop. The proxy only carries the signal.

## Proxy roles and settings

Set these values on each proxy container:

### API gateway sidecar

```text
PROXY_ROLE=gateway
PROXY_LISTEN_HOST=0.0.0.0
PROXY_LISTEN_PORT=15001
DEFAULT_DEADLINE_MILLIS=5000
```

Only this role may create a missing `Request_id` or `deadlinevalue`. It initializes missing flags to `false` and does not act as the service-side inbound deadline detector.

### Sidecar beside a normal service

```text
PROXY_ROLE=service
PROXY_LISTEN_HOST=0.0.0.0
PROXY_LISTEN_PORT=8080
PROXY_DETECT_INBOUND_DEADLINE=true
```

Service is the default role. A service sidecar must receive chain metadata on an inbound request. For an outbound request containing only `Request_id`, it must find the saved context. It never invents a new deadline. A request that carries no chain state at all, such as a kubelet probe or a call from a service without a sidecar, is forwarded unchanged by default (`PROXY_MISSING_CONTEXT=passthrough`); it is counted in `rpe_passthrough_requests_total` and a warning is logged at most once a minute, so forgotten `Request_id` propagation stays visible. Set `PROXY_MISSING_CONTEXT=reject` to answer such requests with HTTP 400. A `Request_id` with no saved context always gets HTTP 400.

The context map is in the proxy process's memory. Each request chain must pass through the same service sidecar instance for its inbound request and its later outbound requests. Do not load-balance those two flows across different sidecars unless the context store is changed to shared storage.

`PROXY_DETECT_INBOUND_DEADLINE` is a proxy setting, not an instruction to create a new deadline. The current implementation schedules deadline detection for service-side inbound contexts. Confirm the setting and behavior against the exact proxy version being deployed.

## TPROXY: what the cluster team must arrange

TPROXY does not change the application's target address. It intercepts packets in Linux, gives them to the transparent listener, and keeps the packet's original destination available. The proxy then connects to that original destination.

In this repository's Kubernetes setup, inbound service traffic uses TPROXY in each pod's `mangle/PREROUTING` chain. Locally generated HTTP calls use `nat/OUTPUT` REDIRECT to the same sidecar listener; the proxy routes those requests using the HTTP `Host` authority. The proxy's Linux UID is excluded from that rule so its backend connection does not loop back into the sidecar. Pod-template annotations configure the inbound port list, outbound allowlist, optional excluded outbound ports, and proxy listener port; the init container reads them through the Downward API. Lists use iptables multiport syntax, such as `8081,8083:8084`. This egress path supports plaintext HTTP/1.1 only, not arbitrary TCP or TLS.

For the current code to work, all of these must be true:

1. The proxy runs on Linux with a supported Netty epoll native library. The provided shaded JAR includes Linux x86_64 and ARM64 epoll runtimes.
2. The sidecar listens on the port used by the traffic rules (`15001` for the API gateway in these Kubernetes manifests, `8080` for backend service sidecars).
3. The rules intercept the intended TCP traffic and deliver it to that port while preserving the original destination.
4. Policy routing marks and routes TPROXY packets locally, as required by Linux TPROXY.
5. Proxy connections to the original destination are excluded from interception, or they will loop back into the proxy.
6. The proxy container has the capability needed to set `IP_TRANSPARENT`. The current Minikube-tested proxy runs as UID `0` with all capabilities dropped except `NET_RAW`; the Linux documentation says `CAP_NET_ADMIN` or `CAP_NET_RAW` can be used.
7. The pod security policy, admission policy, and cluster runtime allow the required capability and network rules. Some clusters prohibit this. A privileged init container or node-level setup may be needed to install the rules; the proxy process itself should not receive broad privileges just to install rules if that can be avoided.

### Linux rule shape (example only)

The following is the standard shape of an IPv4 iptables TPROXY setup. It is a reference for the deployment engineer, not a ready-to-apply Kubernetes manifest. The real rule must match your CNI, pod network namespace, traffic direction, ports, health checks, and proxy UID.

```sh
# Example values only. Pick unused mark/table values and the real proxy port.
iptables -t mangle -N SIDECAR_TPROXY
iptables -t mangle -A PREROUTING -p tcp -j SIDECAR_TPROXY

# Add RETURN rules here for traffic that must not be intercepted:
# - traffic to/from the proxy itself
# - loopback and required Kubernetes/CNI traffic
# - excluded ports, destinations, and health checks

iptables -t mangle -A SIDECAR_TPROXY -p tcp \
  -j TPROXY --on-port 8080 --tproxy-mark 0x1/0x1

ip rule add fwmark 0x1/0x1 lookup 100
ip route add local 0.0.0.0/0 dev lo table 100
```

This example shows the TPROXY packet delivery and policy-routing pieces. It does not define your complete pod egress setup. Locally-created pod traffic and traffic arriving from outside the pod enter different network paths; your team must add and test the correct capture and bypass rules for both directions. Do not copy this example unchanged into a cluster. In particular, wrong bypass rules can create a forwarding loop or intercept Kubernetes networking.

Use the equivalent nftables rules if your cluster uses nftables. Do not install both iptables and nftables rule sets without understanding how they interact.

### Pod permissions: example shape

The Kubernetes security context can grant a Linux capability to the proxy container. The current demo runs the proxy as UID `0` with only `NET_RAW`; the separate routing init container runs as UID `0` with `NET_ADMIN` to install iptables and policy routing. Your cluster security policy must allow these settings.

```yaml
containers:
  - name: sidecar-proxy
    image: your-proxy-image
    securityContext:
      runAsNonRoot: false
      runAsUser: 0
      allowPrivilegeEscalation: false
      capabilities:
        drop: ["ALL"]
        add: ["NET_RAW"]
```

If a separate init container installs iptables rules, it may need `NET_ADMIN` and the required iptables tools. Keep those rule-installing permissions separate from the long-running Java proxy when possible. Kubernetes clusters may reject these permissions through Pod Security Admission or a policy engine; that must be resolved by the cluster administrator.

## Safe rollout checklist

1. Check that the proxy starts on Linux and logs that it is listening on the configured port.
2. Confirm TPROXY and socket-match support in the node kernel, and that the required iptables extensions or nftables features are available.
3. Confirm `ip rule` and the local route table contain the mark and table used by the rules.
4. Send a request through the pod and confirm the proxy forwards it to the original service address, not back to itself.
5. Confirm the gateway proxy creates one ID and deadline, and service proxies preserve them.
6. Confirm an order downstream call carries `X-RPE-Context` with the same `rid`, a `ttl` that only shrinks, and the current `cs`.
7. Use a short deadline and check proxy logs for deadline detection. Check service logs to see which applications received `cs=expired`.
8. Check both success and error paths. The proxy is a proof of concept and currently aggregates HTTP messages up to 10 MB and opens a backend connection per request.

## Current limits to remember

- This is an HTTP/1.1 proof of concept. It is not a full production service mesh proxy.
- The proxy aggregates messages (up to 10 MB) and opens one backend connection per request.
- It does not intercept HTTPS as readable HTTP. TLS interception or application-level TLS handling needs a separate design.
- It does not actively stop application work or notify already-running calls asynchronously.
- The deadline travels as time left (`ttl`), so pod clocks do not need to agree. The network time between two sidecars is not subtracted, so a deadline can be a few milliseconds late per hop.
- Context is held in memory and is removed when the owning inbound request completes. Process restarts lose it.
- The proxy code does not install Linux rules or Kubernetes permissions.

## References

- [Linux kernel: Transparent proxy support](https://docs.kernel.org/networking/tproxy.html) — TPROXY, marks, policy routing, and `IP_TRANSPARENT`.
- [Linux `IP_TRANSPARENT` manual page](https://man7.org/linux/man-pages/man2/IP_TRANSPARENT.2const.html) — capability needed for transparent sockets.
