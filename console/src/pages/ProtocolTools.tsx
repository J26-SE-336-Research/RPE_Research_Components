import { Fragment, useState } from "react";
import { api, useAction } from "../api";
import { Alert, Button, Empty, Field, KV, Kpi, PageHeader, Panel, Status, Tabs, Tag } from "../components/ui";
import type { CompareResult, Matrix, Verdict } from "../types";

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
  out: "Outcome",
  rsn: "Reason",
  term: "Terminal (never retried)",
  bp: "Backpressure level",
  bpu: "Queue utilisation %",
  rbu: "Retry budget used",
  pv: "Policy applied",
  late: "Completed late",
  kids: "Children",
  cb: "Caller token",
  att: "Attempt",
  rv: "Registry version",
  inc: "Incomplete",
  entries: "Entries",
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

const SAMPLES: Record<string, string> = {
  context: EXAMPLE,
  result: 'v=1, out=partial, rsn=deadline, term=?1, bp=2, bpu=78, rbu=1, pv="e42", late=?0, kids="inv:ok:80;loy:expired:90"',
  hop: 'cb="k3f9a2", att=2',
  trail: 'rv=1, inc=?1, h="1:0:41250:F:0;2:1:39870:F:2;3:2:8100:F:6;4:2:38110:X:6;7:2:900:U:6"',
};

