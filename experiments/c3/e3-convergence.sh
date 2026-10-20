#!/usr/bin/env bash
# Experiment E3 (C3): policy propagation time and delivery success of a targeted policy delivery.
# For each replica count of the target service, delivers a new rate limit (a new policy version) several times and
# measures, from the policy store:
#   propagation_ms  from the approval (policy.policies.approved_at) until the last target sidecar has acknowledged
#                   the new version itself (policy.deliveries.confirmed_at, state active)
#   success         every target sidecar acknowledged the version with the approved content hash
# Writes experiments/c3/results/e3-<timestamp>.csv (one row per delivery) and e3-<timestamp>-summary.csv
# (mean, min, max, sd, p95 per replica count). Needs the deployed platform (scripts/deploy.sh). Usage:
#   experiments/c3/e3-convergence.sh                              # 5 deliveries at 1, 2 and 3 replicas
#   RUNS=10 REPLICAS="1 3 5" SERVICE=payment-svc experiments/c3/e3-convergence.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "${HERE}/../../tests/e2e/lib.sh"
RUNS=${RUNS:-5}
REPLICAS=${REPLICAS:-"1 2 3"}
SERVICE=${SERVICE:-payment-svc}
MODE=${MODE:-two-phase}
mkdir -p "${HERE}/results"
stamp=$(date +%Y%m%d-%H%M%S)
csv="${HERE}/results/e3-${stamp}.csv"
summary="${HERE}/results/e3-${stamp}-summary.csv"
id="e3-$(date +%s)"
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
psql() { k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -At -c "$1"; }
echo "replicas,run,mode,target_pods,acknowledged_ok,success,propagation_ms,convergence_ms,rollout" > "${csv}"

hub_login admin >/dev/null || { echo "cannot log in to the hub" >&2; exit 1; }
original=$(k -n rpe-demo get deploy "${SERVICE}" -o jsonpath='{.spec.replicas}')
trap 'k -n rpe-demo scale deploy "${SERVICE}" --replicas="${original}" >/dev/null 2>&1' EXIT

# settle N: scale the service and wait until every pod holds the current policy (the Operator repairs new pods)
settle() {
  k -n rpe-demo scale deploy "${SERVICE}" --replicas="$1" >/dev/null
  k -n rpe-demo rollout status deploy "${SERVICE}" --timeout=240s >/dev/null
  for _ in $(seq 1 60); do
    hub GET /api/hub/fleet/audit
    ready=$(echo "${BODY}" | jq --arg s "${SERVICE}" '[.pods[]? | select(.service==$s and .sidecar and .phase=="Running")] | length')
    drifted=$(echo "${BODY}" | jq --arg s "${SERVICE}" '[.pods[]? | select(.service==$s and ((.drift // []) | length > 0))] | length')
    [[ "${ready}" == "$1" && "${drifted}" == 0 ]] && return 0
    sleep 2
  done
  echo "warning: ${SERVICE} did not settle at $1 replicas" >&2
}

rate=100
echo "E3: ${RUNS} deliveries x replicas [${REPLICAS}] of ${SERVICE}, mode ${MODE}"
for n in ${REPLICAS}; do
  settle "${n}"
  for run in $(seq 1 "${RUNS}"); do
    rate=$((rate+10))   # a different value each time, so every delivery is a real change
    hub POST /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"${SERVICE}\",\"ratePerSec\":${rate},\"policyId\":\"${id}\",\"rollout\":\"${MODE}\"}"
    r=$(echo "${BODY}" | jq -r '.rollout.metadata.name // empty'); epoch=$(echo "${BODY}" | jq -r '.epoch // empty')
    phase=$(wait_settled "${r}" 180)
    hub GET "/api/hub/rollouts/${r}"
    pods=$(echo "${BODY}" | jq -r '.status.targetPods // 0'); conv=$(echo "${BODY}" | jq -r '.status.convergenceMs // 0')
    ok=$(psql "select count(*) from policy.deliveries where epoch='${epoch}' and confirmed_state='active' and confirmed_hash=hash")
    prop=$(psql "select coalesce(round(extract(epoch from (max(d.confirmed_at) - p.approved_at)) * 1000), 0) from policy.deliveries d join policy.policies p on p.epoch = d.epoch where d.epoch='${epoch}' and d.confirmed_state='active' group by p.approved_at")
    success=0; [[ "${phase}" == Complete && "${ok}" == "${pods}" && "${pods}" -ge 1 ]] && success=1
    echo "${n},${run},${MODE},${pods},${ok},${success},${prop:-0},${conv},${r}" >> "${csv}"
    printf '  replicas %s  run %2s  %-8s pods=%s acknowledged=%s propagation=%s ms\n' "${n}" "${run}" "${phase}" "${pods}" "${ok}" "${prop:-0}"
  done
done

# summary per replica count: success rate, mean, min, max, sample sd and p95 of the propagation time
# (portable awk: the values are sorted by sort(1), no gawk-only functions)
{
  echo "replicas,deliveries,success_rate,propagation_ms_mean,propagation_ms_min,propagation_ms_max,propagation_ms_sd,propagation_ms_p95"
  for n in ${REPLICAS}; do
    success=$(awk -F, -v n="${n}" '$1 == n { c++; s += $6 } END { if (c) printf "%.2f", s / c }' "${csv}")
    awk -F, -v n="${n}" '$1 == n { print $7 + 0 }' "${csv}" | sort -n | awk -v n="${n}" -v success="${success}" '
      { v[NR] = $1; sum += $1; sum2 += $1 * $1 }
      END {
        if (NR == 0) exit
        mean = sum / NR; sd = NR > 1 ? sqrt((sum2 - sum * sum / NR) / (NR - 1)) : 0
        idx = int(0.95 * NR); if (idx < 0.95 * NR) idx++; if (idx < 1) idx = 1
        printf "%s,%d,%s,%.1f,%d,%d,%.1f,%d\n", n, NR, success, mean, v[1], v[NR], sd, v[idx]
      }'
  done
} > "${summary}"
echo
column -s, -t < "${summary}" 2>/dev/null || cat "${summary}"
echo
echo "rows:    ${csv}"
echo "summary: ${summary}"
