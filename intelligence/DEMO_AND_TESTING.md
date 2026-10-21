# C4 demo and testing guide

How to test and show the C4 recommendations (rate v2, retry v2, deadline v2) and the joint card. Branch
`c4/joint-recalibration`.

## 1. Run the tests

```bash
cd intelligence
JAVA_HOME="C:\Program Files\Java\jdk-22" ./mvnw -q test     # Git Bash: export JAVA_HOME="/c/Program Files/Java/jdk-22"
```

All tests run without a cluster. The ones that show the recommendations:

| Test class | What it shows |
|---|---|
| `C4ScenarioTests` | every demo scenario through the same path as `POST /demo/{scenario}`, default mode and `?mode=auto` |
| `DemoModeEndpointTests` | `?mode=auto` over HTTP: `c4-auto`, default unchanged, a bad mode → 400, nothing in the outbox |
| `BucketV2Tests`, `RetryV2Tests`, `AutoApprovalTests`, `DeadlineSettledLockTests` | rate v2, retry v2, automatic approval with its guard, undo and pause, the settled-deadline lock (e2e 95 gap) |
| `DeadlineExamplesTests`, `DeadlineScenarioEndpointTests` | deadline v2 cases A–D, holds and pauses |
| `JointEngineTests`, `JointScenarioTests`, `JointBridgeTests`, `JointAfterAppliedTests`, `JointE8Tests` | the joint card, the priority rules, delivery with `parts`, RECALIBRATE, experiment E8 |

## 2. Run the demo script (all scenarios, readable)

`intelligence/scripts/demo-c4.sh` (Git Bash, needs `curl` and `jq`) or `intelligence/scripts/demo-c4.ps1` (Windows
PowerShell). It calls C4's own demo endpoint for every scenario and prints, per recommendation:
`recommendation  current -> proposed  approved by … / waiting for a person  reason`. The rate and retry scenarios run twice:
as a reviewer sees them, and with `?mode=auto`. Everything runs in an isolated engine inside C4; nothing is sent to
Component 3 and nothing in the cluster changes.

```bash
intelligence/scripts/demo-c4.sh --port-forward              # the kind cluster's C4 (kubectl port-forward svc/rpe-intelligence)
intelligence/scripts/demo-c4.sh                             # C4 at http://localhost:8090
intelligence/scripts/demo-c4.sh --only rate-real-overload   # one scenario (both modes)
```
```powershell
powershell -ExecutionPolicy Bypass -File intelligence\scripts\demo-c4.ps1 -PortForward
powershell -ExecutionPolicy Bypass -File intelligence\scripts\demo-c4.ps1 -Only joint-recovery
```

Local C4 with the cluster's settings (without the cluster): from `intelligence/`, build with `./mvnw -q -DskipTests package`
and start `java -jar target/intelligence-layer-0.0.1-SNAPSHOT.jar` with `PROMETHEUS_COLLECTION_ENABLED=false`,
`RRPE_MOCK_ENABLED=true` and the `RRPE_ADAPTIVE_*` values of `deploy/k8s/platform/intelligence.yaml`.

## 3. Run the live check (read only)

`intelligence/scripts/live-check-c4.sh --port-forward` (or `live-check-c4.ps1 -PortForward`) only reads:
- `GET /api/v2/intelligence/connections`: Prometheus and Tempo should be `LIVE_DATA`;
- `GET /api/v2/intelligence`: health label per service and chain, the rate v2 diagnosis per service, the deadline v2 state
  per chain, current recommendations with "approved by c4-auto / <reviewer>" or "waiting for a person", joint cards;
- `GET /api/v2/intelligence/audit?limit=20`: the last audit entries (persisted in PostgreSQL in the cluster).

A freshly started C4 needs about 5–7 minutes to learn its baselines before health labels appear.

## 4. Expected result of every scenario

"Cluster" = the demo cluster's settings (`intelligence.yaml`: window 1 min, cycle 15 s, 5 baseline samples, cooldown 1 min,
rate stabilisation 1 min, max rate 500, allow-shed-windows), as printed by the demo script; "defaults" = the code's default
settings (where different). Approvals: **waiting for a person** unless stated; the deadline v2 example scenarios and the rate
scenarios use a **simulated reviewer** that approves every step; with `?mode=auto` the rate and retry changes are approved by
**c4-auto** (or undone by **c4-auto-revert**). Deadline and joint cards are never automatic.

