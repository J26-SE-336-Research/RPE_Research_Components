import { useEffect, useRef, useState } from "react";
import { api, useAction, useData } from "../api";
import { Alert, Button, Empty, Field, Kpi, KV, PageHeader, Panel, Status, Tag, fmtNum } from "../components/ui";
import type { BackpressureConfig, BackpressureMode, BackpressurePeer, BackpressureRuns, Proxy } from "../types";

// The proxy's signal levels (BACKPRESSURE_SIGNAL_THRESHOLDS_PERCENT / BACKPRESSURE_SIGNAL_CLEAR_PERCENT defaults).
const LEVELS = [50, 65, 80, 95];
const CLEAR = 30;
const HISTORY_S = 120;
const COLORS = ["var(--primary)", "var(--red)", "var(--amber)", "var(--green)", "#7a5af8", "#0e9384", "#c11574", "#667085"];

type Sample = { t: number; util: Record<string, number>; limit: Record<string, number> };

/** The service a peer key (host:port, or an IP:port) refers to. */
function peerService(peer: string, proxies: Proxy[]) {
  const host = peer.slice(0, peer.lastIndexOf(":") > 0 ? peer.lastIndexOf(":") : undefined);
  const byIp = proxies.find((p) => p.ip === host);
  return byIp ? byIp.service : host.split(".")[0];
}

function QueueBar({ depth, capacity, util }: { depth: number; capacity: number; util: number }) {
  const W = 260, H = 18;
  const color = util >= LEVELS[0] ? (util >= LEVELS[2] ? "var(--red)" : "var(--amber)") : "var(--green)";
  return (
    <svg viewBox={`0 0 ${W} ${H + 12}`} width={W} height={H + 12} role="img" aria-label={`queue ${depth} of ${capacity}`}>
      <rect x={0} y={0} width={W} height={H} rx={4} fill="var(--surface-2)" stroke="var(--border)" />
      <rect x={0} y={0} width={(W * Math.min(100, util)) / 100} height={H} rx={4} fill={color} opacity={0.85} />
      <line x1={(W * CLEAR) / 100} x2={(W * CLEAR) / 100} y1={0} y2={H} stroke="var(--green)" strokeDasharray="2 2" />
      {LEVELS.map((l) => (
        <g key={l}>
          <line x1={(W * l) / 100} x2={(W * l) / 100} y1={0} y2={H} stroke="var(--text-2)" strokeOpacity={0.5} />
          <text x={(W * l) / 100} y={H + 11} fontSize="9.5" textAnchor="middle" fill="var(--muted)">
            {l}
          </text>
        </g>
      ))}
      <text x={(W * CLEAR) / 100} y={H + 11} fontSize="9.5" textAnchor="middle" fill="var(--green)">
        {CLEAR}
      </text>
    </svg>
  );
}

