import { useState } from "react";
import { api, useAction, useData } from "../api";
import { Ago, Alert, Button, Drawer, Empty, Field, Kpi, KV, PageHeader, Panel, Status, Tag, fmtMs, type Tone } from "../components/ui";
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
const SERVICES = ["payment-svc", "inventory-svc", "order-svc", "loyalty-svc", "fraud-check-svc", "shipping-svc"];

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
  return r.spec.service === "gateway" ? "the gateway sidecars" : `the ${r.spec.service} sidecars`;
}

const STEPS: Record<string, string[]> = {
  "two-phase": ["Staging", "Activating", "Draining", "Complete"],
  naive: ["Activating", "Complete"],
};
const STEP_LABEL: Record<string, string> = { Staging: "Stage and verify", Activating: "Activate", Draining: "Drain previous version", Complete: "Complete" };

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

type Created = { epoch: string; rollout?: { metadata?: { name: string } } };

type Control = "chain-deadline" | "rate-limit" | "retry-ratio";
const CONTROLS: Record<Control, { label: string; where: string; unit: string }> = {
  "chain-deadline": { label: "Chain deadline", where: "Delivered to the gateway sidecars only: the gateway creates every request's budget.", unit: "ms" },
  "rate-limit": { label: "Rate limit", where: "Delivered to the target service's sidecars only.", unit: "requests per second" },
  "retry-ratio": { label: "Retry ratio", where: "Delivered to the target service's sidecars only.", unit: "0 to 1" },
};

/**
 * Publishes one control as its own policy version and delivers it to the sidecars it concerns. The panel shows
 * which sidecars received it, that each holds the approved content (hash verified) and that each acknowledged it.
 */
function PolicyDelivery({ onStarted }: { onStarted: (name: string) => void }) {
  const [f, setF] = useState({ control: "rate-limit" as Control, service: "payment-svc", route: "GET /api/orders", value: 150, mode: "two-phase", delay: 0 });
  const [running, setRunning] = useState<string | null>(null);
  const live = useData<Rollout>(running ? `/rollouts/${running}` : "/rollouts", running ? 700 : undefined);
  const r = running ? live.data : undefined;
  const finished = !!r && (r.status.phase === "Failed" || (r.status.phase === "Complete" && r.status.settled));
  const c = CONTROLS[f.control];

  const start = useAction(async () => {
    const body: Record<string, unknown> = { control: f.control, rollout: f.mode, skewMs: f.delay };
    if (f.control === "chain-deadline") {
      body.route = f.route;
      body.deadlineMs = f.value;
    } else {
      body.service = f.service;
      if (f.control === "rate-limit") body.ratePerSec = f.value;
      else body.retryRatio = f.value;
    }
    const created = await api<Created>("POST", "/policies", body);
    const name = created.rollout?.metadata?.name;
    if (!name) throw new Error(`Published ${created.epoch}, but the rollout did not start`);
    setRunning(name);
    onStarted(name);
  });

  const busy = start.busy || (!!running && !finished);
  const pods = r?.status.pods ?? [];
  const verified = pods.filter((p) => p.verified).length;
  const acknowledged = pods.filter((p) => p.confirmedAt).length;

  return (
    <Panel
      title="Policy delivery"
      subtitle="Publish a change to one control and deliver it to the sidecars it concerns."
      actions={
        <Button variant="primary" write onClick={() => start.run()} busy={busy}>
          Approve and deliver
        </Button>
      }
    >
      <Alert>{start.error}</Alert>
      <div className="form-grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))" }}>
        <Field label="Control">
          <select
            value={f.control}
            onChange={(e) => {
              const control = e.target.value as Control;
              setF({ ...f, control, value: control === "chain-deadline" ? 1000 : control === "rate-limit" ? 150 : 0.1 });
            }}
          >
            {(Object.keys(CONTROLS) as Control[]).map((k) => (
              <option key={k} value={k}>
                {CONTROLS[k].label}
              </option>
            ))}
          </select>
        </Field>
        {f.control === "chain-deadline" ? (
          <Field label="Route" hint="Budget the gateway stamps for this route">
            <input value={f.route} onChange={(e) => setF({ ...f, route: e.target.value })} />
          </Field>
        ) : (
          <Field label="Target service">
            <select value={f.service} onChange={(e) => setF({ ...f, service: e.target.value })}>
              {SERVICES.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </Field>
        )}
        <Field label={`New value (${c.unit})`}>
          <input type="number" min={f.control === "retry-ratio" ? 0 : 1} max={f.control === "retry-ratio" ? 1 : undefined} step={f.control === "retry-ratio" ? 0.05 : 1} value={f.value} onChange={(e) => setF({ ...f, value: Number(e.target.value) })} />
        </Field>
        <Field label="Strategy">
          <select value={f.mode} onChange={(e) => setF({ ...f, mode: e.target.value })}>
            <option value="two-phase">Verify, then activate</option>
            <option value="naive">Immediate</option>
          </select>
        </Field>
        <Field label="Delivery delay (ms)" hint="Optional: random delay per sidecar, to watch a slow delivery or interrupt it">
          <input type="number" min={0} max={10000} step={250} value={f.delay} onChange={(e) => setF({ ...f, delay: Number(e.target.value) })} />
        </Field>
      </div>
      <p className="muted" style={{ marginBottom: 0 }}>
        {c.where} You are recorded as the approver. Each change publishes a new version; the service's other settings are kept.
      </p>

      {r && (
        <>
          <div className="section-title">
            {CONTROLS[f.control].label} · {r.spec.epoch} → {targetsLabel(r)}
          </div>
          <div className="stack" style={{ gap: 14 }}>
            <Steps r={r} />
            <div className="kpis">
              <Kpi label="Delivered to" value={r.status.targetPods ?? "—"} foot={targetsLabel(r)} />
              <Kpi label="Hash verified" value={`${verified} / ${pods.length}`} foot="proxy holds the approved content" />
              <Kpi label="Acknowledged" value={`${acknowledged} / ${pods.length}`} foot="confirmed by the sidecar itself" />
              <Kpi label="Converged in" value={r.status.convergenceMs ? fmtMs(r.status.convergenceMs) : "—"} foot="until every target enforces it" />
            </div>
            {r.status.message && <div className="muted">{r.status.message}</div>}
            <PodGrid r={r} />
          </div>
        </>
      )}
    </Panel>
  );
}

