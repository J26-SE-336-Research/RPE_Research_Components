#!/usr/bin/env bash
# C3: policy API + Operator. Two-phase and immediate rollouts to the running proxies, supersede/restore,
# Redis fast path, RPEPolicy mirror, deliveries in PostgreSQL verified by content hash and acknowledged by the
# proxies, drain of the previous epoch, and drift repair after a pod restart.
source "$(dirname "$0")/lib.sh"
for crd in rpepolicies rperollouts; do
  assert_eq "$(k get crd "${crd}.resilience.rpe.dev" -o jsonpath='{.spec.group}' 2>/dev/null)" resilience.rpe.dev "CRD ${crd} installed"
done
for d in rpe-operator rpe-redis; do
  assert_eq "$(k -n rpe-system get deploy "$d" -o jsonpath='{.status.availableReplicas}')" 1 "rpe-system/$d available"
done
client POST http://rpe-policy-api.rpe-system/api/k8s/policies '{"policyId":"e2e-unapproved","scope":{"service":"payment-svc"},"spec":{}}'
assert_eq "${CODE}" 422 "policy API refuses a policy without a human approval record"

hub_login admin >/dev/null
id="e2e-rollout-$(date +%s)"
# wait_rollout NAME -> final phase (polls up to 90 s)
wait_rollout() {
  local phase=""
  for _ in $(seq 1 90); do
    hub GET "/api/hub/rollouts/$1"
    phase=$(echo "${BODY}" | jq -r '.status.phase // empty')
    [[ "${phase}" == Complete || "${phase}" == Failed ]] && break
    sleep 1
  done
  echo "${phase}"
}

# v1: two-phase rollout created together with the policy
hub POST /api/hub/policies "{\"policyId\":\"${id}\",\"service\":\"payment-svc\",\"route\":\"GET /api/orders\",\"deadlineMs\":1200,\"retryBudget\":2,\"rollout\":\"two-phase\"}"
assert_eq "${CODE}" 201 "policy v1 published with a two-phase rollout"
assert_eq "$(echo "${BODY}" | jq -r .notification.channel)" rpe_policy_approved "approval fired NOTIFY (event-driven change detection)"
r1=$(echo "${BODY}" | jq -r .rollout.metadata.name)
e1="${id}-v1"
assert_eq "$(wait_rollout "${r1}")" Complete "two-phase rollout of ${e1} completes"
assert_eq "$(echo "${BODY}" | jq -r '.status.active')" "$(echo "${BODY}" | jq -r '.status.targetPods')" "every target proxy confirmed the new version"
assert_ge "$(proxy_holds payment-svc "${e1}" active)" 1 "payment-svc proxies enforce ${e1}"
assert_ge "$(proxy_holds gateway "${e1}" active)" 1 "gateway proxies stamp ${e1} (flipped first at the ingress)"
assert_eq "$(proxy_holds inventory-svc "${e1}" active)" 0 "proxies outside the policy scope are untouched"
assert_eq "$(k -n rpe-system get rpepolicy "${id}" -o jsonpath='{.status.activeEpoch}')" "${e1}" "RPEPolicy mirror reports the active version"
hub GET /api/hub/effective
assert_eq "$(echo "${BODY}" | jq -r '.["payment-svc"].epoch')" "${e1}" "Redis effective-policy cache updated"
client GET http://rpe-policy-api.rpe-system/api/k8s/events
assert_ge "$(echo "${BODY}" | jq --arg r "${r1}" '[.[] | select(.type=="rollout-complete" and .fields.rollout==$r)] | length')" 1 "Redis event stream recorded the completed rollout"
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
deliv=$(k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -At -c "select count(*) from policy.deliveries where epoch='${e1}' and state='active'")
assert_ge "${deliv}" 2 "deliveries recorded per proxy in PostgreSQL"
hub GET "/api/hub/rollouts/${r1}"
assert_eq "$(echo "${BODY}" | jq '[.status.pods[] | select(.verified)] | length')" "$(echo "${BODY}" | jq -r '.status.targetPods')" "every proxy holds ${e1} with the approved content hash (verified)"
acked=0
for _ in $(seq 1 15); do
  acked=$(k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -At -c "select count(*) from policy.deliveries where epoch='${e1}' and confirmed_state='active' and confirmed_hash=hash")
  [[ "${acked}" -ge 2 ]] && break
  sleep 1
