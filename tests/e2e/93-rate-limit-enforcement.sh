#!/usr/bin/env bash
# A rate limit delivered by C3 is enforced by the target service's sidecar: activating a policy sets the rate of the
# sidecar's inbound leaky bucket (rpe_backpressure_inbound_rate_per_second), staged versions are not enforced, the
# sidecar acknowledges after it applied the rate, and traffic through the service really is shaped.
#   - the rate reaches only the target service's sidecars (the gateway keeps its own rate);
#   - a very low rate makes a burst of chain requests slow (the leaky bucket queues payment calls), a high rate lets
#     them through, and the change takes effect at runtime in both directions;
#   - a restarted sidecar gets the rate back from the Operator's drift repair;
#   - a later version without a rate (a retry-ratio change) keeps the rate;
#   - a rate the sidecar would refuse is refused before delivery.
source "$(dirname "$0")/lib.sh"
hub_login admin >/dev/null
traffic_pause
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
psql() { k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -At -c "$1"; }
id="e2e-rate-$(date +%s)"
final_rate=100   # the sidecar default; left in place for the suites that follow
trap 'hub POST /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":${final_rate},\"policyId\":\"${id}\",\"rollout\":\"naive\"}" >/dev/null 2>&1; traffic_resume' EXIT

# set_rate N -> final phase of the rollout that delivers rate N to payment-svc
set_rate() {
  hub POST /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":$1,\"policyId\":\"${id}\",\"rollout\":\"two-phase\"}"
  wait_settled "$(echo "${BODY}" | jq -r '.rollout.metadata.name // empty')" 120
}
acked_with_hash() { psql "select count(*) from policy.deliveries where epoch='$1' and confirmed_state='active' and confirmed_hash=hash"; }
# wait_rate SERVICE N -> succeeds once the sidecar enforces rate N (up to 60 s)
wait_rate() { for _ in $(seq 1 60); do [[ "$(rate_gauge "$1")" == "$2" ]] && return 0; sleep 1; done; return 1; }

gateway_before=$(rate_gauge gateway)

# --- 1. the delivered rate is applied by the target sidecar, and only by it ----------------------------------------
assert_eq "$(set_rate 200)" Complete "rate limit ${id}-v1 (200 requests/s) is delivered"
assert_eq "$(rate_gauge payment-svc)" 200 "the payment-svc sidecar enforces 200 requests/s"
assert_eq "$(rate_gauge gateway)" "${gateway_before}" "the gateway sidecar keeps its own rate (${gateway_before})"
for _ in $(seq 1 15); do [[ "$(acked_with_hash "${id}-v1")" -ge 1 ]] && break; sleep 1; done
assert_ge "$(acked_with_hash "${id}-v1")" 1 "the sidecar acknowledged the version it applied, with the same hash"

# --- 2. traffic is shaped by the delivered rate -----------------------------------------------------------------------
fast=$(burst 8)
assert_lt "${fast}" 2500 "at 200 requests/s a burst of 8 chain requests passes quickly (slowest ${fast} ms)"
assert_eq "$(set_rate 2)" Complete "rate limit ${id}-v2 (2 requests/s) is delivered"
assert_eq "$(rate_gauge payment-svc)" 2 "the sidecar now enforces 2 requests/s"
slow=$(burst 8)
# 8 requests leave the bucket at 0, 0.5, ... 3.5 s: about 3.5 s plus the unshaped chain time
assert_ge "${slow}" 3000 "at 2 requests/s the same burst is shaped by the leaky bucket: the 8th request leaves after 3.5 s (slowest ${slow} ms, unshaped ${fast} ms)"
assert_eq "$(set_rate 200)" Complete "rate limit ${id}-v3 (back to 200 requests/s) is delivered"
assert_eq "$(rate_gauge payment-svc)" 200 "the sidecar enforces 200 requests/s again, at runtime"
again=$(burst 8)
assert_lt "${again}" 2500 "the burst passes quickly again (slowest ${again} ms)"

# --- 3. a restarted sidecar gets the rate back from drift repair --------------------------------------------------------
old=$(pod_of payment-svc)
k -n rpe-demo delete pod "${old}" --wait=false >/dev/null
k -n rpe-demo rollout status deploy/payment-svc --timeout=180s >/dev/null
k -n rpe-demo wait --for=delete pod/"${old}" --timeout=120s >/dev/null 2>&1 || true
if wait_rate payment-svc 200; then ok "the restarted payment-svc sidecar is back at 200 requests/s (drift repair)"; else bad "the restarted sidecar did not get its rate back (gauge $(rate_gauge payment-svc))"; fi

# --- 4. a version without a rate keeps the rate -----------------------------------------------------------------------------
hub POST /api/hub/policies "{\"control\":\"retry-ratio\",\"service\":\"payment-svc\",\"retryRatio\":0.2,\"policyId\":\"${id}\",\"rollout\":\"two-phase\"}"
assert_eq "$(wait_settled "$(echo "${BODY}" | jq -r '.rollout.metadata.name // empty')" 120)" Complete "retry ratio ${id}-v4 is delivered"
assert_eq "$(rate_gauge payment-svc)" 200 "the rate limit stays in force after a retry-ratio version (the new version keeps it)"

# --- 5. a rate the sidecar would refuse never leaves the hub -------------------------------------------------------------------
hub POST /api/hub/policies "{\"control\":\"rate-limit\",\"service\":\"payment-svc\",\"ratePerSec\":2000000,\"policyId\":\"${id}\",\"rollout\":\"two-phase\"}"
assert_eq "${CODE}" 400 "a rate above 1000000 requests/s is refused"
assert_eq "$(rate_gauge payment-svc)" 200 "the refused rate changed nothing"

# --- 6. only the rate is enforced: a queueCapacity inside a policy is ignored -----------------------------------------------
pay=$(pod_of payment-svc)
qc="e2e-qc-$(date +%s)"
capacity_before=$(queue_capacity payment-svc)
hub PUT "/api/hub/sidecars/${pay}/policy" "{\"epoch\":\"${qc}\",\"version\":1,\"state\":\"staged\",\"policy\":{\"leakyBucket\":{\"ratePerSec\":150,\"queueCapacity\":7}}}"
assert_eq "$(rate_gauge payment-svc)" 200 "a staged policy with a rate and a queueCapacity changes nothing"
hub POST "/api/hub/sidecars/${pay}/activate" "{\"epoch\":\"${qc}\"}"
assert_eq "$(rate_gauge payment-svc)" 150 "activated: the rate in the policy is enforced"
assert_eq "$(queue_capacity payment-svc)" "${capacity_before}" "the queueCapacity in the policy is ignored (still ${capacity_before})"

# --- 7. restore the default for the other suites ---------------------------------------------------------------------------------
assert_eq "$(set_rate "${final_rate}")" Complete "the default rate (${final_rate} requests/s) is delivered again"
assert_eq "$(rate_gauge payment-svc)" "${final_rate}" "the sidecar is back at ${final_rate} requests/s"
hub DELETE "/api/hub/sidecars/${pay}/policy/${qc}" >/dev/null   # the probe epoch is no longer the active one
trap traffic_resume EXIT
finish
