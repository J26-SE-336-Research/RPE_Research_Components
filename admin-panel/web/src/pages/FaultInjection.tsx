import { useState } from "react";
import { api, useAction, useData, type ChainResult } from "../api";
import { TraceTable } from "../components/TraceTable";
import { Alert, Button, Empty, Field, Kpi, PageHeader, Panel, Status, Tabs, Tag, httpTone } from "../components/ui";
import type { Faults, ServiceState } from "../types";

const KNOBS: { key: keyof Faults; label: string; step: number; max?: number }[] = [
  { key: "latencyMs", label: "Latency (ms)", step: 50 },
  { key: "jitterMs", label: "Jitter (ms)", step: 50 },
  { key: "errorRate", label: "Error rate", step: 0.05, max: 1 },
  { key: "cpuBurnMs", label: "CPU burn (ms)", step: 50 },
  { key: "clockOffsetMs", label: "Clock offset (ms)", step: 10 },
];

function describe(f: Faults) {
  const parts: string[] = [];
  if (f.latencyMs) parts.push(`+${f.latencyMs} ms`);
  if (f.jitterMs) parts.push(`±${f.jitterMs} ms jitter`);
  if (f.errorRate) parts.push(`${Math.round(f.errorRate * 100)}% errors`);
  if (f.cpuBurnMs) parts.push(`${f.cpuBurnMs} ms CPU`);
  if (f.clockOffsetMs) parts.push(`clock ${f.clockOffsetMs > 0 ? "+" : ""}${f.clockOffsetMs} ms`);
  return parts.join(", ");
}

