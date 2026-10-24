#!/usr/bin/env bash
# Runs every C4 demo scenario (POST /api/v2/intelligence/demo/{scenario}) and prints a short summary per recommendation:
# scenario, recommendation, current -> proposed, approved by (c4-auto / simulated-reviewer / waiting for a person), reason.
# The rate and retry scenarios are run twice: as a reviewer would see them, and with ?mode=auto (automatic approval on).
# Every scenario runs in an isolated engine inside C4; nothing is changed in the cluster and nothing is sent to Component 3.
#
#   intelligence/scripts/demo-c4.sh                          # C4 at http://localhost:8090 (e.g. ./mvnw spring-boot:run)
#   intelligence/scripts/demo-c4.sh --port-forward           # the kind cluster's C4 (kubectl port-forward, context kind-rpe)
#   intelligence/scripts/demo-c4.sh --only joint-recovery    # one scenario (both modes for a rate scenario)
#   C4_URL=http://host:8090 intelligence/scripts/demo-c4.sh
# Needs curl and jq. C4 must allow simulations (RRPE_MOCK_ENABLED=true; set in the demo cluster).
set -euo pipefail
URL="${C4_URL:-http://localhost:8090}"; ONLY=""; PF=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port-forward) PF=1; shift ;;
    --only) ONLY="$2"; shift 2 ;;
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

OLD=(healthy stressed overloaded retry-stressed recovery deadline-increase deadline-recovery multiple-chains)
DEADLINE=(deadline-too-long-under-overload deadline-too-short-day-one services-slowing queue-rate-not-deadline mixed-paths rejecting-middleware-504)
RATE=(rate-too-low rate-too-low-day-one rate-real-overload rate-recovery rate-short-burst rate-bursts retry-storm-rate)
JOINT=(joint-recovery joint-capacity-drop)

# one line per recommendation; rate scenarios list the applied/offered steps of the simulation
SUMMARY='
def num: if . == null then "?" elif (. * 1000 | floor) == (. * 1000) and . >= 1 then (. * 10 | round / 10 | tostring) else (. * 1000 | round / 1000 | tostring) end;
def unit(a): if a == "LEAKY_BUCKET" then " req/s" elif a == "CHAIN_DEADLINE" then " ms" else "" end;
def name(a): if a == "LEAKY_BUCKET" then "rate" elif a == "RETRY_BUDGET" then "retry ratio" elif a == "CHAIN_DEADLINE" then "deadline" else a end;
def who(c): if c.decision == "APPROVED" then "approved by " + (c.reviewer // "?") elif c.decision == "PENDING_APPROVAL" then "waiting for a person"
  elif c.decision == "REJECTED" then "rejected" else c.decision end;
def step(r): if r == "pending" then "waiting for a person" else "approved by " + r end;
# the reason: a deadline card leads with its headline; a joint deadline card with its calculation; rate and retry cards end with the rule
def reason($joint): if (.proposal.id | IN($joint[])) then (if .proposal.action == "CHAIN_DEADLINE" then .proposal.evidence[1] else .proposal.evidence[0] end)
  elif .proposal.action == "CHAIN_DEADLINE" then .proposal.evidence[0] else .proposal.evidence[-1] end;
def short: tostring | gsub("\\s+"; " ") | if length > 110 then .[0:107] + "..." else . end;
([.joints[]?.parts[]?.proposalId]) as $joint
| if .rateEvidence then
    (.rateEvidence[0].steps // []) as $s
    | if ($s | length) == 0 then "    no change   (final rate \(.rateEvidence[0].finalRate | num) req/s, shed \(.rateEvidence[0].shedAtEnd | num) req/s)"
      else ($s[] | "    \(name(.action)) \(.from | num) -> \(.to | num)\(unit(.action))   \(step(.reviewer))   [\(.phase | short)]   \(.evidence | short)"),
           "    final rate \(.rateEvidence[0].finalRate | num) req/s, lowest \(.rateEvidence[0].lowestRate | num), retry ratio \(.rateEvidence[0].finalRetry | num), shed at the end \(.rateEvidence[0].shedAtEnd | num) req/s"
      end
  else
    ([.changes[]? | select(.delivery != "SUPERSEDED" and .delivery != "EXPIRED")]) as $c
    | if ($c | length) == 0 then
        "    no change" + ( [.deadlineEvidence[]?.evidence // empty | " (\(.decisionCase) \(.holdReason // ""), lever \(.lever), state \(.state))"] | join(""))
      else ($c[] | "    \(if (.proposal.id | IN($joint[])) then "[joint] " else "" end)\(name(.proposal.action)) \(.proposal.target | split(" | ")[0]): \(.proposal.currentValue | num) -> \(.proposal.proposedValue | num)\(unit(.proposal.action))   \(who(.))   \(reason($joint) | short)")
      end
  end'

run() {  # scenario [auto]
  local s="$1" q="" label="$1"
  [[ "${2:-}" == auto ]] && q="?mode=auto" && label="$1 ?mode=auto"
  local body code
  body=$(curl -s -w '\n%{http_code}' -X POST "$API/demo/$s$q") || { echo "== $label: C4 not reachable at $URL" >&2; return 1; }
  code=${body##*$'\n'}; body=${body%$'\n'*}
  echo "== $label"
  if [[ "$code" != 200 ]]; then echo "    HTTP $code: $(echo "$body" | jq -r '.detail // .' 2>/dev/null | head -c 200)"; return 0; fi
  echo "$body" | jq -r "$SUMMARY"
}

curl -sf "$URL/actuator/health" >/dev/null || { echo "C4 is not reachable at $URL" >&2; exit 1; }
echo "C4 demo scenarios at $URL (isolated simulations; nothing is sent to Component 3)"
for s in "${OLD[@]}" "${DEADLINE[@]}" "${RATE[@]}" "${JOINT[@]}"; do
  if [[ -n "$ONLY" && "$s" != "$ONLY" ]]; then continue; fi
  run "$s"
  for r in "${RATE[@]}"; do if [[ "$s" == "$r" ]]; then run "$s" auto; fi; done
done
