#!/usr/bin/env bash
# C4: intelligence layer is deployed and serving.
source "$(dirname "$0")/lib.sh"
client GET "http://rpe-intelligence.rpe-system:8090/actuator/health"
assert_eq "${CODE}" 200 "intelligence actuator health"
assert_eq "$(echo "${BODY}" | jq -r .status)" UP "intelligence status UP"
client GET "http://rpe-intelligence.rpe-system:8090/api/v2/intelligence"
assert_eq "${CODE}" 200 "adaptive intelligence API responds"
assert_eq "$(echo "${BODY}" | jq -r 'type')" object "API returns a JSON object"
assert_eq "$(echo "${BODY}" | jq -r '.deadlineEvidence | type')" object "deadline v2 evidence is published (additive field)"

# ---------- deadline v2 simulations (isolated: virtual clock, MOCK source, never delivered) ----------
INTEL_API="http://rpe-intelligence.rpe-system:8090/api/v2/intelligence"
client POST "${INTEL_API}/demo/deadline-too-long-under-overload" '{}'
assert_eq "${CODE}" 200 "deadline v2: 'too long under overload' simulation runs"
assert_ge "$(echo "${BODY}" | jq '[.deadlineEvidence[0].steps[] | select(.decisionCase=="B_LOWER_OVERLOAD" and .toMs < .fromMs)] | length')" 1 \
  "Case B lowers the deadline under overload (proposedMs < currentMs)"
assert_eq "$(echo "${BODY}" | jq '[.deadlineEvidence[0].steps[] | select(.toMs > .fromMs)] | length')" 0 "no increase under overload"
client POST "${INTEL_API}/demo/queue-rate-not-deadline" '{}'
assert_eq "${CODE}" 200 "deadline v2: 'queue rate, not deadline' simulation runs"
assert_eq "$(echo "${BODY}" | jq '.deadlineEvidence[0].steps | length')" 0 "no deadline proposal when the queue rate is the problem"
assert_eq "$(echo "${BODY}" | jq -r '.deadlineEvidence[0].evidence.lever')" RATE "lever diagnosis: RATE"
client GET "${INTEL_API}/outbox"
assert_eq "$(echo "${BODY}" | jq 'length')" 0 "simulations never reach the delivery outbox"
finish
