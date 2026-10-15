#!/usr/bin/env bash
# Runs the Kubernetes end-to-end suites in tests/e2e against the kind cluster.
#   scripts/test-e2e.sh               # all suites
#   scripts/test-e2e.sh 30 35         # suites whose file name starts with 30 or 35
source "$(dirname "$0")/lib.sh"
require kubectl jq
cluster_exists || die "kind cluster '${CLUSTER}' not found"
mkdir -p "${ROOT}/tests/e2e/reports"
suites=()
for f in "${ROOT}"/tests/e2e/[0-9]*.sh; do
  base=$(basename "$f")
  if [[ $# -eq 0 ]]; then suites+=("$f"); else for p in "$@"; do [[ "${base}" == ${p}* ]] && suites+=("$f"); done; fi
done
pass=0; fail=0; failed=()
for s in "${suites[@]}"; do
  name=$(basename "$s" .sh)
  log "e2e: ${name}"
  if KCTX="${KCTX}" bash "$s" 2>&1 | tee "${ROOT}/tests/e2e/reports/${name}.log"; [[ ${PIPESTATUS[0]} -eq 0 ]]; then
    pass=$((pass+1))
  else
    fail=$((fail+1)); failed+=("${name}")
  fi
done
echo
log "e2e summary: ${pass} passed, ${fail} failed"
[[ ${fail} -eq 0 ]] || die "failed suites: ${failed[*]}"
