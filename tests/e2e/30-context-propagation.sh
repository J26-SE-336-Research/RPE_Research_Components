#!/usr/bin/env bash
# C1: X-RPE-Context is created at the gateway and propagated hop by hop with the protocol rules.
source "$(dirname "$0")/lib.sh"
reset_demo
gateway_policy
client GET "${GW}/api/orders"
assert_eq "${CODE}" 200 "chain request succeeds"
gw=$(echo "${BODY}" | jq -r '.context')
order=$(echo "${BODY}" | jq -r '.children[] | select(.name=="order") | .downstream.context')
inv=$(echo "${BODY}" | jq -r '.children[] | select(.name=="order") | .downstream.children[] | select(.name=="inventory") | .downstream.context')
pay=$(echo "${BODY}" | jq -r '.children[] | select(.name=="order") | .downstream.children[] | select(.name=="payment") | .downstream.context')
legacy_ship=$(echo "${BODY}" | jq -r '.children[] | select(.name=="order") | .downstream.children[] | select(.name=="legacy") | .downstream.children[0].downstream.context // ""')

assert_eq "$(field "$gw" bud)" "${GW_ORDERS_BUDGET}" "gateway sets the route budget of the active policy (GET /api/orders=${GW_ORDERS_BUDGET})"
assert_eq "$(field "$gw" rt)" "GET /api/orders" "gateway sets the route key"
assert_eq "$(field "$gw" pid)" 0000000000000000 "gateway context is the root"
assert_eq "$(field "$gw" pe)" "${GW_EPOCH}" "gateway stamps the active policy epoch (${GW_EPOCH})"
assert_eq "$(field "$gw" rb)" 3 "gateway sets the root retry budget"
assert_eq "$(field "$order" rid)" "$(field "$gw" rid)" "R-T01 rid is identical along the chain"
assert_eq "$(field "$order" pid)" "$(field "$gw" iid)" "R-T06 order pid = gateway iid"
assert_ne "$(field "$order" iid)" "$(field "$gw" iid)" "R-T06 order gets a fresh iid"
assert_lt "$(field "$order" ttl)" "$(( $(field "$gw" ttl) + 1 ))" "R-T02 ttl never increases (gateway -> order)"
assert_eq "$(field "$order" bud)" 1000 "R-T01 bud is immutable"
assert_eq "$(field "$inv" pid)" "$(field "$order" iid)" "inventory pid = order iid"
assert_lt "$(field "$inv" ttl)" "$(( $(field "$order" ttl) + 1 ))" "R-T02 ttl never increases (order -> inventory)"
rb_sum=$(( $(field "$inv" rb) + $(field "$pay" rb) ))
assert_lt "${rb_sum}" 4 "R-T03 retry budget conserved across parallel fan-out (inventory+payment=${rb_sum} <= 3)"
assert_eq "$(field "$legacy_ship" rid)" "$(field "$gw" rid)" "context reaches shipping-svc through legacy-svc (same rid)"
assert_contains "$(header X-RPE-Result)" "out=ok" "gateway response carries X-RPE-Result"

client GET "${GW}/api/orders" "" -H 'X-RPE-Context: v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=99999, bud=99999, rb=99, cs=none, rt="GET /api/orders"'
assert_eq "$(field "$(echo "${BODY}" | jq -r .context)" bud)" 1000 "R-S01 client-supplied context is ignored at the gateway"

client GET "http://order-svc.rpe-demo:8080/orders" "" -H 'X-RPE-Context: v=1, rid=nonsense'
assert_eq "${CODE}" 400 "invalid context rejected by a service"
assert_contains "$(header X-RPE-Result)" "rsn=invalid-context" "rejection reason is invalid-context"
client GET "http://payment-svc.rpe-demo:8080/pay" "" -H 'X-RPE-Context: v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=0, bud=300, rb=1, cs=none, rt="GET /api/orders"'
assert_eq "${CODE}" 200 "an expired-on-arrival request is still served (signal, never reject)"
assert_eq "$(field "$(echo "${BODY}" | jq -r .context)" cs)" expired "the service receives the cancellation signal (cs=expired)"
assert_contains "$(header X-RPE-Result)" "cs=expired" "the response carries the signal back upstream"
finish
