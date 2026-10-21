import { useState } from "react";
import { api, useAction, useData } from "../api";
import { Ago, Alert, Button, Drawer, Empty, Field, KV, Kpi, PageHeader, Panel, Status, Tabs, Tag, type Tone } from "../components/ui";

type Assessment = {
  target: string;
  health: string;
  source: string;
  windowEnd?: string;
  baselineSamples: number;
  signals: Record<string, number>;
  evidence: string[];
};
type Proposal = {
  id: string;
  target: string;
  action: string;
  source: string;
  baseVersion: string;
  currentValue: number;
  proposedValue: number;
  unit: string;
  generatedAt: string;
  expiresAt: string;
  measurements: Record<string, number>;
  evidence: string[];
};
type Change = {
  proposal: Proposal;
  decision: string;
  reviewer?: string;
  decidedAt?: string;
  delivery: string;
  detail?: string;
  appliedVersion?: string;
};
type Collection = { enabled: boolean; deliveryConfigured: boolean; configuredServices: string[]; problems: string[] };
type Intel = {
  health?: { status: string };
  status?: { mockEnabled: boolean; changes: Change[]; assessments: Assessment[]; collection: Collection };
  error?: string;
};
type Check = { state: string; detail: string };
type AuditEntry = {
  at: string;
  kind: string;
  proposalId: string;
  target: string;
  action: string;
  source: string;
  currentValue: number;
  proposedValue: number;
  unit: string;
  decision: string;
  actor?: string;
  delivery: string;
  detail?: string;
  policyVersion?: string;
};
type Run = { scenario: string; outcomes: { assessment: Assessment; changes: Change[] }[]; changes: Change[];
  simulatedApprovals?: boolean; joints?: { jointId: string; parts: { proposalId: string }[] }[] };

const SCENARIOS: [string, string][] = [
  ["healthy", "Steady traffic"],
  ["stressed", "Rising latency"],
  ["overloaded", "Overload"],
  ["retry-stressed", "Retry amplification"],
  ["recovery", "Recovery after overload"],
  ["deadline-increase", "Chain deadline failures"],
  ["deadline-recovery", "Chain deadline recovery"],
  ["multiple-chains", "Multiple request chains"],
  ["deadline-too-long-under-overload", "Deadline v2: too long under overload"],
  ["deadline-too-short-day-one", "Deadline v2: too short from day one"],
  ["services-slowing", "Deadline v2: services slowing"],
  ["queue-rate-not-deadline", "Deadline v2: queue rate, not the deadline"],
  ["mixed-paths", "Deadline v2: mixed paths"],
  ["rejecting-middleware-504", "Deadline v2: rejecting middleware (504)"],
  ["rate-too-low", "Rate v2: rate too low"],
  ["rate-too-low-day-one", "Rate v2: rate too low from day one"],
  ["rate-real-overload", "Rate v2: real overload"],
  ["rate-recovery", "Rate v2: recovery after a cut"],
  ["rate-short-burst", "Rate v2: one short burst"],
  ["rate-bursts", "Rate v2: repeating bursts"],
  ["retry-storm-rate", "Retry v2: retry storm from a too-low rate"],
  ["joint-recovery", "Joint card: recovery (rate + deadline)"],
  ["joint-capacity-drop", "Joint card: capacity drop (rate + deadline)"],
];

const HEALTH: Record<string, [Tone, string]> = {
  HEALTHY: ["green", "Healthy"],
  STRESSED: ["amber", "Stressed"],
  OVERLOADED: ["red", "Overloaded"],
  PENDING_BREACH: ["amber", "Breach pending"],
  LEARNING_BASELINE: ["blue", "Learning baseline"],
  BASELINE_READY: ["blue", "Baseline ready"],
  INSUFFICIENT_DATA: ["neutral", "Insufficient data"],
};

const SIGNAL: Record<string, string> = {
  latency: "p95",
  errorRate: "errors",
  retryRate: "retries",
  rejectionRate: "rejections",
  inFlight: "in flight",
  throughput: "throughput",
  p99: "p99",
  deadlineFailureRate: "deadline failures",
};

const ACTION: Record<string, string> = { LEAKY_BUCKET: "Rate limit", RETRY_BUDGET: "Retry budget", CHAIN_DEADLINE: "Request deadline" };

