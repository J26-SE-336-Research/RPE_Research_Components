#!/usr/bin/env bash
# PostgreSQL is the system of record; Redis is a fast path that can be flushed at any time:
#   - the effective policies (GET /api/k8s/effective, read by the hub and by C4 to build the next version) are
#     rebuilt from PostgreSQL after a flush;
#   - the Operator's catch-up position lives in PostgreSQL, so losing Redis never replays old approvals;
#   - a change made after a flush still builds on the service's current policy (its other settings are kept).
source "$(dirname "$0")/lib.sh"
hub_login admin >/dev/null
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
psql() { k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -At -c "$1"; }
redis() { k -n rpe-system exec deploy/rpe-redis -- redis-cli "$@" 2>/dev/null | tr -d '\r'; }
stamp=$(date +%s); pay_id="e2e-cache-pay-${stamp}"; gw_id="e2e-cache-gw-${stamp}"
trap 'hub POST /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":100,\"policyId\":\"${pay_id}\",\"rollout\":\"naive\"}" >/dev/null 2>&1' EXIT

# deliver BODY -> final phase of the rollout it starts
deliver() {
  hub POST /api/hub/policies "$1"
  wait_settled "$(echo "${BODY}" | jq -r '.rollout.metadata.name // empty')" 120
}
effective_epoch() { hub GET /api/hub/effective; echo "${BODY}" | jq -r --arg s "$1" '.[$s].epoch // empty'; }
# wait_effective SERVICE EPOCH -> succeeds once the effective policy of SERVICE is EPOCH (up to 60 s)
wait_effective() { for _ in $(seq 1 60); do [[ "$(effective_epoch "$1")" == "$2" ]] && return 0; sleep 1; done; return 1; }

# --- 1. two services with an effective policy -------------------------------------------------------------------------
assert_eq "$(deliver "{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":110,\"policyId\":\"${pay_id}\",\"rollout\":\"two-phase\"}")" Complete "rate limit ${pay_id}-v1 is delivered"
assert_eq "$(deliver "{\"control\":\"chain-deadline\",\"route\":\"GET /api/orders\",\"deadlineMs\":300,\"policyId\":\"${gw_id}\",\"rollout\":\"two-phase\"}")" Complete "chain deadline ${gw_id}-v1 is delivered"
pay_epoch=$(effective_epoch payment-svc); gw_epoch=$(effective_epoch gateway)
assert_eq "${pay_epoch}" "${pay_id}-v1" "Redis knows the effective policy of payment-svc"
assert_eq "${gw_epoch}" "${gw_id}-v1" "Redis knows the effective policy of the gateway"

# --- 2. flush Redis: the effective policies come back from PostgreSQL ---------------------------------------------------
redis flushall >/dev/null
assert_eq "$(redis dbsize)" 0 "Redis is empty after the flush"
if wait_effective payment-svc "${pay_epoch}" && wait_effective gateway "${gw_epoch}"; then ok "both effective policies are rebuilt from PostgreSQL"; else bad "the effective policies did not come back (payment-svc '$(effective_epoch payment-svc)', gateway '$(effective_epoch gateway)')"; fi
hub GET /api/hub/effective
assert_eq "$(echo "${BODY}" | jq -r '.["payment-svc"].spec | fromjson | .leakyBucket.ratePerSec')" 110 "the rebuilt entry carries the full policy (rate limit 110)"
client GET http://rpe-policy-api.rpe-system/api/k8s/events
assert_ge "$(echo "${BODY}" | jq '[.[] | select(.type=="effective-restored")] | length')" 1 "the rebuild is recorded as an event"

# --- 3. the catch-up position is in PostgreSQL, so an empty Redis replays nothing -----------------------------------------
latest=$(psql "select coalesce(max(id),0) from policy.policy_events where kind='approved'")
assert_eq "$(psql "select value from policy.operator_state where key='last-event-id'")" "${latest}" "the Operator's checkpoint is stored in PostgreSQL and is up to date"
redis flushall >/dev/null
k -n rpe-system rollout restart deploy/rpe-operator >/dev/null
k -n rpe-system rollout status deploy/rpe-operator --timeout=180s >/dev/null
for _ in $(seq 1 40); do client GET http://rpe-policy-api.rpe-system/healthz; [[ "${CODE}" == 200 ]] && break; sleep 1; done
sleep 12   # catch-up runs when the Operator connects; give it and the rebuild a few intervals
client GET http://rpe-policy-api.rpe-system/api/k8s/events
assert_eq "$(echo "${BODY}" | jq '[.[] | select(.type=="policy-approved")] | length')" 0 "no old approval is replayed after the flush and the restart"
assert_eq "$(psql "select value from policy.operator_state where key='last-event-id'")" "${latest}" "the checkpoint did not move"
assert_eq "$(effective_epoch payment-svc)" "${pay_epoch}" "the effective policy is back after the restart too"

# --- 4. a change after a flush still builds on the current policy -----------------------------------------------------------
redis flushall >/dev/null
wait_effective payment-svc "${pay_epoch}" >/dev/null
hub POST /api/hub/policies '{"control":"retry-ratio","service":"payment-svc","retryRatio":0.2,"rollout":"two-phase"}'
assert_eq "$(echo "${BODY}" | jq -r .epoch)" "${pay_id}-v2" "the next version extends the service's own policy (${pay_id}), not a new empty one"
assert_eq "$(wait_settled "$(echo "${BODY}" | jq -r '.rollout.metadata.name // empty')" 120)" Complete "retry ratio ${pay_id}-v2 is delivered"
hub GET /api/hub/policies
spec=$(echo "${BODY}" | jq -c --arg e "${pay_id}-v2" '.policies[] | select(.epoch==$e) | .spec')
assert_eq "$(echo "${spec}" | jq -r '.retry.budgetRatio')" 0.2 "the new version carries the retry ratio"
assert_eq "$(echo "${spec}" | jq -r '.leakyBucket.ratePerSec')" 110 "and keeps the rate limit that was in force before the flush"

# --- 5. leave the default rate for the other suites ------------------------------------------------------------------------------
assert_eq "$(deliver "{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":100,\"policyId\":\"${pay_id}\",\"rollout\":\"two-phase\"}")" Complete "the default rate (100 requests/s) is delivered again"
trap - EXIT
finish
