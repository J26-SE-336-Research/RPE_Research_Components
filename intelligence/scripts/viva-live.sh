#!/usr/bin/env bash
# Live viva demo of C4 on the kind cluster: real Prometheus and Tempo data, real C3 policies, real sidecars.
# Uses only what the project already has: the in-cluster e2e-client pod, the hub (admin), C3's policy controls,
# the demo-app fault switch (/admin/faults) and make reset-data. No code is changed.
#
#   intelligence/scripts/viva-live.sh warmup              # traffic running? LIVE_DATA? services HEALTHY?
#   intelligence/scripts/viva-live.sh lowrate [svc] [r]   # leaky bucket of svc (payment-svc) to r req/s (1): RATE_TOO_LOW
#   intelligence/scripts/viva-live.sh overload [svc]      # fault on svc: latency 500 ms, 10% errors: APP_OVERLOADED
#   intelligence/scripts/viva-live.sh recover [svc] [r]   # fault off, rate back to r (100)
#   intelligence/scripts/viva-live.sh deadline [ms]       # GET /api/orders deadline to ms (100): a deadline card
#   intelligence/scripts/viva-live.sh status              # health, latest c4-auto changes, held changes, waiting cards
#   intelligence/scripts/viva-live.sh toohigh [svc] [r] [burn] [par]  # rate too high from the start (rate learning fix): svc rate r (200),
#                                                         #   fault cpuBurnMs burn (20), a load pod alternating busy (par loops, 8) and quiet
#   intelligence/scripts/viva-live.sh toohigh-stop [svc] [r]  # undo toohigh: load pod deleted, fault off, rate back to r (100)
#   intelligence/scripts/viva-live.sh reset               # everything back (make reset-data)
# Run from Git Bash with kubectl, jq and make; MSYS_NO_PATHCONV is set here so in-pod paths are not rewritten.
set -euo pipefail
export MSYS_NO_PATHCONV=1
CTX="${KCTX:-kind-rpe}"
C4="http://rpe-intelligence.rpe-system:8090/api/v2/intelligence"
HUB="http://rpe-hub.rpe-system"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
command -v kubectl >/dev/null && command -v jq >/dev/null || { echo "needs kubectl and jq" >&2; exit 2; }

pod() { kubectl --context "$CTX" -n rpe-e2e exec e2e-client -- "$@"; }
c4() { pod curl -s --max-time 30 "$C4$1"; }
hub_login() { pod curl -s --max-time 30 -c /tmp/jar-viva -o /dev/null -X POST -H 'Content-Type: application/json' \
  --data '{"username":"admin","password":"admin"}' "$HUB/api/hub/login"; }
hub_post() { pod curl -s --max-time 60 -b /tmp/jar-viva -w ' (HTTP %{http_code})' -X POST -H 'Content-Type: application/json' --data "$2" "$HUB$1"; echo; }
fault() { pod curl -s --max-time 10 -o /dev/null -w "fault on $1: HTTP %{http_code}\n" -X POST -H 'Content-Type: application/json' --data "$2" "http://$1.rpe-demo:8080/admin/faults"; }
look() { echo; echo "  → Admin Panel: $*"; }

status() {
  local s; s=$(c4 "")
  echo "== Health (live)"
  echo "$s" | jq -r '[.assessments[]? | select(.source == "PROMETHEUS")] | sort_by(.target)[] | "  \(.health | . + " " * (18 - length))\(.target)"'
  echo "== Rate v2 per service (held changes are tried again every cycle)"
  echo "$s" | jq -r '(.rateEvidence // {}) | to_entries[] | "  \(.key): \(.value.diagnosis), rate \(.value.rate)\(if (.value.held // []) | length > 0 then "\n      " + ((.value.held) | join("\n      ")) else "" end)"'
  echo "== Latest automatic rate/retry changes (c4-auto)"
  echo "$s" | jq -r '[.changes[]? | select(.reviewer != null and (.reviewer | startswith("c4-auto")))] | sort_by(.proposal.generatedAt) | reverse | .[0:8][]
    | "  \(.proposal.generatedAt[11:19])  \(.proposal.action) \(.proposal.target): \(.proposal.currentValue) -> \(.proposal.proposedValue)  \(.reviewer)  \(.delivery)"'
  echo "== Cards waiting for a person (deadline and joint only)"
  echo "$s" | jq -r '[.changes[]? | select(.decision == "PENDING_APPROVAL" and .delivery != "SUPERSEDED" and .delivery != "EXPIRED" and .proposal.source != "MOCK")]
    | if length == 0 then "  (none)" else .[] | "  \(.proposal.action) \(.proposal.target): \(.proposal.currentValue) -> \(.proposal.proposedValue)" end'
}