function FaultsTab() {
  const { data, error, reload } = useData<ServiceState[]>("/services", 5000);
  const [service, setService] = useState("payment-svc");
  const [draft, setDraft] = useState<Faults>({});
  const apply = useAction(async () => {
    await api("POST", `/services/${service}/faults`, draft);
    await reload();
  });
  const clear = useAction(async (name: string) => {
    await api("POST", `/services/${name}/reset`);
    await reload();
  });
  const clearAll = useAction(async () => {
    await api("POST", "/services/reset-all");
    await reload();
  });
  return (
    <div className="stack">
      <Alert>{error ?? apply.error ?? clear.error ?? clearAll.error}</Alert>
      <Panel title="Inject a fault">
        <div className="row" style={{ alignItems: "flex-end" }}>
          <Field label="Service">
            <select value={service} onChange={(e) => setService(e.target.value)}>
              {data?.map((s) => (
                <option key={s.name}>{s.name}</option>
              ))}
            </select>
          </Field>
          {KNOBS.map((k) => (
            <Field key={k.key} label={k.label}>
              <input
                type="number"
                min={k.key === "clockOffsetMs" ? undefined : 0}
                max={k.max}
                step={k.step}
                style={{ width: 120 }}
                value={draft[k.key] ?? ""}
                placeholder="0"
                onChange={(e) => setDraft({ ...draft, [k.key]: e.target.value === "" ? undefined : Number(e.target.value) })}
              />
            </Field>
          ))}
          <Button variant="primary" write onClick={() => apply.run()} busy={apply.busy}>
            Apply
          </Button>
        </div>
      </Panel>
      <Panel
        title="Active faults"
        flush
        actions={
          <Button size="sm" write onClick={() => clearAll.run()} busy={clearAll.busy}>
            Clear all
          </Button>
        }
      >
        <table>
          <thead>
            <tr>
              <th>Service</th>
              <th>Fault</th>
              <th>Counters</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data?.map((s) => {
              const active = describe(s.faults);
              return (
                <tr key={s.name}>
                  <td>
                    <strong>{s.name}</strong>
                  </td>
                  <td>{s.error ? <Status tone="red">Unreachable</Status> : active ? <Tag tone="amber">{active}</Tag> : <span className="muted">None</span>}</td>
                  <td className="muted" style={{ fontSize: 14 }}>
                    {Object.entries(s.counters)
                      .filter(([k]) => k !== "busy_ms")
                      .map(([k, v]) => `${k.replace(/_/g, " ")}: ${v}`)
                      .join(" · ") || "—"}
                  </td>
                  <td style={{ textAlign: "right" }}>
                    {active && (
                      <Button size="sm" write onClick={() => clear.run(s.name)}>
                        Clear
                      </Button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Panel>
    </div>
  );
}

type LoadResult = { count: number; durationMs: number; statuses: Record<string, number>; terminal: number; p50Ms: number; p95Ms: number; p99Ms: number; maxMs: number };

function LoadTab() {
  const [count, setCount] = useState(100);
  const [concurrency, setConcurrency] = useState(10);
  const run = useAction(() => api<LoadResult>("POST", "/load", { count, concurrency }));
  const r = run.result;
  const ok = r?.statuses["200"] ?? 0;
  return (
    <div className="stack">
      <Panel title="Load test" subtitle="GET /api/orders through the gateway.">
        <div className="row" style={{ alignItems: "flex-end" }}>
          <Field label="Requests">
            <input type="number" min={1} max={500} value={count} onChange={(e) => setCount(Number(e.target.value))} />
          </Field>
          <Field label="Concurrency">
            <input type="number" min={1} max={25} value={concurrency} onChange={(e) => setConcurrency(Number(e.target.value))} />
          </Field>
          <Button variant="primary" write onClick={() => run.run()} busy={run.busy}>
            Start
          </Button>
        </div>
        <Alert>{run.error}</Alert>
      </Panel>
      {r ? (
        <>
          <div className="kpis">
            <Kpi label="Success rate" value={`${((ok / r.count) * 100).toFixed(1)}%`} foot={`${ok} of ${r.count}`} />
            <Kpi label="Throughput" value={`${((r.count / r.durationMs) * 1000).toFixed(1)} req/s`} />
            <Kpi label="p50 / p95" value={`${r.p50Ms} / ${r.p95Ms} ms`} />
            <Kpi label="p99 / max" value={`${r.p99Ms} / ${r.maxMs} ms`} />
            <Kpi label="Non-retryable failures" value={r.terminal} />
          </div>
          <Panel title="Responses" flush>
            <table>
              <tbody>
                {Object.entries(r.statuses).map(([code, n]) => (
                  <tr key={code}>
                    <td>
                      <Status tone={httpTone(Number(code))}>{code === "0" ? "Connection error" : code}</Status>
                    </td>
                    <td className="num">{n}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Panel>
        </>
      ) : (
        <Panel>
          <Empty>No load test has run yet.</Empty>
        </Panel>
      )}
    </div>
  );
}

type DeadlineRun = { latencyMs: number; chain: ChainResult; payment: Record<string, number>; inventory: Record<string, number> };
type FanoutRun = { failing: string; chain: ChainResult };

function ExperimentsTab() {
  const [latency, setLatency] = useState(1500);
  const slow = useAction(() => api<DeadlineRun>("POST", "/demos/deadline", { latencyMs: latency }));
  const [failing, setFailing] = useState("loyalty-svc");
  const partial = useAction(() => api<FanoutRun>("POST", "/demos/fanout", { failing }));
  const d = slow.result;
  const p = partial.result;
  const order = p?.chain.hops.find((h) => h.service === "order-svc");
  return (
    <div className="stack">
      <Panel
        title="Slow dependency"
        subtitle="Delays payment-svc beyond the route deadline (1000 ms), sends one request, then removes the fault."
        actions={
          <div className="row" style={{ alignItems: "flex-end" }}>
            <Field label="Payment delay (ms)">
              <input type="number" min={0} step={100} value={latency} style={{ width: 120 }} onChange={(e) => setLatency(Number(e.target.value))} />
            </Field>
            <Button variant="primary" write onClick={() => slow.run()} busy={slow.busy}>
              Run experiment
            </Button>
          </div>
        }
      >
        <Alert>{slow.error}</Alert>
        {d ? (
          <div className="kpis">
            <Kpi label="Client response" value={<Status tone={httpTone(d.chain.status)}>{d.chain.status}</Status>} foot={`after ${d.chain.ms} ms`} />
            <Kpi label="Retryable" value={d.chain.result?.term === "?1" ? "No" : "Yes"} />
            <Kpi label="Payments charged" value={d.payment.cards_charged ?? 0} />
            <Kpi label="Payment work abandoned" value={d.payment.work_abandoned ?? 0} />
            <Kpi label="Stock reserved" value={d.inventory.stock_reserved ?? 0} foot="committed before the deadline" />
          </div>
        ) : (
          <Empty>Not run yet.</Empty>
        )}
      </Panel>
      {d && (
        <Panel title="Trace: slow dependency" flush>
          <TraceTable result={d.chain} />
        </Panel>
      )}

      <Panel
        title="Partial failure"
        subtitle="Fails one downstream of order-svc and shows how the fan-out policy settles the request."
        actions={
          <div className="row" style={{ alignItems: "flex-end" }}>
            <Field label="Failing service">
              <select value={failing} onChange={(e) => setFailing(e.target.value)}>
                <option value="loyalty-svc">loyalty-svc (optional)</option>
                <option value="payment-svc">payment-svc (required)</option>
                <option value="inventory-svc">inventory-svc (required)</option>
              </select>
            </Field>
            <Button variant="primary" write onClick={() => partial.run()} busy={partial.busy}>
              Run experiment
            </Button>
          </div>
        }
      >
        <Alert>{partial.error}</Alert>
        {p ? (
          <div className="kpis">
            <Kpi label="Client response" value={<Status tone={httpTone(p.chain.status)}>{p.chain.status}</Status>} />
            <Kpi label="order-svc outcome" value={order?.outcome ?? "—"} />
            <Kpi label="Failing dependency" value={p.failing} />
          </div>
        ) : (
          <Empty>Not run yet.</Empty>
        )}
      </Panel>
      {p && (
        <Panel title="Trace: partial failure" flush>
          <TraceTable result={p.chain} />
        </Panel>
      )}
    </div>
  );
}

export function FaultInjectionPage() {
  const [tab, setTab] = useState<"faults" | "load" | "experiments">("faults");
  return (
    <>
      <PageHeader title="Fault Injection" description="Inject failures, generate load and run resilience experiments against the demo application." />
      <Tabs
        tabs={[
          { id: "faults", label: "Faults" },
          { id: "load", label: "Load test" },
          { id: "experiments", label: "Experiments" },
        ]}
        active={tab}
        onChange={setTab}
      />
      {tab === "faults" && <FaultsTab />}
      {tab === "load" && <LoadTab />}
      {tab === "experiments" && <ExperimentsTab />}
    </>
  );
}
