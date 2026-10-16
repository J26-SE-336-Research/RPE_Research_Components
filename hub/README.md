# rpe-hub (shared)

**Owner:** team. **Status:** built (e2e suites 80 and 95).

The Admin Panel backend (Go):
- serves the React console (`console/`) and the `/api/hub/*` API;
- signed-cookie sessions with roles `admin`, `sre` (may change the system) and `viewer` (read-only);
- least-privilege Kubernetes RBAC.

Every action calls the real components: the Kubernetes API, the demo services, every proxy's control API and `/metrics`, the C3 policy API, and the C4 intelligence API. For C4 it sends the control token, and the signed-in user is always the reviewer.

Endpoints: Appendix A3.8 of any guide in `docs/components/`.

Not built yet: the live hop-event SSE stream and the experiments store.

```bash
go test ./...            # unit tests
make console             # http://localhost:8800 against the kind cluster
```
