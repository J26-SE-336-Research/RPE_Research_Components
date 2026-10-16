#!/usr/bin/env bash
# C1: fan-out policy (partial for optional children; failure for required children).
source "$(dirname "$0")/lib.sh"
reset_demo
fault loyalty-svc '{"errorRate":1.0}'
client GET "${GW}/api/orders"
assert_eq "${CODE}" 200 "optional loyalty failure still returns 200"
order_out=$(echo "${BODY}" | jq -r '.children[] | select(.name=="order") | .downstream.outcome')
assert_eq "${order_out}" partial "order-svc reports a partial outcome"
kids=$(echo "${BODY}" | jq -r '.children[] | select(.name=="order") | .downstream.children[] | select(.name=="loyalty") | .outcome')
assert_eq "${kids}" failed "per-child annotation shows loyalty failed"
fault loyalty-svc '{}'
fault payment-svc '{"errorRate":1.0}'
client GET "${GW}/api/orders"
assert_eq "${CODE}" 502 "required payment failure fails the request"
order_hdr=$(echo "${BODY}" | jq -r '.children[] | select(.name=="order") | .status')
assert_eq "${order_hdr}" 502 "order-svc fails the whole fan-out (required child)"
fault payment-svc '{}'
finish
