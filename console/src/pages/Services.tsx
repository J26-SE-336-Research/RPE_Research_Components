import { useEffect, useState } from "react";
import { useData } from "../api";
import { serviceTone } from "../components/ServiceMap";
import { Alert, Drawer, KV, PageHeader, Panel, Status, Tag, fmtMs, fmtNum } from "../components/ui";
import { failed, total, type OverviewData, type Proxy, type ServiceState } from "../types";

function selectedFromHash() {
  return new URLSearchParams(window.location.hash.split("?")[1] ?? "").get("s");
}

export function ServicesPage() {
  const ov = useData<OverviewData>("/overview", 5000);
  const proxies = useData<Proxy[]>("/sidecars", 5000);
  const states = useData<ServiceState[]>("/services", 5000);
  const [selected, setSelected] = useState<string | null>(selectedFromHash());
  useEffect(() => {
    const on = () => setSelected(selectedFromHash());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  const proxyOf = (name: string) => proxies.data?.find((p) => p.service === name);
  const stateOf = (name: string) => states.data?.find((s) => s.name === name);
  const sel = ov.data?.services.find((s) => s.name === selected);
  const close = () => {
    setSelected(null);
    window.location.hash = "#/services";
  };

  return (
    <>
      <PageHeader title="Services" description="Workloads in the demo namespace and the traffic their proxies observe." />
      <Alert>{ov.error ?? proxies.error}</Alert>
      <Panel flush>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Service</th>
                <th>Status</th>
                <th>Proxy</th>
                <th className="num">Requests</th>
                <th className="num">Errors</th>
                <th className="num">p50</th>
                <th className="num">p95</th>
                <th>Policy epoch</th>
              </tr>
            </thead>
            <tbody>
              {ov.data?.services.map((s) => {
                const p = proxyOf(s.name);
                const st = stateOf(s.name);
                const faulted = st && Object.values(st.faults).some((v) => v);
                const tone = serviceTone(s);
                return (
                  <tr key={s.name} className="clickable" onClick={() => (window.location.hash = `#/services?s=${s.name}`)}>
                    <td>
                      <strong>{s.name}</strong>
                      {faulted && (
                        <>
                          {" "}
                          <Tag tone="amber">fault injected</Tag>
                        </>
                      )}
                    </td>
                    <td>
                      <Status tone={s.ready ? "green" : "red"}>{s.ready ? "Running" : "Not ready"}</Status>
                    </td>
                    <td>
                      {!s.rpe ? (
                        <span className="muted">Unmanaged</span>
                      ) : (
                        <Status tone={tone === "green" ? "green" : "amber"}>{s.sidecar ? "Injected" : "Missing"}</Status>
                      )}
                    </td>
                    <td className="num">{p ? fmtNum(total(p.stats.requests.inbound)) : "—"}</td>
                    <td className="num">{p ? fmtNum(failed(p.stats.requests.inbound)) : "—"}</td>
                    <td className="num">{p ? fmtMs(p.stats.p50Ms.inbound) : "—"}</td>
                    <td className="num">{p ? fmtMs(p.stats.p95Ms.inbound) : "—"}</td>
                    <td className="mono">{p?.health?.activeEpoch || "—"}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Panel>

      {sel && (
        <Drawer title={sel.name} subtitle={sel.pod} onClose={close}>
          <KV
            items={[
              ["Status", <Status key="s" tone={sel.ready ? "green" : "red"}>{sel.ready ? "Running" : "Not ready"}</Status>],
              ["Restarts", sel.restarts],
              ["Proxy", sel.rpe ? (sel.sidecar ? "Injected" : "Missing") : "Unmanaged (no RPE proxy)"],
              ["Proxy role", proxyOf(sel.name)?.health?.role ?? "—"],
              ["Pod IP", proxyOf(sel.name)?.ip ?? "—"],
            ]}
          />
          {proxyOf(sel.name) && (
            <>
              <div className="section-title">Traffic</div>
              <KV
                items={[
                  ["Inbound", `${fmtNum(total(proxyOf(sel.name)!.stats.requests.inbound))} (${fmtNum(failed(proxyOf(sel.name)!.stats.requests.inbound))} failed)`],
                  ["Outbound", `${fmtNum(total(proxyOf(sel.name)!.stats.requests.outbound))} (${fmtNum(failed(proxyOf(sel.name)!.stats.requests.outbound))} failed)`],
                  ["Latency p50 / p95", `${fmtMs(proxyOf(sel.name)!.stats.p50Ms.inbound)} / ${fmtMs(proxyOf(sel.name)!.stats.p95Ms.inbound)}`],
                  ["Proxy overhead", fmtMs(proxyOf(sel.name)!.stats.overheadAvgMs)],
                ]}
              />
            </>
          )}
          <div className="section-title">Injected faults</div>
          {stateOf(sel.name) && Object.entries(stateOf(sel.name)!.faults).some(([, v]) => v) ? (
            <KV items={Object.entries(stateOf(sel.name)!.faults).filter(([, v]) => v).map(([k, v]) => [k, String(v)])} />
          ) : (
            <div className="muted">None. Manage faults under Fault Injection.</div>
          )}
          <div className="section-title">Business counters</div>
          {stateOf(sel.name) && Object.keys(stateOf(sel.name)!.counters).length ? (
            <KV items={Object.entries(stateOf(sel.name)!.counters).filter(([k]) => k !== "busy_ms").map(([k, v]) => [k.replace(/_/g, " "), fmtNum(v)])} />
          ) : (
            <div className="muted">No activity recorded.</div>
          )}
        </Drawer>
      )}
    </>
  );
}
