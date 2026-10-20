#!/usr/bin/env bash
# The cluster, platform and demo topology are up.
source "$(dirname "$0")/lib.sh"
ready=$(k get nodes --no-headers | awk '$2=="Ready"' | wc -l | tr -d ' ')
assert_ge "${ready}" 2 "kind nodes Ready"
for d in rpe-admission-webhook rpe-postgres rpe-intelligence; do
  avail=$(k -n rpe-system get deploy "$d" -o jsonpath='{.status.availableReplicas}')
  assert_eq "${avail:-0}" 1 "rpe-system/$d available"
done
for d in "${RPE_SERVICES[@]}"; do
  avail=$(k -n rpe-demo get deploy "$d" -o jsonpath='{.status.availableReplicas}')
  assert_eq "${avail:-0}" 1 "rpe-demo/$d available"
done
phase=$(k -n rpe-e2e get pod e2e-client -o jsonpath='{.status.phase}')
assert_eq "${phase}" Running "e2e client running"
finish
