#!/usr/bin/env bash
# Clean slate for demos: empties the policy store (PostgreSQL), the Redis fast path and the RPE custom
# resources, and restarts the demo services (clears proxy policy epochs, fault knobs and counters) and the
# intelligence layer (clears its learned baselines and open recommendations; the audit trail is recreated empty).
# Approved policies are append-only by design, so a reset recreates the store rather than deleting rows.
source "$(dirname "$0")/lib.sh"
log "Stopping the Operator and the intelligence layer (nothing may write while the stores are replaced:"
log "otherwise the old Operator re-fills the new Redis from the old PostgreSQL and C4 keeps delivering)"
k -n rpe-system scale deploy/rpe-operator deploy/rpe-intelligence --replicas=0 >/dev/null
k -n rpe-system wait --for=delete pod -l 'app in (rpe-operator,rpe-intelligence)' --timeout=120s >/dev/null 2>&1 || true
log "Deleting RPE custom resources"
k -n rpe-system delete rperollouts --all --ignore-not-found >/dev/null
k -n rpe-system delete rpepolicies --all --ignore-not-found >/dev/null
log "Recreating the policy store and the Redis fast path"
k -n rpe-system rollout restart deploy/rpe-postgres deploy/rpe-redis >/dev/null
k -n rpe-system rollout status deploy/rpe-postgres --timeout=180s >/dev/null
k -n rpe-system rollout status deploy/rpe-redis --timeout=120s >/dev/null
for m in "${ROOT}"/k8s/policy-store/migrations/*.sql; do
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    k -n rpe-system exec -i deploy/rpe-postgres -- psql -q -v ON_ERROR_STOP=1 -U rpe -d rpe < "$m" >/dev/null 2>&1 && break
    sleep 2
  done
done
log "Restarting the Operator, the intelligence layer and the demo services"
k -n rpe-system scale deploy/rpe-operator deploy/rpe-intelligence --replicas=1 >/dev/null
k -n rpe-demo rollout restart deploy >/dev/null
k -n rpe-system rollout status deploy/rpe-operator --timeout=120s >/dev/null
k -n rpe-system rollout status deploy/rpe-intelligence --timeout=300s >/dev/null
for d in $(k -n rpe-demo get deploy -o name); do k -n rpe-demo rollout status "$d" --timeout=180s >/dev/null; done
log "Data reset complete"
