#!/usr/bin/env bash
# Observability + C4 live loop + C3 delivery:
#   Prometheus scrapes every sidecar, Tempo stores the demo traces, the intelligence layer analyses both live.
#   An operator rolls out a too-tight gateway deadline; C4 detects the persistent deadline failures and proposes a
#   bounded, censoring-aware increase. Nothing is delivered until a human approves it; the approval becomes an
#   approved C3 policy version (recommendationId + approver) with a two-phase rollout, the gateway enforces it, and
#   every step is in the append-only audit trail in PostgreSQL.
# Takes a few minutes: C4 needs complete telemetry windows (1 min window, 15 s cycle, 20 s ingestion delay).
source "$(dirname "$0")/lib.sh"
PROM="http://rpe-prometheus.rpe-system:9090"
TEMPO="http://rpe-tempo.rpe-system:3200"
enc() { jq -rn --arg v "$1" '$v|@uri'; }
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
psqlc() { k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -v ON_ERROR_STOP=1 -At "$@" 2>&1; }
# wait_for SECONDS DESCRIPTION COMMAND... : retries COMMAND every 5 s until it succeeds
wait_for() {
  local limit=$1 what=$2; shift 2
  local end=$((SECONDS + limit))
  until "$@"; do
    if ((SECONDS >= end)); then echo "  (timed out after ${limit}s waiting for ${what})"; return 1; fi
    sleep 5
  done
}
intel_status() { hub GET /api/hub/intel; INTEL=$(echo "${BODY}" | jq -c '.status // {}'); }

hub_login admin >/dev/null
hub_login sre >/dev/null
hub_login viewer >/dev/null

# ---------- telemetry ----------
client GET "${PROM}/api/v1/query?query=$(enc 'count(up{job="rpe-sidecars"} == 1)')"
assert_eq "$(echo "${BODY}" | jq -r '.data.result[0].value[1] // 0')" 7 "Prometheus scrapes all 7 injected sidecars"
client GET "${PROM}/api/v1/query?query=$(enc 'sum(rpe_chain_requests_total{service="gateway",route="/api/orders"})')"
assert_ge "$(echo "${BODY}" | jq -r '.data.result[0].value[1] // 0 | tonumber | floor')" 1 "gateway sidecar reports per-route chain counts"
client GET "${TEMPO}/api/search?q=$(enc '{ resource.service.name = "gateway" && span.rpe.direction = "inbound" }')&limit=5"
tid=$(echo "${BODY}" | jq -r '.traces[0].traceID // empty')
assert_ne "${tid}" "" "Tempo has gateway request traces"
client GET "${TEMPO}/api/v2/traces/${tid}"
assert_contains "${BODY}" "rpe.deadline.budget_ms" "traces carry the RPE attributes (deadline budget)"
hub GET /api/hub/intel/connections
assert_eq "$(echo "${BODY}" | jq -r '[.prometheus.state, .tempo.state] | join(",")')" "LIVE_DATA,LIVE_DATA" "intelligence layer reads live metrics and traces"

# ---------- live analysis ----------
chain_health() { echo "${INTEL}" | jq -r '[.assessments[]? | select(.source=="TEMPO" and .target=="GET /api/orders")][0].health // ""'; }
chain_ready() { intel_status; [[ "$(chain_health)" == HEALTHY || "$(chain_health)" == BASELINE_READY ]]; }
# a freshly started intelligence layer first learns per-service guards, then the chain (several minutes)
wait_for 600 "the chain baseline" chain_ready
assert_contains "HEALTHY BASELINE_READY" "$(chain_health)" "C4 learned the GET /api/orders chain baseline from live traces ($(chain_health))"
assert_ge "$(echo "${INTEL}" | jq '[.assessments[]? | select(.source=="PROMETHEUS")] | length')" 7 "C4 assesses every monitored service from live metrics"

# ---------- a too-tight deadline ----------
gw_policy="e2e-gateway-$(date +%s)"
hub POST /api/hub/policies "{\"policyId\":\"${gw_policy}\",\"service\":\"gateway\",\"route\":\"GET /api/orders\",\"deadlineMs\":100,\"retryBudget\":3,\"retryRatio\":0.1,\"ratePerSec\":100,\"rollout\":\"two-phase\"}"
assert_eq "${CODE}" 201 "operator rolls out a 100 ms deadline for GET /api/orders (too tight: payment alone takes ~120 ms)"
gw_budget_is() { gateway_policy; [[ "${GW_ORDERS_BUDGET}" == "$1" ]]; }
wait_for 90 "the gateway to enforce 100 ms" gw_budget_is 100
sleep 3
client GET "${GW}/api/orders"
assert_eq "${CODE}" 200 "requests are still served: the deadline is signalled, never enforced by rejecting"
assert_contains "$(header X-RPE-Result)" "cs=expired" "but every chain now exceeds its deadline (cs=expired)"

