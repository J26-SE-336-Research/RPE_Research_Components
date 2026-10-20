#!/usr/bin/env bash
# C3 targeted delivery: each control is its own policy version and reaches only the sidecars it concerns.
#   - chain deadline -> the gateway sidecars only (the gateway creates every request's budget);
#   - rate limit and retry ratio -> the target service's sidecars only, never the gateway;
#   - every delivery is verified by the content hash the sidecar computed and acknowledged by the sidecar itself;
#   - delivering a service policy leaves the gateway's chain deadline untouched (regression: a service policy
#     activated on the gateway used to reset its route deadlines to the static configuration).
source "$(dirname "$0")/lib.sh"
hub_login admin >/dev/null
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
psql() { k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -At -c "$1"; }
stamp=$(date +%s)
gw_id="e2e-chain-${stamp}"; pay_id="e2e-svc-${stamp}"

# deliver CONTROL-JSON -> rollout name (BODY holds the hub answer)
deliver() {
  hub POST /api/hub/policies "$1"
  echo "${BODY}" | jq -r '.rollout.metadata.name // empty'
}
roles_of() { hub GET "/api/hub/rollouts/$1"; echo "${BODY}" | jq -r '[.status.pods[].role] | unique | join(",")'; }
acked_with_hash() { # EPOCH -> proxies whose own acknowledgement carries the approved hash
  psql "select count(*) from policy.deliveries where epoch='$1' and confirmed_state='active' and confirmed_hash=hash"
}

# --- 1. chain deadline -> gateway only -----------------------------------------------------------------------
r=$(deliver "{\"control\":\"chain-deadline\",\"route\":\"GET /api/orders\",\"deadlineMs\":1100,\"policyId\":\"${gw_id}\",\"rollout\":\"two-phase\"}")
assert_ne "${r}" "" "chain deadline: rollout started"
assert_eq "$(wait_settled "${r}")" Complete "chain deadline ${gw_id}-v1 is delivered"
assert_eq "$(roles_of "${r}")" gateway "chain deadline: the targets are gateway sidecars only"
assert_ge "$(proxy_holds gateway "${gw_id}-v1" active)" 1 "the gateway sidecar enforces the chain deadline"
assert_eq "$(proxy_holds payment-svc "${gw_id}-v1" active)" 0 "the chain deadline is not on payment-svc"
assert_eq "$(proxy_holds order-svc "${gw_id}-v1" active)" 0 "the chain deadline is not on order-svc"
gateway_policy
assert_eq "${GW_ORDERS_BUDGET}" 1100 "the gateway stamps the delivered budget for GET /api/orders"
hub GET "/api/hub/rollouts/${r}"
assert_eq "$(echo "${BODY}" | jq '[.status.pods[] | select(.verified)] | length')" "$(echo "${BODY}" | jq -r .status.targetPods)" "chain deadline: every target holds the approved content (hash verified)"
for _ in $(seq 1 15); do [[ "$(acked_with_hash "${gw_id}-v1")" -ge 1 ]] && break; sleep 1; done
assert_ge "$(acked_with_hash "${gw_id}-v1")" 1 "chain deadline: the gateway sidecar acknowledged it with the same hash"

# --- 2. rate limit -> the target service only -------------------------------------------------------------------
r=$(deliver "{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":120,\"policyId\":\"${pay_id}\",\"rollout\":\"two-phase\"}")
assert_eq "$(wait_settled "${r}")" Complete "rate limit ${pay_id}-v1 is delivered"
assert_eq "$(roles_of "${r}")" service "rate limit: the targets are service sidecars only"
assert_ge "$(proxy_holds payment-svc "${pay_id}-v1" active)" 1 "payment-svc sidecars hold the rate limit"
assert_eq "$(proxy_holds gateway "${pay_id}-v1" active)" 0 "the rate limit is not on the gateway"
assert_eq "$(proxy_holds gateway "${pay_id}-v1" staged)" 0 "the rate limit was never staged on the gateway"
assert_eq "$(proxy_holds inventory-svc "${pay_id}-v1" active)" 0 "the rate limit is not on other services"
for _ in $(seq 1 15); do [[ "$(acked_with_hash "${pay_id}-v1")" -ge 1 ]] && break; sleep 1; done
assert_ge "$(acked_with_hash "${pay_id}-v1")" 1 "rate limit: the payment sidecar acknowledged it with the same hash"

# --- 3. the gateway's chain deadline is untouched by the service rollout -----------------------------------------
gateway_policy
assert_eq "${GW_EPOCH}" "${gw_id}-v1" "the gateway still enforces its own policy after a payment-svc rollout"
assert_eq "${GW_ORDERS_BUDGET}" 1100 "the chain deadline survives a rate-limit rollout"

# --- 4. retry ratio -> the target service only, keeping the rate limit -------------------------------------------
r=$(deliver "{\"control\":\"retry-ratio\",\"service\":\"payment-svc\",\"retryRatio\":0.2,\"policyId\":\"${pay_id}\",\"rollout\":\"two-phase\"}")
assert_eq "$(wait_settled "${r}")" Complete "retry ratio ${pay_id}-v2 is delivered"
assert_eq "$(roles_of "${r}")" service "retry ratio: the targets are service sidecars only"
hub GET /api/hub/policies
spec=$(echo "${BODY}" | jq -c --arg e "${pay_id}-v2" '.policies[] | select(.epoch==$e) | .spec')
assert_eq "$(echo "${spec}" | jq -r '.retry.budgetRatio')" 0.2 "the new version carries the retry ratio"
assert_eq "$(echo "${spec}" | jq -r '.leakyBucket.ratePerSec')" 120 "the service's other control (rate limit) is kept"
assert_eq "$(proxy_holds payment-svc "${pay_id}-v1" active)" 0 "the replaced version is retired from the target sidecars"
assert_eq "$(proxy_holds gateway "${pay_id}-v2" active)" 0 "the retry ratio is not on the gateway"
gateway_policy
assert_eq "${GW_ORDERS_BUDGET}" 1100 "the chain deadline survives a retry-ratio rollout"

# --- 5. a new chain deadline version replaces the old one on the gateway only -----------------------------------
r=$(deliver "{\"control\":\"chain-deadline\",\"route\":\"GET /api/orders\",\"deadlineMs\":1000,\"policyId\":\"${gw_id}\",\"rollout\":\"two-phase\"}")
assert_eq "$(wait_settled "${r}")" Complete "chain deadline ${gw_id}-v2 is delivered"
gateway_policy
assert_eq "${GW_EPOCH}" "${gw_id}-v2" "the gateway enforces the new version"
assert_eq "${GW_ORDERS_BUDGET}" 1000 "the gateway stamps the new budget (back to the default)"
assert_eq "$(proxy_holds gateway "${gw_id}-v1" active)" 0 "the replaced chain deadline is retired from the gateway"
assert_ge "$(proxy_holds payment-svc "${pay_id}-v2" active)" 1 "payment-svc keeps its own policy"

# --- 6. misdirected controls are refused ---------------------------------------------------------------------------
hub POST /api/hub/policies '{"control":"chain-deadline","service":"payment-svc","route":"GET /api/orders","deadlineMs":900}'
assert_eq "${CODE}" 400 "a chain deadline cannot target a service"
hub POST /api/hub/policies '{"control":"rate-limit","service":"gateway","ratePerSec":50}'
assert_eq "${CODE}" 400 "a rate limit cannot target the gateway"
hub POST /api/hub/policies '{"control":"retry-ratio","service":"payment-svc","retryRatio":3}'
assert_eq "${CODE}" 400 "a retry ratio must be between 0 and 1"
finish