/** Lines over the last HISTORY_S seconds, with optional horizontal guide lines. */
function Chart(props: { title: string; samples: Sample[]; pick: (s: Sample) => Record<string, number>; max: number; guides?: { v: number; color: string }[]; unit: string }) {
  const W = 760, H = 210, L = 40, B = 24, T = 10, R = 140;
  const now = props.samples.length ? props.samples[props.samples.length - 1].t : Date.now();
  const x = (t: number) => L + (1 - (now - t) / (HISTORY_S * 1000)) * (W - L - R);
  const y = (v: number) => T + (1 - Math.min(v, props.max) / props.max) * (H - T - B);
  const names = Array.from(new Set(props.samples.flatMap((s) => Object.keys(props.pick(s))))).sort();
  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: "100%", height: "auto", display: "block" }} role="img" aria-label={props.title}>
      <rect x={L} y={T} width={W - L - R} height={H - T - B} fill="var(--surface-2)" stroke="var(--border)" />
      {[0, 30, 60, 90, 120].map((s) => (
        <text key={s} x={x(now - s * 1000)} y={H - 7} fontSize="11" textAnchor="middle" fill="var(--muted)">
          {s === 0 ? "now" : `-${s}s`}
        </text>
      ))}
      {[0, props.max / 2, props.max].map((v) => (
        <text key={v} x={L - 5} y={y(v) + 4} fontSize="11" textAnchor="end" fill="var(--muted)">
          {Math.round(v)}
        </text>
      ))}
      {props.guides?.map((g) => (
        <line key={g.v} x1={L} x2={W - R} y1={y(g.v)} y2={y(g.v)} stroke={g.color} strokeDasharray="4 4" strokeOpacity={0.7} />
      ))}
      {names.map((name, i) => {
        const pts = props.samples.filter((s) => props.pick(s)[name] !== undefined).map((s) => `${x(s.t).toFixed(1)},${y(props.pick(s)[name]).toFixed(1)}`);
        const color = COLORS[i % COLORS.length];
        return (
          <g key={name}>
            {pts.length > 1 && <polyline points={pts.join(" ")} fill="none" stroke={color} strokeWidth={2} />}
            <rect x={W - R + 12} y={T + i * 18} width={10} height={10} fill={color} />
            <text x={W - R + 27} y={T + i * 18 + 9} fontSize="11.5" fill="var(--text-2)">
              {name}
            </text>
          </g>
        );
      })}
      <text x={12} y={T + (H - T - B) / 2} fontSize="11" fill="var(--muted)" transform={`rotate(-90 12 ${T + (H - T - B) / 2})`} textAnchor="middle">
        {props.unit}
      </text>
    </svg>
  );
}

function breakerTag(state: BackpressurePeer["breaker"]) {
  if (state === "open") return <Tag tone="red">open</Tag>;
  if (state === "half-open") return <Tag tone="amber">half-open (probe)</Tag>;
  return <Tag tone="green">closed</Tag>;
}

function Experiment() {
  const runs = useData<BackpressureRuns>("/demos/backpressure", 1000);
  const [cfg, setCfg] = useState<BackpressureConfig | undefined>();
  useEffect(() => {
    if (!cfg && runs.data) setCfg(runs.data.defaults);
  }, [cfg, runs.data]);
  const start = useAction(async () => {
    await api("POST", "/demos/backpressure", cfg);
    await runs.reload();
  });
  const run = runs.data?.runs[0];
  const set = (k: keyof BackpressureConfig) => (e: React.ChangeEvent<HTMLInputElement>) => cfg && setCfg({ ...cfg, [k]: Number(e.target.value) });
  return (
    <Panel
      title="Experiment: overload payment-svc"
      subtitle="Lowers payment-svc's rate with a normal C3 rate-limit policy, sends many chain requests at once, then delivers the previous rate again. The proxies' own backpressure handling does the rest."
    >
      <Alert>{runs.error ?? start.error}</Alert>
      {cfg && (
        <div className="row" style={{ alignItems: "flex-end", marginBottom: 12 }}>
          <Field label="Backpressure mode">
            <select value={cfg.mode} onChange={(e) => setCfg({ ...cfg, mode: e.target.value as BackpressureMode })}>
              <option value="full">full: signals + adaptive concurrency</option>
              <option value="basic">basic: leaky bucket + breaker only</option>
            </select>
          </Field>
          <Field label="Low rate (req/s)">
            <input type="number" min={1} value={cfg.lowRate} style={{ width: 100 }} onChange={set("lowRate")} />
          </Field>
          <Field label="Requests at once">
            <input type="number" min={1} max={600} value={cfg.concurrency} style={{ width: 100 }} onChange={set("concurrency")} />
          </Field>
          <Field label="Load (s)">
            <input type="number" min={5} max={120} value={cfg.loadSeconds} style={{ width: 90 }} onChange={set("loadSeconds")} />
          </Field>
          <Field label="Watch recovery (s)">
            <input type="number" min={0} max={120} value={cfg.recoverySeconds} style={{ width: 90 }} onChange={set("recoverySeconds")} />
          </Field>
          <Button variant="primary" write onClick={() => start.run()} busy={start.busy} disabled={runs.data?.busy}>
            {runs.data?.busy ? "Running…" : "Run experiment"}
          </Button>
        </div>
      )}
      {run ? (
        <KV
          items={[
            ["Run", <span key="r" className="mono">{run.id} by {run.requestedBy}</span>],
            ["State", <Status key="s" tone={run.state === "failed" ? "red" : run.state === "running" ? "blue" : "green"}>{run.state}</Status>],
            ["Mode", run.config.mode === "basic" ? "basic (leaky bucket + breaker only)" : "full (signals + adaptive concurrency)"],
            ["Stage", run.error ? `${run.stage}: ${run.error}` : run.stage],
            ["Rate", `${run.baseRate} → ${run.config.lowRate} → ${run.baseRate} requests/s`],
            ["Chain requests", `${fmtNum(run.sent)} sent · ${Object.entries(run.statuses).map(([c, n]) => `${c === "0" ? "no response" : c}: ${fmtNum(n)}`).join(" · ") || "—"} · ${fmtNum(run.cancelled)} with the cancel signal`],
          ]}
        />
      ) : (
        <p className="muted">
          What to watch: payment-svc's queue passes 50 %, its sidecar signals order-svc's sidecar, order-svc's limit for payment-svc drops; a full queue
          answers 503 and repeated failures open the breaker. After the restore the queue drains, a clear signal is sent below 30 % and the limit grows back.
        </p>
      )}
    </Panel>
  );
}

