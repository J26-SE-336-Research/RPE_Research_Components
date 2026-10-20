#!/usr/bin/env bash
# C1 novelty, retry safety (rule R-R02). RPE never rejects an original request because its deadline passed (the
# sidecars only signal cs=expired); it refuses a RETRY of a chain that can no longer finish in time, because that retry
# repeats side effects. payment-svc is made slower than the chain deadline and order-svc gives up on an attempt after
# 600 ms and retries once: a plain retry charges the card twice, the same policy through the RPE retry gate once.
# Counters are read per request id, so the background traffic keeps running.
source "$(dirname "$0")/lib.sh"
hub_login admin >/dev/null

# --- 1. plain retry: the abandoned attempt still completes, the retry charges again ----------------------------------
hub POST /api/hub/demos/retry '{"mode":"plain","latencyMs":1200,"attemptTimeoutMs":600}'
assert_eq "${CODE}" 200 "the retry experiment runs (plain)"
assert_eq "$(echo "${BODY}" | jq -r .attempts)" 2 "plain: order-svc sent a second payment attempt"
assert_eq "$(echo "${BODY}" | jq -r '.retryRefused // ""')" "" "plain: nothing stopped the retry"
assert_eq "$(echo "${BODY}" | jq -r '.cardsCharged // 0')" 2 "plain: the card was charged twice for one order"

# --- 2. the same policy through the RPE retry gate ----------------------------------------------------------------
hub POST /api/hub/demos/retry '{"mode":"rpe","latencyMs":1200,"attemptTimeoutMs":600}'
assert_eq "${CODE}" 200 "the retry experiment runs (rpe)"
assert_eq "$(echo "${BODY}" | jq -r .attempts)" 1 "rpe: no second payment attempt was sent"
assert_contains "insufficient-time deadline cancelled" "$(echo "${BODY}" | jq -r '.retryRefused')" "rpe: the gate refused the retry ($(echo "${BODY}" | jq -r '.retryRefused'))"
assert_eq "$(echo "${BODY}" | jq -r .rule)" R-R02 "rpe: the refusal names rule R-R02"
assert_eq "$(echo "${BODY}" | jq -r '.cardsCharged // 0')" 1 "rpe: the card was charged once"
assert_eq "$(echo "${BODY}" | jq -r '.chain.result.term')" "?1" "rpe: the failure is terminal for the caller too (term=?1)"
client GET "http://order-svc.rpe-demo:8080/admin/retry"
assert_eq "$(echo "${BODY}" | jq -r .mode)" off "the experiment turns order-svc's retry knob off again"

# --- 3. the receiver refuses an expired retry, and serves an expired original -------------------------------------
rid=$(openssl rand -hex 16)
expired="v=1, rid=\"${rid}\", iid=\"00f067aa0ba902b7\", pid=\"0000000000000000\", ttl=500, bud=1000, rb=1, cs=expired, rt=\"GET /api/orders\""
client GET "http://payment-svc.rpe-demo:8080/pay" "" -H "X-RPE-Context: ${expired}" -H "X-RPE-Hop: att=2"
assert_eq "${CODE}" 504 "a retry (att=2) of an expired chain is refused before the application"
assert_contains "$(header X-RPE-Result)" "term=?1" "the refusal is terminal"
assert_contains "$(header X-RPE-Result)" "cs=expired" "the refusal carries the chain state"
client GET "http://payment-svc.rpe-demo:8080/admin/counters?rid=${rid}"
assert_eq "$(echo "${BODY}" | jq -r '.cards_charged // 0')" 0 "the refused retry charged nothing"
client GET "http://payment-svc.rpe-demo:8080/pay" "" -H "X-RPE-Context: ${expired}"
assert_eq "${CODE}" 200 "the original request of the same expired chain is served (signal, never reject)"
client GET "http://payment-svc.rpe-demo:8080/admin/counters?rid=${rid}"
assert_eq "$(echo "${BODY}" | jq -r '.cards_charged // 0')" 1 "and its critical work completes"
finish
