#!/usr/bin/env bash
# The C3 novelty: per-request-consistent rollout of a joint change. One approved decision changes two settings that
# depend on each other: the chain deadline of GET /api/orders at the gateway (1000 -> 4000 ms) and payment-svc's
# rate (100 -> 6 requests/s). At the low rate a burst of chain requests waits in payment's queue for more than a
# second: fine with the new deadline, too long with the old one.
#   - the joint change is one policy version with one epoch; each sidecar receives only the part for its service;
#   - two-phase: the gateway loosens first, the Operator waits until no request of the old version is in flight,
#     then payment tightens; no request expires because of the change and no request is served under another
#     version than it carries;
#   - naive, the service switching first: requests that still carry the old deadline meet the new rate and expire,
#     and the sidecars count mixed-policy requests;
#   - a one-control change to a joint policy keeps its other part;
#   - a joint decision that names other services keeps the parts it does not name;
#   - the first joint decision over separate policies is ordered by what each service enforces (C4's joint-capacity-drop).
source "$(dirname "$0")/lib.sh"
hub_login admin >/dev/null
pol=chain-orders
# leave the baseline in place for the suites that follow
trap 'hub POST /api/hub/policies "{\"policyId\":\"${pol}\",\"rollout\":\"two-phase\",\"changes\":[{\"control\":\"chain-deadline\",\"route\":\"GET /api/orders\",\"deadlineMs\":1000},{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":100}]}" >/dev/null 2>&1' EXIT

# --- 1. consistent (two-phase) --------------------------------------------------------------------------------------
consistency_run '{"mode":"two-phase"}' || bad "the two-phase run did not finish"
assert_eq "$(echo "${RUN}" | jq -r .state)" done "the two-phase run finished ($(echo "${RUN}" | jq -r '.error // "ok"'))"
during=$(echo "${RUN}" | jq -r .during)
assert_ge "${during}" 8 "demo traffic ran while the change was delivered (${during} requests)"
assert_eq "$(echo "${RUN}" | jq -r .expiredBefore)" 0 "no request expired at the baseline"
assert_eq "$(echo "${RUN}" | jq -r .expired)" 0 "two-phase: no request expired because of the change"
assert_eq "$(echo "${RUN}" | jq -r .mixedPolicyRequests)" 0 "two-phase: no request was served under another version than it carries"
assert_eq "$(echo "${RUN}" | jq -r '[.plan[] | select(.service=="gateway")][0] | "\(.kind) \(.step)"')" "loosen 1" "the gateway's longer deadline is a loosening (step 1)"
assert_eq "$(echo "${RUN}" | jq -r '[.plan[] | select(.service=="payment-svc")][0] | "\(.kind) \(.step)"')" "tighten 2" "payment's lower rate is a tightening (step 2)"
assert_eq "$(echo "${RUN}" | jq -r '[.arrivals[] | select(.part=="gateway" or .part=="payment-svc") | .part] | join(",")')" "gateway,payment-svc" "the gateway enforced the new version before payment did"
assert_eq "$(echo "${RUN}" | jq -r .restored)" true "the run restored the baseline afterwards (two-phase)"

# one version, one epoch, a part per service (checked on the restored baseline version)
hub GET /api/hub/policies
base=$(echo "${BODY}" | jq -r --arg p "${pol}" '[.policies[] | select(.policyId==$p)] | max_by(.version) | .epoch')
assert_eq "$(rate_gauge payment-svc)" 100 "the payment sidecar enforces the baseline rate again (100 requests/s)"
client GET "http://$(pod_ip gateway):15020/rpe/v1/policy/active"
assert_eq "$(echo "${BODY}" | jq -r .epoch)" "${base}" "the gateway sidecar enforces the joint version ${base}"
assert_eq "$(echo "${BODY}" | jq -r '.policy.routes["GET /api/orders"].deadlineMs')" 1000 "the gateway's part carries the chain deadline"
assert_eq "$(echo "${BODY}" | jq -r '.policy.leakyBucket.ratePerSec // "none"')" none "the gateway's part does not carry payment's rate"
client GET "http://$(pod_ip payment-svc):15020/rpe/v1/policy/active"
assert_eq "$(echo "${BODY}" | jq -r .epoch)" "${base}" "the payment sidecar enforces the same epoch"
assert_eq "$(echo "${BODY}" | jq -r '.policy.leakyBucket.ratePerSec')" 100 "payment's part carries the rate"
assert_eq "$(echo "${BODY}" | jq -r '.policy.routes // "none"')" none "payment's part does not carry the gateway's route deadlines"

