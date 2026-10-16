#!/usr/bin/env bash
# Deploys the platform + demo topology to a REMOTE cluster (the shared k3s VPS) using images from a registry.
# scripts/deploy.sh is the kind variant (images loaded with `kind load`); this one never touches kind.
#
# Required environment:
#   IMAGE_PREFIX   registry prefix, e.g. ghcr.io/j26-se-336-research/rpe-   (image = ${IMAGE_PREFIX}<name>:${IMAGE_TAG})
#   IMAGE_TAG      immutable tag, e.g. the git SHA
#   GHCR_USER, GHCR_TOKEN   credentials the cluster uses to pull private images (read:packages)
# Optional:
#   RPE_KCTX       kubectl context (default: the current one)
source "$(dirname "$0")/lib.sh"
require kubectl go
: "${IMAGE_PREFIX:?set IMAGE_PREFIX}" "${IMAGE_TAG:?set IMAGE_TAG}" "${GHCR_USER:?set GHCR_USER}" "${GHCR_TOKEN:?set GHCR_TOKEN}"

# lib.sh points at a kind context; here the context comes from the kubeconfig in use.
KCTX="${RPE_KCTX:-$(kubectl config current-context)}"
k() { kubectl --context "${KCTX}" "$@"; }
img() { echo "${IMAGE_PREFIX}$1:${IMAGE_TAG}"; }
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-600s}"   # first pull of every image on a fresh node is slow

log "Deploying ${IMAGE_TAG} to context '${KCTX}'"
k get nodes >/dev/null || die "cannot reach the cluster"
mkdir -p "${GENERATED}/certs" "${GENERATED}/vps/platform" "${GENERATED}/vps/demo"

log "Namespaces and image pull credentials"
k apply -f "${ROOT}/deploy/k8s/platform/namespaces.yaml"
for ns in rpe-system rpe-demo rpe-e2e; do
  k -n "${ns}" create secret docker-registry ghcr-pull --docker-server=ghcr.io \
    --docker-username="${GHCR_USER}" --docker-password="${GHCR_TOKEN}" --dry-run=client -o yaml | k apply -f - >/dev/null
  # the sidecar injected into demo pods is pulled with the pod's service account credentials
  k -n "${ns}" patch serviceaccount default -p '{"imagePullSecrets":[{"name":"ghcr-pull"}]}' >/dev/null
done

log "Rendering registry overlays (generated, not committed)"
cat > "${GENERATED}/vps/platform/kustomization.yaml" <<EOF
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../../platform
images:
  - {name: rpe/admission-webhook, newName: ${IMAGE_PREFIX}admission-webhook, newTag: "${IMAGE_TAG}"}
  - {name: rpe/operator, newName: ${IMAGE_PREFIX}operator, newTag: "${IMAGE_TAG}"}
  - {name: rpe/hub, newName: ${IMAGE_PREFIX}hub, newTag: "${IMAGE_TAG}"}
  - {name: rpe/intelligence, newName: ${IMAGE_PREFIX}intelligence, newTag: "${IMAGE_TAG}"}
patches:
  # the webhook injects this image into every application pod (env index 0 is SIDECAR_IMAGE)
  - target: {kind: Deployment, name: rpe-admission-webhook}
    patch: |-
      - op: replace
        path: /spec/template/spec/containers/0/env/0/value
        value: "$(img sidecar)"
  - target: {kind: ServiceAccount}
    patch: |-
      - op: add
        path: /imagePullSecrets
        value: [{name: ghcr-pull}]
EOF
cat > "${GENERATED}/vps/demo/kustomization.yaml" <<EOF
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../../demo
images:
  - {name: rpe/demo-app, newName: ${IMAGE_PREFIX}demo-app, newTag: "${IMAGE_TAG}"}
EOF

webhook_registered() { k get mutatingwebhookconfiguration rpe-sidecar-injector >/dev/null 2>&1; }
if ! webhook_registered; then
  log "Generating webhook TLS certificates (first deploy)"
  k -n rpe-system delete secret rpe-admission-webhook-tls --ignore-not-found
  (cd "${ROOT}/k8s/admission-webhook" && go run ./cmd/certgen "${GENERATED}/certs" rpe-admission-webhook rpe-system)
  k -n rpe-system create secret tls rpe-admission-webhook-tls \
    --cert="${GENERATED}/certs/tls.crt" --key="${GENERATED}/certs/tls.key"
fi

if ! k -n rpe-system get secret rpe-control-token >/dev/null 2>&1; then
  log "Generating the C4 control token"
  k -n rpe-system create secret generic rpe-control-token --from-literal=token="$(openssl rand -hex 24)"
fi

log "Applying policy-store migrations (ConfigMap)"
k -n rpe-system create configmap rpe-policy-store-migrations \
  --from-file="${ROOT}/k8s/policy-store/migrations" --dry-run=client -o yaml | k apply -f -

log "Applying platform"
k apply -k "${GENERATED}/vps/platform"
k -n rpe-system rollout status deploy/rpe-admission-webhook --timeout="${ROLLOUT_TIMEOUT}"
k -n rpe-system rollout status deploy/rpe-postgres --timeout="${ROLLOUT_TIMEOUT}"
log "Applying policy-store migrations to the running database (idempotent)"
for m in "${ROOT}"/k8s/policy-store/migrations/*.sql; do
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    k -n rpe-system exec -i deploy/rpe-postgres -- psql -q -v ON_ERROR_STOP=1 -U rpe -d rpe < "$m" >/dev/null 2>&1 && break
    sleep 3
  done
done
k -n rpe-system rollout status deploy/rpe-redis --timeout="${ROLLOUT_TIMEOUT}"

if ! webhook_registered; then
  log "Registering the MutatingWebhookConfiguration"
  [[ -f "${GENERATED}/certs/ca.crt" ]] || die "CA certificate missing"
  CA_BUNDLE="$(base64 < "${GENERATED}/certs/ca.crt" | tr -d '\n')"
  sed "s|\${CA_BUNDLE}|${CA_BUNDLE}|" "${ROOT}/deploy/k8s/platform/webhook-config.tmpl.yaml" > "${GENERATED}/webhook-config.yaml"
  k apply -f "${GENERATED}/webhook-config.yaml"
else
  log "MutatingWebhookConfiguration already registered (kept)"
fi

log "Deploying demo topology (pods are injected by the webhook)"
k apply -k "${GENERATED}/vps/demo"
k apply -f "${ROOT}/deploy/k8s/e2e/client.yaml"
k apply -f "${ROOT}/deploy/k8s/e2e/traffic.yaml"
for d in $(k -n rpe-demo get deploy -o name); do k -n rpe-demo rollout status "${d}" --timeout="${ROLLOUT_TIMEOUT}"; done
for d in rpe-prometheus rpe-tempo rpe-intelligence rpe-hub rpe-operator; do
  k -n rpe-system rollout status "deploy/${d}" --timeout="${ROLLOUT_TIMEOUT}"
done
k -n rpe-e2e wait --for=condition=Ready pod/e2e-client --timeout=180s
log "Deployed ${IMAGE_TAG}"
k -n rpe-system get pods
k -n rpe-demo get pods