const DELIVERY: Record<string, [Tone, string]> = {
  WAITING_FOR_COMPONENT_3: ["neutral", "Policy API not configured"],
  READY: ["neutral", "Queued"],
  SENT: ["blue", "Rolling out"],
  APPLIED: ["green", "Applied"],
  FAILED: ["red", "Delivery failed"],
  EXPIRED: ["neutral", "Expired"],
  SUPERSEDED: ["neutral", "Superseded"],
};

const AUDIT: Record<string, string> = {
  proposed: "Proposed",
  approved: "Approved",
  rejected: "Rejected",
  "delivery-sent": "Accepted by policy store",
  "delivery-applied": "Applied to proxies",
  "delivery-failed": "Delivery failed",
};

function value(v: number, unit: string) {
  if (unit === "ratio") return `${(v * 100).toFixed(1)}%`;
  if (unit === "milliseconds") return `${Math.round(v)} ms`;
  if (unit === "requests/second") return `${Math.round(v)}/s`;
  return `${Number(v.toFixed(3))} ${unit}`;
}

function delta(p: Proposal) {
  if (!p.currentValue) return "";
  const pct = ((p.proposedValue - p.currentValue) / p.currentValue) * 100;
  return `${pct > 0 ? "+" : ""}${pct.toFixed(0)}%`;
}

function signals(a: Assessment) {
  return Object.entries(a.signals ?? {})
    .map(([k, v]) => {
      const n = Number(v);
      const text = k.endsWith("Rate") ? `${(n * 100).toFixed(1)}%` : k === "throughput" ? `${n.toFixed(1)}/s` : k === "latency" || k === "p99" ? `${n.toFixed(0)} ms` : n.toFixed(0);
      return `${SIGNAL[k] ?? k} ${text}`;
    })
    .join(" · ");
}

/** Engine evidence for people: long decimals rounded. */
function readable(text?: string) {
  return (text ?? "").replace(/(\d+\.\d{2})\d+(E-?\d+)?/g, (_m, n: string, e?: string) => (e ? Number(n + e).toPrecision(2) : n));
}

function HealthTag({ health }: { health: string }) {
  const [tone, label] = HEALTH[health] ?? ["neutral", health];
  return <Status tone={tone}>{label}</Status>;
}

function Review({ change, onClose, onDone }: { change: Change; onClose: () => void; onDone: (msg: string) => void }) {
  const p = change.proposal;
  const [comment, setComment] = useState("");
  const decide = useAction(async (approve: boolean) => {
    await api("POST", `/intel/changes/${p.id}/review`, { approve, comment });
    onDone(approve ? `Approved: ${ACTION[p.action]} for ${p.target}. It is now being delivered to the policy store.` : `Rejected: ${ACTION[p.action]} for ${p.target}.`);
    onClose();
  });
  const pending = change.decision === "PENDING_APPROVAL";
  return (
    <Drawer title={`${ACTION[p.action] ?? p.action} · ${p.target}`} subtitle={`Generated from ${p.source === "TEMPO" ? "traces" : "metrics"} · based on policy ${p.baseVersion}`} onClose={onClose}>
      <div className="stack">
        <div className="kpis">
          <Kpi label="Current" value={value(p.currentValue, p.unit)} />
          <Kpi label="Proposed" value={value(p.proposedValue, p.unit)} foot={delta(p)} />
        </div>
        <Panel title="Why">
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {p.evidence.map((e, i) => (
              <li key={i} style={{ marginBottom: 6 }}>
                {readable(e)}
              </li>
            ))}
          </ul>
        </Panel>
        <Panel title="Measurements">
          <KV
            items={Object.entries(p.measurements)
              .filter(([k]) => k !== "windowEndEpochSeconds")
              .map(([k, v]) => [k.replace(/([A-Z])/g, " $1").toLowerCase(), Number.isInteger(v) ? String(v) : Number(v).toFixed(3)])}
          />
        </Panel>
        {pending ? (
          <Panel title="Decision" subtitle="You are recorded as the reviewer. Approved changes become a new policy version and roll out in two phases.">
            <Alert>{decide.error}</Alert>
            <Field label="Comment" hint="Required when rejecting">
              <textarea rows={3} value={comment} onChange={(e) => setComment(e.target.value)} />
            </Field>
            <div className="row" style={{ marginTop: 12 }}>
              <Button variant="primary" write onClick={() => decide.run(true)} busy={decide.busy}>
                Approve
              </Button>
              <Button variant="danger" write disabled={!comment.trim()} onClick={() => decide.run(false)} busy={decide.busy}>
                Reject
              </Button>
            </div>
          </Panel>
        ) : (
          <Panel title="Decision">
            <KV
              items={[
                ["Decision", change.decision === "APPROVED" ? "Approved" : change.decision === "REJECTED" ? "Rejected" : change.decision],
                ["Reviewer", change.reviewer ?? "—"],
                ["Delivery", DELIVERY[change.delivery]?.[1] ?? change.delivery],
                ["Policy version", change.appliedVersion ?? "—"],
                ["Detail", change.detail ?? "—"],
              ]}
            />
          </Panel>
        )}
      </div>
    </Drawer>
  );
}

