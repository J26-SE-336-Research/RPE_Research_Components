import { useEffect, useRef, useState } from "react";
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

const strategy = (mode: string) => (mode === "two-phase" ? "Two-phase" : "Immediate");

function duration(r: Rollout) {
  if (!r.status.startedAt) return "—";
  const end = r.status.completedAt ? new Date(r.status.completedAt).getTime() : Date.now();
  const s = Math.max(0, (end - new Date(r.status.startedAt).getTime()) / 1000);
  return s < 60 ? `${s.toFixed(1)} s` : `${Math.floor(s / 60)}m ${Math.round(s % 60)}s`;
}

function Mixed({ r }: { r: Rollout }) {
  const n = r.status.mixedPolicyRequests ?? 0;
  if (!r.status.startedAt) return <span className="muted">—</span>;
  return (
    <Tag tone={n > 0 ? "red" : r.status.settled ? "green" : "neutral"} title={r.status.settled ? "Final count" : "Counting"}>
      {n}
      {!r.status.settled && r.status.phase !== "Failed" ? "…" : ""}
    </Tag>
  );
}

const STEPS: Record<string, string[]> = {
  "two-phase": ["Staging", "Activating", "Draining", "Complete"],
  naive: ["Activating", "Complete"],
};

function Steps({ r }: { r: Rollout }) {
  const steps = STEPS[r.spec.mode] ?? STEPS["two-phase"];
  const at = r.status.phase === "Failed" ? -1 : steps.indexOf(r.status.phase ?? "");
  return (
    <div className="row" style={{ gap: 6 }}>
      {steps.map((s, i) => (
        <Tag key={s} tone={r.status.phase === "Failed" ? "red" : i < at || r.status.phase === "Complete" ? "green" : i === at ? "blue" : "neutral"}>
          {i + 1}. {s === "Activating" && r.spec.mode === "two-phase" ? "Activating (gateway first)" : s}
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
          <th>Proxy</th>
          <th>Role</th>
          <th>State</th>
          <th>Content hash</th>
          <th>Acknowledged</th>
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

/**
 * Rolls out a policy change under live traffic and shows how many requests were served by two different
 * versions of the policy along their call chain. Every number comes from the proxies' own counters.
 */
function ConsistencyCheck({ onStarted }: { onStarted: (name: string) => void }) {
  const [f, setF] = useState({ policyId: "payment-consistency", service: "payment-svc", deadlineMs: 1200, retryBudget: 1, mode: "two-phase", skewMs: 1500, traffic: true });
  const [running, setRunning] = useState<string | null>(null);
  const [sent, setSent] = useState(0);
  const stop = useRef(false);
  const live = useData<Rollout>(running ? `/rollouts/${running}` : "/rollouts", running ? 700 : undefined);
  const r = running ? live.data : undefined;
  const finished = !!r && (r.status.phase === "Failed" || (r.status.phase === "Complete" && r.status.settled));

  useEffect(() => {
    if (finished) stop.current = true;
  }, [finished]);
  useEffect(() => () => void (stop.current = true), []);

  const traffic = async () => {
    const until = Date.now() + 90_000;
    while (!stop.current && Date.now() < until) {
      try {
        const res = await api<{ count?: number }>("POST", "/load", { count: 40, concurrency: 8 });
        setSent((n) => n + (res?.count ?? 40));
      } catch {
        await new Promise((ok) => setTimeout(ok, 500));
      }
    }
  };

  const start = useAction(async () => {
    stop.current = false;
    setSent(0);
    if (f.traffic) {
      void traffic();
      await new Promise((ok) => setTimeout(ok, 1000)); // requests are in flight before the change
    }
    const c = await api<Created>("POST", "/policies", {
      policyId: f.policyId,
      service: f.service,
      route: "GET /api/orders",
      deadlineMs: f.deadlineMs,
      retryBudget: f.retryBudget,
      retryRatio: 0.1,
      ratePerSec: 100,
      rollout: f.mode,
      skewMs: f.skewMs,
    });
    const name = c.rollout?.metadata?.name;
    if (!name) {
      stop.current = true;
      throw new Error(`Published ${c.epoch}, but the rollout did not start`);
    }
    setRunning(name);
    onStarted(name);
  });

  const num = (k: "deadlineMs" | "retryBudget" | "skewMs") => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: Number(e.target.value) });
  const busy = start.busy || (!!running && !finished);

  return (
    <Panel
      title="Consistency check"
      subtitle="Roll out a policy change under live traffic and count the requests whose call chain was served by two versions of the policy."
      actions={
        <Button variant="primary" write onClick={() => start.run()} busy={busy}>
          Approve and roll out
        </Button>
      }
    >
      <Alert>{start.error}</Alert>
      <div className="form-grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))" }}>
        <Field label="Policy">
          <input value={f.policyId} onChange={(e) => setF({ ...f, policyId: e.target.value.toLowerCase() })} />
        </Field>
        <Field label="Service">
          <select value={f.service} onChange={(e) => setF({ ...f, service: e.target.value })}>
            {SERVICES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="New deadline (ms)">
          <input type="number" min={1} value={f.deadlineMs} onChange={num("deadlineMs")} />
        </Field>
        <Field label="New retry budget">
          <input type="number" min={0} max={255} value={f.retryBudget} onChange={num("retryBudget")} />
        </Field>
        <Field label="Strategy">
          <select value={f.mode} onChange={(e) => setF({ ...f, mode: e.target.value })}>
            <option value="two-phase">Two-phase</option>
            <option value="naive">Immediate</option>
          </select>
        </Field>
        <Field label="Propagation spread (ms)" hint="Random delay per proxy, same for both strategies">
          <input type="number" min={0} max={10000} step={250} value={f.skewMs} onChange={num("skewMs")} />
        </Field>
      </div>
      <label className="check" style={{ marginTop: 12 }}>
        <input type="checkbox" checked={f.traffic} onChange={(e) => setF({ ...f, traffic: e.target.checked })} />
        Send traffic through the gateway during the rollout
      </label>
      <p className="muted" style={{ marginBottom: 0 }}>
        You are recorded as the approver. Each run publishes a new version of the policy; the previous version stays active until the rollout replaces it.
      </p>

      {r && (
        <>
          <div className="section-title">
            {strategy(r.spec.mode)} rollout of {r.spec.epoch}
            {r.spec.previousEpoch ? ` (replaces ${r.spec.previousEpoch})` : ""}
          </div>
          <div className="stack" style={{ gap: 14 }}>
            <Steps r={r} />
            <div className="kpis">
              <Kpi
                label="Mixed-policy requests"
                value={<span style={{ color: (r.status.mixedPolicyRequests ?? 0) > 0 ? "var(--red)" : r.status.settled ? "var(--green)" : undefined }}>{r.status.mixedPolicyRequests ?? 0}</span>}
                foot={r.status.settled ? "final, counted by the proxies" : "counting…"}
              />
              <Kpi label="Converged in" value={r.status.convergenceMs ? fmtMs(r.status.convergenceMs) : "—"} foot="until every proxy enforces it" />
              <Kpi
                label="Drain"
                value={r.spec.mode === "two-phase" ? (r.status.phase === "Draining" ? `${r.status.inflightPrevious ?? 0} in flight` : r.status.drainMs !== undefined ? fmtMs(r.status.drainMs) : "—") : "none"}
                foot={r.spec.previousEpoch ? `requests pinned to ${r.spec.previousEpoch}` : undefined}
              />
              <Kpi label="Requests sent" value={sent} foot={f.traffic ? "GET /api/orders through the gateway" : "traffic off"} />
            </div>
            {r.status.message && <div className="muted">{r.status.message}</div>}
            <PodGrid r={r} />
          </div>
        </>
      )}
    </Panel>
  );
}

function Comparison({ rows }: { rows: Rollout[] }) {
  const done = rows.filter((r) => r.status.settled && r.status.phase === "Complete").slice(0, 12);
  if (!done.length) return null;
  const avg = (mode: string, k: "mixedPolicyRequests" | "convergenceMs") => {
    const xs = done.filter((r) => r.spec.mode === mode).map((r) => r.status[k] ?? 0);
    return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : undefined;
  };
  return (
    <Panel title="Measured rollouts" subtitle="Settled rollouts, newest first." flush>
      <div className="kpis" style={{ padding: 16 }}>
        <Kpi label="Two-phase: mixed (avg)" value={avg("two-phase", "mixedPolicyRequests")?.toFixed(1) ?? "—"} foot={`convergence ${fmtMs(avg("two-phase", "convergenceMs"))}`} />
        <Kpi label="Immediate: mixed (avg)" value={avg("naive", "mixedPolicyRequests")?.toFixed(1) ?? "—"} foot={`convergence ${fmtMs(avg("naive", "convergenceMs"))}`} />
      </div>
      <table>
        <thead>
          <tr>
            <th>Version</th>
            <th>Strategy</th>
            <th className="num">Spread</th>
            <th className="num">Proxies</th>
            <th className="num">Mixed-policy requests</th>
            <th className="num">Converged in</th>
            <th className="num">Drain</th>
          </tr>
        </thead>
        <tbody>
          {done.map((r) => (
            <tr key={r.metadata.name}>
              <td className="mono">{r.spec.epoch}</td>
              <td>{strategy(r.spec.mode)}</td>
              <td className="num">{fmtMs(r.spec.skewMs ?? 0)}</td>
              <td className="num">{r.status.targetPods}</td>
              <td className="num">
                <Mixed r={r} />
              </td>
              <td className="num">{fmtMs(r.status.convergenceMs)}</td>
              <td className="num">{r.spec.mode === "two-phase" ? fmtMs(r.status.drainMs ?? 0) : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
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
      <PageHeader title="Rollouts" description="Delivery of approved policy versions to the running proxies." />
      <Alert>{error}</Alert>
      <div className="stack">
        <ConsistencyCheck onStarted={() => reload()} />
        <div className="kpis">
          <Kpi label="In progress" value={data ? running : "—"} />
          <Kpi label="Completed" value={data?.filter((r) => r.status.phase === "Complete").length ?? "—"} />
          <Kpi label="Failed" value={data ? failedCount : "—"} />
        </div>
        {data && <Comparison rows={data} />}
        <Panel title="All rollouts" flush>
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
                    <th className="num">Mixed</th>
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
                      <td className="num">
                        <Mixed r={r} />
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
            <Empty>No rollouts yet. Publish a policy and roll it out from Policies, or run a consistency check above.</Empty>
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
              ["Propagation spread", fmtMs(sel.spec.skewMs ?? 0)],
              ["Mixed-policy requests", <Mixed key="m" r={sel} />],
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
          <div className="section-title">Proxies</div>
          <PodGrid r={sel} />
        </Drawer>
      )}
    </>
  );
}
