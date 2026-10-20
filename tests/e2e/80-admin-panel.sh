#!/usr/bin/env bash
# Admin Panel (rpe-hub + console): auth/roles and every component feature exercised through the panel's API.
source "$(dirname "$0")/lib.sh"
k -n rpe-e2e exec e2e-client -- rm -f /tmp/jar-admin /tmp/jar-viewer
# wait until the Service routes to a ready hub (e.g. right after a rollout restart)
for _ in $(seq 1 30); do
  k -n rpe-e2e exec e2e-client -- curl -s -o /dev/null --max-time 2 "${HUB}/healthz" && break
  sleep 1
done

hub GET /
assert_eq "${CODE}" 200 "console SPA is served"
assert_contains "${BODY}" "<title>RPE Admin</title>" "index.html is the admin panel"
hub GET /api/hub/overview
assert_eq "${CODE}" 401 "API requires login"
hub POST /api/hub/login '{"username":"admin","password":"wrong"}'
assert_eq "${CODE}" 401 "wrong password rejected"
hub POST /api/hub/login '{"username":"admin","password":"admin"}'
assert_eq "$(echo "${BODY}" | jq -r .role)" admin "admin can log in"

hub GET /api/hub/overview
assert_eq "$(echo "${BODY}" | jq -r '.coverage.injected')/$(echo "${BODY}" | jq -r '.coverage.eligible')" "7/7" "overview: injection coverage 7/7"
assert_eq "$(echo "${BODY}" | jq -r '.platform["rpe-admission-webhook"]')" true "overview: webhook running"

hub POST /api/hub/services/reset-all
hub POST /api/hub/chain '{"path":"/api/orders"}'
assert_eq "$(echo "${BODY}" | jq -r .status)" 200 "C1 inspector: chain request succeeds"
assert_eq "$(echo "${BODY}" | jq '.hops | length')" 7 "C1 inspector: all 7 services in the chain are reported"
assert_eq "$(echo "${BODY}" | jq '[.hops[].checks[] | select(.pass==false)] | length')" 0 "C1 inspector: all protocol rule checks pass on the live chain"
assert_eq "$(echo "${BODY}" | jq -r '[.hops[].checks[] | select(.rule=="R-F05")][0].pass')" true "C1 inspector: fan-out budget conservation verified"
assert_ge "$(echo "${BODY}" | jq '[.hops[].checks[] | select(.rule=="unknown-edge")] | length')" 1 "C1 inspector: unknown edge behind legacy-svc detected"

hub POST /api/hub/protocol/validate '{"header":"v=2, rid=\"4bf92f3577b34da6a3ce929d0e0e4736\", iid=\"00f067aa0ba902b7\", pid=\"0000000000000000\", ttl=5, bud=300, rb=1, cs=none, rt=\"GET /x\""}'
assert_eq "$(echo "${BODY}" | jq -r .error.rule)" R-V02 "C1 validator reports the spec rule ID"
hub POST /api/hub/protocol/transition '{"parent":"v=1, rid=\"4bf92f3577b34da6a3ce929d0e0e4736\", iid=\"00f067aa0ba902b7\", pid=\"0000000000000000\", ttl=287, bud=300, rb=3, cs=none, rt=\"GET /x\"","child":"v=1, rid=\"4bf92f3577b34da6a3ce929d0e0e4736\", iid=\"1111111111111111\", pid=\"00f067aa0ba902b7\", ttl=290, bud=300, rb=5, cs=none, rt=\"GET /x\""}'
assert_eq "$(echo "${BODY}" | jq -r '[.violations[].rule] | sort | join(",")')" "R-T02,R-T03" "C1 context guard flags illegal transitions"
hub POST /api/hub/protocol/split '{"rb":7,"reserve":1,"n":4,"mode":"parallel"}'
assert_eq "$(echo "${BODY}" | jq -c .grants)" "[2,2,1,1]" "C1 splitter conserves the budget"

