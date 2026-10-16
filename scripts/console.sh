#!/usr/bin/env bash
# Exposes the RPE Admin Panel on http://localhost:${PORT:-8800} (Ctrl-C to stop).
# Reconnects automatically when the hub pod is restarted (port-forwards bind to one pod).
source "$(dirname "$0")/lib.sh"
PORT="${PORT:-8800}"
k -n rpe-system rollout status deploy/rpe-hub --timeout=120s >/dev/null || die "rpe-hub is not running (make deploy)"
log "RPE Admin Panel: http://localhost:${PORT}   (logins: admin/admin, sre/sre, viewer/viewer)"
trap 'exit 0' INT TERM
while true; do
  kubectl --context "${KCTX}" -n rpe-system port-forward svc/rpe-hub "${PORT}:80" >/dev/null 2>&1 || true
  sleep 1
done