function VerdictView({ v, other }: { v?: Verdict; other?: Verdict }) {
  if (!v) return null;
  if (v.failure) return <Alert>{`rpe-${v.impl} unavailable: ${v.failure}`}</Alert>;
  const keys = [...new Set([...Object.keys(v.fields ?? {}), ...Object.keys(other?.fields ?? {})])].sort();
  return (
    <div>
      <div className="row" style={{ marginBottom: 12 }}>
        {v.valid ? <Status tone="green">Valid</Status> : <Status tone="red">Invalid</Status>}
        {v.error?.rule && <Tag tone="red">{v.error.rule}</Tag>}
        {v.error && <Tag>{v.error.code}</Tag>}
      </div>
      {v.error?.detail && <p style={{ marginTop: 0 }}>{v.error.detail}</p>}
      {v.valid && (
        <table>
          <tbody>
            {keys.map((k) => (
              <tr key={k} className={other && other.fields?.[k] !== v.fields?.[k] ? "diff" : ""}>
                <td className="muted">{LABELS[k] ?? k}</td>
                <td className="mono">{v.fields?.[k] ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {v.canonical && (
        <>
          <div className="section-title">Canonical encoding</div>
          <pre className={`code ${other && other.canonical !== v.canonical ? "diff" : ""}`}>{v.canonical}</pre>
        </>
      )}
    </div>
  );
}

function CompareImpls() {
  const [kind, setKind] = useState("context");
  const [value, setValue] = useState(SAMPLES.context);
  const run = useAction(() => api<CompareResult>("POST", "/protocol/compare", { kind, value }));
  const r = run.result;
  return (
    <div className="stack">
      <Panel
        title="Java and Go, side by side"
        subtitle="The same header value decoded by rpe-go and by rpe-java, each running in its own process. Any difference is highlighted."
      >
        <div className="row" style={{ marginBottom: 10 }}>
          <Field label="Header">
            <select
              value={kind}
              onChange={(e) => {
                setKind(e.target.value);
                setValue(SAMPLES[e.target.value]);
              }}
            >
              <option value="context">X-RPE-Context</option>
              <option value="result">X-RPE-Result</option>
              <option value="hop">X-RPE-Hop</option>
              <option value="trail">X-RPE-Trail</option>
            </select>
          </Field>
        </div>
        <textarea value={value} onChange={(e) => setValue(e.target.value)} aria-label="Header value" />
        <div className="row" style={{ marginTop: 10 }}>
          <Button variant="primary" onClick={() => run.run()} busy={run.busy}>
            Decode with both
          </Button>
          <Button variant="ghost" onClick={() => setValue(SAMPLES[kind])}>
            Reset example
          </Button>
          {r && (r.agree ? <Status tone="green">Both libraries agree</Status> : <Status tone="red">The libraries disagree</Status>)}
        </div>
        <Alert>{run.error}</Alert>
      </Panel>
      {r && (
        <div className="cmp">
          <Panel title="rpe-go">
            <VerdictView v={r.go} other={r.java} />
          </Panel>
          <Panel title="rpe-java">
            <VerdictView v={r.java} other={r.go} />
          </Panel>
        </div>
      )}
    </div>
  );
}

function ConformanceMatrix() {
  const run = useAction(() => api<Matrix>("GET", "/protocol/conformance"));
  const m = run.result;
  const [failedOnly, setFailedOnly] = useState(false);
  const mark = (pass: boolean | null, detail?: string) =>
    pass === null ? <span className="muted">—</span> : pass ? <Status tone="green">pass</Status> : <span title={detail}><Status tone="red">fail</Status></span>;
  return (
    <div className="stack">
      <Panel
        title="Conformance matrix"
        subtitle="Every shared test case of the conformance kit, run now by rpe-go and rpe-java. An implementation conforms when it passes all of them."
        actions={
          <Button variant="primary" onClick={() => run.run()} busy={run.busy}>
            Run all cases
          </Button>
        }
      >
        <Alert>{run.error ?? (m?.javaError ? `rpe-java unavailable: ${m.javaError}` : undefined)}</Alert>
        {m ? (
          <div className="kpis">
            <Kpi label="Test cases" value={m.total} foot={`${m.files.length} files`} />
            <Kpi label="rpe-go" value={`${m.goPassed} / ${m.total}`} foot={m.goPassed === m.total ? "all pass" : "failures"} />
            <Kpi label="rpe-java" value={`${m.javaPassed} / ${m.total}`} foot={m.javaPassed === m.total ? "all pass" : "failures"} />
            <Kpi label="Agreement" value={m.goPassed === m.total && m.javaPassed === m.total ? "100%" : `${Math.round((100 * Math.min(m.goPassed, m.javaPassed)) / m.total)}%`} />
          </div>
        ) : (
          <Empty>Run the cases to fill the matrix.</Empty>
        )}
      </Panel>
      {m && (
        <Panel
          title="Cases"
          flush
          actions={
            <label className="check">
              <input type="checkbox" checked={failedOnly} onChange={(e) => setFailedOnly(e.target.checked)} /> Failures only
            </label>
          }
        >
          <table className="matrix">
            <thead>
              <tr>
                <th>Case</th>
                <th className="cell">Go</th>
                <th className="cell">Java</th>
              </tr>
            </thead>
            <tbody>
              {m.files.map((f) => {
                const cases = failedOnly ? f.cases.filter((c) => !c.go || c.java === false) : f.cases;
                if (failedOnly && cases.length === 0) return null;
                const goOk = f.cases.filter((c) => c.go).length;
                const javaOk = f.cases.filter((c) => c.java).length;
                return (
                  <Fragment key={f.file}>
                    <tr className="file">
                      <td title={f.description}>
                        {f.file} <span className="muted" style={{ fontWeight: 400 }}>· {f.description}</span>
                      </td>
                      <td className="cell">
                        {goOk}/{f.cases.length}
                      </td>
                      <td className="cell">
                        {javaOk}/{f.cases.length}
                      </td>
                    </tr>
                    {cases.map((c) => (
                      <tr key={c.name}>
                        <td style={{ paddingLeft: 28 }}>{c.name}</td>
                        <td className="cell">{mark(c.go, c.goDetail)}</td>
                        <td className="cell">{mark(c.java, c.javaDetail)}</td>
                      </tr>
                    ))}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </Panel>
      )}
    </div>
  );
}

export function ProtocolToolsPage() {
  const [tab, setTab] = useState<"decode" | "compare" | "transition" | "split" | "conformance">("decode");
  return (
    <>
      <PageHeader title="Protocol Tools" description="Utilities for working with the RPE resilience context headers." />
      <Tabs
        tabs={[
          { id: "decode", label: "Decode header" },
          { id: "compare", label: "Java vs Go" },
          { id: "transition", label: "Validate transition" },
          { id: "split", label: "Budget allocation" },
          { id: "conformance", label: "Conformance" },
        ]}
        active={tab}
        onChange={setTab}
      />
      {tab === "decode" && <Decode />}
      {tab === "compare" && <CompareImpls />}
      {tab === "transition" && <TransitionCheck />}
      {tab === "split" && <BudgetSplit />}
      {tab === "conformance" && <ConformanceMatrix />}
    </>
  );
}
