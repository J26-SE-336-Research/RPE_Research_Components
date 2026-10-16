# RPE: Resilience Protocol Ecosystem for Microservices (J26-SE-336)

A closed-loop resilience ecosystem for HTTP microservices, built by four SLIIT final-year students:

| # | Component | Owner | Code |
|---|---|---|---|
| C1 | Protocol & Specification Layer: `X-RPE-*` context, Java/Go libraries, conformance, in-band trail | Ruwanmali K.H | `spec/`, `libs/rpe-go/`, `libs/rpe-java/`, `conformance-kit/`, `bench/`, `services/protocol-service/` |
| C2 | Service Interceptor Layer: HTTP sidecar enforcing deadline, cancellation, backpressure, retry budget | R.R.D Pinto | `sidecar/`, `scenarios/` |
| C3 | Kubernetes Enforcement Layer: sidecar injection, PostgreSQL policy store, Operator, consistent rollout | Kalhara D.M.R | `k8s/`, `charts/rpe/` |
| C4 | Resilience Intelligence Layer: health classification, joint recommendations, human approval | R.M.S.B. Rathnayake | `intelligence/` |
| Shared | Demo app, **Admin Panel** (console + rpe-hub), Prometheus + Tempo, e2e tests | team | `demo-app/`, `admin-panel/`, `tests/e2e/`, `deploy/` |

```
client → gateway → order-svc → (parallel) inventory-svc, payment-svc, loyalty-svc (optional), legacy-svc (no RPE) → shipping-svc
          every hop: app + rpe-sidecar (injected by the C3 webhook), context X-RPE-Context (C1)
```

**📘 Full explanation of every component and a complete run guide: [COMPONENTS.md](COMPONENTS.md).**

## Quick start (Kubernetes, the whole ecosystem)

```bash
make prereqs      # docker, kind, kubectl, helm, go, java, jq, k6
make all          # unit tests → kind cluster → build images → deploy → e2e tests
make console      # Admin Panel → http://localhost:8800  (admin/admin, sre/sre, viewer/viewer)
```

Or step by step:

| Command | What it does |
|---|---|
| `make test-unit` | Unit tests of every component (`SKIP_JAVA=1` skips Maven) |
| `make cluster` | Create the local kind cluster `rpe` (1 control-plane + 1 worker) |
| `make images` | Build `rpe/{sidecar,admission-webhook,demo-app,intelligence,hub,operator}:dev` and load them into kind (`IMAGES="sidecar"` for a subset) |
| `make deploy` | Deploy the platform (webhook + TLS, PostgreSQL + Redis, Operator + policy API, Prometheus + Tempo, live intelligence, hub) and the demo topology with steady background traffic |
| `make console` | Open the **Admin Panel** at http://localhost:8800 |
| `make reset-data` | Clean slate for demos (empty policy store, Redis and RPE resources; restart the demo services and the intelligence layer) |
| `make restart` | Restart RPE workloads after rebuilding images (`:dev` tags don't change) |
| `make test-e2e` | Run the Kubernetes e2e suites in `tests/e2e/` (`SUITES="30 35"` for a subset) |
| `make undeploy` / `make cluster-down` | Clean up |

All targets wrap scripts in `scripts/`, so `scripts/test-e2e.sh 35` works without make. Scripts are bash-3.2 compatible (stock macOS).

Try it by hand:

```bash
kubectl --context kind-rpe -n rpe-e2e exec e2e-client -- curl -s http://gateway.rpe-demo:8080/api/orders | jq
kubectl --context kind-rpe -n rpe-e2e exec e2e-client -- \
  curl -s -X POST http://payment-svc.rpe-demo:8080/admin/faults -d '{"latencyMs":1500}'   # then call again: terminal 504
```

## What is tested on Kubernetes today (`tests/e2e/`)

| Suite | Proves |
|---|---|
| `00-cluster` | Platform and demo topology are available |
| `10-webhook-injection` | C3: 100% injection coverage, opt-out, namespace scoping, admission validation |
| `20-traffic-interception` | C2/C3: inbound and outbound traffic through sidecars, **including root containers** (legacy uid-0 bypass fixed) |
| `30-context-propagation` | C1: `X-RPE-Context` rules R-T01..R-T06, budget conservation, trust boundary, unknown edge, invalid/expired rejection |
| `35-deadline-cancellation` | C1/C2: deadline enforced, cooperative cancellation, point of no return (no charge after expiry), terminal results |
| `40-fanout` | C1: partial vs whole fan-out outcomes with per-child annotation |
| `50-sidecar-control-api` | C2/C3 contract: health, staged/active policy epochs, metrics |
| `60-policy-store` | C3: approval required (FR10), NOTIFY on approval, append-only policies |
| `70-intelligence` | C4: service deployed and serving |
| `80-admin-panel` | Admin Panel: login/roles and every page's feature through the hub |
| `90-rollouts` | C3: Operator two-phase/immediate rollouts, supersede/restore, Redis, RPEPolicy mirror, drift repair |
| `95-intelligence-live` | Observability + C4 + C3: live metrics and traces; C4 proposes a deadline fix for a too-tight deadline; human approval (viewer blocked, rejection needs a reason); delivered as an approved C3 version and rolled out; gateway enforces it; append-only audit in PostgreSQL |

**Adding tests:** every new feature adds assertions to a suite (or a new `NN-name.sh`) using the helpers in `tests/e2e/lib.sh` (`client`, `field`, `metric`, `fault`, `counters`, `assert_*`).

## Repository layout

```
libs/rpe-go/              C1  Go library: X-RPE-Context codec, context guard, deadline, ledger, splitter, middleware + transport
libs/rpe-java/            C1  Java library: same codec/guard/splitter, admission step, java.net.http adapter
conformance-kit/          C1  103 shared vectors (rpe-go and rpe-java both 100%) + Java-vs-Go differential corpus
sidecar/                  C2  Java 21 + Netty sidecar (pass-through skeleton: pooled proxying, control API, contract metrics)
k8s/admission-webhook/    C3  Mutating webhook (hardened: UID/GID 1337, REDIRECT/TPROXY modes, tests)
k8s/policy-store/         C3  PostgreSQL schema: approval-enforced, append-only, LISTEN/NOTIFY (+ Redis fast path, deploy/k8s/platform/redis.yaml)
intelligence/             C4  Spring Boot intelligence layer: live Prometheus/Tempo analysis, approval queue, delivery to C3, audit
demo-app/                 shared  Configurable Go demo service (faults, counters, fan-out policy, OpenTelemetry tracing)
deploy/kind, deploy/k8s/  shared  kind cluster config, platform + demo manifests
scripts/, tests/e2e/      shared  build / deploy / unit + Kubernetes e2e test scripts
spec/ bench/ services/protocol-service/                                    C1, to be built (see READMEs)
k8s/operator/              C3  Operator + policy API (two-phase rollouts, drift repair), CRDs RPEPolicy/RPERollout
charts/rpe/                                                                C3, to be built
admin-panel/              shared  Admin Panel: React web app (web/) + Go server rpe-hub (server/)
scenarios/                                                                 shared, to be built
```

**Component guides** (A to Z per member: proposal commitments, novelty, design, contracts, status, plan, evaluation, viva) are in [`docs/components/`](docs/components/README.md). Each member works from their guide:
`claude "Read docs/components/<your-guide>.md completely before doing anything. Then <task>."`

Local-only material (proposal PDFs, legacy prototypes, early scope notes) lives in `_research/`, which is gitignored.
