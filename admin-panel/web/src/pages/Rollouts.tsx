import { useState } from "react";
import { useData } from "../api";
import { Ago, Alert, Drawer, Empty, Kpi, KV, PageHeader, Panel, Status, Tag, type Tone } from "../components/ui";
import type { Rollout } from "../types";

const PHASE: Record<string, [Tone, string]> = {
  Pending: ["neutral", "Pending"],
  Staging: ["blue", "Staging"],
  Activating: ["blue", "Activating"],
  Draining: ["blue", "Draining"],
  Complete: ["green", "Complete"],
  Failed: ["red", "Failed"],
};

const POD_STATE: Record<string, Tone> = { active: "green", staged: "blue", pending: "neutral", gone: "amber", failed: "red" };

export function phaseStatus(phase?: string) {
  const [tone, label] = PHASE[phase ?? "Pending"] ?? ["neutral", phase ?? "Pending"];
  return <Status tone={tone}>{label}</Status>;
}

function duration(r: Rollout) {
  if (!r.status.startedAt) return "—";
  const end = r.status.completedAt ? new Date(r.status.completedAt).getTime() : Date.now();
  const s = Math.max(0, (end - new Date(r.status.startedAt).getTime()) / 1000);
  return s < 60 ? `${s.toFixed(0)} s` : `${Math.floor(s / 60)}m ${Math.round(s % 60)}s`;
}

export function RolloutsPage() {
  const { data, error } = useData<Rollout[]>("/rollouts", 2000);
  const [selected, setSelected] = useState<string | null>(null);
  const sel = data?.find((r) => r.metadata.name === selected);
  const running = data?.filter((r) => !["Complete", "Failed"].includes(r.status.phase ?? "")).length ?? 0;
  const failedCount = data?.filter((r) => r.status.phase === "Failed").length ?? 0;

  return (
    <>
      <PageHeader title="Rollouts" description="Delivery of approved policy versions to the running proxies." />
      <Alert>{error}</Alert>
      <div className="stack">
        <div className="kpis">
          <Kpi label="In progress" value={data ? running : "—"} />
          <Kpi label="Completed" value={data?.filter((r) => r.status.phase === "Complete").length ?? "—"} />
          <Kpi label="Failed" value={data ? failedCount : "—"} />
        </div>
        <Panel flush>
          {data?.length ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Policy</th>
                    <th>Version</th>
                    <th>Strategy</th>
                    <th>Status</th>
                    <th className="num">Proxies</th>
                    <th>Started</th>
                    <th className="num">Duration</th>
                    <th>Requested by</th>
                  </tr>
                </thead>
                <tbody>
                  {data.map((r) => (
                    <tr key={r.metadata.name} className="clickable" onClick={() => setSelected(r.metadata.name)}>
                      <td>
                        <strong>{r.spec.policyId}</strong>
                        <div className="muted">{r.spec.service}</div>
                      </td>
                      <td className="mono">{r.spec.epoch}</td>
                      <td>{r.spec.mode === "two-phase" ? "Two-phase" : <Tag tone="amber">Immediate</Tag>}</td>
                      <td>{phaseStatus(r.status.phase)}</td>
                      <td className="num">
                        {r.status.active ?? 0} / {r.status.targetPods ?? "—"}
                      </td>
                      <td className="muted nowrap">
                        <Ago at={r.metadata.creationTimestamp} />
                      </td>
                      <td className="num">{duration(r)}</td>
                      <td>{r.spec.requestedBy || "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <Empty>No rollouts yet. Publish a policy and roll it out from Policies.</Empty>
          )}
        </Panel>
      </div>

      {sel && (
        <Drawer title={sel.spec.epoch} subtitle={sel.metadata.name} onClose={() => setSelected(null)}>
          <KV
            items={[
              ["Status", phaseStatus(sel.status.phase)],
              ["Policy", `${sel.spec.policyId} v${sel.spec.version}`],
              ["Service", sel.spec.service],
              ["Strategy", sel.spec.mode === "two-phase" ? "Two-phase (stage, activate at the gateway first, drain)" : "Immediate (all proxies at once)"],
              ["Replaces", sel.spec.previousEpoch || "—"],
              ["Started", sel.status.startedAt ? new Date(sel.status.startedAt).toLocaleString() : "—"],
              ["Completed", sel.status.completedAt ? new Date(sel.status.completedAt).toLocaleString() : "—"],
              ["Duration", duration(sel)],
            ]}
          />
          {sel.status.message && (
            <p className="muted" style={{ marginBottom: 0 }}>
              {sel.status.message}
            </p>
          )}
          <div className="section-title">Proxies</div>
          <table>
            <thead>
              <tr>
                <th>Pod</th>
                <th>Role</th>
                <th>State</th>
              </tr>
            </thead>
            <tbody>
              {sel.status.pods?.map((p) => (
                <tr key={p.name}>
                  <td>
                    {p.service}
                    <div className="muted mono">{p.name}</div>
                  </td>
                  <td>{p.role}</td>
                  <td>
                    <Status tone={POD_STATE[p.state] ?? "neutral"}>{p.state}</Status>
                    {p.error && p.state !== "active" && <div className="muted" style={{ fontSize: 13.5 }}>{p.error}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Drawer>
      )}
    </>
  );
}
