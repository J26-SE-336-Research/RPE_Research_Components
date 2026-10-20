import { useState } from "react";
import { useData } from "../api";
import { Ago, Alert, Drawer, Empty, Kpi, KV, PageHeader, Panel, Status, Tag, fmtMs, type Tone } from "../components/ui";
import type { Rollout } from "../types";
import { ConsistencyDemo } from "./Consistency";

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
  return s < 60 ? `${s.toFixed(1)} s` : `${Math.floor(s / 60)}m ${Math.round(s % 60)}s`;
}

/** The proxies a policy is delivered to, by its scope: the gateway for the chain deadline, else the service. */
function targetsLabel(r: Rollout) {
  if (r.spec.services?.length) return `the ${r.spec.services.join(" and ")} sidecars (one joint version, a part each)`;
  return r.spec.service === "gateway" ? "the gateway sidecars" : `the ${r.spec.service} sidecars`;
}

const STEPS: Record<string, string[]> = {
  "two-phase": ["Staging", "Activating", "Draining", "Complete"],
  naive: ["Activating", "Complete"],
};
const STEP_LABEL: Record<string, string> = { Staging: "Stage and verify", Activating: "Activate (enforced)", Draining: "Remove the old version once unused", Complete: "Complete" };

function Steps({ r }: { r: Rollout }) {
  const steps = STEPS[r.spec.mode] ?? STEPS["two-phase"];
  const at = r.status.phase === "Failed" ? -1 : steps.indexOf(r.status.phase ?? "");
  return (
    <div className="row" style={{ gap: 6 }}>
      {steps.map((s, i) => (
        <Tag key={s} tone={r.status.phase === "Failed" ? "red" : i < at || r.status.phase === "Complete" ? "green" : i === at ? "blue" : "neutral"}>
          {i + 1}. {STEP_LABEL[s] ?? s}
        </Tag>
      ))}
    </div>
  );
}

