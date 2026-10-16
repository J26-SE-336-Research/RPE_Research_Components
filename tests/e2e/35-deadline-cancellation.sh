#!/usr/bin/env bash
# C1/C2: the propagated deadline is enforced, downstream work is abandoned cooperatively before commit
# (point of no return), and the expired result is terminal.
source "$(dirname "$0")/lib.sh"
reset_demo
fault payment-svc '{"latencyMs":1500}'
client GET "${GW}/api/orders"
assert_eq "${CODE}" 504 "slow payment exceeds the 1000 ms budget -> 504"
assert_contains "$(header X-RPE-Result)" "term=?1" "deadline failure is terminal (never retried)"
assert_lt "${MS}" 1400 "the client gets the answer near the deadline, not after the slow call (${MS} ms)"
sleep 2
c=$(counters payment-svc)
assert_eq "$(echo "$c" | jq -r '.cards_charged // 0')" 0 "payment did NOT commit (abandoned before the point of no return)"
assert_ge "$(echo "$c" | jq -r '.work_abandoned // 0')" 1 "payment observed the deadline and abandoned its work"
assert_eq "$(counters inventory-svc | jq -r '.stock_reserved // 0')" 1 "inventory committed in time (no atomicity across fan-out: documented non-goal)"
fault payment-svc '{}'
client GET "${GW}/api/orders"
assert_eq "${CODE}" 200 "after removing the fault the chain recovers"
assert_eq "$(counters payment-svc | jq -r '.cards_charged // 0')" 1 "payment commits exactly once on the successful request"
finish