function Queue({ changes, onOpen }: { changes: Change[]; onOpen: (c: Change) => void }) {
  if (!changes.length) return <Empty>No recommendations are waiting for review. New ones appear here when live telemetry breaches the learned baseline.</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Target</th>
            <th>Setting</th>
            <th className="num">Current</th>
            <th className="num">Proposed</th>
            <th>Reason</th>
            <th>Generated</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {changes.map((c) => (
            <tr key={c.proposal.id}>
              <td className="mono">{c.proposal.target}</td>
              <td>{ACTION[c.proposal.action] ?? c.proposal.action}</td>
              <td className="num">{value(c.proposal.currentValue, c.proposal.unit)}</td>
              <td className="num">
                <strong>{value(c.proposal.proposedValue, c.proposal.unit)}</strong> <span className="muted">{delta(c.proposal)}</span>
              </td>
              <td style={{ fontSize: 14, maxWidth: 360 }}>{readable(c.proposal.evidence[0])}</td>
              <td className="muted nowrap">
                <Ago at={c.proposal.generatedAt} />
              </td>
              <td className="nowrap">
                <Button size="sm" variant="primary" onClick={() => onOpen(c)}>
                  Review
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Delivered({ changes, onOpen }: { changes: Change[]; onOpen: (c: Change) => void }) {
  if (!changes.length) return <Empty>No reviewed recommendations yet.</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Target</th>
            <th>Setting</th>
            <th className="num">Change</th>
            <th>Decision</th>
            <th>Reviewer</th>
            <th>Delivery</th>
            <th>Policy version</th>
          </tr>
        </thead>
        <tbody>
          {changes.map((c) => {
            const [tone, label] = DELIVERY[c.delivery] ?? ["neutral", c.delivery];
            return (
              <tr key={c.proposal.id} className="clickable" onClick={() => onOpen(c)}>
                <td className="mono">{c.proposal.target}</td>
                <td>{ACTION[c.proposal.action] ?? c.proposal.action}</td>
                <td className="num nowrap">
                  {value(c.proposal.currentValue, c.proposal.unit)} → {value(c.proposal.proposedValue, c.proposal.unit)}
                </td>
                <td>{c.decision === "APPROVED" ? <Tag tone="green">Approved</Tag> : <Tag>Rejected</Tag>}</td>
                <td>{c.reviewer}</td>
                <td>{c.decision === "APPROVED" ? <Status tone={tone}>{label}</Status> : <span className="muted">Not delivered</span>}</td>
                <td className="mono">{c.appliedVersion ?? "—"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function HealthTable({ assessments }: { assessments: Assessment[] }) {
  if (!assessments.length) return <Empty>Waiting for the first complete telemetry window.</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Target</th>
            <th>Type</th>
            <th>Health</th>
            <th>Signals</th>
            <th>Assessment</th>
            <th>Window</th>
          </tr>
        </thead>
        <tbody>
          {assessments.map((a) => (
            <tr key={`${a.source}:${a.target}`}>
              <td className="mono">{a.target}</td>
              <td className="muted">{a.source === "TEMPO" ? "Request chain" : "Service"}</td>
              <td>
                <HealthTag health={a.health} />
              </td>
              <td className="muted" style={{ fontSize: 14 }}>
                {signals(a)}
              </td>
              <td style={{ fontSize: 14, maxWidth: 380 }}>{readable(a.evidence?.[0])}</td>
              <td className="muted nowrap">{a.windowEnd ? <Ago at={a.windowEnd} /> : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function History() {
  const audit = useData<{ persistent: boolean; entries: AuditEntry[] }>("/intel/audit?limit=200", 10000);
  const entries = audit.data?.entries ?? [];
  return (
    <Panel
      title="Audit trail"
      subtitle={audit.data?.persistent ? "Append-only record stored in PostgreSQL" : "In-memory record (database not configured)"}
      flush
    >
      <Alert>{audit.error}</Alert>
      {entries.length ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>When</th>
                <th>Event</th>
                <th>Target</th>
                <th>Setting</th>
                <th className="num">Change</th>
                <th>By</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((e, i) => (
                <tr key={i}>
                  <td className="muted nowrap">
                    <Ago at={e.at} />
                  </td>
                  <td className="nowrap">
                    {AUDIT[e.kind] ?? e.kind} {e.source === "MOCK" && <Tag>Simulation</Tag>}
                  </td>
                  <td className="mono">{e.target}</td>
                  <td>{ACTION[e.action] ?? e.action}</td>
                  <td className="num nowrap">
                    {value(e.currentValue, e.unit)} → {value(e.proposedValue, e.unit)}
                  </td>
                  <td>{e.actor ?? (e.kind === "proposed" ? "intelligence engine" : "—")}</td>
                  <td className="muted" style={{ fontSize: 14 }}>
                    {e.policyVersion ? `${e.policyVersion} · ` : ""}
                    {e.detail}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty>No recommendations have been recorded yet.</Empty>
      )}
    </Panel>
  );
}

function approvedBy(c: Change) {
  if (c.decision === "APPROVED") return c.reviewer ?? "approved";
  if (c.decision === "PENDING_APPROVAL") return "waiting for a person";
  if (c.decision === "REJECTED") return `rejected${c.reviewer ? ` by ${c.reviewer}` : ""}`;
  return "";
}

function Simulation() {
  const [scenario, setScenario] = useState("stressed");
  const run = useAction(() => api<Run>("POST", `/intel/demo/${scenario}`));
  const r = run.result;
  const jointOf = new Map<string, number>();
  r?.joints?.forEach((j, i) => j.parts.forEach((p) => jointOf.set(p.proposalId, i + 1)));
  const showApproval = r?.simulatedApprovals !== undefined;
  const rows = r ? [...r.changes.filter((c) => jointOf.has(c.proposal.id)).sort((a, b) => jointOf.get(a.proposal.id)! - jointOf.get(b.proposal.id)!),
    ...r.changes.filter((c) => !jointOf.has(c.proposal.id))] : [];
  const latest = new Map<string, Assessment>();
  r?.outcomes.forEach((o) => latest.set(o.assessment.target, o.assessment));
  return (
    <div className="stack">
      <Panel title="Telemetry profile" subtitle="Evaluates a synthetic telemetry sequence with the same engine. Simulated recommendations are isolated: they are never queued for review or delivered.">
        <div className="row">
          <Field label="Profile">
            <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
              {SCENARIOS.map(([id, label]) => (
                <option key={id} value={id}>
                  {label}
                </option>
              ))}
            </select>
          </Field>
          <div style={{ alignSelf: "flex-end" }}>
            <Button variant="primary" write onClick={() => run.run()} busy={run.busy}>
              Analyse
            </Button>
          </div>
        </div>
        <Alert>{run.error}</Alert>
      </Panel>
      {r && (
        <>
          <Panel title="Health" flush>
            <HealthTable assessments={[...latest.values()]} />
          </Panel>
          <Panel title="What the engine would propose" flush>
            {r.changes.length ? (
              <table>
                <thead>
                  <tr>
                    <th>Target</th>
                    <th>Setting</th>
                    <th className="num">Current</th>
                    <th className="num">Proposed</th>
                    <th>Reason</th>
                    {showApproval && <th>Approved by</th>}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((c, i) => (
                    <tr key={i}>
                      <td className="mono">{c.proposal.target}</td>
                      <td>{jointOf.has(c.proposal.id) && <><Tag tone="blue">joint</Tag>{" "}</>}{ACTION[c.proposal.action] ?? c.proposal.action}</td>
                      <td className="num">{value(c.proposal.currentValue, c.proposal.unit)}</td>
                      <td className="num">
                        <strong>{value(c.proposal.proposedValue, c.proposal.unit)}</strong>
                      </td>
                      <td style={{ fontSize: 14 }}>{readable(c.proposal.evidence[0])}</td>
                      {showApproval && <td>{approvedBy(c)}</td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <Empty>No change is warranted for this profile.</Empty>
            )}
          </Panel>
        </>
      )}
    </div>
  );
}

type TabId = "queue" | "health" | "reviewed" | "history" | "simulation";

export function RecommendationsPage() {
  const intel = useData<Intel>("/intel", 5000);
  const conn = useData<Record<string, Check>>("/intel/connections", 30000);
  const [tab, setTab] = useState<TabId>("queue");
  const [open, setOpen] = useState<Change>();
  const [notice, setNotice] = useState<string>();
  const relearn = useAction(async () => {
    await api("POST", "/intel/baselines/reset");
    setNotice("Baselines cleared. New baselines are learned from the next telemetry windows.");
    intel.reload();
  });

  const changes = (intel.data?.status?.changes ?? []).filter((c) => c.proposal.source !== "MOCK");
  const active = (c: Change) => c.delivery !== "SUPERSEDED" && c.delivery !== "EXPIRED";
  const queue = changes.filter((c) => c.decision === "PENDING_APPROVAL" && active(c));
  const reviewed = changes.filter((c) => c.decision === "APPROVED" || c.decision === "REJECTED");
  const assessments = (intel.data?.status?.assessments ?? []).filter((a) => a.source !== "MOCK");
  const stressed = assessments.filter((a) => a.health === "STRESSED" || a.health === "OVERLOADED").length;
  const live = (k: string) => conn.data?.[k]?.state === "LIVE_DATA";
  const collection = intel.data?.status?.collection;

  return (
    <>
      <PageHeader title="Recommendations" description="Policy changes proposed from live telemetry. Nothing is applied until a reviewer approves it." />
      <Alert>{intel.error ?? intel.data?.error}</Alert>
      {notice && <Alert kind="info">{notice} <a href="#/policies">View policies</a></Alert>}
      <div className="stack">
        <div className="kpis">
          <Kpi label="Engine" value={intel.data?.health?.status === "UP" ? <Status tone="green">Running</Status> : <Status tone="red">Unavailable</Status>} foot={collection?.enabled ? "Live collection" : "Collection disabled"} />
          <Kpi
            label="Telemetry"
            value={live("prometheus") && live("tempo") ? <Status tone="green">Live</Status> : <Status tone="amber">Partial</Status>}
            foot={`Metrics ${live("prometheus") ? "live" : "unavailable"} · Traces ${live("tempo") ? "live" : "unavailable"}`}
          />
          <Kpi label="Awaiting review" value={queue.length} />
          <Kpi label="Degraded targets" value={stressed} foot={`${assessments.length} monitored`} />
        </div>
        {collection?.problems?.length ? (
          <Alert kind="info">
            {collection.problems.slice(0, 3).join(" · ")}
            {collection.problems.length > 3 ? ` (+${collection.problems.length - 3} more)` : ""}
          </Alert>
        ) : null}
        <Tabs
          tabs={[
            { id: "queue", label: `Approval queue (${queue.length})` },
            { id: "health", label: "Health" },
            { id: "reviewed", label: `Reviewed (${reviewed.length})` },
            { id: "history", label: "Audit trail" },
            { id: "simulation", label: "Simulation" },
          ]}
          active={tab}
          onChange={setTab}
        />
        {tab === "queue" && (
          <Panel flush>
            <Queue changes={queue} onOpen={setOpen} />
          </Panel>
        )}
        {tab === "health" && (
          <Panel
            flush
            subtitle="Each target is compared with a baseline learned from its own healthy traffic."
            actions={
              <Button size="sm" write onClick={() => window.confirm("Forget the learned baselines and learn new ones from the next windows? Use this after an intentional change such as a release.") && relearn.run()} busy={relearn.busy}>
                Re-learn baselines
              </Button>
            }
          >
            <Alert>{relearn.error}</Alert>
            <HealthTable assessments={assessments} />
          </Panel>
        )}
        {tab === "reviewed" && (
          <Panel flush>
            <Delivered changes={reviewed} onOpen={setOpen} />
          </Panel>
        )}
        {tab === "history" && <History />}
        {tab === "simulation" && <Simulation />}
      </div>
      {open && (
        <Review
          change={open}
          onClose={() => setOpen(undefined)}
          onDone={(m) => {
            setNotice(m);
            intel.reload();
          }}
        />
      )}
    </>
  );
}
