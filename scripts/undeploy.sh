#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
k delete mutatingwebhookconfiguration rpe-sidecar-injector --ignore-not-found
k delete namespace rpe-demo rpe-e2e rpe-system --ignore-not-found --wait=true
rm -rf "${GENERATED}"
log "Undeployed"