# --- 2. naive, the service switches first -----------------------------------------------------------------------------
consistency_run '{"mode":"naive","order":"services-first","skewMs":4000}' || bad "the naive run did not finish"
assert_eq "$(echo "${RUN}" | jq -r .state)" done "the naive run finished ($(echo "${RUN}" | jq -r '.error // "ok"'))"
assert_ge "$(echo "${RUN}" | jq -r .expired)" 1 "naive: requests expired because they met the new rate with the old deadline ($(echo "${RUN}" | jq -r .expired) of $(echo "${RUN}" | jq -r .during))"
assert_ge "$(echo "${RUN}" | jq -r .expiredOldDeadline)" 1 "naive: the expired requests carried the old deadline"
assert_ge "$(echo "${RUN}" | jq -r .mixedPolicyRequests)" 1 "naive: the sidecars served requests under another version than they carry"
assert_eq "$(echo "${RUN}" | jq -r '[.arrivals[] | select(.part=="gateway" or .part=="payment-svc") | .part] | join(",")')" "payment-svc,gateway" "payment switched before the gateway"

# --- 3. consistent again (the baseline is restored safely in between) -------------------------------------------------
consistency_run '{"mode":"two-phase"}' || bad "the second two-phase run did not finish"
assert_eq "$(echo "${RUN}" | jq -r '"\(.state) \(.expired) \(.mixedPolicyRequests)"')" "done 0 0" "two-phase again: no expired and no mixed-policy request"

# --- 4. a one-control change to the joint policy keeps the other part ---------------------------------------------------
hub POST /api/hub/policies '{"control":"rate-limit","service":"payment-svc","ratePerSec":50,"rollout":"two-phase"}'
assert_eq "$(echo "${BODY}" | jq -r .policyId)" "${pol}" "a rate change for payment-svc is a new version of the joint policy"
single=$(echo "${BODY}" | jq -r '.rollout.metadata.name // empty')
assert_eq "$(wait_settled "${single}" 120)" Complete "it is delivered"
hub GET "/api/hub/rollouts/${single}"
assert_eq "$(echo "${BODY}" | jq -r '[.status.plan[] | select(.service=="gateway")][0].kind')" unchanged "the gateway's part of that version is unchanged"
assert_eq "$(echo "${BODY}" | jq -r '.status.gateStartedAt // "none"')" none "the lower rate was not held back: no wait for the previous version before it"
hub GET /api/hub/policies
latest=$(echo "${BODY}" | jq -c --arg p "${pol}" '[.policies[] | select(.policyId==$p)] | max_by(.version)')
assert_eq "$(echo "${latest}" | jq -r '[.spec.parts[].scope.service] | contains(["gateway","payment-svc"])')" true "the new version still has both parts"
assert_eq "$(echo "${latest}" | jq -r '.spec.parts[] | select(.scope.service=="gateway") | .spec.routes["GET /api/orders"].deadlineMs')" 1000 "the gateway's part is kept unchanged"
assert_eq "$(rate_gauge payment-svc)" 50 "payment enforces the changed rate"

# --- 5. every hop keeps the version the gateway stamped while the gateway switches (C1 rule R-T01) --------------------
hub POST /api/hub/policies "{\"policyId\":\"${pol}\",\"rollout\":\"two-phase\",\"changes\":[{\"control\":\"chain-deadline\",\"route\":\"GET /api/orders\",\"deadlineMs\":1200},{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":100}]}"
broken=0; checked=0
for _ in $(seq 1 30); do
  hub POST /api/hub/chain '{"path":"/api/orders"}'
  [[ "$(echo "${BODY}" | jq '.hops | length')" == 7 ]] || continue
  checked=$((checked+1))
  [[ "$(echo "${BODY}" | jq '[.hops[].checks[] | select(.pass==false)] | length')" == 0 ]] || broken=$((broken+1))