| Scenario | Expected result (cluster) |
|---|---|
| `healthy` | rate 120 → 132 (HEALTHY: headroom over demand) |
| `stressed` | rate 120 → 131.3 |
| `overloaded` | rate 120 → 64 (APP_OVERLOADED), retry 0.05 → 0, deadline 600 → 460 ms (Case B; defaults: 600 → 510, no retry change) |
| `retry-stressed` | retry 0.05 → 0.02 (retries rarely succeed), rate 120 → 132 (defaults: rate only) |
| `recovery` | rate 60 → 66, retry 0.02 → 0.04 (defaults: rate only) |
| `deadline-increase` | deadline 1000 → 1500 ms (Case A) |
| `deadline-recovery` | deadline 1440 → 1160 ms (Case D) |
| `multiple-chains` | 1000 → 1500 (A), 1200 → 960 (D), 600 → 490 ms (B) |
| `deadline-too-long-under-overload` | only lowers: 2000 → 1600 → 1200 → 900 → 680 → 550 ms (simulated reviewer) |
| `deadline-too-short-day-one` | 300 → 450 → 680 ms (Case A, simulated reviewer) |
| `services-slowing`, `mixed-paths` | no change (HOLD `STRESS_GATE`) |
| `queue-rate-not-deadline` | no deadline change; lever RATE (the queue rate is the problem) |
| `rejecting-middleware-504` | 1000 → 800 ms, then the chain is PAUSED |
| `rate-too-low` | only raises: 100 → 109.2 → 163.8 → … → 204.8 req/s, nothing shed; `?mode=auto`: the same steps by c4-auto |
| `rate-too-low-day-one` | raises from the start: 100 → 150 → … → 204.8; `?mode=auto`: by c4-auto |
| `rate-real-overload` | reviewer: cut to 55, retry 0.05 → 0, probes up and down, settles at 54.2, never below the floor 50, rises to 117 after recovery. `?mode=auto`: a healthy raise 100 → 110 is undone (c4-auto-revert), then stepped cuts **100 → 80 → 64 → 51.2**, settles at **57.6**, retry → 0 by c4-auto, never below 50, rises to 117 after recovery |
| `rate-recovery` | raises back: 60 → 66 → 99 → … → 119.3, never a cut (both modes) |
| `rate-short-burst` | no change (both modes) |
| `rate-bursts` | 120 → 157.5 → 169.5, never a cut (both modes) |
| `retry-storm-rate` | raises 100 → … → 177.5, the retry budget is held at 0.05 (both modes) |
| `joint-recovery` | one joint card: payment 50 → 100 req/s **and** deadline 875 → 780 ms, waiting for a person (both modes) |
| `joint-capacity-drop` | one joint card: payment 100 → 60 req/s **and** deadline 780 → 850 ms, waiting for a person (both modes) |

With the code defaults `rate-real-overload` settles at 54.7 (reviewer) and the automatic steps are the same
(100 → 80 → 64 → 51.2 → 57.6); `rate-too-low` starts 100 → 150 instead of 100 → 109.2 (no healthy raise while learning).

Priority (joint): when a normal recommendation is needed (overload, RATE_TOO_LOW, a retry change, a deadline case), no joint
card appears and a waiting one is withdrawn (`C4ScenarioTests`, `JointEngineTests`).

Example output (cluster settings, `demo-c4.sh --only rate-real-overload`, `?mode=auto` part):
```
== rate-real-overload ?mode=auto
    rate 100 -> 110 req/s   approved by c4-auto   [healthy (learn), demand 90]   HEALTHY: move toward max demand ...
    retry ratio 0.05 -> 0   approved by c4-auto   [app capacity 60, demand 90]   APP_OVERLOADED: disable additional retries; ...
    rate 110 -> 100 req/s   approved by c4-auto-revert   [app capacity 60, demand 90]   Automatic undo: the automatic raise 100 → 110 ...
    rate 100 -> 80 req/s   approved by c4-auto   [app capacity 60, demand 90]   APP_OVERLOADED: cut max(floor 50, ...); automatic mode: a checked step...
    rate 80 -> 64 req/s   approved by c4-auto   [app capacity 60, demand 90]   APP_OVERLOADED: cut ...
    rate 64 -> 51.2 req/s   approved by c4-auto   [app capacity 60, demand 90]   APP_OVERLOADED: cut ...
    rate 51.2 -> 57.6 req/s   approved by c4-auto   [app capacity 60, demand 90]   RATE_TOO_LOW: the bucket is the bottleneck; ...
    rate 57.6 -> 86.4 req/s   approved by c4-auto   [app recovered (capacity 200)]   RATE_TOO_LOW: ...
    rate 86.4 -> 112.5 req/s   approved by c4-auto   [app recovered (capacity 200)]   RATE_TOO_LOW: ...
    rate 112.5 -> 117 req/s   approved by c4-auto   [app recovered (capacity 200)]   HEALTHY: ...
    final rate 117 req/s, lowest 51.2, retry ratio 0, shed at the end 0 req/s
```

## 5. How the Admin Panel runs a scenario (hub → C4)

- **Console** (`console/src/pages/Recommendations.tsx`, tab **Simulation**): a drop-down `SCENARIOS` and an **Analyse**
  button that calls `POST /api/hub/intel/demo/{scenario}`.
- **Hub** (`hub/features.go`, `intelDemo`): accepts only the names in `intelScenarios` (404 "unknown scenario" otherwise) and
  forwards to C4 `POST /api/v2/intelligence/demo/{scenario}`, relaying the JSON unchanged (20 s timeout). It does not
  forward query strings, so `?mode=auto` is only available by calling C4 directly (the demo script does).
- **Result:** shown only in the Simulation tab as a table (target, setting, current, proposed, reason); simulated
  recommendations never appear in the Queue, Reviewed or History tabs and are never delivered. Real cards (Approve /
  Reject) come only from the live engine; a live joint card appears there as two linked cards ("Joint recommendation …"),
  and approving either decides both.

## 6. Note for the hub and console owners

To click the new scenarios and see who approved and the joint card in the Admin Panel:

**Hub** (`hub/features.go`, `intelScenarios`): add `deadline-too-long-under-overload`, `deadline-too-short-day-one`,
`services-slowing`, `queue-rate-not-deadline`, `mixed-paths`, `rejecting-middleware-504`, `rate-too-low`,
`rate-too-low-day-one`, `rate-real-overload`, `rate-recovery`, `rate-short-burst`, `rate-bursts`, `retry-storm-rate`,
`joint-recovery`, `joint-capacity-drop`. Nothing else changes in the hub.

**Console** (`console/src/pages/Recommendations.tsx`): add the same names to `SCENARIOS`; in the Simulation table show the
decision and reviewer of each change ("approved by …" / "waiting for a person"); group the parts of a joint card using the
response's `joints` field (`jointId`, `parts[].proposalId`). The live status (`GET /api/hub/intel` → `status.joints`) has the
same shape, so the Queue can later show one combined card.
