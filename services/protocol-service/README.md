# Protocol service (C1)

**Owner:** C1 Ruwanmali. **Status:** built for PP1 (20 October 2026). Spec: `spec/RPE-v1.md` §12, guide Appendix A3.7.

One pod, two containers built from one image (`rpe/protocol-service:dev`, Service `rpe-protocol.rpe-system:8095`):

- **protocol** (Go, rpe-go): the API below.
- **protocol-java** (Java, rpe-java, `java/`): the Java side of validation and conformance on `127.0.0.1:8096`.

| Method / path | Purpose |
|---|---|
| `GET /api/protocol/registry` | Service name → 2-byte trail id, with a version (R-P03). The demo services fetch it at start |
| `PUT /api/protocol/registry` | `{services: [...]}` adds services (existing ids never change; the version increases) |
| `POST /api/protocol/trails` | `{trails: [{rid, route, budgetMs, epoch, status, at, trail}]}` from the gateway (batched, R-P04) |
| `GET /api/protocol/trails?limit=&window=&route=` | Recent requests with their trail summary (hops, bytes, late, bottleneck) |
| `GET /api/protocol/attribution/{rid}` | Per-hop deadline breakdown of one request: start in the budget, time taken, own time, bottleneck |
| `GET /api/protocol/topology?window=&route=` | Service graph rebuilt from the trails of the window (edges, calls, p50/p95, unknown edges) |
| `GET /api/protocol/topology/compare?window=` | The same graph scored against the declared ground truth (precision, recall, missing edges) |
| `POST /api/protocol/validate` | `{kind: context\|result\|hop\|trail, value, impl: go\|java\|both}` → both verdicts and whether they agree |
| `GET /api/protocol/conformance` | Every conformance case run now by rpe-go and rpe-java (the matrix) |

Port 8095 is not one of the sidecars' outbound ports, so registry lookups and trail reports from the demo services
bypass the RPE sidecars and never appear in their metrics.

```bash
go test -race ./...                       # Go side
cd java && ../../../libs/rpe-java/mvnw -q package   # Java side (after installing rpe-java)
```

Next (PP2): trail encodings (ring, interned-delta, Bloom) with the size–fidelity study against Tempo (E6), and the
Tempo comparison endpoint.
