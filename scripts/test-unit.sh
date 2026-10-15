#!/usr/bin/env bash
# Runs every component's unit tests. Set SKIP_JAVA=1 to skip Maven builds.
source "$(dirname "$0")/lib.sh"
require go
failed=()
run() { local name=$1; shift; log "unit: ${name}"; if ! "$@"; then failed+=("${name}"); fi; }
for mod in libs/rpe-go conformance-kit/go demo-app k8s/admission-webhook k8s/operator hub; do
  run "${mod}" bash -c "cd '${ROOT}/${mod}' && go vet ./... && go test -race -count=1 ./..."
done
if [[ "${SKIP_NODE:-0}" != "1" ]] && command -v npm >/dev/null 2>&1; then
  run console bash -c "cd '${ROOT}/console' && { [[ -d node_modules ]] || npm ci --no-audit --no-fund >/dev/null; } && npm run -s build >/dev/null"
fi
if [[ "${SKIP_JAVA:-0}" != "1" ]]; then
  # C1: rpe-java must pass the shared conformance vectors and agree with rpe-go on the differential corpus
  run differential-corpus bash -c "cd '${ROOT}/conformance-kit/go' && go run ./cmd/difffuzz -n 5000 -seed \"\${RPE_FUZZ_SEED:-1}\" ../generated/differential.json"
  run rpe-java bash -c "cd '${ROOT}/libs/rpe-java' && ./mvnw -q -B verify"
  run sidecar bash -c "cd '${ROOT}/sidecar' && ./mvnw -q -B verify"
  run intelligence bash -c "cd '${ROOT}/intelligence' && ./mvnw -q -B test"
fi
if [[ ${#failed[@]} -gt 0 ]]; then die "unit tests failed: ${failed[*]}"; fi
log "All unit tests passed"
