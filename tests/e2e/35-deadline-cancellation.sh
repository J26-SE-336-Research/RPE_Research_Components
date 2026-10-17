#!/usr/bin/env bash
# C1/C2: signal, never reject. A request that runs past its deadline is not rejected or cut off: the sidecars mark the
# chain as cancelled (cs=expired) and carry the signal downstream and upstream; each service decides what to skip
# (non-critical work such as loyalty points) and what must complete (critical work such as charging the card).
source "$(dirname "$0")/lib.sh"
reset_demo
fault payment-svc '{"latencyMs":1500}'
client GET "${GW}/api/orders"
assert_eq "${CODE}" 200 "slow payment exceeds the 1000 ms budget: the request is still served (no 504)"
assert_contains "$(header X-RPE-Result)" "cs=expired" "the late chain carries the cancellation signal upstream (cs=expired)"
assert_ge "${MS}" 1500 "the slow work was not cut off at the deadline (${MS} ms)"
sleep 1
assert_eq "$(counters payment-svc | jq -r '.cards_charged // 0')" 1 "critical work (payment) completes even when the request is late"
assert_eq "$(counters inventory-svc | jq -r '.stock_reserved // 0')" 1 "inventory committed"
fault payment-svc '{}'

# A later call inside a cancelled chain: the service still answers and decides what to do.
cancelled='v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=500, bud=1000, rb=1, cs=expired, rt="GET /api/orders"'
points_before=$(counters loyalty-svc | jq -r '.points_awarded // 0')
client GET "http://loyalty-svc.rpe-demo:8080/award" "" -H "X-RPE-Context: ${cancelled}"
assert_eq "${CODE}" 200 "a cancelled request to a non-critical service is answered"
assert_eq "$(counters loyalty-svc | jq -r '.points_awarded // 0')" "${points_before}" "non-critical work (loyalty points) is skipped on a cancelled chain"
assert_ge "$(counters loyalty-svc | jq -r '.work_skipped // 0')" 1 "the service records the work it skipped"
client GET "http://payment-svc.rpe-demo:8080/pay" "" -H "X-RPE-Context: ${cancelled}"
assert_eq "${CODE}" 200 "a cancelled request to a critical service is answered"
assert_eq "$(counters payment-svc | jq -r '.cards_charged // 0')" 2 "critical work (payment) still completes on a cancelled chain"

client GET "${GW}/api/orders"
assert_eq "${CODE}" 200 "after removing the fault the chain is fast again"
assert_contains "$(header X-RPE-Result)" "cs=none" "an on-time chain is not cancelled (cs=none)"
assert_eq "$(counters payment-svc | jq -r '.cards_charged // 0')" 3 "payment commits once per request"
finish
