#!/usr/bin/env bash
# Runs every component's unit tests. Set SKIP_JAVA=1 to skip Maven builds.
source "$(dirname "$0")/lib.sh"
require go
failed=()
run() { local name=$1; shift; log "unit: ${name}"; if ! "$@"; then failed+=("${name}"); fi; }
# the race detector needs cgo and a C compiler (not present on a stock Windows machine)
race=""
if command -v gcc >/dev/null 2>&1 || command -v cc >/dev/null 2>&1; then race="-race"; else warn "no C compiler: Go tests run without -race"; fi
for mod in libs/rpe-go conformance-kit/go services/protocol-service demo-app k8s/admission-webhook k8s/operator hub; do
  run "${mod}" bash -c "cd '${ROOT}/${mod}' && go vet ./... && CGO_ENABLED=\${CGO_ENABLED:-1} go test ${race} -count=1 ./..."
done
# C1: the committed conformance cases must be exactly what the generator produces from the reference library. A case
# change is a spec change: regenerate (go run ./cmd/genvectors ../vectors), review the diff and commit it.
run conformance-vectors-current bash -c "cd '${ROOT}/conformance-kit/go' && mkdir -p ../generated/vectors-check && go run ./cmd/genvectors ../generated/vectors-check >/dev/null && diff -r ../vectors ../generated/vectors-check"
if [[ "${SKIP_NODE:-0}" != "1" ]] && command -v npm >/dev/null 2>&1; then
  run console bash -c "cd '${ROOT}/console' && { [[ -d node_modules ]] || npm ci --no-audit --no-fund >/dev/null; } && npm run -s build >/dev/null"
fi
if [[ "${SKIP_JAVA:-0}" != "1" ]]; then
  # C1: rpe-java must pass the shared conformance vectors and agree with rpe-go on the differential corpus
  run differential-corpus bash -c "cd '${ROOT}/conformance-kit/go' && go run ./cmd/difffuzz -n 5000 -seed \"\${RPE_FUZZ_SEED:-1}\" ../generated/differential.json"
  run rpe-java bash -c "cd '${ROOT}/libs/rpe-java' && ./mvnw -q -B verify"
  # C1 protocol service, Java side (validation and live conformance with rpe-java)
  run protocol-java bash -c "cd '${ROOT}/libs/rpe-java' && ./mvnw -q -B -DskipTests install && ./mvnw -q -B -f '${ROOT}/services/protocol-service/java/pom.xml' package"
  # C2 sidecar (netty-sidecar-proxy) builds against rpe-java; its integration tests need Linux epoll (skipped elsewhere)
  run sidecar bash -c "cd '${ROOT}/libs/rpe-java' && ./mvnw -q -B -DskipTests install && ./mvnw -q -B -f '${ROOT}/netty-sidecar-proxy/pom.xml' verify"
  run intelligence bash -c "cd '${ROOT}/intelligence' && ./mvnw -q -B test"
fi
if [[ ${#failed[@]} -gt 0 ]]; then die "unit tests failed: ${failed[*]}"; fi
log "All unit tests passed"
