#!/usr/bin/env bash
# C2+C3: inbound and outbound traffic flows through the sidecars, including root application containers.
source "$(dirname "$0")/lib.sh"
reset_demo
# bash 3.2 compatible (no associative arrays): one baseline variable per service/direction
key() { echo "b_$(echo "$1" | tr ':-' '__')"; }
for pair in gateway:inbound gateway:outbound order-svc:inbound order-svc:outbound payment-svc:inbound shipping-svc:inbound; do
  eval "$(key "$pair")=$(metric "${pair%%:*}" "${pair##*:}")"
done
N=5; okc=0
for _ in $(seq 1 ${N}); do client GET "${GW}/api/orders"; [[ "${CODE}" == 200 ]] && okc=$((okc+1)); done
assert_eq "${okc}" "${N}" "${N} requests through the full chain return 200"
delta() { local b; eval "b=\$$(key "$1")"; echo $(( $(metric "${1%%:*}" "${1##*:}") - b )); }
assert_ge "$(delta gateway:inbound)" ${N} "gateway sidecar intercepted inbound requests"
assert_ge "$(delta gateway:outbound)" ${N} "gateway sidecar intercepted outbound calls to order-svc"
assert_ge "$(delta order-svc:inbound)" ${N} "order-svc sidecar intercepted inbound requests"
assert_ge "$(delta order-svc:outbound)" $((N*4)) "order-svc sidecar intercepted its 4 fan-out calls per request"
assert_ge "$(delta payment-svc:inbound)" ${N} "payment-svc sidecar intercepted inbound requests"
assert_ge "$(delta shipping-svc:inbound)" ${N} "shipping-svc (behind non-RPE legacy-svc) still intercepted inbound"

# Regression test for the legacy bug: egress exclusion by --uid-owner 0 let root containers bypass the proxy.
cat <<'YAML' | k apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: e2e-root-client
  namespace: rpe-demo
  labels: {app: e2e-root-client}
  annotations: {resilience.rpe.dev/inbound-ports: "9999"}
spec:
  terminationGracePeriodSeconds: 1
  containers:
    - name: curl
      image: curlimages/curl:8.11.1
      command: ["sleep", "infinity"]
      securityContext: {runAsUser: 0}
YAML
k -n rpe-demo wait --for=condition=Ready pod/e2e-root-client --timeout=120s >/dev/null
pay_before=$(metric payment-svc inbound)
codes=$(for _ in 1 2 3; do k -n rpe-demo exec e2e-root-client -c curl -- curl -s -o /dev/null -w '%{http_code} ' http://payment-svc:8080/pay; done)
assert_eq "${codes}" "200 200 200 " "root container calls succeed"
root_out=$(k -n rpe-e2e exec e2e-client -- curl -s "http://$(k -n rpe-demo get pod e2e-root-client -o jsonpath='{.status.podIP}'):15090/metrics" \
  | grep '^rpe_requests_total{' | grep 'direction="outbound"' | awk '{s+=$2} END {print s+0}')
assert_ge "${root_out}" 3 "outbound calls from a ROOT container are intercepted by its sidecar (uid-0 bypass fixed)"
assert_ge $(( $(metric payment-svc inbound) - pay_before )) 3 "payment-svc received the root client's calls through its sidecar"
k -n rpe-demo delete pod e2e-root-client --wait=false >/dev/null
finish
