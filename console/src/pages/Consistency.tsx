import { useState } from "react";
import { api, useAction, useData } from "../api";
import { Alert, Button, Empty, Field, Kpi, Panel, Status, Tag, fmtMs } from "../components/ui";
import { planStep, type ConsistencyRun, type ConsistencyState } from "../types";

type Delivery = "two-phase" | "naive-services-first" | "naive-gateway-first" | "naive-random";
const DELIVERY: Record<Delivery, { label: string; mode: string; order?: string; skewMs: number; hint: string }> = {
  "two-phase": {
    label: "With C3 (two-phase, as in production)",
    mode: "two-phase",
    skewMs: 0,
    hint: "Exactly what RPE does for every approved change: stage on every sidecar, loosen first (the gateway stamps the new version), wait until no request of the old version is in flight, then tighten.",
  },
  "naive-services-first": {
    label: "Simulated without C3: the service switches first",
    mode: "naive",
    order: "services-first",
    skewMs: 4000,
    hint: "Simulates a system without C3: each sidecar switches when the change happens to reach it. Here payment's sidecar is reached first and the gateway's later.",
  },
  "naive-gateway-first": {
    label: "Simulated without C3: the gateway switches first",
    mode: "naive",
    order: "gateway-first",
    skewMs: 4000,
    hint: "Simulates a system without C3: each sidecar switches when the change happens to reach it. Here the gateway's sidecar is reached first and payment's later.",
  },
  "naive-random": {
    label: "Simulated without C3: random arrival",
    mode: "naive",
    skewMs: 3000,
    hint: "Simulates a system without C3: each sidecar is reached after a random network delay, so the order is left to chance, as in a real fleet.",
  },
};

function label(r: ConsistencyRun) {
  if (r.mode === "two-phase") return "With C3 (two-phase)";
  if (r.order === "services-first") return "Without C3: service first (simulated)";
  if (r.order === "gateway-first") return "Without C3: gateway first (simulated)";
  return `Without C3: random arrival (simulated, ${r.skewMs} ms)`;
}

/** Slowest demo request after the approval: the same with and without C3, which only changes the version a request runs under. */
function maxLatencyAfter(r: ConsistencyRun) {
  return r.samples.filter((s) => s.atMs >= 0).reduce((m, s) => Math.max(m, s.ms), 0);
}

/** E4, 18 Oct 2026, 5 runs of each delivery (experiments/c3/results/e4-20261018-150733-summary.csv). */
const E4 = [
  { delivery: "With C3 (two-phase)", runs: "0 of 5", signals: "0", mixed: "0" },
  { delivery: "Without C3: service first (simulated 4 s delay)", runs: "5 of 5", signals: "2 to 3", mixed: "14" },
  { delivery: "Without C3: gateway first (simulated 4 s delay)", runs: "0 of 5", signals: "0", mixed: "16 to 17" },
  { delivery: "Without C3: random arrival (simulated up to 3 s)", runs: "1 of 5", signals: "0 to 2", mixed: "1 to 15" },
];

