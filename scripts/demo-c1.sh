#!/usr/bin/env bash
# Runs the C1 demos on the cluster through the Admin Panel API and prints the results. These are the same steps as
# the live demo in the panel, so use it to check everything works before presenting, or to show it in a terminal.
#   scripts/demo-c1.sh                 # all steps
#   scripts/demo-c1.sh chain retry     # chosen steps: chain interop conformance topology signal retry
# The signal and retry steps slow down payment-svc for a moment and then reset it, so only run them on the demo cluster.
source "$(dirname "$0")/../tests/e2e/lib.sh"
STEPS="${*:-chain interop conformance topology signal retry}"
title() { printf '\n\033[1m%s\033[0m\n' "$*"; }
line()  { printf '  %s\n' "$*"; }
hub_login admin >/dev/null
[[ "${CODE}" == 200 ]] || { echo "cannot sign in to the hub (is the cluster deployed?)" >&2; exit 1; }

step_chain() {
  title "1. One request, hop by hop (X-RPE-Context and the trail)"
  hub POST /api/hub/chain '{"path":"/api/orders"}'
  local rid hops failed
  rid=$(echo "${BODY}" | jq -r '.rid // empty')
  hops=$(echo "${BODY}" | jq -r '.hops | length')
  failed=$(echo "${BODY}" | jq -r '[.hops[].checks[] | select(.pass==false) | .rule] | unique | join(", ")')
  line "GET /api/orders -> $(echo "${BODY}" | jq -r .status) in $(echo "${BODY}" | jq -r .ms) ms, ${hops} hops, request id ${rid}"
  line "protocol checks at every hop: ${failed:-all passed (R-S01, R-T01, R-T02, R-T03, R-T06, R-F05)}"
  echo "${BODY}" | jq -r '.hops[] | select(.fields) | "    \(.service): ttl=\(.fields.ttl) ms of bud=\(.fields.bud), rb=\(.fields.rb), cs=\(.fields.cs), pe=\(.fields.pe)"'
  for _ in $(seq 1 10); do hub GET "/api/hub/protocol/attribution/${rid}"; [[ "${CODE}" == 200 ]] && break; sleep 1; done
  if [[ "${CODE}" == 200 ]]; then
    line "trail (${CODE}): $(echo "${BODY}" | jq -r .trail)  ($(echo "${BODY}" | jq -r .bytes) bytes)"
    echo "${BODY}" | jq -r '.hops[] | "    \(("  " * .depth) // "")\(.service): started at \(.startMs) ms, took \(.elapsedMs) ms, own \(.selfMs) ms, \(.label)"'
    line "used most of the deadline: $(echo "${BODY}" | jq -r '.hops[.bottleneck-1].service')"
  else
    line "no trail reported for this request (is rpe-protocol running?)"
  fi
}

step_interop() {
  title "2. Java and Go decode the same header (rpe-java and rpe-go, each in its own process)"
  local good bad
  good='v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=287, bud=300, rb=3, cs=none, pe="e42", rt="POST /api/orders"'
  bad=${good/ttl=287/ttl=301}
  hub POST /api/hub/protocol/compare "$(jq -nc --arg v "${good}" '{kind:"context", value:$v}')"
  line "valid header:  go valid=$(echo "${BODY}" | jq -r .go.valid), java valid=$(echo "${BODY}" | jq -r .java.valid), agree=$(echo "${BODY}" | jq -r .agree)"
  hub POST /api/hub/protocol/compare "$(jq -nc --arg v "${bad}" '{kind:"context", value:$v}')"
  line "ttl > bud:     go $(echo "${BODY}" | jq -r '"\(.go.error.code) \(.go.error.rule)"'), java $(echo "${BODY}" | jq -r '"\(.java.error.code) \(.java.error.rule)"'), agree=$(echo "${BODY}" | jq -r .agree)"
}

step_conformance() {
  title "3. Conformance: every shared test case, run now by both libraries"
  hub GET /api/hub/protocol/conformance
  line "rpe-go $(echo "${BODY}" | jq -r '"\(.goPassed)/\(.total)"'), rpe-java $(echo "${BODY}" | jq -r '"\(.javaPassed)/\(.total)"') ($(echo "${BODY}" | jq -r '.files | length') files)"
}

step_topology() {
  title "4. Topology rebuilt from the trails of the last 5 minutes (no tracing backend, no sampling)"
  hub GET "/api/hub/protocol/topology?window=300"
  line "$(echo "${BODY}" | jq -r '"\(.topology.trails) requests, \(.topology.incomplete) marked incomplete, trail \(.topology.avgBytes) B on average"')"
  echo "${BODY}" | jq -r '.topology.edges[] | "    \(.from) -> \(.to): \(.calls) calls, p50 \(.p50Ms) ms\(if .unknown then "  (no RPE: unknown hop)" else "" end)"'
  line "against the declared call graph: precision $(echo "${BODY}" | jq -r .comparison.precision)%, recall $(echo "${BODY}" | jq -r .comparison.recall)%"
  line "missing: $(echo "${BODY}" | jq -r '.comparison.missing | if length == 0 then "none" else join(", ") end')"
  line "ground truth: $(echo "${BODY}" | jq -r .comparison.source)"
}

step_signal() {
  title "5. Signal, never reject: payment-svc made slower (1500 ms) than the chain deadline"
  hub POST /api/hub/demos/deadline '{"latencyMs":1500}'
  line "client response $(echo "${BODY}" | jq -r .chain.status) after $(echo "${BODY}" | jq -r .chain.ms) ms (served, not rejected)"
  line "chain state cs=$(echo "${BODY}" | jq -r .signal.cs), retryable: $(echo "${BODY}" | jq -r .signal.retryable)"
  line "this request: payment charged $(echo "${BODY}" | jq -r '.request["payment-svc"].cards_charged // 0') (critical work completes even when late)"
  line "loyalty skipped its work $(echo "${BODY}" | jq -r '.request["loyalty-svc"].work_skipped // 0') time(s): optional work skips only a call made after the chain was"
  line "  cancelled; here loyalty ran in parallel before the deadline passed (e2e 35 shows the skip on a cancelled chain)"
}

step_retry() {
  title "6. Retry safety (R-R02): the same retry policy without and with the RPE retry gate"
  local mode
  for mode in plain rpe; do
    hub POST /api/hub/demos/retry "{\"mode\":\"${mode}\",\"latencyMs\":1200,\"attemptTimeoutMs\":600}"
    line "$(printf '%-6s' "${mode}") card charged $(echo "${BODY}" | jq -r '.cardsCharged // 0')x, payment attempts $(echo "${BODY}" | jq -r .attempts), retry $(echo "${BODY}" | jq -r 'if .retryRefused == "" then "sent" else "refused (\(.rule): \(.retryRefused))" end')"
  done
}

for s in ${STEPS}; do
  case "${s}" in
    chain|interop|conformance|topology|signal|retry) "step_${s}" ;;
    *) echo "unknown step ${s} (chain interop conformance topology signal retry)" >&2; exit 2 ;;
  esac
done
echo
