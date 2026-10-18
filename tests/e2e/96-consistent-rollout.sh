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
#   - a one-control change to a joint policy keeps its other part.
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
assert_eq "$(echo "${RUN}" | jq -r '.arrivals | map(.part) | join(",")')" "gateway,payment-svc" "the gateway enforced the new version before payment did"
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
assert_eq "$(echo "${RUN}" | jq -r '.arrivals | map(.part) | join(",")')" "payment-svc,gateway" "payment switched before the gateway"

# --- 3. consistent again (the baseline is restored safely in between) -------------------------------------------------
consistency_run '{"mode":"two-phase"}' || bad "the second two-phase run did not finish"
assert_eq "$(echo "${RUN}" | jq -r '"\(.state) \(.expired) \(.mixedPolicyRequests)"')" "done 0 0" "two-phase again: no expired and no mixed-policy request"

# --- 4. a one-control change to the joint policy keeps the other part ---------------------------------------------------
hub POST /api/hub/policies '{"control":"rate-limit","service":"payment-svc","ratePerSec":50,"rollout":"two-phase"}'
assert_eq "$(echo "${BODY}" | jq -r .policyId)" "${pol}" "a rate change for payment-svc is a new version of the joint policy"
assert_eq "$(wait_settled "$(echo "${BODY}" | jq -r '.rollout.metadata.name // empty')" 120)" Complete "it is delivered"
hub GET /api/hub/policies
latest=$(echo "${BODY}" | jq -c --arg p "${pol}" '[.policies[] | select(.policyId==$p)] | max_by(.version)')
assert_eq "$(echo "${latest}" | jq -r '.spec.parts | length')" 2 "the new version still has both parts"
assert_eq "$(echo "${latest}" | jq -r '.spec.parts[] | select(.scope.service=="gateway") | .spec.routes["GET /api/orders"].deadlineMs')" 1000 "the gateway's part is kept unchanged"
assert_eq "$(rate_gauge payment-svc)" 50 "payment enforces the changed rate"
finish