case "${1:-}" in
  warmup)
    echo "== Traffic generator"
    kubectl --context "$CTX" -n rpe-e2e get deploy rpe-traffic -o jsonpath='  rpe-traffic: {.status.readyReplicas}/{.spec.replicas} ready{"\n"}'
    echo "== Telemetry"
    c4 /connections | jq -r 'to_entries[] | "  \(.key): \(.value.state)"'
    status
    look "Recommendations → Health: every service HEALTHY (a fresh C4 needs 5–7 minutes to learn); Telemetry: Live"
    ;;
  lowrate)
    svc="${2:-payment-svc}"; r="${3:-1}"
    hub_login
    echo "== Leaky bucket of $svc to $r req/s (C3 rate-limit control, two-phase rollout)"
    hub_post /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"$svc\",\"ratePerSec\":$r,\"rollout\":\"two-phase\"}"
    echo "  The traffic (~3 req/s) now exceeds the rate: the queue fills within 1–2 minutes (RATE_TOO_LOW: the app is fine)."
    look "Health → $svc shows the rate diagnosis; Reviewed → rate raises approved by c4-auto (or 'held: …' in Health while a deadline is not settled). No card waits for a person."
    ;;
  overload)
    svc="${2:-payment-svc}"
    echo "== Fault on $svc: latency 500 ms, 10% errors"
    fault "$svc" '{"latencyMs":500,"errorRate":0.1}'
    look "Health → $svc STRESSED/OVERLOADED; Reviewed → cuts of at most −20% and retry → 0 by c4-auto (never below the floor)."
    ;;
  recover)
    svc="${2:-payment-svc}"; r="${3:-100}"
    echo "== Fault off on $svc, rate back to $r req/s"
    fault "$svc" '{}'
    hub_login
    hub_post /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"$svc\",\"ratePerSec\":$r,\"rollout\":\"two-phase\"}"
    look "Health → $svc back to HEALTHY; Reviewed → raises by c4-auto; a too-generous rate is lowered in −10% steps after generous-wait (2 min)."
    ;;
  deadline)
    ms="${2:-100}"
    hub_login
    echo "== Route deadline of GET /api/orders to $ms ms (like e2e 95)"
    hub_post /api/hub/policies "{\"control\":\"chain-deadline\",\"service\":\"gateway\",\"route\":\"GET /api/orders\",\"deadlineMs\":$ms,\"rollout\":\"two-phase\"}"
    echo "  Requests are still served; they are signalled (cs=expired). C4 proposes a deadline increase after 1–3 minutes."
    look "Approval queue → a deadline card waiting for a person (Review → Approve). Health → rate changes on the chain are 'held: deadline not settled …, trying again'."
    ;;
  toohigh)
    svc="${2:-payment-svc}"; r="${3:-200}"; burn="${4:-20}"; par="${5:-8}"
    hub_login
    echo "== Leaky bucket of $svc to $r req/s: far above what the app can do once it burns CPU"
    hub_post /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"$svc\",\"ratePerSec\":$r,\"rollout\":\"two-phase\"}"
    echo "== Fault on $svc: cpuBurnMs $burn (each request burns CPU, so the app gets slower with more load)"
    fault "$svc" "{\"cpuBurnMs\":$burn}"
    echo "== Load pod rpe-viva-load: 3 min busy ($par parallel loops on GET /api/orders), 3 min quiet, repeating"
    kubectl --context "$CTX" -n rpe-e2e delete pod rpe-viva-load --ignore-not-found --wait=true >/dev/null
    kubectl --context "$CTX" -n rpe-e2e run rpe-viva-load --image=curlimages/curl:8.11.1 --restart=Never --labels=app.kubernetes.io/part-of=rpe \
      --command -- /bin/sh -c "while true; do end=\$((\$(date +%s) + 180)); i=0; while [ \$i -lt $par ]; do i=\$((i+1)); \
(while [ \$(date +%s) -lt \$end ]; do curl -s -o /dev/null --max-time 5 http://gateway.rpe-demo:8080/api/orders; done) & done; wait; sleep 180; done"
    echo "  Watch (refresh with: $0 status), allow 10-15 minutes:"
    echo "  1. quiet periods teach P0: Health evidence 'P0 … ms from the N quietest of M windows'"
    echo "  2. busy periods: $svc APP_OVERLOADED ('app time … > 1.5 × P0' or 'app time grows with load'), not HEALTHY"
    echo "  3. automatic cuts by c4-auto in steps of at most −20% (Reviewed tab), never a card for a person"
    echo "  4. the rate settles below what $svc really managed: ceiling 0.9 × accepted req/s (remembered 10 min in the demo)"
    echo "  Nothing happens? Raise the burn (e.g. 40) or the loops (e.g. 12): $0 toohigh $svc $r 40 12"
    look "Health → $svc rate diagnosis and P0 evidence; Reviewed → c4-auto cuts. Undo everything with: $0 toohigh-stop"
    ;;
  toohigh-stop)
    svc="${2:-payment-svc}"; r="${3:-100}"
    echo "== Undo toohigh: load pod deleted, fault off on $svc, rate back to $r req/s"
    kubectl --context "$CTX" -n rpe-e2e delete pod rpe-viva-load --ignore-not-found --wait=false
    fault "$svc" '{}'
    hub_login
    hub_post /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"$svc\",\"ratePerSec\":$r,\"rollout\":\"two-phase\"}"
    look "Health → $svc back to HEALTHY within a few minutes (C4 keeps the learned P0; make reset-data starts fresh)."
    ;;
  status) status ;;
  reset)
    echo "== Reset: faults off, policies and data back to the demo start (make reset-data)"
    kubectl --context "$CTX" -n rpe-e2e delete pod rpe-viva-load --ignore-not-found --wait=false || true
    for svc in gateway order-svc inventory-svc payment-svc loyalty-svc shipping-svc fraud-check-svc; do fault "$svc" '{}' || true; done
    (cd "$ROOT" && unset MSYS_NO_PATHCONV && make reset-data)
    look "a fresh C4 relearns its baselines (5–7 minutes); rate recommendations work without any C3 policy (the sidecar's rate is the reference)."
    ;;
  *) sed -n '2,18p' "$0"; exit 2 ;;
esac
