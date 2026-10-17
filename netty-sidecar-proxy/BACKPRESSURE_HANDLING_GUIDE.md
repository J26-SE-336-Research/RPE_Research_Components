# Sidecar Backpressure Handling

## Purpose

This guide describes how the current sidecar proxy manages overload.

The proxy uses three related mechanisms:

1. **Leaky bucket rate limiting** controls how quickly each service receives inbound requests.
2. **Backpressure signaling** tells callers to reduce calls to a busy destination.
3. **Circuit breakers** temporarily stop calls to a destination that keeps failing.

Together, these mechanisms help protect services from a sudden rush of work, give overloaded services time to recover, and avoid repeatedly calling an unhealthy service.

## Where the controls run

Each Pod has its own sidecar state. The inbound rate limit and queue are therefore **per sidecar Pod**, not one shared limit for all replicas of a service. Outbound concurrency and circuit-breaker state are also local to each caller sidecar and are tracked separately for each destination host and port.

For example, calls to `inventory-service:8082` share one peer entry in a caller sidecar. A different destination host or port has a different entry. Calls to different paths on the same host and port share that entry.

## 1. Inbound leaky bucket

When a request arrives at a service sidecar, the proxy puts it in a first-in, first-out waiting queue. It releases queued requests toward the application at a steady configured rate. This smooths out bursts instead of letting all queued work reach the application at once.

Current defaults:

| Setting | Default | Meaning |
| --- | ---: | --- |
| `BACKPRESSURE_INBOUND_RATE_PER_SECOND` | 100 | Maximum pace at which this sidecar releases inbound requests toward its application. |
| `BACKPRESSURE_INBOUND_QUEUE_CAPACITY` | 200 | Maximum number of requests waiting in this sidecar's inbound queue. |

These are environment variables for the `netty-proxy` container. For example, a rate of 100 requests per second spaces releases about 10 milliseconds apart. The queue capacity is a count of waiting requests, not a request rate.

If the queue is full, the sidecar rejects the new request with HTTP **503 Service Unavailable**. The application does not receive that rejected request. The response includes `Retry-After: 1` and reports 100% queue utilization.

## 2. Backpressure signals

The receiver sidecar watches its inbound waiting queue. When it gets busy, it tells caller sidecars that have recently sent it requests to slow down.

### When signals are sent

The default queue pressure levels are **50%, 65%, 80%, and 95%** full. When the queue crosses a new level, the receiver sends an update. When the queue drains to **30% or less**, it sends a clear signal.

These percentages describe how full the receiver's **inbound leaky bucket waiting queue** is. The pressure levels and clear level can be configured with `BACKPRESSURE_SIGNAL_THRESHOLDS_PERCENT` and `BACKPRESSURE_SIGNAL_CLEAR_PERCENT`.

### How the receiver finds callers

When a caller sidecar sends an outbound request, it adds two proxy-internal headers:

- `X-Proxy-Peer-Key`: the destination host-and-port key that the caller uses to track concurrency.
- `X-Proxy-Caller-Token`: a short-lived random token for this caller-to-destination relationship.

The receiving sidecar observes the source Pod IP from the incoming network connection and remembers it with the peer key and token. It only remembers private or loopback source addresses, and caller registrations expire after five minutes without a new request.

When pressure changes, the receiver makes a separate HTTP `POST` to the caller Pod IP on port `9090`, at `/backpressure/signal`. That control request carries the token, current queue utilization, and whether pressure is active or cleared. The caller validates the token and uses it to find the destination peer entry to update. The proxy-internal request headers are removed before the request reaches the application.

### How callers react

Each caller sidecar has an outbound concurrency limit for each tracked destination. It starts at 32 in-flight calls. When it receives a pressure percentage, it calculates a target from the learned healthy limit:

`target concurrency = healthy limit × (100 − queue utilization percentage) / 100`

The target has a minimum of 1. For example, if the healthy limit is 32 and the receiver reports 50% queue utilization, the target is 16. At 65%, the target is about 11.

As the receiver queue becomes less full, the caller raises its active limit gradually. A clear signal does not instantly restore the old limit; successful responses increase it over time. The learned healthy limit can grow beyond the initial 32 when successful responses continue and there is actual queued demand. There is no configured maximum concurrency in the current implementation.

### Two ways the pressure information travels

The direct notification is the main fast signal. There is also a response-header path: while the receiver queue is under pressure, its sidecar adds these headers to responses it forwards:

```http
X-Proxy-Backpressure: slow-down
X-Proxy-Queue-Utilization-Percent: 80
```

The caller sidecar reads these headers on the response and updates the same destination peer state. This can happen on a normal application response. It does not require a 503. The response headers provide another way to deliver pressure information if the direct notification is not received.

The receiver's queue-full 503 also includes pressure information. Separately, if the caller's own outbound waiting queue is full, its sidecar returns a 503 to its local application because that new call could not be queued.

## 3. Outbound queue and circuit breaker