export function RolloutsPage() {
  const { data, error, reload } = useData<Rollout[]>("/rollouts", 2000);
  const [selected, setSelected] = useState<string | null>(null);
  const sel = data?.find((r) => r.metadata.name === selected);
  const running = data?.filter((r) => !["Complete", "Failed"].includes(r.status.phase ?? "")).length ?? 0;
  const failedCount = data?.filter((r) => r.status.phase === "Failed").length ?? 0;

  return (
    <>
      <PageHeader title="Rollouts" description="Delivery of approved policy versions to the sidecars they concern." />
      <Alert>{error}</Alert>
      <div className="stack">
        <PolicyDelivery onStarted={() => reload()} />
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
                    <th>Strategy</th>
                    <th>Status</th>
                    <th className="num">Sidecars</th>
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
                      </td>
                      <td className="mono">{r.spec.epoch}</td>
                      <td>{r.spec.service}</td>
                      <td>{r.spec.mode === "two-phase" ? "Verify, then activate" : <Tag tone="amber">Immediate</Tag>}</td>
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
            <Empty>No rollouts yet. Deliver a change above, or publish a policy from Policies.</Empty>
          )}
        </Panel>
      </div>

      {sel && (
        <Drawer title={sel.spec.epoch} subtitle={sel.metadata.name} onClose={() => setSelected(null)}>
          <KV
            items={[
              ["Status", phaseStatus(sel.status.phase)],
              ["Policy", `${sel.spec.policyId} v${sel.spec.version}`],
              ["Delivered to", targetsLabel(sel)],
              ["Strategy", sel.spec.mode === "two-phase" ? "Verify, then activate (stage, check the content hash, activate, drain the previous version)" : "Immediate (all target sidecars at once)"],
              ["Replaces", sel.spec.previousEpoch || "—"],
              ["Converged in", fmtMs(sel.status.convergenceMs)],
              ["Drain", sel.spec.mode === "two-phase" ? fmtMs(sel.status.drainMs) : "—"],
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
          <div className="section-title">Sidecars</div>
          <PodGrid r={sel} />
        </Drawer>
      )}
    </>
  );
}
