#!/usr/bin/env bash
# C1 novelty, the self-describing request (X-RPE-Trail, rules R-P01..R-P05) and interoperability. Every RPE hop adds
# one bounded entry to the trail; the gateway reports the finished trail to the protocol service and removes it from
# its response. From trails alone the protocol service rebuilds the service graph (no tracing backend, no sampling)
# and shows which hop used the deadline. The hop that does not speak RPE (legacy-svc) is an unknown edge and marks the
# trail incomplete. rpe-go and rpe-java decode headers identically and pass every conformance case, live.
source "$(dirname "$0")/lib.sh"
hub_login admin >/dev/null
gateway_policy

# --- 1. one request, its trail and its deadline attribution -------------------------------------------------------
attribution() {
  hub GET "/api/hub/protocol/attribution/${RID}"
  [[ "${CODE}" == 200 ]]
}
RID=""
for _ in 1 2 3 4 5; do
  hub POST /api/hub/chain '{"path":"/api/orders"}'
  RID=$(echo "${BODY}" | jq -r '.rid // empty')
  [[ -n "${RID}" ]] && break
  sleep 2
done
assert_ne "${RID}" "" "the chain has a request id"
for _ in $(seq 1 20); do attribution && break; sleep 1; done
assert_eq "${CODE}" 200 "the gateway reported the request's trail to the protocol service"
A=${BODY}
svcs=$(echo "${A}" | jq -r '[.hops[].service] | sort | join(",")')
assert_eq "${svcs}" "gateway,inventory-svc,legacy-svc,loyalty-svc,order-svc,payment-svc" "every hop the request visited is in its trail"
assert_eq "$(echo "${A}" | jq -r '.hops[0] | "\(.service) \(.parent)"')" "gateway 0" "the gateway is the root entry"
assert_eq "$(echo "${A}" | jq -r '[.hops[] | select(.service=="order-svc")][0].parent')" 1 "order-svc was called by the gateway"
assert_eq "$(echo "${A}" | jq -r '[.hops[] | select(.parent==2)] | length')" 4 "order-svc's four calls hang below it"
assert_eq "$(echo "${A}" | jq -r '[.hops[] | select(.service=="legacy-svc")][0].unknown')" true "legacy-svc (no RPE) is an unknown hop (R-P05)"
assert_eq "$(echo "${A}" | jq -r .incomplete)" true "the trail is marked incomplete because of it"
assert_eq "$(echo "${A}" | jq -r .budgetMs)" "${GW_ORDERS_BUDGET}" "the trail is scored against the chain budget (${GW_ORDERS_BUDGET} ms)"
assert_ge "$(echo "${A}" | jq -r .bottleneck)" 1 "the hop that used most of the deadline is identified ($(echo "${A}" | jq -r '.hops[.bottleneck-1].service'))"
assert_lt "$(echo "${A}" | jq -r .bytes)" 1401 "the trail stays within its 1400-byte bound ($(echo "${A}" | jq -r .bytes) B)"
gw_ms=$(echo "${A}" | jq -r '.hops[0].elapsedMs | floor'); order_ms=$(echo "${A}" | jq -r '.hops[1].elapsedMs | floor')
assert_ge "${gw_ms}" "${order_ms}" "a caller's time includes its callee's (gateway ${gw_ms} ms >= order ${order_ms} ms)"

# --- 2. the trail never leaves the chain, and a hop returns its subtree --------------------------------------------
client GET "${GW}/api/orders"
assert_eq "$(header X-RPE-Trail)" "" "the gateway removes the trail from its response (R-P04)"
fresh="v=1, rid=\"$(openssl rand -hex 16)\", iid=\"00f067aa0ba902b7\", pid=\"0000000000000000\", ttl=1000, bud=1000, rb=3, cs=none, rt=\"GET /api/orders\""
client GET "http://order-svc.rpe-demo:8080/orders" "" -H "X-RPE-Context: ${fresh}"
sub=$(header X-RPE-Trail)
assert_contains "${sub}" 'h="2:0:' "order-svc returns its own subtree, rooted at its registry id (2)"
assert_contains "${sub}" ":U:" "with the unknown legacy hop inside it"

# --- 3. topology rebuilt from trails, scored against the declared call graph ----------------------------------------
hub GET "/api/hub/protocol/topology?window=300"
assert_eq "${CODE}" 200 "the topology is available"
edges=$(echo "${BODY}" | jq -r '[.topology.edges[] | "\(.from)>\(.to)"] | sort | join(",")')
assert_contains "${edges}" "gateway>order-svc" "edge gateway → order-svc rebuilt"
assert_contains "${edges}" "order-svc>payment-svc" "edge order-svc → payment-svc rebuilt"
assert_contains "${edges}" "order-svc>legacy-svc" "edge order-svc → legacy-svc rebuilt (as an unknown hop)"
assert_eq "$(echo "${BODY}" | jq -r .comparison.precision)" 100 "precision 100%: every rebuilt edge is a real edge"
assert_ge "$(echo "${BODY}" | jq -r '.comparison.recall | floor')" 83 "recall $(echo "${BODY}" | jq -r .comparison.recall)%: only the call behind the non-RPE hop is invisible"
assert_eq "$(echo "${BODY}" | jq -r '.comparison.missing | join(",")')" "legacy-svc → shipping-svc" "the invisible edge is the one behind legacy-svc"

# --- 4. interoperability: rpe-go and rpe-java ---------------------------------------------------------------------
trail=$(echo "${A}" | jq -r .trail)
hub POST /api/hub/protocol/compare "$(jq -nc --arg v "${trail}" '{kind:"trail", value:$v}')"
assert_eq "$(echo "${BODY}" | jq -r .agree)" true "rpe-go and rpe-java decode the reported trail identically"
bad='v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=301, bud=300, rb=3, cs=none, rt="GET /x"'
hub POST /api/hub/protocol/compare "$(jq -nc --arg v "${bad}" '{kind:"context", value:$v}')"
assert_eq "$(echo "${BODY}" | jq -r '"\(.go.error.rule) \(.java.error.rule) \(.agree)"')" "R-T02 R-T02 true" "both reject ttl > bud with rule R-T02"
hub GET /api/hub/protocol/conformance
assert_eq "$(echo "${BODY}" | jq -r '"\(.goPassed)/\(.total)"')" "150/150" "rpe-go passes every conformance case, run live"
assert_eq "$(echo "${BODY}" | jq -r '"\(.javaPassed)/\(.total)"')" "150/150" "rpe-java passes every conformance case, run live"
hub GET /api/hub/protocol/registry
assert_eq "$(echo "${BODY}" | jq -r '.services.gateway')" 1 "the service registry maps names to trail ids"
finish
