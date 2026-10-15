#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
require kind
if cluster_exists; then log "Deleting kind cluster '${CLUSTER}'"; kind delete cluster --name "${CLUSTER}"; else log "No cluster '${CLUSTER}'"; fi