done
assert_ge "${checked}" 20 "chain requests ran while the gateway switched versions (${checked})"
assert_eq "${broken}" 0 "every hop of every chain kept the version the gateway stamped (C1 rule R-T01)"

# --- 6. a joint decision keeps the parts it does not name ---------------------------------------------------------------
# v1 = a route deadline + shipping's rate, then v2 = a longer deadline + fraud-check's rate (shipping not named), then a
# single change of shipping's rate, sent to the policy API the way Component 4 sends them (only the parts that change).
# Its own policy and route, at each service's current rate, so nothing else moves.
jid="e2e-merge-$(date +%s)"; jroute="GET /api/e2e-merge"
ship=$(rate_gauge shipping-svc); fraud=$(rate_gauge fraud-check-svc)
approval='"source":"c4-recommendation","approval":{"approvedBy":"e2e","decision":"approved"}'
deliver() {
  client POST http://rpe-policy-api.rpe-system/api/k8s/policies "$1"
  client POST http://rpe-policy-api.rpe-system/api/k8s/rollouts "{\"policyId\":\"${jid}\",\"version\":$(echo "${BODY}" | jq -r .policy.version),\"mode\":\"two-phase\",\"requestedBy\":\"e2e\"}"
  wait_settled "$(echo "${BODY}" | jq -r '.metadata.name // empty')" 120
}
joint_change() {
  deliver "{\"policyId\":\"${jid}\",${approval},\"parts\":[{\"scope\":{\"service\":\"gateway\",\"route\":\"${jroute}\"},\"spec\":{\"routes\":{\"${jroute}\":{\"deadlineMs\":$1}}}},{\"scope\":{\"service\":\"$2\"},\"spec\":{\"leakyBucket\":{\"ratePerSec\":$3}}}]}"
}
parts_of_latest() {
  hub GET /api/hub/policies
  echo "${BODY}" | jq -c --arg p "${jid}" --arg r "${jroute}" '[.policies[] | select(.policyId==$p)] | max_by(.version) | [.spec.parts[] | "\(.scope.service)=\(.spec.routes[$r].deadlineMs // .spec.leakyBucket.ratePerSec)"]'
}
active_on() { client GET "http://$(pod_ip "$1"):15020/rpe/v1/policy"; echo "${BODY}" | jq -r --arg p "${jid}-" '[.epochs[] | select(.epoch|startswith($p)) | select(.state=="active") | .epoch] | join(",")'; }
assert_eq "$(joint_change 1000 shipping-svc "${ship}")" Complete "v1 (deadline 1000 ms + shipping's rate) is delivered"
assert_eq "$(joint_change 1200 fraud-check-svc "${fraud}")" Complete "v2 (deadline 1200 ms + fraud-check's rate) is delivered"
assert_eq "$(parts_of_latest)" "[\"gateway=1200\",\"shipping-svc=${ship}\",\"fraud-check-svc=${fraud}\"]" "v2 kept shipping's part, which it did not name"
assert_eq "$(active_on gateway)|$(active_on shipping-svc)|$(active_on fraud-check-svc)" "${jid}-v2|${jid}-v2|${jid}-v2" "the gateway, shipping and fraud-check all enforce v2 and nothing older"
assert_eq "$(deliver "{\"policyId\":\"${jid}\",${approval},\"scope\":{\"service\":\"shipping-svc\"},\"spec\":{\"leakyBucket\":{\"ratePerSec\":${ship}}}}")" Complete "v3 (a single recommendation for shipping) is delivered"
assert_eq "$(parts_of_latest)" "[\"gateway=1200\",\"shipping-svc=${ship}\",\"fraud-check-svc=${fraud}\"]" "v3 kept the gateway's 1200 ms and fraud-check's part"
assert_eq "$(active_on gateway)|$(active_on shipping-svc)|$(active_on fraud-check-svc)" "${jid}-v3|${jid}-v3|${jid}-v3" "every part moved to v3"

