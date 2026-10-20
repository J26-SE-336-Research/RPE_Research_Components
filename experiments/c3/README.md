# C3 experiments

Scripts that measure the Kubernetes enforcement layer on the running platform (`scripts/deploy.sh`). Results are
written to `results/` as CSV (one row per trial, plus a summary with mean, min, max, sd and p95).

| Script | Experiment | What it measures |
|---|---|---|
| `e3-convergence.sh` | E3: propagation time and delivery success | Time from the approval of a policy version until the last target sidecar has acknowledged it, and whether every target holds the approved content, for 1, 2, 3 ... replicas of a service |
| `e4-consistent-rollout.sh` | E4: consistent vs naive rollout of a joint change (the C3 novelty) | Requests that expire because a joint change (longer chain deadline at the gateway, lower rate at payment-svc) reached the sidecars in the wrong order, and the mixed-policy requests the sidecars count, for two-phase and three naive deliveries |

## How the numbers are produced

- **Targets:** a policy reaches only the sidecars its scope names: the chain deadline the gateway sidecars, a rate
  limit or retry ratio the sidecars of the target service. The gateway is never a target of a service policy.
- **Propagation time:** `policy.deliveries.confirmed_at` (the sidecar's own acknowledgement, state `active`)
  minus `policy.policies.approved_at`, both stamped by PostgreSQL, so no clock needs to agree between machines.
  The time includes staging, the content-hash check and activation (the default two-phase delivery).
- **Delivery success:** the rollout completed and every target sidecar acknowledged the version with the same
  content hash the Operator approved (`confirmed_hash = hash`).
- **Convergence time (`convergence_ms`):** the Operator's own measure, from the rollout start until every target
  enforces the version.

## E3 usage

```bash
experiments/c3/e3-convergence.sh                                      # 5 deliveries at 1, 2 and 3 replicas
RUNS=10 REPLICAS="1 3 5" SERVICE=payment-svc experiments/c3/e3-convergence.sh
MODE=naive experiments/c3/e3-convergence.sh                           # immediate delivery, for comparison
```

The script scales the service to each replica count, waits until every pod holds the current policy, and restores
the original replica count when it ends. The rate-limit values are only delivered and acknowledged; enforcing them
is the sidecar's job (C2).

The same delivery guarantees run as e2e checks in `tests/e2e/92-targeted-delivery.sh` and live in the Admin Panel
(Rollouts, Policy delivery).

## E4 usage (the C3 novelty)

```bash
experiments/c3/e4-consistent-rollout.sh                                  # 5 runs of each delivery
RUNS=10 DELIVERIES="two-phase naive-random" experiments/c3/e4-consistent-rollout.sh
SKEW_MS=2000 RANDOM_SKEW_MS=5000 experiments/c3/e4-consistent-rollout.sh  # other propagation delays
```

Each run is one hub "consistency run" (the same as the Run button in the Admin Panel, Rollouts): it restores the
baseline (chain deadline 1000 ms, payment rate 100 requests/s, delivered two-phase), sends a burst of 8 chain
requests every 4 s, approves the joint change (4000 ms, 6 requests/s) during the traffic and delivers it:

| Delivery | What happens |
|---|---|
| `two-phase` | Stage on both sidecars; the gateway loosens first and stamps the new version; the Operator waits until no request stamped with the old version is in flight; then payment tightens |
| `naive-services-first` | Each sidecar switches on arrival; payment's sidecar gets the change `SKEW_MS` before the gateway's |
| `naive-gateway-first` | The gateway's sidecar gets it `SKEW_MS` before payment's |
| `naive-random` | Each sidecar switches after a random delay of 0 to `RANDOM_SKEW_MS` (the order is left to chance) |

- **Expired (`expired`):** demo requests started after the approval whose response carries `cs=expired` (the chain
  deadline was exceeded; signal, never reject, so the request was still served). `expired_old_deadline` counts
  those that still carried the old 1000 ms deadline: the requests that met the new rate with the old deadline.
- **Mixed-policy requests:** requests a sidecar served under another version of the policy than the epoch they
  carry (`rpe_policy_mismatch_total`, all traffic, relative to the rollout start).
- **Gate (`gate_ms`):** two-phase only, how long payment's tightening waited for the old version to drain.

The rows are interleaved (one run of every delivery, then the next), so every delivery sees the same cluster
conditions. The same checks run as the e2e suite `tests/e2e/96-consistent-rollout.sh`.