function E4Summary() {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Delivery (E4, 5 runs each)</th>
            <th className="num">Runs with stale-deadline signals</th>
            <th className="num">Stale-deadline signals per run</th>
            <th className="num">Mixed-policy requests per run</th>
          </tr>
        </thead>
        <tbody>
          {E4.map((e) => (
            <tr key={e.delivery}>
              <td>{e.delivery}</td>
              <td className="num">{e.runs}</td>
              <td className="num">{e.signals}</td>
              <td className="num">{e.mixed}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Latency of every demo request over time, with the deadlines and the moments each sidecar switched. */
function Timeline({ r }: { r: ConsistencyRun }) {
  const W = 760, H = 230, L = 46, B = 26, T = 10, R = 10;
  const from = -r.config.warmupMs, to = Math.max(r.completedAtMs + r.config.tailMs, 1000);
  const top = Math.max(r.config.newDeadlineMs, ...r.samples.map((s) => s.ms)) * 1.08;
  const x = (t: number) => L + ((t - from) / (to - from)) * (W - L - R);
  const y = (ms: number) => T + (1 - ms / top) * (H - T - B);
  const ticks = [];
  for (let t = Math.ceil(from / 2000) * 2000; t <= to; t += 2000) ticks.push(t);
  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: "100%", height: "auto", display: "block" }} role="img" aria-label="Request latency during the change">
      <rect x={L} y={T} width={W - L - R} height={H - T - B} fill="var(--surface-2)" stroke="var(--border)" />
      {ticks.map((t) => (
        <g key={t}>
          <line x1={x(t)} x2={x(t)} y1={H - B} y2={H - B + 4} stroke="var(--muted)" />
          <text x={x(t)} y={H - 8} fontSize="11" textAnchor="middle" fill="var(--muted)">
            {t / 1000}s
          </text>
        </g>
      ))}
      {[r.config.baseDeadlineMs, r.config.newDeadlineMs].map((d, i) => (
        <g key={d}>
          <line x1={L} x2={W - R} y1={y(d)} y2={y(d)} stroke={i === 0 ? "var(--red)" : "var(--green)"} strokeDasharray="5 4" />
          <text x={L - 4} y={y(d) + 4} fontSize="11" textAnchor="end" fill={i === 0 ? "var(--red)" : "var(--green)"}>
            {d}
          </text>
        </g>
      ))}
      <line x1={x(0)} x2={x(0)} y1={T} y2={H - B} stroke="var(--text-2)" />
      <text x={x(0) + 4} y={T + 12} fontSize="11" fill="var(--text-2)">
        change approved
      </text>
      {r.arrivals.map((a, i) => (
        <g key={a.pod}>
          <line x1={x(a.atMs)} x2={x(a.atMs)} y1={T} y2={H - B} stroke="var(--primary)" strokeDasharray="2 3" />
          <text x={x(a.atMs) + 4} y={T + 26 + i * 13} fontSize="11" fill="var(--primary)">
            {a.part === "gateway" ? "gateway: deadline " + r.config.newDeadlineMs + " ms" : `${a.service}: rate ${r.config.newRate}/s`}
          </text>
        </g>
      ))}
      {r.samples.map((s, i) => (
        <circle key={i} cx={x(s.atMs)} cy={y(s.ms)} r={3.2} fill={s.cs === "expired" ? "var(--red)" : s.status >= 300 || s.status === 0 ? "var(--amber)" : "var(--green)"} opacity={0.8}>
          <title>{`${s.ms} ms, deadline ${s.bud} ms, ${s.cs === "expired" ? "expired" : "on time"} (${s.pe || "no epoch"})`}</title>
        </circle>
      ))}
      <text x={12} y={T + (H - T - B) / 2} fontSize="11" fill="var(--muted)" transform={`rotate(-90 12 ${T + (H - T - B) / 2})`} textAnchor="middle">
        latency (ms)
      </text>
    </svg>
  );
}

function RunDetail({ r }: { r: ConsistencyRun }) {
  const stale = r.expiredOldDeadline > 0;
  return (
    <div className="stack" style={{ gap: 14 }}>
      <div className="kpis">
        <Kpi
          label="Stale-deadline signals"
          value={<span style={{ color: stale ? "var(--red)" : "var(--green)" }}>{r.expiredOldDeadline}</span>}
          foot={`cancel signals judged against the replaced ${r.config.baseDeadlineMs} ms deadline (of ${r.during} requests)`}
        />
        <Kpi label="Mixed-policy requests" value={r.mixedPolicyRequests} foot="served under another version than they carry" />
        <Kpi label="Slowest request after the approval" value={fmtMs(maxLatencyAfter(r))} foot="latency is the same with and without C3" />
        <Kpi
          label={r.mode === "two-phase" ? "Waited before tightening" : "Simulated network delay"}
          value={r.mode === "two-phase" ? fmtMs(r.gateMs) : fmtMs(r.skewMs)}
          foot={r.mode === "two-phase" ? "until the old version drained" : "added by the simulation, not by RPE"}
        />
      </div>
      <Timeline r={r} />
      <div className="row muted" style={{ fontSize: 13.5 }}>
        <Tag tone="green">on time</Tag>
        <Tag tone="red">cancel signal (cs=expired), request still served</Tag>
        <span>Dashed lines: the old ({r.config.baseDeadlineMs} ms) and the new ({r.config.newDeadlineMs} ms) chain deadline. Blue: when each sidecar started enforcing the new version.</span>
      </div>
      {r.plan && r.plan.length > 0 && (
        <div className="muted" style={{ fontSize: 13.5 }}>
          Order decided by C3:{" "}
          {r.plan
            .slice()
            .sort((a, b) => a.step - b.step)
            .map(planStep)
            .join(" → wait for the old version to drain → ")}
        </div>
      )}
      <div className="muted" style={{ fontSize: 13.5 }}>
        {r.previousEpoch || "—"} → {r.epoch || "—"} · rollout {r.rollout || "—"} · {r.requests} requests sent, {r.failed} without a 2xx answer, {r.expiredBefore} expired before the change
      </div>
    </div>
  );
}

/**
 * Experiment E4, not the real delivery path. RPE always delivers an approved change automatically and two-phase (see
 * "Latest deliveries" on the Rollouts page). This card simulates a system without C3 handling one joint decision
 * (a longer chain deadline at the gateway and a lower rate at payment-svc) and compares it with C3 under the same
 * traffic. The naive deliveries and their network delay exist only here.
 */
export function ConsistencyDemo() {
  const [open, setOpen] = useState(false);
  const [delivery, setDelivery] = useState<Delivery>("two-phase");
  const [skew, setSkew] = useState(DELIVERY["two-phase"].skewMs);
  const [selected, setSelected] = useState<string | null>(null);
  const { data, error, reload } = useData<ConsistencyState>("/demos/consistency", open ? 1000 : 5000);
  const d = DELIVERY[delivery];
  const c = data?.defaults;
  const start = useAction(async () => {
    const run = await api<ConsistencyRun>("POST", "/demos/consistency", { mode: d.mode, order: d.order, skewMs: d.mode === "naive" ? skew : 0 });
    setSelected(run.id);
    reload();
  });
  const runs = data?.runs ?? [];
  const shown = runs.find((r) => r.id === selected) ?? runs[0];

  return (
    <Panel
      title="Experiment E4: why coordinated delivery matters (simulation)"
      subtitle="Not the delivery path. RPE delivers every approved change automatically, as shown above. This experiment simulates a system without C3 and compares it with C3 under the same traffic."
      actions={
        <>
          <Button onClick={() => setOpen(!open)}>{open ? "Hide experiment" : "Open experiment"}</Button>
          {open && (
            <Button variant="primary" write onClick={() => start.run()} busy={start.busy || !!data?.busy}>
              Run experiment
            </Button>
          )}
        </>
      }
    >
      <Alert>{start.error ?? error}</Alert>
      {!open && (
        <>
          <p style={{ marginTop: 0 }}>
            One joint decision, chain deadline 1000 → 4000 ms at the gateway and payment-svc 100 → 6 requests/s, delivered under the same traffic. Without C3 each
            sidecar switches when the change happens to reach it; with C3 it switches as one unit. Measured results:
          </p>
          <E4Summary />
          {data?.busy && <div className="muted" style={{ marginTop: 10 }}>An experiment run is in progress.</div>}
        </>
      )}
      {open && (
        <>
      {c && (
        <p style={{ marginTop: 0 }}>
          The decision: chain deadline of <span className="mono">GET /api/orders</span> at the gateway {c.baseDeadlineMs} → {c.newDeadlineMs} ms, and the rate of
          payment-svc {c.baseRate} → {c.newRate} requests/s, computed together. Traffic: a burst of {c.burst} chain requests every {c.burstEveryMs / 1000} s. At {c.newRate}/s a
          burst waits more than a second in payment's queue, which fits the new {c.newDeadlineMs} ms deadline but not the old {c.baseDeadlineMs} ms one.
        </p>
      )}
      <div className="form-grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))" }}>
        <Field label="Compare">
          <select
            value={delivery}
            onChange={(e) => {
              const v = e.target.value as Delivery;
              setDelivery(v);
              setSkew(DELIVERY[v].skewMs);
            }}
          >
            {(Object.keys(DELIVERY) as Delivery[]).map((k) => (
              <option key={k} value={k}>
                {DELIVERY[k].label}
              </option>
            ))}
          </select>
        </Field>
        {d.mode === "naive" && (
          <Field
            label={d.order ? "Simulated network delay to the later sidecar (ms)" : "Simulated network delay, random up to (ms)"}
            hint="Models a slow network or delivery channel in a system without C3. RPE itself adds no delay."
          >
            <input type="number" min={0} max={10000} step={500} value={skew} onChange={(e) => setSkew(Number(e.target.value))} />
          </Field>
        )}
      </div>
      <p className="muted" style={{ marginBottom: 0 }}>
        {d.hint} Each run starts from the baseline and restores it afterwards; you are recorded as the approver.
      </p>

      {data?.busy && runs[0]?.state === "running" && (
        <div className="row" style={{ marginTop: 14 }}>
          <Status tone="blue">Running</Status>
          <span className="muted">{runs[0].stage}</span>
        </div>
      )}

      <div className="section-title">Experiment runs</div>
      {runs.length ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Delivery</th>
                <th className="num">Stale-deadline signals</th>
                <th className="num">Mixed-policy requests</th>
                <th className="num">Without 2xx</th>
                <th className="num">Wait before tightening</th>
                <th>Result</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((r) => (
                <tr key={r.id} className="clickable" onClick={() => setSelected(r.id)} style={r.id === shown?.id ? { background: "var(--primary-soft)" } : undefined}>
                  <td>
                    {label(r)}
                    <div className="muted mono">{r.id}</div>
                  </td>
                  <td className="num">{r.state === "done" ? `${r.expiredOldDeadline} / ${r.during}` : "—"}</td>
                  <td className="num">{r.state === "done" ? r.mixedPolicyRequests : "—"}</td>
                  <td className="num">{r.state === "done" ? r.failed : "—"}</td>
                  <td className="num">{r.state === "done" && r.mode === "two-phase" ? fmtMs(r.gateMs) : "—"}</td>
                  <td>
                    {r.state === "running" ? (
                      <Status tone="blue">Running</Status>
                    ) : r.state === "failed" ? (
                      <Status tone="red">Failed</Status>
                    ) : r.expiredOldDeadline > 0 || r.mixedPolicyRequests > 0 ? (
                      <Status tone="red">Mixed versions</Status>
                    ) : (
                      <Status tone="green">One version per request</Status>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty>No experiment runs yet. Run "With C3" and one "Simulated without C3" to compare them.</Empty>
      )}

      {shown && shown.state === "failed" && <Alert>{shown.error}</Alert>}
      {shown && shown.state === "done" && (
        <>
          <div className="section-title">{label(shown)}</div>
          <RunDetail r={shown} />
        </>
      )}
        </>
      )}
    </Panel>
  );
}