The caller sidecar limits how many calls can be in flight to a destination at once. Calls above that concurrency limit wait in a per-destination FIFO queue.

| Setting | Default | Meaning |
| --- | ---: | --- |
| `BACKPRESSURE_OUTBOUND_QUEUE_CAPACITY` | 200 | Maximum number of calls waiting for capacity for one tracked destination. |
| `BACKPRESSURE_CIRCUIT_BREAKER_FAILURE_THRESHOLD` | 10 | Consecutive counted failures before that destination's circuit opens. |
| `BACKPRESSURE_CIRCUIT_BREAKER_OPEN_MILLIS` | 5000 | How long the circuit stays open before one probe call is allowed. |

If a destination's outbound waiting queue is full, the caller sidecar rejects the new call with HTTP 503 to its local application. This local queue rejection does not count as a destination response or as a circuit-breaker failure because the call did not reach the destination.

The circuit breaker handles repeated failures from a destination. The current code counts destination responses with HTTP **5xx** or **429**, and connection or transport failures. The count is per destination in that caller sidecar. Other 4xx responses, such as 404, are not counted as breaker failures.

After 10 consecutive counted failures by default, the circuit opens. While open, the caller sidecar fails calls quickly instead of continuing to wait on a failing destination. After five seconds, it allows one probe call. A successful probe closes the circuit; a failed probe keeps the circuit open.

When pressure-marked 503 or 429 responses come back from a receiver, they also count toward the current circuit-breaker failure threshold. This means sustained queue rejections can eventually open the circuit, even though backpressure signaling is already asking the caller to slow down. This behavior should be monitored and can be refined later if overload responses should be treated separately from service failures.

## Example request flow

Suppose both Order and Payment call Inventory:

1. Order's and Payment's sidecars send calls to Inventory and include their peer key and temporary token.
2. Inventory's sidecar queues inbound requests and releases them toward Inventory at its configured rate.
3. If Inventory's inbound queue crosses 50%, 65%, 80%, or 95%, its sidecar sends each recent caller sidecar a direct signal with the current percentage. It also adds pressure headers to responses while pressure is active.
4. Order's sidecar uses the signal to reduce concurrency for Inventory. Payment's sidecar independently does the same. Calls to other destinations are unaffected.
5. If Inventory's queue drains to 30% or less, its sidecar sends a clear signal. Callers recover their concurrency gradually as successful responses arrive.
6. If Inventory keeps returning errors, each caller's circuit breaker can open for Inventory. After the open period, one probe checks whether Inventory has recovered.

## Why use all three mechanisms?

| Mechanism | Main job | Advantage |
| --- | --- | --- |
| Inbound leaky bucket | Smooth request bursts before they reach a service. | Gives the service a steady workload and bounds the number of waiting requests. |
| Backpressure signaling | Tell callers that a particular destination is getting overloaded. | Reduces pressure at the source and reduce the rejected requests by the inbound queue and try to control the caller services requests speed and try to reduce the inbound queue fill. |
| Circuit breaker | Stop calls temporarily when a destination repeatedly fails. | Avoids spending time and resources on calls likely to fail, while allowing a probe to test recovery. |

They address different situations. The leaky bucket controls the inbound arrival pace. Signaling coordinates the caller's outbound pace with the receiver's queue pressure. The circuit breaker handles persistent failures. Used together, they can reduce queue growth, wasted work, and the spread of failures across a request chain.

## Prometheus metrics

The sidecar exposes backpressure metrics at `/metrics` on port `9090`. Current metrics include:

- `rpe_backpressure_inbound_rate_per_second`
- `rpe_backpressure_inbound_queue_capacity`
- `rpe_backpressure_inbound_queue_depth`
- `rpe_backpressure_inbound_queue_utilization_percent`
- `rpe_backpressure_inbound_pressure_active`
- `rpe_backpressure_inbound_rejected_total`
- `rpe_backpressure_outbound_rejected_total`
- `rpe_backpressure_peer_pressure_signals_total`
- `rpe_backpressure_peer_pressure_notifications_sent_total`
- `rpe_backpressure_peer_pressure_notifications_failed_total`
- `rpe_backpressure_peer_pressure_notifications_rejected_total`
- `rpe_backpressure_outbound_in_flight`
- `rpe_backpressure_outbound_queued`
- `rpe_circuit_breaker_open_peers`

These metrics help show queue pressure, rejected work, notification delivery, and whether breakers are open. The current metrics are mainly aggregate for a sidecar; they do not expose every destination's live concurrency limit as a separate Prometheus series.

## Scope and operational notes

- The proxy does not retry calls. The circuit breaker rejects calls while open and allows a probe after the open period.
- Limits and queues are local to each Pod sidecar. Adding service replicas adds more independent sidecar queues and capacity; it does not create one shared queue.
- Direct notifications require the cluster network to permit sidecar-to-sidecar traffic to port `9090`. If that path is unavailable, the response-header signal can still carry pressure information when a response returns.
- The circuit breaker currently counts pressure-marked 503 and 429 responses as failures, as described above.
