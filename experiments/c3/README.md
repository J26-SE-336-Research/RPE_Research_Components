# C3 experiments

Scripts that measure the Kubernetes enforcement layer on the running platform (`make deploy`). Results are
written to `results/` as CSV (one row per trial, plus a summary with mean, min, max and sd).

| Script | Experiment | What it measures |
|---|---|---|
| `e4-consistent-rollout.sh` | E4: consistent rollout (main novelty) | Mixed-policy requests, convergence time and drain time of immediate (naive) vs two-phase rollouts, under the same traffic and the same propagation spread per proxy |

## How the numbers are produced

- **Mixed-policy request:** a request that a proxy served under a different version of the policy than the
  epoch the request carries (`pe` in `X-RPE-Context`). Each proxy counts these (`rpe_policy_mismatch_total`,
  and `mismatched` in `GET /rpe/v1/policy`). The Operator subtracts the count at the rollout start and reads it
  again after the rollout settles (`SETTLE_MS`), then stores it in `RPERollout.status.mixedPolicyRequests` and
  in `policy.rollouts.mixed_policy_requests`.
- **Convergence time:** from the rollout start until every target proxy enforces the new version, verified by
  the content hash the proxy computed (`status.convergenceMs`).
- **Drain time (two-phase only):** from activation until no target proxy has a request pinned to the previous
  epoch in flight (`status.drainMs`), read from the per-epoch in-flight counts the proxies report.
- **Propagation spread (`skewMs`):** every delivery to a proxy starts after a random delay from 0 to `skewMs`.
  This models a real fleet, where updates reach proxies at different times. It applies equally to both
  strategies, so they are compared under the same delivery timing. Report the spread with every result.

## E4 usage

```bash
experiments/c3/e4-consistent-rollout.sh                          # 5 runs, spreads 0 / 1000 / 2000 ms
RUNS=10 SKEWS="500 2000" CONCURRENCY=12 experiments/c3/e4-consistent-rollout.sh
```

The same comparison runs as an e2e check in `tests/e2e/92-consistent-rollout.sh`, and live in the Admin Panel
(Rollouts, Consistency check).
