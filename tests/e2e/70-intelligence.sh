#!/usr/bin/env bash
# C4: intelligence layer is deployed and serving.
source "$(dirname "$0")/lib.sh"
client GET "http://rpe-intelligence.rpe-system:8090/actuator/health"
assert_eq "${CODE}" 200 "intelligence actuator health"
assert_eq "$(echo "${BODY}" | jq -r .status)" UP "intelligence status UP"
client GET "http://rpe-intelligence.rpe-system:8090/api/v2/intelligence"
assert_eq "${CODE}" 200 "adaptive intelligence API responds"
assert_eq "$(echo "${BODY}" | jq -r 'type')" object "API returns a JSON object"
finish