hub POST /api/hub/demos/deadline '{"latencyMs":1500}'
assert_eq "$(echo "${BODY}" | jq -r .chain.status)" 200 "C1 deadline demo: the late request is served (signal, never reject)"
assert_eq "$(echo "${BODY}" | jq -r .chain.result.cs)" expired "C1 deadline demo: the sidecars signal the cancellation (cs=expired)"
assert_ge "$(echo "${BODY}" | jq -r '.payment.cards_charged // 0')" 1 "C1 deadline demo: payment's critical work completes"
hub POST /api/hub/demos/fanout '{"failing":"loyalty-svc"}'
assert_eq "$(echo "${BODY}" | jq -r '.chain.hops[] | select(.service=="order-svc") | .outcome')" partial "C1 fan-out demo: optional failure gives partial"

hub GET /api/hub/sidecars
assert_eq "$(echo "${BODY}" | jq '[.[] | select(.health.status=="ok")] | length')" 7 "C2: all 7 sidecars report healthy"
pay=$(echo "${BODY}" | jq -r '.[] | select(.service=="payment-svc") | .pod')
hub PUT "/api/hub/sidecars/${pay}/policy" '{"epoch":"e2e-panel","version":1,"hash":"h","state":"staged"}'
hub POST "/api/hub/sidecars/${pay}/activate" '{"epoch":"e2e-panel"}'
assert_eq "$(echo "${BODY}" | jq -r .activeEpoch)" e2e-panel "C2: policy epoch staged and activated through the panel"

hub GET /api/hub/fleet
assert_eq "$(echo "${BODY}" | jq -r '"\(.coverage.injected)/\(.coverage.eligible)"')" "7/7" "C3 fleet: coverage 7/7 (legacy opted out)"
hub POST /api/hub/fleet/simulate '{"name":"panel-preview","port":8080,"annotations":{}}'
assert_eq "$(echo "${BODY}" | jq -r .pod.sidecar)" true "C3 admission simulator: new pod would be injected (dry run)"
hub POST /api/hub/fleet/simulate '{"name":"panel-bad","port":15001,"annotations":{}}'
assert_eq "$(echo "${BODY}" | jq -r .allowed)" false "C3 admission simulator: reserved port rejected"
hub POST /api/hub/policies '{"policyId":"e2e-panel","service":"payment-svc","route":"GET /api/orders","deadlineMs":1200,"retryBudget":2}'
assert_eq "$(echo "${BODY}" | jq -r .notification.channel)" rpe_policy_approved "C3 policy store: approval fires NOTIFY"
assert_eq "$(echo "${BODY}" | jq -r .approvedBy)" admin "C3 policy store: logged-in user recorded as approver"
hub POST /api/hub/policies '{"control":"rate-limit","service":"gateway","ratePerSec":10}'
assert_eq "${CODE}" 400 "C3 policy store: a service control cannot be aimed at the gateway"
hub POST /api/hub/policies '{"control":"chain-deadline","service":"payment-svc","route":"GET /api/orders","deadlineMs":900}'
assert_eq "${CODE}" 400 "C3 policy store: the chain deadline can only target the gateway"
hub GET /api/hub/policies/e2e-panel/status
assert_eq "$(echo "${BODY}" | jq -r .policyId)" e2e-panel "C3 policy status: desired vs observed per policy"
hub GET /api/hub/fleet/audit
assert_eq "$(echo "${BODY}" | jq -r '.coverage.ratio')" 1 "C3 coverage audit: every eligible pod in the enabled namespaces runs the proxy"
assert_ge "$(echo "${BODY}" | jq '[.pods[] | select(.sidecar)] | length')" 7 "C3 coverage audit: policy state read from every proxy"

hub GET /api/hub/intel
assert_eq "$(echo "${BODY}" | jq -r .health.status)" UP "C4: intelligence UP"
hub POST /api/hub/intel/demo/stressed
assert_ge "$(echo "${BODY}" | jq '.outcomes | length')" 1 "C4: analysis scenario runs through the panel"

hub POST /api/hub/login '{"username":"viewer","password":"viewer"}' /tmp/jar-viewer
hub POST /api/hub/load '{"count":1}' /tmp/jar-viewer
assert_eq "${CODE}" 403 "viewer role is read-only"
hub POST /api/hub/fleet/reinject '{"namespace":"rpe-demo","pod":"x"}' /tmp/jar-viewer
assert_eq "${CODE}" 403 "viewer cannot recreate pods"
hub GET /api/hub/overview "" /tmp/jar-viewer
assert_eq "${CODE}" 200 "viewer can read"
hub POST /api/hub/services/reset-all
finish
