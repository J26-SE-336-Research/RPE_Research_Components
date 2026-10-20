import { useState } from "react";
import { api, useAction, useData } from "../api";
import { TrailGraph } from "../components/TrailGraph";
import { Waterfall, WaterfallSummary } from "../components/Waterfall";
import { Alert, Drawer, Empty, Kpi, PageHeader, Panel, Status, Tag, httpTone } from "../components/ui";
import { DECISIONS, type Attribution, type TopologyData, type TrailList } from "../types";

const WINDOWS = [
  { sec: 60, label: "Last minute" },
  { sec: 300, label: "Last 5 minutes" },
  { sec: 900, label: "Last 15 minutes" },
];

export function TopologyPage() {
  const [window, setWindow] = useState(300);
  const topo = useData<TopologyData>(`/protocol/topology?window=${window}`, 5000);
  const trails = useData<TrailList>(`/protocol/trails?limit=25&window=${window}`, 5000);
  const open = useAction((rid: string) => api<Attribution>("GET", `/protocol/attribution/${rid}`));
  const t = topo.data?.topology;
  const c = topo.data?.comparison;
  const incompletePct = t && t.trails ? Math.round((100 * t.incomplete) / t.trails) : 0;

  return (
    <>
      <PageHeader
        title="Topology"
        description="The service graph rebuilt from the X-RPE-Trail each request carries: every request, no sampling and no tracing backend."
        actions={
          <select value={window} onChange={(e) => setWindow(Number(e.target.value))} aria-label="Time window">
            {WINDOWS.map((w) => (
              <option key={w.sec} value={w.sec}>
                {w.label}
              </option>
            ))}
          </select>
        }
      />
      <div className="stack">
        <Alert>{topo.error}</Alert>
        {t && (
          <div className="kpis">
            <Kpi label="Requests with a trail" value={t.trails} foot={`registry v${t.registryVersion}`} />
            <Kpi label="Edges found" value={`${c?.matched.length ?? 0} / ${c?.truth.length ?? 0}`} foot={`precision ${c?.precision ?? 0}% · recall ${c?.recall ?? 0}%`} />
            <Kpi label="Incomplete trails" value={`${incompletePct}%`} foot="a hop on the path does not speak RPE" />
            <Kpi label="Trail size" value={`${t.avgBytes} B`} foot={`max ${t.maxBytes} B (limit 1400 B)`} />
          </div>
        )}
        <Panel title="Service graph" subtitle="Rebuilt only from the trails the gateway reported. A dashed node is a hop that does not speak RPE (unknown edge, R-P05).">
          {t && t.nodes.length > 0 ? <TrailGraph t={t} /> : <Empty>No trails in this window yet. Send a request from the Request Inspector or start a load test.</Empty>}
        </Panel>
        {c && t && t.edges.length > 0 && (
          <div className="cols cols-2">
            <Panel title="Edges" flush>
              <table>
                <thead>
                  <tr>
                    <th>Caller → callee</th>
                    <th className="num">Calls</th>
                    <th className="num">p50 / p95</th>
                    <th>Decisions</th>
                  </tr>
                </thead>
                <tbody>
                  {t.edges.map((e) => (
                    <tr key={e.from + e.to}>
                      <td>
                        {e.from} → <strong>{e.to}</strong> {e.unknown && <Tag tone="amber">unknown hop</Tag>}
                      </td>
                      <td className="num">{e.calls}</td>
                      <td className="num">
                        {e.p50Ms} / {e.p95Ms} ms
                      </td>
                      <td>
                        {Object.entries(e.decisions).map(([d, n]) => (
                          <Tag key={d} title={DECISIONS[d]}>
                            {DECISIONS[d] ?? d}: {n}
                          </Tag>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Panel>
            <Panel title="Compared with the declared topology" subtitle="Ground truth: the call graph the demo shop is deployed with.">
              <table>
                <tbody>
                  {c.truth.map((e) => (
                    <tr key={e}>
                      <td>{e}</td>
                      <td>{c.matched.includes(e) ? <Status tone="green">found in trails</Status> : <Status tone="amber">not visible</Status>}</td>
                    </tr>
                  ))}
                  {c.extra.map((e) => (
                    <tr key={e}>
                      <td>{e}</td>
                      <td>
                        <Status tone="red">not declared</Status>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {c.note && <p className="muted" style={{ marginBottom: 0 }}>{c.note}</p>}
            </Panel>
          </div>
        )}
        <Panel title="Recent requests" subtitle="Click a request to see where its deadline went." flush>
          {trails.data && trails.data.trails.length > 0 ? (
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Request id</th>
                  <th>Route</th>
                  <th>Status</th>
                  <th className="num">Hops</th>
                  <th className="num">Took / budget</th>
                  <th>Used most</th>
                  <th className="num">Trail</th>
                </tr>
              </thead>
              <tbody>
                {trails.data.trails.map((r) => (
                  <tr key={r.rid} className="clickable" onClick={() => open.run(r.rid)}>
                    <td className="muted">{new Date(r.at).toLocaleTimeString()}</td>
                    <td className="mono">{r.rid.slice(0, 12)}…</td>
                    <td className="mono">{r.route}</td>
                    <td>
                      <Status tone={httpTone(r.status)}>{r.status}</Status>
                    </td>
                    <td className="num">{r.hops}</td>
                    <td className="num">
                      {r.totalMs} / {r.budgetMs} ms {r.late && <Tag tone="amber">late</Tag>}
                    </td>
                    <td>{r.bottleneck}</td>
                    <td className="num">
                      {r.bytes} B {r.incomplete && <Tag tone="amber">incomplete</Tag>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <Empty>No trails yet.</Empty>
          )}
        </Panel>
      </div>
      {open.result && (
        <Drawer title="Deadline used per hop" subtitle={<span className="mono">{open.result.rid}</span>} onClose={() => open.setResult(undefined)}>
          <WaterfallSummary a={open.result} />
          <Waterfall a={open.result} />
          <div className="section-title">X-RPE-Trail as reported</div>
          <pre className="code">{open.result.trail}</pre>
        </Drawer>
      )}
      <Alert>{open.error}</Alert>
    </>
  );
}
