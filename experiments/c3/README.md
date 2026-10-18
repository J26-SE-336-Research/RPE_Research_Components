# C3 experiments

Scripts that measure the Kubernetes enforcement layer on the running platform (`scripts/deploy.sh`). Results are
written to `results/` as CSV (one row per trial, plus a summary with mean, min, max, sd and p95).

| Script | Experiment | What it measures |
|---|---|---|
| `e3-convergence.sh` | E3: propagation time and delivery success | Time from the approval of a policy version until the last target sidecar has acknowledged it, and whether every target holds the approved content, for 1, 2, 3 ... replicas of a service |

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