# --- 7. the first joint decision over separate policies (C4's joint-capacity-drop) ------------------------------------
# The gateway and shipping-svc each have their own policy. C4's joint card is a new version of the gateway's policy:
# a longer route deadline (780 -> 850 ms, looser) and a lower rate for shipping (stricter). Shipping's change must be
# compared with shipping's own policy: the gateway loosens first, the stricter rate waits, and the joint version then
# replaces shipping's own policy. Shipping's rate is set back afterwards.
# C4 can change rates on its own while this runs, so we don't check the live rate here. We check that each rollout
# completed and that every sidecar has the approved policy, using its hash.
gid="e2e-firstgw-$(date +%s)"; sid="e2e-firstship-$(date +%s)"; froute="GET /api/e2e-first"
low=$(( ${ship%.*} * 6 / 10 )); (( low < 1 )) && low=1
deliver_to() {
  client POST http://rpe-policy-api.rpe-system/api/k8s/policies "$2"
  client POST http://rpe-policy-api.rpe-system/api/k8s/rollouts "{\"policyId\":\"$1\",\"version\":$(echo "${BODY}" | jq -r .policy.version),\"mode\":\"two-phase\",\"requestedBy\":\"e2e\"}"
  FIRST_RO=$(echo "${BODY}" | jq -r '.metadata.name // empty'); wait_settled "${FIRST_RO}" 120 >/dev/null
  hub GET "/api/hub/rollouts/${FIRST_RO}"
}
delivered() { echo "${BODY}" | jq -r '"\(.status.phase) \([.status.pods[] | .verified] | all)"'; }
first_joint() {
  deliver_to "${gid}" "{\"policyId\":\"${gid}\",${approval},\"parts\":[{\"scope\":{\"service\":\"shipping-svc\",\"route\":\"\"},\"spec\":{\"leakyBucket\":{\"ratePerSec\":$2}}},{\"scope\":{\"service\":\"gateway\",\"route\":\"${froute}\"},\"spec\":{\"routes\":{\"${froute}\":{\"deadlineMs\":$1}}}}]}"
}
deliver_to "${gid}" "{\"policyId\":\"${gid}\",${approval},\"scope\":{\"service\":\"gateway\",\"route\":\"${froute}\"},\"spec\":{\"routes\":{\"${froute}\":{\"deadlineMs\":780}}}}"
deliver_to "${sid}" "{\"policyId\":\"${sid}\",${approval},\"scope\":{\"service\":\"shipping-svc\",\"route\":\"\"},\"spec\":{\"leakyBucket\":{\"ratePerSec\":${ship}}}}"
assert_eq "$(delivered)" "Complete true" "shipping holds its own policy (${ship}/s) before the joint card, verified by hash"
first_joint 850 "${low}"
assert_eq "$(echo "${BODY}" | jq -r .status.phase)" Complete "the joint card (deadline 780 -> 850 ms + shipping ${ship} -> ${low}/s) is delivered"
assert_eq "$(echo "${BODY}" | jq -r '[.status.plan[] | "\(.service) \(.kind) \(.step)"] | sort | join(",")')" "gateway loosen 1,shipping-svc tighten 2" "shipping's lower rate is a tightening against its own policy (step 2)"
assert_ne "$(echo "${BODY}" | jq -r '.status.gateStartedAt // "none"')" none "the stricter rate waited for the requests of the gateway's previous version"
assert_eq "$(delivered)" "Complete true" "the gateway and shipping hold their parts of the joint card, verified by hash"
hub GET /api/hub/policies
assert_eq "$(echo "${BODY}" | jq -r --arg p "${sid}" '[.policies[] | select(.policyId==$p)][0].status')" superseded "shipping's own policy is replaced by the joint version"
client GET "http://$(pod_ip shipping-svc):15020/rpe/v1/policy"
assert_eq "$(echo "${BODY}" | jq -r --arg e "${sid}-v1" '[.epochs[] | select(.epoch==$e)] | length')" 0 "and removed from shipping's sidecar"
first_joint 850 "${ship}"
assert_eq "$(echo "${BODY}" | jq -r '"\(.status.phase) \([.status.plan[] | select(.service=="shipping-svc")][0].kind)"')" "Complete loosen" "the next version compares with the joint policy itself (shipping ${low} -> ${ship}/s, looser)"
assert_eq "$(delivered)" "Complete true" "shipping's rate is set back to ${ship}/s, verified by hash"
finish
