#!/usr/bin/env bash
# Assertion and cluster helpers for the RPE Kubernetes e2e suites.
set -uo pipefail
KCTX="${KCTX:-kind-rpe}"
PASS=0; FAILS=0
GW="http://gateway.rpe-demo:8080"
RPE_SERVICES=(gateway order-svc inventory-svc payment-svc loyalty-svc fraud-check-svc shipping-svc)

k() { kubectl --context "${KCTX}" "$@"; }
ok()  { printf '  \033[32mPASS\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAILS=$((FAILS+1)); }
assert_eq()  { if [[ "$1" == "$2" ]]; then ok "$3"; else bad "$3 (expected '$2', got '$1')"; fi; }
assert_ne()  { if [[ "$1" != "$2" ]]; then ok "$3"; else bad "$3 (did not expect '$2')"; fi; }
assert_ge()  { if [[ "$1" =~ ^-?[0-9]+$ && "$1" -ge "$2" ]]; then ok "$3"; else bad "$3 (expected >= $2, got '$1')"; fi; }
assert_lt()  { if [[ "$1" =~ ^-?[0-9]+$ && "$1" -lt "$2" ]]; then ok "$3"; else bad "$3 (expected < $2, got '$1')"; fi; }
assert_contains() { if [[ "$1" == *"$2"* ]]; then ok "$3"; else bad "$3 (missing '$2' in: ${1:0:300})"; fi; }
finish() { echo "  -> ${PASS} passed, ${FAILS} failed"; [[ ${FAILS} -eq 0 ]]; }

# client METHOD URL [JSON-BODY] [extra curl args...]  -> sets CODE, BODY, HEADERS, MS
client() {
  local method=$1 url=$2 data=${3:-}; shift 3 2>/dev/null || shift $#
  local args=(-sS --max-time 20 -o /tmp/body -D /tmp/headers -w '%{http_code} %{time_total}' -X "${method}")
  [[ -n "${data}" ]] && args+=(-H 'Content-Type: application/json' --data "${data}")
  local out
  out=$(k -n rpe-e2e exec e2e-client -- curl "${args[@]}" "$@" "${url}" 2>&1) || true
  CODE=${out%% *}
  MS=$(awk -v t="${out##* }" 'BEGIN { printf "%d", t * 1000 }')
  BODY=$(k -n rpe-e2e exec e2e-client -- cat /tmp/body 2>/dev/null || true)
  HEADERS=$(k -n rpe-e2e exec e2e-client -- cat /tmp/headers 2>/dev/null | tr -d '\r' || true)
}

header() { echo "${HEADERS}" | grep -i "^$1:" | tail -1 | cut -d: -f2- | sed 's/^ *//'; }

# field DICTIONARY KEY -> value of an RFC 8941 dictionary member (quotes removed)
field() { echo "$1" | tr ',' '\n' | sed 's/^ *//' | grep "^$2=" | head -1 | cut -d= -f2- | tr -d '"'; }

pod_of()  { k -n rpe-demo get pod -l "app=$1" -o jsonpath='{.items[0].metadata.name}'; }
pod_ip()  { k -n rpe-demo get pod -l "app=$1" -o jsonpath='{.items[0].status.podIP}'; }

# metric SERVICE DIRECTION -> total rpe_requests_total for that direction on the service's sidecar
metric() {
  local ip; ip=$(pod_ip "$1")
  k -n rpe-e2e exec e2e-client -- curl -s --max-time 5 "http://${ip}:15090/metrics" \
    | grep "^rpe_requests_total{" | grep "direction=\"$2\"" | awk '{s+=$2} END {print s+0}'
}

counters() { client GET "http://$1.rpe-demo:8080/admin/counters"; echo "${BODY}"; }
fault()    { client POST "http://$1.rpe-demo:8080/admin/faults" "$2" >/dev/null; }
# The background traffic generator (rpe-traffic) keeps live data flowing for C4; suites that assert exact
# counters pause it and resume it when they exit.
traffic_pause() {
  [[ -n "${TRAFFIC_PAUSED:-}" ]] && return 0
  TRAFFIC_PAUSED=1
  trap traffic_resume EXIT
  k -n rpe-e2e scale deploy/rpe-traffic --replicas=0 >/dev/null 2>&1 || return 0
  k -n rpe-e2e wait --for=delete pod -l app=rpe-traffic --timeout=60s >/dev/null 2>&1 || true
}
traffic_resume() { k -n rpe-e2e scale deploy/rpe-traffic --replicas=1 >/dev/null 2>&1 || true; }
reset_demo() { traffic_pause; for s in "${RPE_SERVICES[@]}"; do client POST "http://${s}.rpe-demo:8080/admin/reset" >/dev/null; done; }
# gateway_policy -> sets GW_EPOCH and GW_ORDERS_BUDGET from the policy active on the gateway proxy (e0/1000 when none)
gateway_policy() {
  client GET "http://$(pod_ip gateway):15020/rpe/v1/policy/active"
  if [[ "${CODE}" == 200 ]]; then
    GW_EPOCH=$(echo "${BODY}" | jq -r '.epoch')
    GW_ORDERS_BUDGET=$(echo "${BODY}" | jq -r '.policy.routes["GET /api/orders"].deadlineMs // 1000')
  else
    GW_EPOCH=e0; GW_ORDERS_BUDGET=1000
  fi
}

# Admin panel API (rpe-hub). hub METHOD PATH [JSON] [cookie-jar] -> sets CODE and BODY
HUB="http://rpe-hub.rpe-system"
hub() {
  local jar=${4:-/tmp/jar-admin} out
  out=$(k -n rpe-e2e exec e2e-client -- curl -s --max-time 30 -b "$jar" -c "$jar" -o /tmp/hub-body -w '%{http_code}' \
    -X "$1" ${3:+-H 'Content-Type: application/json' --data "$3"} "${HUB}$2" 2>&1) || true
  CODE=$out; BODY=$(k -n rpe-e2e exec e2e-client -- cat /tmp/hub-body 2>/dev/null || true)
}
hub_login() { hub POST /api/hub/login "{\"username\":\"$1\",\"password\":\"$1\"}" "/tmp/jar-$1"; }
# proxy_holds SERVICE EPOCH STATE -> number of that service's proxies holding EPOCH in STATE
proxy_holds() {
  hub GET /api/hub/sidecars
  echo "${BODY}" | jq --arg s "$1" --arg e "$2" --arg st "$3" '[.[] | select(.service==$s) | select([.policy.epochs[]? | select(.epoch==$e and .state==$st)] | length > 0)] | length'
}
