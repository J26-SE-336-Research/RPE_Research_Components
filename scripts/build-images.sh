#!/usr/bin/env bash
# Builds component images and loads them into the kind cluster.
#   scripts/build-images.sh                 # all images
#   scripts/build-images.sh sidecar demo-app
source "$(dirname "$0")/lib.sh"
require docker kind
targets=("$@"); [[ ${#targets[@]} -eq 0 ]] && targets=("${IMAGES[@]}")
build() {
  local name=$1
  case "${name}" in
    # C2 sidecar: the Netty proxy (deadline/cancellation propagation, backpressure) in netty-sidecar-proxy/
    sidecar)           docker build -t rpe/sidecar:dev -f "${ROOT}/netty-sidecar-proxy/docker/Dockerfile" "${ROOT}" ;;
    admission-webhook) docker build -t rpe/admission-webhook:dev "${ROOT}/k8s/admission-webhook" ;;
    demo-app)          docker build -t rpe/demo-app:dev -f "${ROOT}/demo-app/Dockerfile" "${ROOT}" ;;
    intelligence)      docker build -t rpe/intelligence:dev "${ROOT}/intelligence" ;;
    operator)          docker build -t rpe/operator:dev "${ROOT}/k8s/operator" ;;
    hub)               docker build -t rpe/hub:dev -f "${ROOT}/hub/Dockerfile" "${ROOT}" ;;
    *) die "unknown image ${name} (known: ${IMAGES[*]})" ;;
  esac
}
for t in "${targets[@]}"; do
  log "Building rpe/${t}:dev"
  build "${t}"
done
if cluster_exists; then
  for t in "${targets[@]}"; do
    log "Loading rpe/${t}:dev into kind/${CLUSTER}"
    kind load docker-image "rpe/${t}:dev" --name "${CLUSTER}"
  done
else
  warn "kind cluster '${CLUSTER}' not found; images built but not loaded (run scripts/cluster-up.sh)"
fi