done
assert_ge "${acked}" 2 "proxies acknowledged ${e1} themselves with the same hash (POST /api/k8s/acks)"
expected_hash=$(echo "${BODY}" | jq -r '[.status.pods[] | select(.service=="payment-svc") | .hash][0]')
client GET "http://$(pod_ip payment-svc):15020/rpe/v1/policy"
assert_eq "$(echo "${BODY}" | jq -r --arg e "${e1}" '.epochs[] | select(.epoch==$e) | .hash')" "${expected_hash}" "the proxy reports the content hash it computed itself"

# v2: new version supersedes v1; v1 is retired from the proxies after the drain
hub POST /api/hub/policies "{\"policyId\":\"${id}\",\"service\":\"payment-svc\",\"route\":\"GET /api/orders\",\"deadlineMs\":900,\"retryBudget\":1,\"rollout\":\"two-phase\"}"
r2=$(echo "${BODY}" | jq -r .rollout.metadata.name); e2="${id}-v2"
assert_eq "$(wait_rollout "${r2}")" Complete "rollout of ${e2} completes"
hub GET "/api/hub/rollouts/${r2}"
assert_eq "$(echo "${BODY}" | jq -r '(.status.inflightPrevious // 0) == 0 and (.status.activatedAt != null)')" true "two-phase drained until no request pinned to ${e1} was in flight"
assert_eq "$(proxy_holds payment-svc "${e1}" active)" 0 "previous version ${e1} retired from the proxies"
hub GET /api/hub/policies
assert_eq "$(echo "${BODY}" | jq -r --arg e "${e1}" '.policies[] | select(.epoch==$e) | .status')" superseded "store marks ${e1} superseded"
assert_eq "$(echo "${BODY}" | jq -r --arg e "${e2}" '.policies[] | select(.epoch==$e) | .status')" active "store marks ${e2} active"

# restore v1 with an immediate (naive) rollout
hub POST /api/hub/rollouts "{\"policyId\":\"${id}\",\"version\":1,\"mode\":\"naive\"}"
r3=$(echo "${BODY}" | jq -r .metadata.name)
assert_eq "$(wait_rollout "${r3}")" Complete "immediate rollout restores ${e1}"
assert_ge "$(proxy_holds payment-svc "${e1}" active)" 1 "payment-svc enforces the restored ${e1}"

# drift repair: a restarted pod comes up with no policy; the Operator re-delivers it
old=$(pod_of payment-svc)
k -n rpe-demo delete pod "${old}" --wait=false >/dev/null
k -n rpe-demo rollout status deploy/payment-svc --timeout=120s >/dev/null
repaired=0
for _ in $(seq 1 40); do
  [[ "$(proxy_holds payment-svc "${e1}" active)" -ge 1 && "$(pod_of payment-svc)" != "${old}" ]] && { repaired=1; break; }
  sleep 1
done
assert_eq "${repaired}" 1 "drift repaired: the new payment-svc pod received ${e1} without a new rollout"
# leave no terminating pod behind for the next suite (95 counts the scraped sidecars)
k -n rpe-demo wait --for=delete pod/"${old}" --timeout=120s >/dev/null 2>&1 || true
client GET http://rpe-policy-api.rpe-system/api/k8s/events
assert_ge "$(echo "${BODY}" | jq '[.[] | select(.type=="drift-repaired")] | length')" 1 "drift repair recorded in the event stream"
finish
