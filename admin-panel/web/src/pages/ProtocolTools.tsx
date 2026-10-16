import { useState } from "react";
import { api, useAction } from "../api";
import { Alert, Button, Field, KV, PageHeader, Panel, Status, Tabs, Tag } from "../components/ui";

type ProtoErr = { code: string; rule?: string; detail: string };
type Decoded = { valid: boolean; fields?: Record<string, string>; unknown?: Record<string, string>; canonical?: string; error?: ProtoErr };
type Transition = { valid?: boolean; violations?: ProtoErr[]; error?: string };
type Split = { grants: number[]; sum: number; conserved: boolean };

const EXAMPLE =
  'v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=287, bud=300, rb=3, cs=none, pe="e42", rt="POST /api/orders"';
const EXAMPLE_CHILD =
  'v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="1111111111111111", pid="00f067aa0ba902b7", ttl=250, bud=300, rb=1, cs=none, pe="e42", rt="POST /api/orders"';

const LABELS: Record<string, string> = {
  v: "Version",
  rid: "Request ID",
  iid: "Invocation ID",
  pid: "Parent invocation",
  ttl: "Deadline remaining (ms)",
  bud: "Original budget (ms)",
  rb: "Retry budget",
  cs: "Cancellation",
  pe: "Policy epoch",
  rt: "Route",
};

function Decode() {
  const [value, setValue] = useState(EXAMPLE);
  const run = useAction(() => api<Decoded>("POST", "/protocol/validate", { header: value }));
  const r = run.result;
  return (
    <Panel title="Decode X-RPE-Context" subtitle="Parses and validates a header value with the reference library.">
      <textarea value={value} onChange={(e) => setValue(e.target.value)} aria-label="Header value" />
      <div className="row" style={{ marginTop: 10 }}>
        <Button variant="primary" onClick={() => run.run()} busy={run.busy}>
          Decode
        </Button>
        <Button variant="ghost" onClick={() => setValue(EXAMPLE)}>
          Reset example
        </Button>
      </div>
      <Alert>{run.error}</Alert>
      {r && (
        <div style={{ marginTop: 16 }}>
          {r.valid ? (
            <>
              <Status tone="green">Valid</Status>
              <div style={{ height: 12 }} />
              <KV items={Object.entries(r.fields ?? {}).map(([k, v]) => [LABELS[k] ?? k, <span key={k} className="mono">{v || "—"}</span>])} />
              {r.unknown && (
                <p className="muted" style={{ marginBottom: 0 }}>
                  Unrecognised members are preserved and forwarded: <span className="mono">{Object.keys(r.unknown).join(", ")}</span>
                </p>
              )}
            </>
          ) : (
            <>
              <div className="row">
                <Status tone="red">Invalid</Status>
                {r.error?.rule && <Tag tone="red">{r.error.rule}</Tag>}
                <Tag>{r.error?.code}</Tag>
              </div>
              <p style={{ marginBottom: 0 }}>{r.error?.detail}</p>
            </>
          )}
        </div>
      )}
    </Panel>
  );
}

function TransitionCheck() {
  const [parent, setParent] = useState(EXAMPLE);
  const [child, setChild] = useState(EXAMPLE_CHILD);
  const [elapsed, setElapsed] = useState(0);
  const run = useAction(() => api<Transition>("POST", "/protocol/transition", { parent, child, elapsedMs: elapsed }));
  const r = run.result;
  return (
    <Panel title="Validate a hop transition" subtitle="Checks that an outgoing context is a legal successor of the incoming one.">
      <div className="cols cols-2">
        <Field label="Incoming (parent)">
          <textarea value={parent} onChange={(e) => setParent(e.target.value)} />
        </Field>
        <Field label="Outgoing (child)">
          <textarea value={child} onChange={(e) => setChild(e.target.value)} />
        </Field>
      </div>
      <div className="row" style={{ marginTop: 10, alignItems: "flex-end" }}>
        <Field label="Elapsed at the hop (ms)">
          <input type="number" min={0} value={elapsed} style={{ width: 140 }} onChange={(e) => setElapsed(Number(e.target.value))} />
        </Field>
        <Button variant="primary" onClick={() => run.run()} busy={run.busy}>
          Validate
        </Button>
      </div>
      <Alert>{run.error ?? r?.error}</Alert>
      {r?.violations && (
        <div style={{ marginTop: 16 }}>
          {r.valid ? (
            <Status tone="green">Legal transition</Status>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Rule</th>
                  <th>Violation</th>
                </tr>
              </thead>
              <tbody>
                {r.violations.map((v, i) => (
                  <tr key={i}>
                    <td>
                      <Tag tone="red">{v.rule}</Tag>
                    </td>
                    <td>{v.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </Panel>
  );
}

function BudgetSplit() {
  const [f, setF] = useState({ rb: 7, reserve: 1, n: 4, mode: "parallel" });
  const run = useAction(() => api<Split>("POST", "/protocol/split", f));
  const r = run.result;
  return (
    <Panel title="Retry budget allocation" subtitle="How a parent's retry budget is divided among downstream calls.">
      <div className="row" style={{ alignItems: "flex-end" }}>
        <Field label="Parent budget">
          <input type="number" min={0} max={255} value={f.rb} onChange={(e) => setF({ ...f, rb: Number(e.target.value) })} />
        </Field>
        <Field label="Reserved by parent">
          <input type="number" min={0} value={f.reserve} onChange={(e) => setF({ ...f, reserve: Number(e.target.value) })} />
        </Field>
        <Field label="Downstream calls">
          <input type="number" min={1} max={32} value={f.n} onChange={(e) => setF({ ...f, n: Number(e.target.value) })} />
        </Field>
        <Field label="Mode">
          <select value={f.mode} onChange={(e) => setF({ ...f, mode: e.target.value })}>
            <option value="parallel">Parallel</option>
            <option value="sequential">Sequential</option>
          </select>
        </Field>
        <Button variant="primary" onClick={() => run.run()} busy={run.busy}>
          Calculate
        </Button>
      </div>
      <Alert>{run.error}</Alert>
      {r && (
        <table style={{ marginTop: 16 }}>
          <thead>
            <tr>
              <th>Downstream call</th>
              <th className="num">Retry budget granted</th>
            </tr>
          </thead>
          <tbody>
            {r.grants.map((g, i) => (
              <tr key={i}>
                <td>Call {i + 1}</td>
                <td className="num">{g}</td>
              </tr>
            ))}
            <tr>
              <td>
                <strong>{f.mode === "parallel" ? "Total granted" : "Each call may use"}</strong>
              </td>
              <td className="num">
                <strong>{f.mode === "parallel" ? r.sum : r.grants[0]}</strong>{" "}
                {r.conserved ? <Tag tone="green">within parent budget</Tag> : <Tag tone="red">exceeds parent budget</Tag>}
              </td>
            </tr>
          </tbody>
        </table>
      )}
    </Panel>
  );
}

export function ProtocolToolsPage() {
  const [tab, setTab] = useState<"decode" | "transition" | "split">("decode");
  return (
    <>
      <PageHeader title="Protocol Tools" description="Utilities for working with the RPE resilience context headers." />
      <Tabs
        tabs={[
          { id: "decode", label: "Decode header" },
          { id: "transition", label: "Validate transition" },
          { id: "split", label: "Budget allocation" },
        ]}
        active={tab}
        onChange={setTab}
      />
      {tab === "decode" && <Decode />}
      {tab === "transition" && <TransitionCheck />}
      {tab === "split" && <BudgetSplit />}
    </>
  );
}
