#!/usr/bin/env bash
# Experiment E4 (C3 novelty): consistent vs naive rollout of a joint change.
# One approved decision changes two settings that depend on each other: the chain deadline of GET /api/orders at the
# gateway (1000 -> 4000 ms) and payment-svc's rate (100 -> 6 requests/s). Under the same traffic (a burst of chain
# requests every 4 s, plus the background traffic) the change is delivered, from the baseline each time:
#   two-phase              stage, loosen first (the gateway stamps the new version), wait until no request of the old
#                          version is in flight, then tighten
#   naive-services-first   each sidecar switches on arrival; payment's sidecar gets it SKEW_MS before the gateway's
#   naive-gateway-first    the gateway's sidecar gets it SKEW_MS before payment's
#   naive-random           each sidecar switches after a random delay of 0..RANDOM_SKEW_MS
# Per run it records the demo requests after the approval, those that expired (cs=expired), those of them that still
# carried the old deadline, the mixed-policy requests the sidecars counted, the requests without a 2xx answer and the
# rollout's timings. Writes experiments/c3/results/e4-<timestamp>.csv and e4-<timestamp>-summary.csv.
# Needs the deployed platform (scripts/deploy.sh). Usage:
#   experiments/c3/e4-consistent-rollout.sh                    # 5 runs of each delivery
#   RUNS=10 DELIVERIES="two-phase naive-random" experiments/c3/e4-consistent-rollout.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "${HERE}/../../tests/e2e/lib.sh"
RUNS=${RUNS:-5}
DELIVERIES=${DELIVERIES:-"two-phase naive-services-first naive-gateway-first naive-random"}
SKEW_MS=${SKEW_MS:-4000}
RANDOM_SKEW_MS=${RANDOM_SKEW_MS:-3000}
mkdir -p "${HERE}/results"
stamp=$(date +%Y%m%d-%H%M%S)
csv="${HERE}/results/e4-${stamp}.csv"
summary="${HERE}/results/e4-${stamp}-summary.csv"
echo "delivery,run,requests_during,expired,expired_old_deadline,mixed_policy_requests,without_2xx,gate_ms,convergence_ms,first_switch,rollout" > "${csv}"

hub_login admin >/dev/null || { echo "cannot log in to the hub" >&2; exit 1; }
body() {
  case "$1" in
    two-phase)            echo '{"mode":"two-phase"}' ;;
    naive-services-first) echo "{\"mode\":\"naive\",\"order\":\"services-first\",\"skewMs\":${SKEW_MS}}" ;;
    naive-gateway-first)  echo "{\"mode\":\"naive\",\"order\":\"gateway-first\",\"skewMs\":${SKEW_MS}}" ;;
    naive-random)         echo "{\"mode\":\"naive\",\"skewMs\":${RANDOM_SKEW_MS}}" ;;
    *) echo "unknown delivery $1" >&2; exit 1 ;;
  esac
}

echo "E4: ${RUNS} runs x [${DELIVERIES}]"
for run in $(seq 1 "${RUNS}"); do
  for d in ${DELIVERIES}; do   # interleaved, so every delivery sees the same cluster conditions
    if ! consistency_run "$(body "${d}")" 300 || [[ "$(echo "${RUN}" | jq -r .state)" != done ]]; then
      echo "  ${d} run ${run}: failed ($(echo "${RUN}" | jq -r '.error // "timeout"'))" >&2
      continue
    fi
    echo "${RUN}" | jq -r --arg d "${d}" --arg r "${run}" \
      '[$d, $r, .during, .expired, .expiredOldDeadline, .mixedPolicyRequests, .failed, .gateMs, .convergenceMs, ((.arrivals[0].part) // ""), .rollout] | @csv' \
      | tr -d '"' >> "${csv}"
    printf '  %-22s run %2s  expired %3s of %3s  mixed %3s  without 2xx %s\n' "${d}" "${run}" \
      "$(echo "${RUN}" | jq -r .expired)" "$(echo "${RUN}" | jq -r .during)" "$(echo "${RUN}" | jq -r .mixedPolicyRequests)" "$(echo "${RUN}" | jq -r .failed)"
  done
done

# summary per delivery: runs, share of runs in which requests expired, mean/min/max/sd of expired and mixed requests
{
  echo "delivery,runs,runs_with_expired,expired_mean,expired_min,expired_max,expired_sd,mixed_mean,mixed_min,mixed_max,mixed_sd"
  for d in ${DELIVERIES}; do
    awk -F, -v d="${d}" '
      NR > 1 && $1 == d { n++; e = $4 + 0; m = $6 + 0; if (e > 0) harmed++
        se += e; se2 += e * e; sm += m; sm2 += m * m
        if (n == 1 || e < emin) emin = e; if (n == 1 || e > emax) emax = e
        if (n == 1 || m < mmin) mmin = m; if (n == 1 || m > mmax) mmax = m }
      END {
        if (!n) exit
        sde = n > 1 ? sqrt((se2 - se * se / n) / (n - 1)) : 0; sdm = n > 1 ? sqrt((sm2 - sm * sm / n) / (n - 1)) : 0
        printf "%s,%d,%d,%.1f,%d,%d,%.1f,%.1f,%d,%d,%.1f\n", d, n, harmed, se / n, emin, emax, sde, sm / n, mmin, mmax, sdm
      }' "${csv}"
  done
} > "${summary}"
echo
column -s, -t < "${summary}" 2>/dev/null || cat "${summary}"
echo
echo "rows:    ${csv}"
echo "summary: ${summary}"
