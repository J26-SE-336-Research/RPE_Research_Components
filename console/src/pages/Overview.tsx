import { useData } from "../api";
import { ServiceMap } from "../components/ServiceMap";
import { Ago, Alert, Empty, Kpi, PageHeader, Panel, Status, fmtMs, fmtNum } from "../components/ui";
import { EVENT_LABEL, eventSubject, failed, total, type OverviewData, type PoliciesData, type Proxy } from "../types";

const PLATFORM: [string, string][] = [
  ["rpe-admission-webhook", "Admission webhook"],
  ["rpe-operator", "Operator and policy API"],
  ["rpe-postgres", "Policy store (PostgreSQL)"],
  ["rpe-redis", "Event bus and cache (Redis)"],
  ["rpe-intelligence", "Intelligence engine"],
  ["rpe-hub", "Admin API"],
];

export function OverviewPage() {
  const ov = useData<OverviewData>("/overview", 5000);
  const proxies = useData<Proxy[]>("/sidecars", 5000);
  const pol = useData<PoliciesData>("/policies", 10000);
  const eff = useData<Record<string, Record<string, string>>>("/effective", 10000);
  const services = ov.data?.services ?? [];
  const healthy = services.filter((s) => s.ready).length;
  const inbound = proxies.data?.reduce((a, p) => a + total(p.stats.requests.inbound), 0) ?? 0;
  const errors = proxies.data?.reduce((a, p) => a + failed(p.stats.requests.inbound), 0) ?? 0;
  const gateway = proxies.data?.find((p) => p.service === "gateway");

  return (
    <>
      <PageHeader title="Overview" description="Health and activity across the mesh." />
      <Alert>{ov.error ?? ov.data?.error}</Alert>
      <div className="stack">
        <div className="kpis">
          <Kpi label="Services healthy" value={ov.data ? `${healthy} / ${services.length}` : "—"} />
          <Kpi label="Proxy coverage" value={ov.data ? `${ov.data.coverage.injected} / ${ov.data.coverage.eligible}` : "—"} foot="managed workloads" />
          <Kpi label="Requests handled" value={fmtNum(inbound)} foot="inbound, all proxies" />
          <Kpi label="Error rate" value={inbound ? `${((errors / inbound) * 100).toFixed(1)}%` : "—"} />
          <Kpi label="Edge latency p95" value={fmtMs(gateway?.stats.p95Ms.inbound)} foot="gateway" />
          <Kpi label="Services with policy" value={eff.data ? Object.keys(eff.data).length : "—"} foot={pol.data ? `${pol.data.policies.length} versions stored` : undefined} />
        </div>

        <Panel title="Service map" subtitle="demo namespace">
          {ov.data && <ServiceMap services={ov.data.services} edges={ov.data.edges} onSelect={(n) => (window.location.hash = `#/services?s=${n}`)} />}
        </Panel>

        <div className="cols cols-2">
          <Panel title="Control plane" flush>
            <table>
              <tbody>
                {PLATFORM.map(([id, label]) => (
                  <tr key={id}>
                    <td>{label}</td>
                    <td style={{ textAlign: "right" }}>
                      {ov.data?.platform[id] === undefined ? (
                        <Status tone="neutral">Unknown</Status>
                      ) : ov.data.platform[id] ? (
                        <Status tone="green">Running</Status>
                      ) : (
                        <Status tone="red">Unavailable</Status>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Panel>
          <Panel title="Recent policy activity" flush>
            {pol.data?.events.length ? (
              <table>
                <tbody>
                  {pol.data.events.slice(0, 4).map((e) => (
                    <tr key={e.id}>
                      <td>
                        {EVENT_LABEL[e.type] ?? e.type}
                        <div className="muted mono" style={{ fontSize: 13.5 }}>{eventSubject(e)}</div>
                      </td>
                      <td className="muted nowrap" style={{ textAlign: "right" }}>
                        <Ago at={e.at} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <Empty>No policy changes yet.</Empty>
            )}
          </Panel>
        </div>
      </div>
    </>
  );
}
