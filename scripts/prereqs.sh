#!/usr/bin/env bash
# Checks the local toolchain (Appendix A7 of the component guides).
source "$(dirname "$0")/lib.sh"
missing=0
check() {
  local tool=$1 hint=$2
  if command -v "${tool}" >/dev/null 2>&1; then
    printf '  %-8s %s\n' "${tool}" "$("${tool}" ${3:-version} 2>&1 | head -1)"
  else
    printf '  %-8s MISSING  -> %s\n' "${tool}" "${hint}"; missing=1
  fi
}
log "Toolchain"
check docker  "install Docker Desktop" "--version"
check kind    "brew install kind"
check kubectl "brew install kubectl" "version --client"
check helm    "brew install helm" "version --short"
check go      "brew install go"
check java    "brew install openjdk@21" "-version"
check jq      "brew install jq" "--version"
check k6      "brew install k6"
docker info >/dev/null 2>&1 || { warn "Docker daemon is not running"; missing=1; }
[[ ${missing} -eq 0 ]] && log "All prerequisites present" || die "Install the missing tools above"
