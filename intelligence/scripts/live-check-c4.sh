#!/usr/bin/env bash
# READ-ONLY check of the running C4 (intelligence layer): only GET requests, nothing in the cluster is changed.
#   GET /api/v2/intelligence/connections   Prometheus / Tempo reachable with live data?
#   GET /api/v2/intelligence               health label per service and chain, current recommendations and who approved them,
#                                          rate v2 diagnosis per service, deadline v2 state per chain, joint cards
#   GET /api/v2/intelligence/audit?limit=20  the last audit entries (persisted in PostgreSQL in the cluster)
#
#   intelligence/scripts/live-check-c4.sh --port-forward   # the kind cluster (kubectl port-forward, context kind-rpe)
#   intelligence/scripts/live-check-c4.sh                  # C4 at http://localhost:8090
# Needs curl and jq.
set -euo pipefail
URL="${C4_URL:-http://localhost:8090}"; PF=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port-forward) PF=1; shift ;;
    --url) URL="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
command -v curl >/dev/null && command -v jq >/dev/null || { echo "needs curl and jq" >&2; exit 2; }
if [[ -n "$PF" ]]; then
  kubectl --context "${KCTX:-kind-rpe}" -n rpe-system port-forward svc/rpe-intelligence 18090:8090 >/dev/null 2>&1 &
  PF_PID=$!; trap 'kill $PF_PID 2>/dev/null || true' EXIT; URL="http://localhost:18090"; sleep 3
fi
API="$URL/api/v2/intelligence"
get() { curl -sf "$1" || { echo "C4 not reachable: $1" >&2; exit 1; }; }

echo "== Connections ($URL)"
get "$API/connections" | jq -r 'to_entries[] | "  \(.key): \(.value.state // .value)\(if .value.detail then "  (" + (.value.detail | tostring | .[0:120]) + ")" else "" end)"'

STATUS=$(get "$API")
echo "== Collection"
echo "$STATUS" | jq -r '.collection | "  enabled: \(.enabled), delivery to C3: \(.deliveryConfigured), services: \((.configuredServices // []) | join(", "))\(if (.problems // []) | length > 0 then "\n  problems: " + ((.problems)[0:5] | join("; ")) else "" end)"'

echo "== Health (live assessments)"
echo "$STATUS" | jq -r '[.assessments[]? | select(.source != "MOCK")] | if length == 0 then "  (none yet: C4 is still learning, or collection is off)" else
  sort_by(.source, .target)[] | "  \(.health | . + " " * (18 - length))\(.source | . + " " * (11 - length))\(.target)" end'

echo "== Rate v2 (per service)"
echo "$STATUS" | jq -r '(.rateEvidence // {}) | if length == 0 then "  (no rate decision yet)" else to_entries[] |
  "  \(.key): \(.value.diagnosis)  rate \(.value.rate)\(if .value.proposed then " -> \(.value.proposed)" else "" end)\(if .value.autoPausedUntil then "  automatic approval paused until \(.value.autoPausedUntil)" else "" end)  (\(.value.reason))" end'

echo "== Deadline v2 (per chain)"
echo "$STATUS" | jq -r '(.deadlineEvidence // {}) | if length == 0 then "  (no chain analysed yet)" else to_entries[] |
  "  \(.key): \(.value.state)  \(.value.decisionCase)\(if .value.holdReason then " " + .value.holdReason else "" end)  deadline \(.value.currentMs) ms\(if .value.proposedMs then " -> \(.value.proposedMs) ms" else "" end)\(if .value.verification then "  [" + .value.verification + "]" else "" end)" end'

echo "== Current recommendations (not superseded or expired)"
echo "$STATUS" | jq -r '[.changes[]? | select(.delivery != "SUPERSEDED" and .delivery != "EXPIRED")] as $c | ([.joints[]?.parts[]?.proposalId]) as $j
  | if ($c | length) == 0 then "  (none)" else $c[0:20][] |
  "  \(if (.proposal.id | IN($j[])) then "[joint] " else "" end)\(.proposal.action) \(.proposal.target): \(.proposal.currentValue) -> \(.proposal.proposedValue)  \(if .decision == "APPROVED" then "approved by " + (.reviewer // "?") elif .decision == "PENDING_APPROVAL" then "waiting for a person" else .decision end)  delivery \(.delivery)" end'
echo "  joint cards: $(echo "$STATUS" | jq '[.joints[]?] | length')"

echo "== Audit (last 20)"
get "$API/audit?limit=20" | jq -r '"  persisted in PostgreSQL: \(.persistent)", (.entries[]? |
  "  \(.at | .[0:19])  \(.kind | . + " " * (22 - length))\(.action) \(.target): \(.currentValue) -> \(.proposedValue)  \(.decision // "")  \(.actor // "")")'
