#!/usr/bin/env bash
# C3 failure and recovery (proposal validation: Operator restart, pod restart, delivery verification):
#   - an approval that lands while the Operator is down is caught up from policy_events after the restart;
#   - a rollout interrupted by an Operator restart resumes from its RPERollout status and completes;
#   - a proxy whose policy content is tampered with is detected by content hash and repaired;
#   - coverage audit: a pod admitted while the webhook was down (failurePolicy Ignore) is reported as a gap,
#     and re-injecting it (recreate through its controller) restores 100% coverage.
source "$(dirname "$0")/lib.sh"
hub_login admin >/dev/null
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
psql() { k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -At -c "$1"; }
operator_ready() { k -n rpe-system rollout status deploy/rpe-operator --timeout=120s >/dev/null; for _ in $(seq 1 30); do client GET http://rpe-policy-api.rpe-system/healthz; [[ "${CODE}" == 200 ]] && return; sleep 1; done; }
restore_webhook() {
  k patch mutatingwebhookconfiguration rpe-sidecar-injector --type=json -p '[{"op":"replace","path":"/webhooks/0/failurePolicy","value":"Fail"}]' >/dev/null 2>&1
  k -n rpe-system scale deploy/rpe-admission-webhook --replicas=1 >/dev/null 2>&1
  k -n rpe-system scale deploy/rpe-operator --replicas=1 >/dev/null 2>&1
  k -n rpe-demo delete deploy e2e-gap --ignore-not-found >/dev/null 2>&1
}
trap restore_webhook EXIT

# --- 1. catch-up: approval while the Operator is down -------------------------------------------------
catchup="e2e-catchup-$(date +%s)"
k -n rpe-system scale deploy/rpe-operator --replicas=0 >/dev/null
k -n rpe-system wait --for=delete pod -l app=rpe-operator --timeout=90s >/dev/null 2>&1
# another writer of the store (the schema still enforces the approval record)
psql "insert into policy.policies(policy_id, version, epoch, scope, spec, source, approved_by, approved_at)
      values ('${catchup}', 1, '${catchup}-v1', '{\"service\":\"payment-svc\"}', '{\"leakyBucket\":{\"ratePerSec\":90}}', 'admin', 'e2e-sre', now())" >/dev/null
k -n rpe-system scale deploy/rpe-operator --replicas=1 >/dev/null
operator_ready
mirrored=""
for _ in $(seq 1 30); do
  mirrored=$(k -n rpe-system get rpepolicy "${catchup}" -o jsonpath='{.spec.epoch}' 2>/dev/null)
  [[ -n "${mirrored}" ]] && break
  sleep 1
done
assert_eq "${mirrored}" "${catchup}-v1" "approval made while the Operator was down is caught up after its restart"

# --- 2. a rollout survives an Operator restart ---------------------------------------------------------
hub POST /api/hub/rollouts "{\"policyId\":\"${catchup}\",\"version\":1,\"mode\":\"two-phase\",\"skewMs\":4000}"
r=$(echo "${BODY}" | jq -r .metadata.name)
for _ in $(seq 1 20); do
  phase=$(k -n rpe-system get rperollout "${r}" -o jsonpath='{.status.phase}' 2>/dev/null)
  [[ "${phase}" == Staging || "${phase}" == Activating ]] && break
  sleep 0.5
done
k -n rpe-system delete pod -l app=rpe-operator --wait=false >/dev/null
operator_ready
assert_eq "$(wait_settled "${r}")" Complete "rollout interrupted by an Operator restart (in ${phase:-?}) resumes and completes"
assert_ge "$(proxy_holds payment-svc "${catchup}-v1" active)" 1 "payment-svc enforces ${catchup}-v1 after the resumed rollout"

# --- 3. tampered proxy: drift detected by content hash and repaired --------------------------------------
pay=$(pod_of payment-svc)
hub PUT "/api/hub/sidecars/${pay}/policy" "{\"epoch\":\"${catchup}-v1\",\"version\":1,\"state\":\"active\",\"policy\":{\"leakyBucket\":{\"ratePerSec\":1}}}"
hub GET /api/hub/fleet/audit
assert_contains "$(echo "${BODY}" | jq -r --arg p "${pay}" '.pods[] | select(.pod==$p) | (.drift // []) | join(";")')" "hash mismatch" "tampered policy content detected by hash"
want=$(psql "select hash from policy.deliveries where pod='${pay}' and epoch='${catchup}-v1'")
repaired=0
for _ in $(seq 1 30); do
  client GET "http://$(pod_ip payment-svc):15020/rpe/v1/policy"
  [[ "$(echo "${BODY}" | jq -r --arg e "${catchup}-v1" '.epochs[] | select(.epoch==$e) | .hash')" == "${want}" ]] && { repaired=1; break; }
  sleep 1
done
assert_eq "${repaired}" 1 "drift repaired: the approved content is back on ${pay}"
client GET http://rpe-policy-api.rpe-system/api/k8s/events
assert_ge "$(echo "${BODY}" | jq --arg p "${pay}" '[.[] | select(.type=="drift-repaired" and .fields.pod==$p and (.fields.reason | test("hash")))] | length')" 1 "repair recorded with its reason"
client GET "http://rpe-policy-api.rpe-system:8081/metrics"
assert_ge "$(echo "${BODY}" | awk '/^rpe_drift_repairs_total/ {print int($2)}')" 1 "rpe_drift_repairs_total counts the repair"

# --- 4. coverage audit: webhook down with failurePolicy Ignore -------------------------------------------
hub GET /api/hub/fleet/audit
assert_eq "$(echo "${BODY}" | jq -r '.coverage.ratio')" 1 "audited coverage is 100% before the outage"
k patch mutatingwebhookconfiguration rpe-sidecar-injector --type=json -p '[{"op":"replace","path":"/webhooks/0/failurePolicy","value":"Ignore"}]' >/dev/null
k -n rpe-system scale deploy/rpe-admission-webhook --replicas=0 >/dev/null
k -n rpe-system wait --for=delete pod -l app=rpe-admission-webhook --timeout=90s >/dev/null 2>&1
k -n rpe-demo create deployment e2e-gap --image=rpe/demo-app:dev --port=8080 >/dev/null
gap=""
for _ in $(seq 1 30); do
  hub GET /api/hub/fleet/audit
  gap=$(echo "${BODY}" | jq -r '[.gaps[]? | select(.namespace=="rpe-demo" and (.pod | startswith("e2e-gap")))][0].pod // empty')
  [[ -n "${gap}" ]] && break
  sleep 1
done
assert_ne "${gap}" "" "pod admitted while the webhook was down is reported as a coverage gap"
assert_eq "$(echo "${BODY}" | jq -r '.coverage.ratio < 1')" true "audited coverage drops below 100%"
client GET "http://rpe-policy-api.rpe-system:8081/metrics"
ratio=""
for _ in $(seq 1 15); do
  client GET "http://rpe-policy-api.rpe-system:8081/metrics"
  ratio=$(echo "${BODY}" | awk '/^rpe_injection_coverage_ratio/ {print $2}')
  [[ -n "${ratio}" && "${ratio}" != 1 ]] && break
  sleep 1
done
assert_ne "${ratio:-1}" 1 "rpe_injection_coverage_ratio reports the gap (${ratio})"

k patch mutatingwebhookconfiguration rpe-sidecar-injector --type=json -p '[{"op":"replace","path":"/webhooks/0/failurePolicy","value":"Fail"}]' >/dev/null
k -n rpe-system scale deploy/rpe-admission-webhook --replicas=1 >/dev/null
k -n rpe-system rollout status deploy/rpe-admission-webhook --timeout=120s >/dev/null
sleep 3
hub POST /api/hub/fleet/reinject "{\"namespace\":\"rpe-demo\",\"pod\":\"${gap}\"}"
assert_eq "${CODE}" 202 "re-inject recreates the gap pod through its controller"
injected=""
for _ in $(seq 1 60); do
  injected=$(k -n rpe-demo get pod -l app=e2e-gap -o json | jq -r '[.items[] | select(.metadata.deletionTimestamp == null) | [.spec.containers[].name] | index("rpe-sidecar") != null] | all')
  [[ "${injected}" == true ]] && break
  sleep 1
done
assert_eq "${injected}" true "the recreated pod runs with the RPE sidecar"
hub GET /api/hub/fleet/audit
assert_eq "$(echo "${BODY}" | jq -r '[.gaps[]? | select(.pod | startswith("e2e-gap"))] | length')" 0 "the coverage gap is closed"
hub POST /api/hub/fleet/reinject "{\"namespace\":\"rpe-demo\",\"pod\":\"$(pod_of gateway)\"}"
assert_eq "${CODE}" 409 "re-inject refuses a pod that already runs the sidecar"
k -n rpe-demo delete deploy e2e-gap --wait=false >/dev/null
k -n rpe-demo wait --for=delete pod -l app=e2e-gap --timeout=120s >/dev/null 2>&1 || true
finish
