#!/usr/bin/env bash
# Shared helpers for the RPE build/deploy/test scripts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER="${RPE_CLUSTER:-rpe}"
KCTX="kind-${CLUSTER}"
GENERATED="${ROOT}/deploy/k8s/generated"
IMAGES=(sidecar admission-webhook demo-app intelligence hub operator)

log()  { printf '\033[1;34m[rpe]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[rpe]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[rpe]\033[0m %s\n' "$*" >&2; exit 1; }

k() { kubectl --context "${KCTX}" "$@"; }

require() {
  for tool in "$@"; do
    command -v "${tool}" >/dev/null 2>&1 || die "missing tool: ${tool} (run scripts/prereqs.sh)"
  done
}

cluster_exists() { kind get clusters 2>/dev/null | grep -qx "${CLUSTER}"; }
