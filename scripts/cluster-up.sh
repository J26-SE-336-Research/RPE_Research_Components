#!/usr/bin/env bash
# Creates the local kind cluster used for all Kubernetes tests.
source "$(dirname "$0")/lib.sh"
require kind kubectl docker
if cluster_exists; then
  log "kind cluster '${CLUSTER}' already exists"
else
  log "Creating kind cluster '${CLUSTER}'"
  kind create cluster --name "${CLUSTER}" --config "${ROOT}/deploy/kind/kind-config.yaml" --wait 120s
fi
k wait --for=condition=Ready nodes --all --timeout=180s
k get nodes -o wide