/** The finished runs side by side: the same load in "basic" and "full" mode shows what signals and adaptive concurrency add. */
function Comparison() {
  const { data } = useData<BackpressureRuns>("/demos/backpressure", 5000);
  const done = (data?.runs ?? []).filter((r) => r.results && r.loadUntil);
  const pct = (n: number, total: number) => (total ? `${((n / total) * 100).toFixed(1)} %` : "—");
  return (
    <Panel
      title="Comparison: basic vs full"
      subtitle="Run the experiment once in each mode with the same settings. After the load period no new request is sent and every request already sent is waited for, so each one counts as OK or failed (Unfinished: still running after 5 more minutes). After a failure a worker waits 200 ms before its next request, like a client honouring Retry-After."
      flush
    >
      {done.length ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Run</th>
                <th>Mode</th>
                <th>Load</th>
                <th className="num">Chain OK</th>
                <th className="num">Chain failed</th>
                <th className="num">Unfinished</th>
                <th className="num">503 at payment (queue full)</th>
                <th className="num">Failed fast (breaker / outbound queue)</th>
                <th className="num">Peak payment queue</th>
                <th className="num">Peak open breakers</th>
                <th className="num">Lowest limit order → payment</th>
                <th className="num">Signals sent</th>
                <th className="num">p50 / p95</th>
              </tr>
            </thead>
            <tbody>
              {done.map((r) => {
                const x = r.results!;
                const total = x.chainOk + x.chainFailed;
                return (
                  <tr key={r.id}>
                    <td className="mono">{r.id}</td>
                    <td>{r.config.mode === "basic" ? <Tag tone="amber">basic</Tag> : <Tag tone="green">full</Tag>}</td>
                    <td className="muted">
                      {r.config.concurrency} at once · {r.config.lowRate}/s · {r.config.loadSeconds} s
                    </td>
                    <td className="num">{fmtNum(x.chainOk)}</td>
                    <td className="num">
                      <strong style={{ color: x.chainFailed ? "var(--red)" : undefined }}>{fmtNum(x.chainFailed)}</strong>{" "}
                      <span className="muted">({pct(x.chainFailed, total)})</span>
                    </td>
                    <td className="num">{fmtNum(x.unfinished ?? 0)}</td>
                    <td className="num">{fmtNum(x.paymentRejected)}</td>
                    <td className="num">{fmtNum(x.outboundRejected)}</td>
                    <td className="num">{Math.round(x.peakPaymentQueue)} %</td>
                    <td className="num">{fmtNum(x.peakOpenBreakers)}</td>
                    <td className="num">{x.minLimitToPayment < 0 ? "—" : fmtNum(x.minLimitToPayment)}</td>
                    <td className="num">{fmtNum(x.signalsSent)}</td>
                    <td className="num">
                      {fmtNum(x.p50Ms)} / {fmtNum(x.p95Ms)} ms
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty>No finished runs yet. Run the experiment in basic mode, then in full mode with the same settings.</Empty>
      )}
    </Panel>
  );
}

export function BackpressurePage() {
  const { data, error } = useData<Proxy[]>("/sidecars", 1000);
  const history = useRef<Sample[]>([]);
  const [, redraw] = useState(0);
  useEffect(() => {
    if (!data) return;
    const s: Sample = { t: Date.now(), util: {}, limit: {} };
    for (const p of data) {
      const bp = p.stats.backpressure;
      if (!bp) continue;
      s.util[p.service] = bp.queueUtilizationPercent;
      for (const peer of bp.peers ?? []) s.limit[`${p.service} → ${peerService(peer.peer, data)}`] = peer.concurrencyLimit;
    }
    history.current = [...history.current.filter((x) => s.t - x.t <= HISTORY_S * 1000), s];
    redraw((n) => n + 1);
  }, [data]);

  const proxies = (data ?? []).filter((p) => p.stats.backpressure);
  const bps = proxies.map((p) => p.stats.backpressure!);
  const sum = (f: (b: NonNullable<Proxy["stats"]["backpressure"]>) => number) => bps.reduce((a, b) => a + f(b), 0);
  const peers = proxies.flatMap((p) => (p.stats.backpressure!.peers ?? []).map((peer) => ({ caller: p.service, to: peerService(peer.peer, data ?? []), peer })));
  // only services whose queue was ever used, so the chart stays readable
  const used = new Set(history.current.flatMap((s) => Object.entries(s.util).filter(([, v]) => v > 0).map(([k]) => k)));
  const limits = history.current.flatMap((s) => Object.values(s.limit));

  return (
    <>
      <PageHeader
        title="Backpressure"
        description="The RPE proxies' overload handling: each service's inbound leaky bucket, the pressure signals between sidecars, the callers' adaptive concurrency per destination and the circuit breakers."
      />
      <Alert>{error}</Alert>
      <div className="stack">
        <div className="kpis">
          <Kpi label="Services under pressure" value={data ? bps.filter((b) => b.pressureActive).length : "—"} foot={`of ${bps.length} proxies`} />
          <Kpi label="Queue full (503)" value={data ? fmtNum(sum((b) => b.inboundRejected)) : "—"} foot="inbound requests shed" />
          <Kpi label="Pressure signals sent" value={data ? fmtNum(sum((b) => b.notificationsSent)) : "—"} foot={`${fmtNum(sum((b) => b.notificationsFailed))} failed`} />
          <Kpi label="Signals received" value={data ? fmtNum(sum((b) => b.signalsReceived)) : "—"} foot="by callers (signal or header)" />
          <Kpi label="Open breakers" value={data ? fmtNum(sum((b) => b.openBreakers)) : "—"} foot={`${fmtNum(sum((b) => b.outboundRejected))} calls failed fast`} />
        </div>

        <Experiment />

        <Comparison />

        <Panel title="Inbound leaky bucket" subtitle={`Queue fill per service. Signals are sent at ${LEVELS.join(" / ")} % and cleared at ${CLEAR} % or less.`} flush>
          {proxies.length ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Service</th>
                    <th>Queue</th>
                    <th className="num">Waiting</th>
                    <th className="num">Release rate</th>
                    <th>Pressure</th>
                    <th className="num">503 (queue full)</th>
                    <th className="num">Signals sent</th>
                  </tr>
                </thead>
                <tbody>
                  {proxies.map((p) => {
                    const b = p.stats.backpressure!;
                    return (
                      <tr key={p.pod}>
                        <td>
                          <strong>{p.service}</strong>
                        </td>
                        <td>
                          <QueueBar depth={b.queueDepth} capacity={b.queueCapacity} util={b.queueUtilizationPercent} />
                        </td>
                        <td className="num">
                          {fmtNum(b.queueDepth)} / {fmtNum(b.queueCapacity)} ({Math.round(b.queueUtilizationPercent)} %)
                        </td>
                        <td className="num">{b.inboundRatePerSec} /s</td>
                        <td>{b.pressureActive ? <Tag tone="red">signalling</Tag> : <Tag tone="green">clear</Tag>}</td>
                        <td className="num">{fmtNum(b.inboundRejected)}</td>
                        <td className="num">
                          {fmtNum(b.notificationsSent)}
                          {b.notificationsFailed ? <span className="muted"> ({fmtNum(b.notificationsFailed)} failed)</span> : null}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <Empty>{data ? "No proxy reports backpressure data (the sidecars may run an older version)." : "Loading…"}</Empty>
          )}
        </Panel>

        <Panel
          title="Callers: adaptive concurrency and circuit breaker per destination"
          subtitle="A caller's sidecar cuts its concurrency to a pressured destination to healthy limit × (100 − utilisation) / 100 (minimum 1) and recovers it step by step. The breaker opens after 10 failures in a row, for 5 s, then lets one probe through."
          flush
        >
          {peers.length ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Caller → destination</th>
                    <th className="num">Limit / healthy</th>
                    <th className="num">In flight</th>
                    <th className="num">Waiting</th>
                    <th>Reported pressure</th>
                    <th>Breaker</th>
                  </tr>
                </thead>
                <tbody>
                  {peers.map(({ caller, to, peer }) => (
                    <tr key={caller + peer.peer}>
                      <td>
                        <strong>{caller}</strong> → {to} <span className="muted mono">{peer.peer}</span>
                      </td>
                      <td className="num">
                        {peer.concurrencyLimit < peer.healthyLimit ? <strong style={{ color: "var(--amber)" }}>{fmtNum(peer.concurrencyLimit)}</strong> : fmtNum(peer.concurrencyLimit)} /{" "}
                        {fmtNum(peer.healthyLimit)}
                      </td>
                      <td className="num">{fmtNum(peer.inFlight)}</td>
                      <td className="num">{fmtNum(peer.queued)}</td>
                      <td>{peer.pressureActive ? <Tag tone="amber">{peer.reportedUtilizationPercent} %</Tag> : <span className="muted">none</span>}</td>
                      <td>{breakerTag(peer.breaker)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <Empty>{data ? "No outbound destinations yet (or the sidecars run an older version without per-destination metrics)." : "Loading…"}</Empty>
          )}
        </Panel>

        <div className="cols cols-2">
          <Panel title="Queue fill (last 2 minutes)" subtitle="Per service, in % of its queue capacity.">
            <Chart
              title="Queue fill"
              samples={history.current.map((s) => ({ ...s, util: Object.fromEntries(Object.entries(s.util).filter(([k]) => used.has(k))) }))}
              pick={(s) => s.util}
              max={100}
              unit="queue fill (%)"
              guides={[...LEVELS.map((v) => ({ v, color: "var(--red)" })), { v: CLEAR, color: "var(--green)" }]}
            />
          </Panel>
          <Panel title="Concurrency limits (last 2 minutes)" subtitle="Per caller → destination: drops on a pressure signal, grows back afterwards.">
            <Chart title="Concurrency limits" samples={history.current} pick={(s) => s.limit} max={Math.max(32, ...limits)} unit="limit (calls)" />
          </Panel>
        </div>
      </div>
    </>
  );
}
