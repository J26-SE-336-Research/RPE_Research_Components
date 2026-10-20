#!/usr/bin/env bash
# Deploys the platform (webhook, policy store, observability, intelligence, operator, hub) and the demo topology into kind.
source "$(dirname "$0")/lib.sh"
require kubectl go
cluster_exists || die "kind cluster '${CLUSTER}' not found (run scripts/cluster-up.sh)"
mkdir -p "${GENERATED}/certs"

log "Applying platform namespaces"
k apply -f "${ROOT}/deploy/k8s/platform/namespaces.yaml"

if ! k -n rpe-system get secret rpe-admission-webhook-tls >/dev/null 2>&1; then
  log "Generating webhook TLS certificates"
  (cd "${ROOT}/k8s/admission-webhook" && go run ./cmd/certgen "${GENERATED}/certs" rpe-admission-webhook rpe-system)
  k -n rpe-system create secret tls rpe-admission-webhook-tls \
    --cert="${GENERATED}/certs/tls.crt" --key="${GENERATED}/certs/tls.key"
fi
[[ -f "${GENERATED}/certs/ca.crt" ]] || die "CA certificate missing; delete secret rpe-system/rpe-admission-webhook-tls and rerun"

if ! k -n rpe-system get secret rpe-control-token >/dev/null 2>&1; then
  log "Generating the C4 control token (shared by the hub and the intelligence layer)"
  k -n rpe-system create secret generic rpe-control-token --from-literal=token="$(openssl rand -hex 24)"
fi

log "Applying policy-store migrations"
k -n rpe-system create configmap rpe-policy-store-migrations \
  --from-file="${ROOT}/k8s/policy-store/migrations" --dry-run=client -o yaml | k apply -f -

log "Applying platform"
k apply -k "${ROOT}/deploy/k8s/platform"
k -n rpe-system rollout status deploy/rpe-admission-webhook --timeout=180s
k -n rpe-system rollout status deploy/rpe-postgres --timeout=180s
log "Applying policy-store migrations to the running database (idempotent)"
for m in "${ROOT}"/k8s/policy-store/migrations/*.sql; do
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    k -n rpe-system exec -i deploy/rpe-postgres -- psql -q -v ON_ERROR_STOP=1 -U rpe -d rpe < "$m" >/dev/null 2>&1 && break
    sleep 2
  done
done
k -n rpe-system rollout status deploy/rpe-redis --timeout=120s

log "Registering the MutatingWebhookConfiguration"
CA_BUNDLE="$(base64 < "${GENERATED}/certs/ca.crt" | tr -d '\n')"
sed "s|\${CA_BUNDLE}|${CA_BUNDLE}|" "${ROOT}/deploy/k8s/platform/webhook-config.tmpl.yaml" > "${GENERATED}/webhook-config.yaml"
k apply -f "${GENERATED}/webhook-config.yaml"

log "Deploying demo topology (pods are injected by the webhook)"
k apply -k "${ROOT}/deploy/k8s/demo"
k apply -f "${ROOT}/deploy/k8s/e2e/client.yaml"
k apply -f "${ROOT}/deploy/k8s/e2e/traffic.yaml"
for d in $(k -n rpe-demo get deploy -o name); do k -n rpe-demo rollout status "${d}" --timeout=240s; done
k -n rpe-system rollout status deploy/rpe-prometheus --timeout=180s
k -n rpe-system rollout status deploy/rpe-tempo --timeout=180s
k -n rpe-system rollout status deploy/rpe-intelligence --timeout=300s
k -n rpe-system rollout status deploy/rpe-hub --timeout=180s
k -n rpe-system rollout status deploy/rpe-protocol --timeout=180s
k -n rpe-system rollout status deploy/rpe-operator --timeout=180s
k -n rpe-e2e wait --for=condition=Ready pod/e2e-client --timeout=120s
log "Deployed. Admin Panel: run 'make console' then open http://localhost:8800"
k get pods -A -l 'app.kubernetes.io/part-of in (rpe,rpe-demo)' -o wide 2>/dev/null || true
k -n rpe-demo get pods
