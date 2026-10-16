import { useState } from "react";
import { api, useAction, useData } from "../api";
import { Alert, Button, Drawer, Empty, Field, Kpi, KV, PageHeader, Panel, Status, Tag, fmtMs, fmtNum } from "../components/ui";
import { failed, total, type Proxy } from "../types";

function ProxyDrawer({ proxy, onClose, onChanged }: { proxy: Proxy; onClose: () => void; onChanged: () => void }) {
  const [epoch, setEpoch] = useState("");
  const [version, setVersion] = useState(1);
  const stage = useAction(async () => {
    await api("PUT", `/sidecars/${proxy.pod}/policy`, { epoch, version, hash: `${epoch}:${version}`, state: "staged" });
    setEpoch("");
    onChanged();
  });
  const activate = useAction(async (e: string) => {
    await api("POST", `/sidecars/${proxy.pod}/activate`, { epoch: e });
    onChanged();
  });
  const retire = useAction(async (e: string) => {
    await api("DELETE", `/sidecars/${proxy.pod}/policy/${e}`);
    onChanged();
  });
  const s = proxy.stats;
  return (
    <Drawer title={proxy.service} subtitle={proxy.pod} onClose={onClose}>
      <KV
        items={[
          ["Status", proxy.error ? <Status key="x" tone="red">Unreachable</Status> : <Status key="x" tone="green">Healthy</Status>],
          ["Role", proxy.health?.role ?? "—"],
          ["Address", <span key="a" className="mono">{proxy.ip}</span>],
          ["Listeners", <span key="l" className="mono">outbound :15001 · inbound :15006 · admin :15020 · metrics :15090</span>],
        ]}
      />
      <div className="section-title">Traffic</div>
      <KV
        items={[
          ["Inbound", `${fmtNum(total(s.requests.inbound))} requests · ${fmtNum(failed(s.requests.inbound))} failed`],
          ["Outbound", `${fmtNum(total(s.requests.outbound))} requests · ${fmtNum(failed(s.requests.outbound))} failed`],
          ["Inbound latency", `p50 ${fmtMs(s.p50Ms.inbound)} · p95 ${fmtMs(s.p95Ms.inbound)}`],
          ["Outbound latency", `p50 ${fmtMs(s.p50Ms.outbound)} · p95 ${fmtMs(s.p95Ms.outbound)}`],
          ["Proxy overhead", fmtMs(s.overheadAvgMs)],
          ["In flight", s.inflight],
        ]}
      />
      <div className="section-title">Policy epochs</div>
      <Alert>{stage.error ?? activate.error ?? retire.error}</Alert>
      {proxy.policy?.epochs.length ? (
        <table style={{ marginBottom: 12 }}>
          <thead>
            <tr>
              <th>Epoch</th>
              <th>Version</th>
              <th>State</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {proxy.policy.epochs.map((e) => (
              <tr key={e.epoch}>
                <td className="mono">{e.epoch}</td>
                <td>v{e.version}</td>
                <td>{e.state === "active" ? <Tag tone="green">Active</Tag> : <Tag tone="blue">Staged</Tag>}</td>
                <td style={{ textAlign: "right" }}>
                  <div className="row" style={{ justifyContent: "flex-end" }}>
                    {e.state !== "active" && (
                      <Button size="sm" write onClick={() => activate.run(e.epoch)}>
                        Activate
                      </Button>
                    )}
                    <Button size="sm" variant="danger" write disabled={e.state === "active"} onClick={() => retire.run(e.epoch)}>
                      Retire
                    </Button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="muted">No policy epochs loaded.</p>
      )}
      <div className="row">
        <Field label="Epoch">
          <input value={epoch} placeholder="e.g. e1" onChange={(e) => setEpoch(e.target.value)} />
        </Field>
        <Field label="Version">
          <input type="number" min={1} value={version} style={{ width: 80 }} onChange={(e) => setVersion(Number(e.target.value))} />
        </Field>
        <div style={{ alignSelf: "flex-end" }}>
          <Button write onClick={() => stage.run()} disabled={!epoch} busy={stage.busy}>
            Stage
          </Button>
        </div>
      </div>
    </Drawer>
  );
}

export function ProxiesPage() {
  const { data, error, reload } = useData<Proxy[]>("/sidecars", 3000);
  const [selected, setSelected] = useState<string | null>(null);
  const sel = data?.find((p) => p.pod === selected);
  const inbound = data?.reduce((a, p) => a + total(p.stats.requests.inbound), 0) ?? 0;
  const outbound = data?.reduce((a, p) => a + total(p.stats.requests.outbound), 0) ?? 0;
  const overhead = data?.length ? data.reduce((a, p) => a + (p.stats.overheadAvgMs || 0), 0) / data.length : undefined;

  return (
    <>
      <PageHeader title="Proxies" description="RPE sidecar proxies running next to each managed workload." />
      <Alert>{error}</Alert>
      <div className="stack">
        <div className="kpis">
          <Kpi label="Proxies" value={data ? `${data.filter((p) => !p.error).length} healthy` : "—"} foot={data ? `${data.length} total` : undefined} />
          <Kpi label="Inbound requests" value={fmtNum(inbound)} />
          <Kpi label="Outbound requests" value={fmtNum(outbound)} />
          <Kpi label="Avg. proxy overhead" value={fmtMs(overhead)} />
        </div>
        <Panel flush>
          {data?.length ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Workload</th>
                    <th>Status</th>
                    <th>Role</th>
                    <th className="num">Inbound</th>
                    <th className="num">Outbound</th>
                    <th className="num">p95 in</th>
                    <th className="num">p95 out</th>
                    <th className="num">Overhead</th>
                    <th className="num">In flight</th>
                    <th>Active epoch</th>
                  </tr>
                </thead>
                <tbody>
                  {data.map((p) => (
                    <tr key={p.pod} className="clickable" onClick={() => setSelected(p.pod)}>
                      <td>
                        <strong>{p.service}</strong>
                        <div className="muted mono">{p.pod}</div>
                      </td>
                      <td>{p.error ? <Status tone="red">Unreachable</Status> : <Status tone="green">Healthy</Status>}</td>
                      <td>{p.health?.role ?? "—"}</td>
                      <td className="num">{fmtNum(total(p.stats.requests.inbound))}</td>
                      <td className="num">{fmtNum(total(p.stats.requests.outbound))}</td>
                      <td className="num">{fmtMs(p.stats.p95Ms.inbound)}</td>
                      <td className="num">{fmtMs(p.stats.p95Ms.outbound)}</td>
                      <td className="num">{fmtMs(p.stats.overheadAvgMs)}</td>
                      <td className="num">{p.stats.inflight}</td>
                      <td className="mono">{p.health?.activeEpoch || "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <Empty>No proxies found.</Empty>
          )}
        </Panel>
      </div>
      {sel && <ProxyDrawer proxy={sel} onClose={() => setSelected(null)} onChanged={reload} />}
    </>
  );
}
