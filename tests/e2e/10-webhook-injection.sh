#!/usr/bin/env bash
# C3: the admission webhook injects the RPE sidecar and init container (FR1, FR2; metric: injection coverage).
source "$(dirname "$0")/lib.sh"
injected=0
for s in "${RPE_SERVICES[@]}"; do
  p=$(k -n rpe-demo get pod -l "app=${s}" -o json | jq '.items[0]')
  containers=$(echo "$p" | jq -r '[.spec.containers[].name] | join(",")')
  inits=$(echo "$p" | jq -r '[.spec.initContainers[]?.name] | join(",")')
  ann=$(echo "$p" | jq -r '.metadata.annotations["resilience.rpe.dev/injected"]')
  uid=$(echo "$p" | jq -r '.spec.containers[] | select(.name=="rpe-sidecar") | .securityContext.runAsUser')
  initExit=$(echo "$p" | jq -r '.status.initContainerStatuses[]? | select(.name=="rpe-init") | .state.terminated.exitCode')
  if [[ "${containers}" == *rpe-sidecar* && "${inits}" == *rpe-init* && "${ann}" == true && "${uid}" == 1337 && "${initExit}" == 0 ]]; then
    ok "${s}: sidecar (uid 1337) + rpe-init (exit 0) injected"; injected=$((injected+1))
  else
    bad "${s}: containers=${containers} inits=${inits} annotation=${ann} uid=${uid} initExit=${initExit}"
  fi
done
assert_eq "${injected}" "${#RPE_SERVICES[@]}" "injection coverage 100% of eligible pods (${injected}/${#RPE_SERVICES[@]})"

partner=$(k -n rpe-demo get pod -l app=partner-svc -o jsonpath='{.items[0].spec.containers[*].name}')
assert_eq "${partner}" app "opted-out partner-svc (no RPE) has no sidecar"
client_c=$(k -n rpe-e2e get pod e2e-client -o jsonpath='{.spec.containers[*].name}')
assert_eq "${client_c}" curl "pods in non-enabled namespaces are not injected"
role=$(k -n rpe-demo get pod -l app=gateway -o json | jq -r '.items[0].spec.containers[] | select(.name=="rpe-sidecar") | .env[] | select(.name=="RPE_ROLE") | .value')
assert_eq "${role}" gateway "gateway pod gets the gateway sidecar role"

out=$(k -n rpe-demo run e2e-dryrun --image=rpe/demo-app:dev --port=8080 --dry-run=server -o json 2>&1)
assert_contains "$(echo "${out}" | jq -r '[.spec.containers[].name] | join(",")' 2>/dev/null)" rpe-sidecar "new pods are injected at admission (server dry-run)"
out=$(k -n rpe-demo run e2e-bad --image=rpe/demo-app:dev --port=8080 --annotations=resilience.rpe.dev/role=edge --dry-run=server -o name 2>&1)
assert_contains "${out}" "rpe injection" "invalid RPE annotations are rejected at admission"
out=$(k -n rpe-demo run e2e-reserved --image=rpe/demo-app:dev --port=15001 --dry-run=server -o name 2>&1)
assert_contains "${out}" "reserved sidecar port" "app ports colliding with sidecar ports are rejected"
finish
