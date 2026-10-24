#!/usr/bin/env bash
# Do chains stay consistent while C3 rolls out a new gateway deadline? We send chain requests during a two-phase
# rollout and a naive one, and the hub checks every hop against the C1 context rules. A chain is broken if a hop
# saw a different policy epoch, a longer deadline or a bigger retry budget than its caller. The new deadlines are
# new versions of the gateway's current policy, and the old deadline is put back at the end.
#   experiments/c1/rt01-rollout.sh [CHAINS_PER_ROLLOUT]      default 30, results go to experiments/c1/results/
source "$(dirname "$0")/../../tests/e2e/lib.sh"
N=${1:-30}
OUT="$(cd "$(dirname "$0")" && pwd)/results"
mkdir -p "${OUT}"
CSV="${OUT}/rt01-$(date +%Y%m%d-%H%M%S).csv"
hub_login admin >/dev/null
gateway_policy
BASE=${GW_ORDERS_BUDGET}

deadline_change() { # MODE DEADLINE -> starts the rollout, sets RO to its name
  hub POST /api/hub/policies "{\"control\":\"chain-deadline\",\"service\":\"gateway\",\"route\":\"GET /api/orders\",\"deadlineMs\":$2,\"rollout\":\"$1\"}"
  RO=$(echo "${BODY}" | jq -r '.rollout.metadata.name // empty')
}
restore() {
  deadline_change two-phase "${BASE}"
  [[ -n "${RO}" ]] && wait_settled "${RO}" 120 >/dev/null
  echo "restored the gateway deadline to ${BASE} ms"
}
trap restore EXIT

# chains MODE DEADLINE sends N chain requests and adds a row to the CSV
TOTAL_BROKEN=0
chains() {
  local checked=0 broken=0 rules="" failed
  for _ in $(seq 1 "${N}"); do
    hub POST /api/hub/chain '{"path":"/api/orders"}'
    [[ "$(echo "${BODY}" | jq '.hops | length' 2>/dev/null)" -ge 2 ]] 2>/dev/null || continue
    checked=$((checked+1))
    failed=$(echo "${BODY}" | jq -r '[.hops[].checks[] | select(.pass==false) | .rule] | unique | join(" ")')
    if [[ -n "${failed}" ]]; then broken=$((broken+1)); rules="${rules} ${failed}"; fi
  done
  rules=$(echo "${rules}" | tr ' ' '\n' | grep -v '^$' | sort -u | tr '\n' ' ')
  TOTAL_BROKEN=$((TOTAL_BROKEN+broken))
  echo "$1,$2,${checked},${broken},${rules}" >> "${CSV}"
  printf '  %-10s deadline %5s ms: %3d chains checked, %d broken %s\n' "$1" "$2" "${checked}" "${broken}" "${rules:+(rules: ${rules})}"
}

echo "mode,deadline_ms,chains_checked,chains_broken,rules_broken" > "${CSV}"
echo "R-T01 during rollouts (starting deadline ${BASE} ms, ${N} chains per rollout)"
for mode in two-phase naive; do
  for d in $((BASE+150)) $((BASE+250)); do
    deadline_change "${mode}" "${d}"
    chains "${mode}" "${d}"
    [[ -n "${RO}" ]] && wait_settled "${RO}" 120 >/dev/null # the next rollout starts after this one settled
  done
done
chains none "${BASE}"
echo "results: ${CSV}"
[[ ${TOTAL_BROKEN} -eq 0 ]]
