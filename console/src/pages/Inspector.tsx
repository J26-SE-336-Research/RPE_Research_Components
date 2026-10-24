import { useEffect, useState } from "react";
import { api, useAction, type ChainResult } from "../api";
import { TraceTable } from "../components/TraceTable";
import { Waterfall, WaterfallSummary } from "../components/Waterfall";
import { Alert, Button, Empty, Kpi, PageHeader, Panel, Status, httpTone } from "../components/ui";
import type { Attribution } from "../types";

/** The request's trail reaches the protocol service within about a second of the response; poll briefly. */
function useAttribution(rid?: string) {
  const [a, setA] = useState<Attribution | null>();
  useEffect(() => {
    setA(undefined);
    if (!rid) return;
    let alive = true;
    (async () => {
      for (let i = 0; i < 8 && alive; i++) {
        try {
          const got = await api<Attribution>("GET", `/protocol/attribution/${rid}`);
          if (alive) setA(got);
          return;
        } catch {
          await new Promise((r) => setTimeout(r, 500));
        }
      }
      if (alive) setA(null);
    })();
    return () => {
      alive = false;
    };
  }, [rid]);
  return a;
}

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
  const attribution = useAttribution(r?.rid);

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

        {r && !r.error && (
          <Panel title="Deadline used per hop" subtitle="Rebuilt from the request's own X-RPE-Trail (no tracing backend).">
            {attribution ? (
              <>
                <WaterfallSummary a={attribution} />
                <Waterfall a={attribution} />
              </>
            ) : attribution === null ? (
              <Empty>No trail was reported for this request (is the protocol service running?).</Empty>
            ) : (
              <Empty>Waiting for the trail…</Empty>
            )}
          </Panel>
        )}

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
