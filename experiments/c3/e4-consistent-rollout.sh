#!/usr/bin/env bash
# Experiment E4 (C3 main novelty): mixed-policy requests during a rollout, immediate (naive) vs two-phase, under
# the same steady traffic and the same propagation spread per proxy. Repeats every configuration and writes
#   experiments/c3/results/e4-<timestamp>.csv      one row per rollout
#   experiments/c3/results/e4-<timestamp>-summary.csv  mean, min, max, sd per mode and spread
# Needs the deployed platform (make deploy). Usage:
#   experiments/c3/e4-consistent-rollout.sh                      # 5 runs, spreads 0 1000 2000 ms
#   RUNS=10 SKEWS="500 2000" CONCURRENCY=12 experiments/c3/e4-consistent-rollout.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "${HERE}/../../tests/e2e/lib.sh"
RUNS=${RUNS:-5}
SKEWS=${SKEWS:-"0 1000 2000"}
MODES=${MODES:-"naive two-phase"}
CONCURRENCY=${CONCURRENCY:-8}
LOAD_SECONDS=${LOAD_SECONDS:-14}
mkdir -p "${HERE}/results"
stamp=$(date +%Y%m%d-%H%M%S)
csv="${HERE}/results/e4-${stamp}.csv"
summary="${HERE}/results/e4-${stamp}-summary.csv"
id="e4-$(date +%s)"
echo "run,mode,skew_ms,target_pods,mixed_policy_requests,convergence_ms,drain_ms,requests_sent,rollout" > "${csv}"

hub_login admin >/dev/null || { echo "cannot log in to the hub" >&2; exit 1; }
version=0
publish() { # MODE SKEW -> rollout name; alternates the policy values so every run is a real change
  version=$((version+1))
  local deadline=$(( version % 2 == 0 ? 800 : 1200 )) budget=$(( version % 2 == 0 ? 3 : 1 ))
  hub POST /api/hub/policies "{\"policyId\":\"${id}\",\"service\":\"payment-svc\",\"route\":\"GET /api/orders\",\"deadlineMs\":${deadline},\"retryBudget\":${budget},\"rollout\":\"$1\",\"skewMs\":$2}"
  echo "${BODY}" | jq -r '.rollout.metadata.name // empty'
}

echo "E4: ${RUNS} runs x spreads [${SKEWS}] ms x modes [${MODES}], ${CONCURRENCY} concurrent requests"
r=$(publish two-phase 0); wait_settled "${r}" >/dev/null   # baseline version
for skew in ${SKEWS}; do
  for run in $(seq 1 "${RUNS}"); do
    for mode in ${MODES}; do
      load_start "${LOAD_SECONDS}" "${CONCURRENCY}"
      sleep 2
      r=$(publish "${mode}" "${skew}")
      phase=$(wait_settled "${r}" 180)
      hub GET "/api/hub/rollouts/${r}"
      row=$(echo "${BODY}" | jq -r '[.status.targetPods, (.status.mixedPolicyRequests // 0), (.status.convergenceMs // 0), (.status.drainMs // 0)] | @csv')
      load_wait
      echo "${run},${mode},${skew},${row},${LOAD_SENT:-0},${r}" >> "${csv}"
      printf '  spread %5s ms  run %2s  %-9s  %-8s mixed=%s\n' "${skew}" "${run}" "${mode}" "${phase}" "$(echo "${row}" | cut -d, -f2)"
    done
  done
done

# summary: mean, min, max, sample sd of mixed-policy requests and convergence per (mode, spread)
awk -F, 'NR > 1 {
  k = $2 "," $3; n[k]++; m[k] += $5; m2[k] += $5 * $5; c[k] += $6; c2[k] += $6 * $6
  if (!(k in mn) || $5 < mn[k]) mn[k] = $5; if (!(k in mx) || $5 > mx[k]) mx[k] = $5
  if (!(k in cmn) || $6 < cmn[k]) cmn[k] = $6; if (!(k in cmx) || $6 > cmx[k]) cmx[k] = $6
}
function sd(s, s2, n) { return n > 1 ? sqrt((s2 - s * s / n) / (n - 1)) : 0 }
END {
  print "mode,skew_ms,runs,mixed_mean,mixed_min,mixed_max,mixed_sd,convergence_ms_mean,convergence_ms_min,convergence_ms_max,convergence_ms_sd"
  for (k in n) printf "%s,%d,%.2f,%d,%d,%.2f,%.1f,%d,%d,%.1f\n", k, n[k], m[k]/n[k], mn[k], mx[k], sd(m[k], m2[k], n[k]), c[k]/n[k], cmn[k], cmx[k], sd(c[k], c2[k], n[k])
}' "${csv}" | (read -r header; echo "${header}"; sort) > "${summary}"
echo
column -s, -t < "${summary}" 2>/dev/null || cat "${summary}"
echo
echo "rows:    ${csv}"
echo "summary: ${summary}"
