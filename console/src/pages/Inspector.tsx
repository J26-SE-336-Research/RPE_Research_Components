import { useState } from "react";
import { api, useAction, type ChainResult } from "../api";
import { TraceTable } from "../components/TraceTable";
import { Alert, Button, Empty, Kpi, PageHeader, Panel, Status, httpTone } from "../components/ui";

export function InspectorPage() {
  const [method, setMethod] = useState<"GET" | "POST">("GET");
  const [path, setPath] = useState("/api/orders");
  const [history, setHistory] = useState<{ at: Date; method: string; path: string; r: ChainResult }[]>([]);
  const send = useAction(async () => {
    const r = await api<ChainResult>("POST", "/chain", { method, path });
    setHistory((h) => [{ at: new Date(), method, path, r }, ...h].slice(0, 10));
    return r;
  });
  const r = send.result;
  const res = r?.result ?? {};
  const allPassed = r ? r.hops.every((h) => h.checks.every((c) => c.pass)) : false;

  return (
    <>
      <PageHeader title="Request Inspector" description="Send a request through the gateway and trace the resilience context at every hop." />
      <div className="stack">
        <Panel>
          <form
            className="row"
            onSubmit={(e) => {
              e.preventDefault();
              send.run();
            }}
          >
            <select value={method} onChange={(e) => setMethod(e.target.value as "GET" | "POST")} aria-label="Request method">
              <option>GET</option>
              <option>POST</option>
            </select>
            <span className="muted mono">gateway.rpe-demo</span>
            <input className="grow mono" value={path} onChange={(e) => setPath(e.target.value)} aria-label="Request path" />
            <Button variant="primary" type="submit" busy={send.busy}>
              Send request
            </Button>
          </form>
        </Panel>
        <Alert>{send.error}</Alert>

        {r && !r.error && (
          <div className="kpis">
            <Kpi label="Response" value={<Status tone={httpTone(r.status)}>{r.status}</Status>} />
            <Kpi label="End-to-end latency" value={`${r.ms} ms`} />
            <Kpi label="Outcome" value={res.out ?? "—"} foot={res.rsn && res.rsn !== "none" ? `reason: ${res.rsn}` : undefined} />
            <Kpi label="Retryable" value={res.term === "?1" ? "No" : "Yes"} foot={res.term === "?1" ? "terminal result" : undefined} />
            <Kpi label="Protocol checks" value={allPassed ? "All passed" : "Violations"} foot={`${r.hops.length} hops`} />
          </div>
        )}

        <Panel title="Trace" subtitle={r?.resultRaw ? <span className="mono">X-RPE-Result: {r.resultRaw}</span> : undefined} flush>
          {r ? <TraceTable result={r} /> : <Empty>Send a request to see its trace.</Empty>}
        </Panel>

        {history.length > 1 && (
          <Panel title="Recent requests" flush>
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Path</th>
                  <th>Status</th>
                  <th className="num">Latency</th>
                  <th>Outcome</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h, i) => (
                  <tr key={i}>
                    <td className="muted">{h.at.toLocaleTimeString()}</td>
                    <td className="mono">
                      {h.method} {h.path}
                    </td>
                    <td>
                      <Status tone={httpTone(h.r.status)}>{h.r.status}</Status>
                    </td>
                    <td className="num">{h.r.ms} ms</td>
                    <td>{h.r.result?.out ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Panel>
        )}
      </div>
    </>
  );
}