function PodGrid({ r }: { r: Rollout }) {
  return (
    <table>
      <thead>
        <tr>
          <th>Sidecar</th>
          <th>Role</th>
          <th>State</th>
          <th>Content hash</th>
          <th>Acknowledged by the sidecar</th>
        </tr>
      </thead>
      <tbody>
        {r.status.pods?.map((p) => (
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
            <td className="mono">
              {p.hash ? (
                <>
                  {p.hash} {p.verified ? <Tag tone="green">verified</Tag> : <Tag tone="red">mismatch</Tag>}
                </>
              ) : (
                <span className="muted">—</span>
              )}
            </td>
            <td className="muted nowrap">{p.confirmedAt ? <Ago at={p.confirmedAt} /> : "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Time from the start of a rollout until its last target sidecar enforced the new version. */
export function enforcedAfter(r: Rollout) {
  if (!r.status.startedAt) return undefined;
  const times = (r.status.pods ?? []).map((p) => (p.activeAt ? new Date(p.activeAt).getTime() : NaN)).filter((t) => !Number.isNaN(t));
  if (!times.length) return undefined;
  return Math.max(0, Math.max(...times) - new Date(r.status.startedAt).getTime());
}

/**
 * The real delivery path: every approved Component 4 recommendation is delivered as soon as it is approved, two-phase,
 * with no choice of mechanism and no waiting for an operator. Experiment runs (simulated naive deliveries) are left out.
 */
function LatestDeliveries({ data }: { data?: Rollout[] }) {
  const rows = (data ?? []).filter((r) => r.spec.mode !== "naive").slice(0, 6);
  return (
    <Panel
      title="Latest deliveries (automatic)"
      subtitle="Every Component 4 recommendation approved in Recommendations is delivered the moment it is approved: staged, verified by hash, activated. No value is entered by hand, no delivery mechanism is chosen and nobody has to press Run."
      flush
    >
      {rows.length ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Change (version)</th>
                <th>Delivered to</th>
                <th>Approved by</th>
                <th>Approved</th>
                <th className="num">Enforced after</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.metadata.name}>
                  <td className="mono">{r.spec.epoch}</td>
                  <td>{r.spec.services?.join(", ") ?? r.spec.service}</td>
                  <td>{r.spec.requestedBy || "—"}</td>
                  <td className="muted nowrap">
                    <Ago at={r.metadata.creationTimestamp} />
                  </td>
                  <td className="num">{fmtMs(enforcedAfter(r))}</td>
                  <td>{phaseStatus(r.status.phase)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty>No deliveries yet. They appear here as soon as a recommendation is approved in Recommendations.</Empty>
      )}
    </Panel>
  );
}

export function RolloutsPage() {
  const { data, error } = useData<Rollout[]>("/rollouts", 2000);
  const [selected, setSelected] = useState<string | null>(null);
  const sel = data?.find((r) => r.metadata.name === selected);
  const running = data?.filter((r) => !["Complete", "Failed"].includes(r.status.phase ?? "")).length ?? 0;
  const failedCount = data?.filter((r) => r.status.phase === "Failed").length ?? 0;

  return (
    <>
      <PageHeader title="Rollouts" description="Delivery of approved policy versions to the sidecars they concern." />
      <Alert>{error}</Alert>
      <div className="stack">
        <LatestDeliveries data={data} />
        <div className="kpis">
          <Kpi label="In progress" value={data ? running : "—"} />
          <Kpi label="Completed" value={data?.filter((r) => r.status.phase === "Complete").length ?? "—"} />
          <Kpi label="Failed" value={data ? failedCount : "—"} />
        </div>
        <Panel title="All rollouts" flush>
          {data?.length ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Policy</th>
                    <th>Version</th>
                    <th>Delivered to</th>
                    <th>Delivery</th>
                    <th>Status</th>
                    <th className="num">Sidecars</th>
                    <th>Started</th>
                    <th className="num">Enforced after</th>
                    <th>Requested by</th>
                  </tr>
                </thead>
                <tbody>
                  {data.map((r) => (
                    <tr key={r.metadata.name} className="clickable" onClick={() => setSelected(r.metadata.name)}>
                      <td>
                        <strong>{r.spec.policyId}</strong>
                      </td>
                      <td className="mono">{r.spec.epoch}</td>
                      <td>{r.spec.services?.join(", ") ?? r.spec.service}</td>
                      <td>{r.spec.mode === "two-phase" ? "Automatic" : <Tag tone="amber">Experiment: simulated without C3</Tag>}</td>
                      <td>{phaseStatus(r.status.phase)}</td>
                      <td className="num">
                        {r.status.active ?? 0} / {r.status.targetPods ?? "—"}
                      </td>
                      <td className="muted nowrap">
                        <Ago at={r.metadata.creationTimestamp} />
                      </td>
                      <td className="num">{fmtMs(enforcedAfter(r))}</td>
                      <td>{r.spec.requestedBy || "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <Empty>No rollouts yet. They start automatically when a recommendation is approved.</Empty>
          )}
        </Panel>
        <ConsistencyDemo />
      </div>

      {sel && (
        <Drawer title={sel.spec.epoch} subtitle={sel.metadata.name} onClose={() => setSelected(null)}>
          <Steps r={sel} />
          <KV
            items={[
              ["Status", phaseStatus(sel.status.phase)],
              ["Policy", `${sel.spec.policyId} v${sel.spec.version}`],
              ["Delivered to", targetsLabel(sel)],
              [
                "Delivery",
                sel.spec.mode === "two-phase"
                  ? "Automatic (stage, check the content hash, activate, then remove the previous version once unused)"
                  : "Experiment E4 only: simulated delivery without C3 (each sidecar switches when the change reaches it)",
              ],
              ["Enforced after", fmtMs(enforcedAfter(sel))],
              ["Replaces", sel.spec.previousEpoch || "—"],
              ...(sel.status.plan?.length
                ? ([["Order", sel.status.plan.map((p) => `${p.step}. ${p.kind} ${p.service}${p.changes?.length ? ` (${p.changes.join(", ")})` : ""}`).join("; ")], ["Waited before tightening", fmtMs(sel.status.gateMs)]] as [string, string][])
                : []),
              ...(sel.spec.order ? ([["Simulated arrival order", `${sel.spec.order}, ${sel.spec.skewMs ?? 0} ms simulated network delay`]] as [string, string][]) : []),
              ["Mixed-policy requests", String(sel.status.mixedPolicyRequests ?? 0)],
              ["Old version removed after", sel.spec.mode === "two-phase" ? `${fmtMs(sel.status.drainMs)} (clean-up only; the new version was already enforced)` : "—"],
              ["Started", sel.status.startedAt ? new Date(sel.status.startedAt).toLocaleString() : "—"],
              ["Completed", sel.status.completedAt ? new Date(sel.status.completedAt).toLocaleString() : "—"],
              ["Rollout finished after", duration(sel)],
            ]}
          />
          {sel.status.message && (
            <p className="muted" style={{ marginBottom: 0 }}>
              {sel.status.message}
            </p>
          )}
          <div className="section-title">Sidecars</div>
          <PodGrid r={sel} />
        </Drawer>
      )}
    </>
  );
}