proposal() {
  intel_status
  REC=$(echo "${INTEL}" | jq -c '[.changes[]? | select(.proposal.action=="CHAIN_DEADLINE" and .proposal.target=="GET /api/orders"
    and .decision=="PENDING_APPROVAL" and .delivery!="SUPERSEDED" and .delivery!="EXPIRED")][0] // empty')
  [[ -n "${REC}" ]]
}
wait_for 420 "a deadline recommendation" proposal
id=$(echo "${REC}" | jq -r '.proposal.id')
proposed=$(echo "${REC}" | jq -r '.proposal.proposedValue | floor')
assert_ne "${id}" "" "C4 proposes a chain-deadline change from live evidence"
assert_eq "$(echo "${REC}" | jq -r '.proposal.currentValue | floor')" 100 "the recommendation starts from the enforced deadline"
assert_ge "${proposed}" 101 "it proposes an increase (${proposed} ms)"
assert_lt "${proposed}" 151 "within the bounded step of +50% per change"
assert_contains "$(echo "${REC}" | jq -r '.proposal.evidence | join(" ")')" "censored" "the evidence explains that observed latency is cut at the deadline"

hub GET /api/hub/policies
assert_eq "$(echo "${BODY}" | jq --arg id "${id}" '[.policies[] | select(.recommendationId==$id)] | length')" 0 "nothing is delivered while the recommendation awaits review"
hub POST "/api/hub/intel/changes/${id}/review" '{"approve":true}' /tmp/jar-viewer
assert_eq "${CODE}" 403 "the viewer role cannot approve"
hub POST "/api/hub/intel/changes/${id}/review" '{"approve":false}' /tmp/jar-sre
assert_eq "${CODE}" 400 "a rejection needs a reason"
hub POST "/api/hub/intel/changes/${id}/review" '{"approve":true,"comment":"e2e: deadline too tight"}' /tmp/jar-sre
assert_eq "${CODE}" 200 "an SRE approves the recommendation"
assert_eq "$(echo "${BODY}" | jq -r '.decision + "/" + .reviewer')" "APPROVED/sre" "the signed-in user is recorded as the reviewer"

delivered() {
  hub GET /api/hub/policies
  POL=$(echo "${BODY}" | jq -c --arg id "${id}" '[.policies[] | select(.recommendationId==$id)][0] // empty')
  [[ -n "${POL}" ]]
}
wait_for 90 "delivery to the policy store" delivered
assert_eq "$(echo "${POL}" | jq -r '.source')" c4-recommendation "C3 stores it as a new approved version from a recommendation"
assert_eq "$(echo "${POL}" | jq -r '.approvedBy')" sre "C3's approval record names the human reviewer"
assert_eq "$(echo "${POL}" | jq -r '.policyId')" "${gw_policy}" "it is the next version of the gateway's current policy"
assert_eq "$(echo "${POL}" | jq -r '.spec.routes["GET /api/orders"].deadlineMs')" "${proposed}" "the version carries the approved route deadline"
epoch=$(echo "${POL}" | jq -r '.epoch')

applied() {
  intel_status
  [[ "$(echo "${INTEL}" | jq -r --arg id "${id}" '.changes[]? | select(.proposal.id==$id) | .delivery')" == APPLIED ]]
}
wait_for 120 "the rollout to complete" applied
assert_eq "$(echo "${INTEL}" | jq -r --arg id "${id}" '.changes[] | select(.proposal.id==$id) | .appliedVersion')" "${epoch}" "C4 confirms enforcement after the two-phase rollout (${epoch})"
hub GET /api/hub/rollouts
assert_eq "$(echo "${BODY}" | jq -r --arg e "${epoch}" '[.[] | select(.spec.epoch==$e)][0] | .spec.mode + "/" + .status.phase')" "two-phase/Complete" "delivered with a two-phase rollout"
wait_for 30 "the gateway to follow the new policy" gw_budget_is "${proposed}"
assert_eq "${GW_ORDERS_BUDGET}/${GW_EPOCH}" "${proposed}/${epoch}" "the gateway now stamps the approved deadline and epoch"

# ---------- audit ----------
hub GET /api/hub/intel/audit
assert_eq "$(echo "${BODY}" | jq -r '.persistent')" true "the audit trail is persisted"
assert_eq "$(echo "${BODY}" | jq -r --arg id "${id}" '[.entries[] | select(.proposalId==$id) | .kind] | sort | unique | join(",")' | grep -o 'approved\|delivery-applied\|proposed' | sort -u | tr '\n' ',')" "approved,delivery-applied,proposed," "audit has proposed, approved and applied entries"
assert_eq "$(echo "${BODY}" | jq -r --arg id "${id}" '[.entries[] | select(.proposalId==$id and .kind=="approved")][0].actor')" sre "audit names the reviewer"
assert_ge "$(psqlc -c "select count(*) from intel.audit where proposal_id='${id}'")" 3 "audit rows are stored in PostgreSQL (intel.audit)"
out=$(psqlc -c "update intel.audit set actor='someone-else' where proposal_id='${id}'")
assert_contains "${out}" "append-only" "the audit table is append-only"

# ---------- restore ----------
hub POST /api/hub/policies "{\"policyId\":\"${gw_policy}\",\"service\":\"gateway\",\"route\":\"GET /api/orders\",\"deadlineMs\":1000,\"retryBudget\":3,\"retryRatio\":0.1,\"ratePerSec\":100,\"rollout\":\"two-phase\"}"
wait_for 90 "the gateway deadline to be restored" gw_budget_is 1000
assert_eq "${GW_ORDERS_BUDGET}" 1000 "restored the 1000 ms route deadline"
finish
