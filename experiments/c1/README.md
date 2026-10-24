# C1 experiments

Scripts that measure the protocol on the running cluster (kind cluster `rpe`, deployed with `make deploy`). Each
script writes a CSV to `results/` and leaves the system as it found it.

| Script | What it measures | Output |
|---|---|---|
| `rt01-rollout.sh [N]` | Rule R-T01 (and R-T02..R-T06, R-F05) on every hop of real chain requests while C3 rolls out a new gateway chain deadline, two-phase and naive, plus a run without a rollout | `results/rt01-<time>.csv`: mode, deadline, chains checked, chains broken, broken rules. Exit code 1 when any chain broke a rule |

The PP2 studies will be added here as scripts in the same form: the wire-format study (E2), trail encodings against
sampled tracing (E6) and clock skew (E7).
