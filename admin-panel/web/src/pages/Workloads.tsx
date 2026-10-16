import { useState } from "react";
import { api, useAction, useData } from "../api";
import { Alert, Button, Empty, Field, Kpi, KV, Modal, PageHeader, Panel, Status, Tag } from "../components/ui";
import type { FleetData, FleetPod } from "../types";

type Preview = { allowed: boolean; message?: string; pod?: FleetPod };

function AdmissionPreview({ onClose }: { onClose: () => void }) {
  const [f, setF] = useState({ name: "checkout-svc", port: 8080, role: "service", mode: "redirect", optOut: false });
  const run = useAction(() => {
    const annotations: Record<string, string> = {};
    if (f.role !== "service") annotations["resilience.rpe.dev/role"] = f.role;
    if (f.mode !== "redirect") annotations["resilience.rpe.dev/inbound-mode"] = f.mode;
    if (f.optOut) annotations["resilience.rpe.dev/inject"] = "disabled";
    return api<Preview>("POST", "/fleet/simulate", { name: f.name, port: f.port, annotations });
  });
  const r = run.result;
  return (
    <Modal
      title="Preview admission"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Close</Button>
          <Button variant="primary" write onClick={() => run.run()} busy={run.busy}>
            Run preview
          </Button>
        </>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>
        Submits the pod to the API server as a dry run. Admission runs as normal; nothing is created.
      </p>
      <div className="form-grid">
        <Field label="Pod name">
          <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
        </Field>
        <Field label="Container port" hint="0 for none">
          <input type="number" min={0} value={f.port} onChange={(e) => setF({ ...f, port: Number(e.target.value) })} />
        </Field>
        <Field label="Proxy role">
          <select value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}>
            <option value="service">service</option>
            <option value="gateway">gateway</option>
            <option value="edge">edge</option>
          </select>
        </Field>
        <Field label="Inbound interception">
          <select value={f.mode} onChange={(e) => setF({ ...f, mode: e.target.value })}>
            <option value="redirect">redirect</option>
            <option value="tproxy">tproxy</option>
          </select>
        </Field>
      </div>
      <label className="check" style={{ marginTop: 12 }}>
        <input type="checkbox" checked={f.optOut} onChange={(e) => setF({ ...f, optOut: e.target.checked })} />
        Exclude from injection
      </label>
      <Alert>{run.error}</Alert>
      {r && (
        <div style={{ marginTop: 14 }}>
          {r.allowed ? (
            <>
              <div className="row" style={{ marginBottom: 10 }}>
                <Status tone="green">Admitted</Status>
                {r.pod?.sidecar ? <Tag tone="green">Proxy injected</Tag> : <Tag>No proxy</Tag>}
              </div>
              {r.pod && (
                <KV
                  items={[
                    ["Containers", r.pod.containers.join(", ")],
                    ["Init containers", r.pod.initContainers?.join(", ") || "—"],
                    ["Proxy role", r.pod.role || "—"],
                    ["Interception", r.pod.inboundMode || "—"],
                    ["Proxy user", r.pod.sidecarUid ?? "—"],
                  ]}
                />
              )}
            </>
          ) : (
            <>
              <Status tone="red">Rejected</Status>
              <pre className="code" style={{ marginTop: 8 }}>{r.message}</pre>
            </>
          )}
        </div>
      )}
    </Modal>
  );
}

export function WorkloadsPage() {
  const { data, error } = useData<FleetData>("/fleet", 5000);
  const [preview, setPreview] = useState(false);
  return (
    <>
      <PageHeader
        title="Workloads"
        description="Proxy injection across namespaces and pods."
        actions={
          <Button write onClick={() => setPreview(true)}>
            Preview admission
          </Button>
        }
      />
      <Alert>{error}</Alert>
      <div className="stack">
        <div className="kpis">
          <Kpi label="Injection coverage" value={data ? `${Math.round((data.coverage.injected / Math.max(1, data.coverage.eligible)) * 100)}%` : "—"} foot={data ? `${data.coverage.injected} of ${data.coverage.eligible} eligible pods` : undefined} />
          <Kpi label="Pods" value={data?.pods.length ?? "—"} />
          <Kpi label="Excluded" value={data?.pods.filter((p) => p.optedOut).length ?? "—"} foot="opted out" />
        </div>
        <Panel title="Namespaces" flush>
          <table>
            <thead>
              <tr>
                <th>Namespace</th>
                <th>Injection</th>
              </tr>
            </thead>
            <tbody>
              {data?.namespaces.map((n) => (
                <tr key={n.name}>
                  <td>{n.name}</td>
                  <td>{n.injection ? <Status tone="green">Enabled</Status> : <Status tone="neutral">Disabled</Status>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
        <Panel title="Pods" subtitle="rpe-demo" flush>
          {data?.pods.length ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Pod</th>
                    <th>Status</th>
                    <th>Containers</th>
                    <th>Traffic setup</th>
                    <th>Proxy user</th>
                    <th>Role</th>
                    <th>Interception</th>
                    <th>Injection</th>
                  </tr>
                </thead>
                <tbody>
                  {data.pods.map((p) => (
                    <tr key={p.pod}>
                      <td>
                        <strong>{p.service}</strong>
                        <div className="muted mono">{p.pod}</div>
                      </td>
                      <td>
                        <Status tone={p.ready ? "green" : "red"}>{p.ready ? "Ready" : "Not ready"}</Status>
                      </td>
                      <td className="mono">{p.containers.join(", ")}</td>
                      <td>{p.initExitCode === null ? <span className="muted">—</span> : p.initExitCode === 0 ? <Status tone="green">Completed</Status> : <Status tone="red">Exit {p.initExitCode}</Status>}</td>
                      <td>{p.sidecarUid ?? "—"}</td>
                      <td>{p.role || "—"}</td>
                      <td>{p.inboundMode || "—"}</td>
                      <td>{p.optedOut ? <Tag>Excluded</Tag> : p.sidecar ? <Tag tone="green">Injected</Tag> : <Tag tone="red">Missing</Tag>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <Empty>No pods.</Empty>
          )}
        </Panel>
      </div>
      {preview && <AdmissionPreview onClose={() => setPreview(false)} />}
    </>
  );
}
