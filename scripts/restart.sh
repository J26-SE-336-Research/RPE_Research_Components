#!/usr/bin/env bash
# Restarts RPE workloads so they pick up freshly built :dev images (image tags do not change between builds).
source "$(dirname "$0")/lib.sh"
for ns in rpe-system rpe-demo; do
  k -n "${ns}" rollout restart deploy >/dev/null
done
for ns in rpe-system rpe-demo; do
  for d in $(k -n "${ns}" get deploy -o name); do k -n "${ns}" rollout status "${d}" --timeout=300s; done
done
log "Restarted"
