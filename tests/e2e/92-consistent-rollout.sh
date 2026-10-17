#!/usr/bin/env bash
# C3 main novelty: per-request policy consistency during a rollout. Under steady traffic through the gateway,
# an immediate (naive) rollout lets requests be served by two versions of the policy along their chain
# (mixed-policy requests > 0); the two-phase rollout (stage everywhere, flip at the gateway, drain the previous
# epoch) produces none. Both rollouts get the same propagation spread per proxy. Every count comes from the
# proxies' own counters (rpe_policy_mismatch_total), read by the Operator relative to the rollout start.
source "$(dirname "$0")/lib.sh"
hub_login admin >/dev/null
id="e2e-consistency-$(date +%s)"
SKEW=${SKEW_MS:-2000}

# publish VERSION-PARAMS MODE -> rollout name (the new version replaces the active one)
publish() {
  hub POST /api/hub/policies "{\"policyId\":\"${id}\",\"service\":\"payment-svc\",\"route\":\"GET /api/orders\",\"deadlineMs\":$1,\"retryBudget\":$2,\"rollout\":\"$3\",\"skewMs\":${SKEW}}"
  echo "${BODY}" | jq -r '.rollout.metadata.name // empty'
}

# v1 establishes the active version (no traffic needed)
r1=$(publish 800 3 two-phase)
assert_eq "$(wait_settled "${r1}")" Complete "baseline version ${id}-v1 is active"

# v2 immediate, under traffic
load_start 25 8
sleep 2
r2=$(publish 1200 1 naive)
assert_eq "$(wait_settled "${r2}")" Complete "immediate rollout of ${id}-v2 completes"
hub GET "/api/hub/rollouts/${r2}"
naive_mixed=$(echo "${BODY}" | jq -r '.status.mixedPolicyRequests // 0')
assert_ge "${naive_mixed}" 1 "immediate rollout: requests were served by two versions of the policy (${naive_mixed} mixed)"
assert_eq "$(echo "${BODY}" | jq -r '.status.drainMs // 0')" 0 "immediate rollout has no drain"
load_wait

# v3 two-phase, under the same traffic and the same propagation spread
load_start 30 8
sleep 2
r3=$(publish 800 3 two-phase)
assert_eq "$(wait_settled "${r3}")" Complete "two-phase rollout of ${id}-v3 completes"
hub GET "/api/hub/rollouts/${r3}"
assert_eq "$(echo "${BODY}" | jq -r '.status.mixedPolicyRequests // 0')" 0 "two-phase rollout: no request saw two versions of the policy"
assert_eq "$(echo "${BODY}" | jq '[.status.pods[] | select(.verified)] | length')" "$(echo "${BODY}" | jq -r .status.targetPods)" "two-phase: every proxy verified by content hash"
assert_eq "$(echo "${BODY}" | jq -r '.status.stagedAt != null and .status.activatedAt != null')" true "two-phase: staged on every proxy, then activated"
load_wait
assert_ge "${LOAD_SENT:-0}" 50 "traffic flowed through the gateway during the rollouts (${LOAD_SENT:-0} requests)"

# the proxies served requests pinned to both epochs during the two-phase rollout, and v2 is retired everywhere
client GET "http://$(pod_ip payment-svc):15020/rpe/v1/policy"
assert_ge "$(echo "${BODY}" | jq -r --arg e "${id}-v3" '.served[$e] // 0')" 1 "payment proxy served requests pinned to ${id}-v3"
assert_eq "$(echo "${BODY}" | jq -r --arg e "${id}-v2" '[.epochs[] | select(.epoch==$e)] | length')" 0 "the replaced version ${id}-v2 is retired after the drain"
client GET "http://rpe-policy-api.rpe-system:8081/metrics"
assert_contains "${BODY}" 'rpe_mixed_policy_requests_total{mode="naive"}' "Operator exports mixed-policy requests by mode"
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
assert_eq "$(k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -At -c "select mixed_policy_requests from policy.rollouts where name='${r3}'")" 0 "measurements recorded in the policy store (experiment E4)"
echo "  naive: ${naive_mixed} mixed-policy requests; two-phase: 0 (spread ${SKEW} ms)"
finish
