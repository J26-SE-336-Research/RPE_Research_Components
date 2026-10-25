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
type Joint = { jointId: string; parts: { proposalId: string }[] };
/** C4's check of one automatic rate change (rateEvidence[service].checks). */
type RateCheck = { proposalId: string; kind: string; fromRate: number; toRate: number; result: string; appBeforeMs?: number | null; appAfterMs?: number | null;
  normalMs?: number | null; shedBeforePerSecond?: number | null; shedAfterPerSecond?: number | null; at: string };
/** C4's latest rate decision per live service (status field rateEvidence). */
type RateEvidence = { diagnosis: string; reason: string; rate?: number; inputs?: Record<string, number>; checks?: RateCheck[] };
/** C4's latest deadline decision per live chain (status field deadlineEvidence); only the fields this page reads. */
type DeadlineEvidence = { decisionCase?: string; serviceLoadReasons?: Record<string, string>; model?: { thMs?: number | null; windows?: number; requests?: number } };
type Intel = {
  health?: { status: string };
  status?: { mockEnabled: boolean; changes: Change[]; assessments: Assessment[]; collection: Collection; joints?: Joint[];
    rateEvidence?: Record<string, RateEvidence>; deadlineEvidence?: Record<string, DeadlineEvidence> };
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
  simulatedApprovals?: boolean; joints?: Joint[] };

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

// ---------- plain-language explanations, built from C4's structured numbers ----------

/** Styles of this page only (class prefix c4-), on the console's own colour tokens. */
const C4_CSS = `
.c4-cards { display: flex; flex-direction: column; gap: 14px; }
.c4-card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow); padding: 18px 20px; display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.c4-card.joint { border-color: var(--primary); }
.c4-top { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 12px; align-items: flex-start; }
.c4-title { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.c4-title h3 { margin: 0; font-size: 17px; font-weight: 600; }
.c4-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.c4-path { font-family: var(--mono); font-size: 13px; color: var(--muted); overflow-wrap: anywhere; }
.c4-change { display: flex; align-items: baseline; gap: 10px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.c4-change .from { color: var(--muted); font-size: 18px; }
.c4-change .to { font-size: 22px; font-weight: 700; }
.c4-change .pct { font-weight: 600; font-size: 14px; color: var(--muted); }
.c4-why { margin: 0; line-height: 1.6; }
.c4-pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 10px; }
.c4-cell { border: 1px solid var(--border); border-radius: var(--radius); padding: 12px 14px; display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 8px; }
.c4-cell .k { font-size: 13px; color: var(--muted); }
.c4-cell .v { font-size: 17px; font-weight: 600; font-variant-numeric: tabular-nums; }
.c4-foot { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; border-top: 1px solid var(--border); padding-top: 12px; }
.c4-note { font-size: 13px; color: var(--muted); }
.c4-duo { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.c4-box { border-radius: var(--radius); padding: 12px 14px; background: var(--surface-2); border: 1px solid var(--border); display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.c4-box.to { background: var(--primary-soft); border-color: var(--primary); }
.c4-box .k { font-size: 13px; color: var(--muted); }
.c4-box .v { font-size: 20px; font-weight: 700; font-variant-numeric: tabular-nums; }
.c4-sec { display: flex; flex-direction: column; gap: 10px; }
.c4-sec h4 { margin: 0; font-size: 15px; font-weight: 600; }
.c4-because { list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: var(--radius); }
.c4-because li { display: grid; grid-template-columns: 24px minmax(0, 1fr); gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--border); }
.c4-because li:last-child { border-bottom: 0; }
.c4-num { width: 22px; height: 22px; border-radius: 50%; background: var(--primary-soft); color: var(--primary); font-size: 12px; font-weight: 700; display: grid; place-items: center; }
.c4-reason { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.c4-reason .h { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.c4-reason .h strong { font-size: 14.5px; font-weight: 600; }
.c4-reason .t { font-size: 13.5px; color: var(--text-2); line-height: 1.5; }
.c4-src { font-size: 11.5px; font-weight: 600; letter-spacing: .03em; text-transform: uppercase; color: var(--muted); border: 1px solid var(--border); border-radius: 4px; padding: 0 5px; white-space: nowrap; }
.c4-result { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
.c4-result .c4-box .v { font-size: 17px; }
.c4-auto { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow); }
.c4-arow { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 16px; padding: 16px 20px; border-bottom: 1px solid var(--border); }
.c4-arow:last-child { border-bottom: 0; }
.c4-arow .when { font-size: 13px; color: var(--muted); padding-top: 2px; }
.c4-arow .body { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.c4-arow .head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; align-items: baseline; }
.c4-arow .head strong { font-size: 15px; }
.c4-goal { font-size: 14px; color: var(--text-2); }
.c4-goal b { color: var(--primary); font-weight: 600; }
.c4-ev { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.c4-ev .c4-box .lab { font-size: 12px; font-weight: 600; letter-spacing: .03em; text-transform: uppercase; color: var(--muted); }
.c4-ev .c4-box .txt { font-size: 14px; color: var(--text-2); line-height: 1.5; }
@media (max-width: 640px) { .c4-arow { grid-template-columns: minmax(0, 1fr); gap: 6px; } .c4-duo, .c4-ev { grid-template-columns: minmax(0, 1fr); } }
`;

type Reason = { title: string; text: string; source: string };
type Explained = { headline: string; tags: [Tone, string][]; why: string; because: Reason[]; result: [string, string][] };

const num = (m: Record<string, number> | undefined, k: string) => (m && typeof m[k] === "number" && Number.isFinite(m[k]) ? m[k] : undefined);
const pct = (x?: number) => (x === undefined ? "—" : `${Number((x * 100).toFixed(1))}%`);
const ms = (x?: number | null) => (x === undefined || x === null ? "—" : `${Math.round(x)} ms`);
const rps = (x?: number | null) => (x === undefined || x === null ? "—" : `${Number(x.toFixed(1))} req/s`);

/** Engine lines that carry no decision content for a reviewer. */
const NOISE = [/^Learning a stable/, /^Counts normalized/, /^[A-Za-z]+=/, /^Every deadline change requires/, /^Counted disjoint window/, /^Retry budget waits/];
function fallbackReasons(p: Proposal): Reason[] {
  return p.evidence.filter((e) => !NOISE.some((r) => r.test(e))).slice(0, 4).map((e) => ({ title: "", text: readable(e), source: p.source === "TEMPO" ? "Traces" : "Metrics" }));
}
/** Services the deadline evidence names as overloaded, for a sentence like "(order-svc)". */
function overloadedNames(dl?: DeadlineEvidence) {
  const names = Object.entries(dl?.serviceLoadReasons ?? {}).filter(([, r]) => r.startsWith("OVERLOADED")).map(([s]) => s);
  return names.length ? ` (${names.join(", ")})` : "";
}

/** What a reviewer needs to decide: a headline, one-line why, 3–4 reasons with their source, and the expected result. */
function explain(c: Change, partner: Change | undefined, dl: DeadlineEvidence | undefined): Explained {
  const p = c.proposal, m = p.measurements;
  const up = p.proposedValue > p.currentValue;
  if (num(m, "jointRateNew") !== undefined) {
    const rate = p.action === "LEAKY_BUCKET" ? p : partner?.proposal;
    const svc = rate?.target ?? "the service";
    const d = { cur: num(m, "currentMs"), prop: num(m, "proposedMs"), th: num(m, "thMs"), thNew: num(m, "thNewMs") };
    const moved = d.cur !== undefined && d.prop !== undefined ? Math.abs(d.cur - d.prop) : undefined;
    return {
      headline: `Change ${svc}'s rate and the request deadline together`, tags: [["blue", "Joint · one decision"], ["green", "Healthy recalibration"]],
      why: `${svc}'s healthy rate changed to ${num(m, "jointRateNew")} req/s, which makes the chain faster, so the deadline should follow.`,
      because: [
        { title: "The service's healthy rate changed", text: `Its healthy rate is now ${num(m, "jointRateNew")} req/s instead of ${num(m, "jointRateOld")} req/s.`, source: "Policy" },
        { title: "Requests wait less in its queue", text: `With ${num(m, "jointQ")} requests waiting, the wait falls from ${ms(num(m, "jointWaitOldMs"))} to ${ms(num(m, "jointWaitNewMs"))}.`, source: "Metrics" },
        { title: "The chain gets faster", text: `Its normal time falls from ${ms(d.th)} to ${ms(d.thNew)}, so ${ms(d.cur)} no longer fits.`, source: "Traces" },
        { title: "Worth changing together", text: `The deadline moves by ${ms(moved)}, more than the ${ms(num(m, "thresholdMs"))} threshold. Changing only one would leave them out of step.`, source: "C4" },
      ],
      result: [["Queue wait", `${ms(num(m, "jointWaitOldMs"))} → ${ms(num(m, "jointWaitNewMs"))}`], ["Chain normal time", `${ms(d.th)} → ${ms(d.thNew)}`], ["Deadline", `${ms(d.cur)} → ${ms(d.prop)}`]],
    };
  }
  if (p.action === "CHAIN_DEADLINE") {
    const N = num(m, "requestCount"), hs = num(m, "healthySamples"), her = num(m, "healthyExceedRate"), os = num(m, "overloadShare") ?? 0;
    const p99 = num(m, "recentP99Ms"), th = num(m, "healthyTimeMs") ?? (dl?.model?.thMs ?? undefined);
    const cur = Math.round(p.currentValue), prop = Math.round(p.proposedValue);
    if (up) {
      const late = her !== undefined && hs ? Math.round(her * hs) : undefined;
      return {
        headline: "Raise the request deadline", tags: [["blue", "Request deadline"], ["amber", "Deadline too short"]],
        why: late !== undefined ? `${late} of ${hs} normal requests took longer than ${cur} ms, so healthy requests are being marked as late.` : `Healthy requests are being marked as late at ${cur} ms.`,
        because: [
          ...(late !== undefined ? [{ title: "Normal requests are marked late", text: `${late} of ${hs} healthy requests (${pct(her)}, likely ${pct(num(m, "healthyExceedLower"))}–${pct(num(m, "healthyExceedUpper"))}) took longer than ${cur} ms.`, source: "Traces" }] : []),
          { title: os > 0 ? "Some requests come from overloaded services" : "The services are not the problem", text: os > 0 ? `${pct(os)} of requests passed through an overloaded service${overloadedNames(dl)}.` : "None of the requests passed through an overloaded service.", source: "Metrics" },
          ...(p99 !== undefined ? [{ title: "Chain time now", text: `The slowest 1% of requests take about ${ms(p99)}, against a deadline of ${cur} ms.`, source: "Traces" }] : []),
          { title: "One bounded step", text: th !== undefined ? `A normal request takes ${ms(th)}; the deadline is raised to ${prop} ms in one step.` : "The normal time cannot be measured yet because many requests are cut off at the deadline, so it is raised in one step of at most +50% and measured again.", source: "C4" },
        ],
        result: [["Healthy requests marked late", `${pct(her)} → ${num(m, "predictedHealthyExceed") !== undefined ? pct(num(m, "predictedHealthyExceed")) : "fewer"}`], ["Requests rejected", "0"]],
      };
    }
    if (os >= 0.5) {
      return {
        headline: "Lower the request deadline", tags: [["blue", "Request deadline"], ["red", "Too long under overload"]],
        why: "Services on this chain are overloaded, but the deadline is too long for their requests to be signalled as late.",
        because: [
          { title: "Services are overloaded", text: `${pct(os)}${N !== undefined ? ` of ${N}` : ""} requests passed through an overloaded service${overloadedNames(dl)}.`, source: "Metrics" },
          { title: "Nobody is told", text: `${pct(num(m, "overloadExceedRate"))} of them were signalled as late (at most ${pct(num(m, "overloadExceedUpper"))})${p99 !== undefined ? `; the slowest 1% take about ${ms(p99)}, under the ${cur} ms deadline` : ""}.`, source: "Traces" },
          { title: "The new deadline separates normal from slow", text: `A normal request takes ${ms(th)}${dl?.model?.requests ? ` (learned from ${dl.model.requests} healthy requests)` : ""}. ${prop} ms leaves normal requests alone and marks the slow ones.`, source: "Traces" },
        ],
        result: [["Healthy requests affected", pct(num(m, "predictedHealthyExceed"))], ["Overloaded requests signalled", `${pct(num(m, "overloadExceedRate"))} → ${pct(num(m, "predictedOverloadExceed"))}`], ["Requests rejected", "0"]],
      };
    }
    return {
      headline: "Lower the request deadline", tags: [["blue", "Request deadline"], ["green", "More generous than needed"]],
      why: `The chain is healthy and much faster than its ${cur} ms deadline.`,
      because: [
        { title: "The deadline is more generous than needed", text: `A normal request takes ${ms(th)}; the deadline is ${cur} ms.`, source: "Traces" },
        { title: "The services are healthy", text: os > 0 ? `${pct(os)} of requests passed through an overloaded service.` : "None of the requests passed through an overloaded service.", source: "Metrics" },
      ],
      result: [["Healthy requests affected", pct(num(m, "predictedHealthyExceed"))], ["Requests rejected", "0"]],
    };
  }
  if (p.action === "LEAKY_BUCKET") {
    const app = num(m, "appMs"), p0 = num(m, "p0Ms");
    if (!up) {
      return {
        headline: "Lower the rate limit", tags: [["blue", "Rate limit"], ["red", "App overloaded"]],
        why: `The service itself is slow: ${ms(app)} per request where ${ms(p0)} is normal.`,
        because: [
          { title: "The app itself is slow", text: `It takes ${ms(app)} per request where ${ms(p0)} is normal; only ${ms(num(m, "queueWaitMs"))} is waiting in our queue.`, source: "Metrics" },
          { title: "What it can handle", text: `It handles about ${rps(num(m, "acceptedPerSecond"))}.`, source: "Metrics" },
        ],
        result: [["Rate", `${value(p.currentValue, p.unit)} → ${value(p.proposedValue, p.unit)}`], ["Goal", "Let the service recover"]],
      };
    }
    return {
      headline: "Raise the rate limit", tags: [["blue", "Rate limit"], ["amber", "Rate too low"]],
      why: `Requests are turned away while the service is fast (${ms(app)}).`,
      because: [
        { title: "Requests are turned away", text: `Our queue is ${pct((num(m, "queuePercent") ?? 0) / 100)} full and ${rps(num(m, "shedPerSecond"))} are turned away.`, source: "Metrics" },
        { title: "The app is fine", text: `It takes ${ms(app)} per request (normal ${ms(p0)}).`, source: "Metrics" },
      ],
      result: [["Rate", `${value(p.currentValue, p.unit)} → ${value(p.proposedValue, p.unit)}`], ["Goal", "Serve more users"]],
    };
  }
  const reasons = fallbackReasons(p);
  return {
    headline: `${ACTION[p.action] ?? p.action}: ${up ? "raise" : "lower"}`, tags: [["blue", ACTION[p.action] ?? p.action]],
    why: reasons[0]?.text ?? "", because: reasons, result: [["Change", `${value(p.currentValue, p.unit)} → ${value(p.proposedValue, p.unit)}`]],
  };
}

/** The two parts of a joint recommendation, rate part first. */
const rateFirst = (parts: Change[]) => [...parts].sort((a, b) => Number(b.proposal.action === "LEAKY_BUCKET") - Number(a.proposal.action === "LEAKY_BUCKET"));

function Because({ reasons }: { reasons: Reason[] }) {
  return (
    <ol className="c4-because">
      {reasons.map((r, i) => (
        <li key={i}>
          <span className="c4-num">{i + 1}</span>
          <div className="c4-reason">
            <div className="h">
              <strong>{r.title}</strong>
              <span className="c4-src">{r.source}</span>
            </div>
            <span className="t">{r.text}</span>
          </div>
        </li>
      ))}
    </ol>
  );
}

function Review({ change, partner, dl, onClose, onDone }: { change: Change; partner?: Change; dl?: DeadlineEvidence; onClose: () => void; onDone: (msg: string) => void }) {
  const p = change.proposal;
  const sim = p.source === "MOCK";
  const x = explain(change, partner, dl);
  const joint = partner !== undefined;
  const parts = partner ? rateFirst([change, partner]) : [change];
  const [comment, setComment] = useState("");
  const decide = useAction(async (approve: boolean) => {
    await api("POST", `/intel/changes/${p.id}/review`, { approve, comment });
    onDone(sim ? `Simulation: ${approve ? "approved" : "rejected"}, never applied (${ACTION[p.action]} for ${p.target}).`
      : approve ? `Approved: ${ACTION[p.action]} for ${p.target}. It is now being delivered to the policy store.` : `Rejected: ${ACTION[p.action]} for ${p.target}.`);
    onClose();
  });
  const pending = change.decision === "PENDING_APPROVAL";
  return (
    <Drawer title={x.headline} subtitle={`${sim ? "Simulation · " : ""}${p.target} · from ${p.source === "TEMPO" ? "traces" : "metrics"} · policy ${p.baseVersion}`} onClose={onClose}>
      <div className="stack">
        {joint ? (
          <div className="c4-duo">
            {parts.map((c) => (
              <div key={c.proposal.id} className="c4-box to">
                <span className="k">{ACTION[c.proposal.action] ?? c.proposal.action} · {c.proposal.target}</span>
                <span className="v" style={{ fontSize: 17 }}>{value(c.proposal.currentValue, c.proposal.unit)} → {value(c.proposal.proposedValue, c.proposal.unit)}</span>
              </div>
            ))}
          </div>
        ) : (
          <div className="c4-duo">
            <div className="c4-box"><span className="k">Now</span><span className="v">{value(p.currentValue, p.unit)}</span></div>
            <div className="c4-box to"><span className="k">Proposed</span><span className="v">{value(p.proposedValue, p.unit)} <span className="muted" style={{ fontSize: 14 }}>{delta(p)}</span></span></div>
          </div>
        )}
        <section className="c4-sec">
          <h4>We recommend this because</h4>
          <Because reasons={x.because} />
        </section>
        <section className="c4-sec">
          <h4>Expected result</h4>
          <div className="c4-result">
            {x.result.map(([k, v]) => (
              <div key={k} className="c4-box"><span className="k">{k}</span><span className="v">{v}</span></div>
            ))}
          </div>
        </section>
        {pending ? (
          <Panel title={joint ? "Your decision (applies to both)" : "Your decision"} subtitle={sim ? "Simulation: you are recorded as the reviewer; nothing is applied." : "You are recorded as the reviewer. Approved changes become a new policy version and roll out in two phases."}>
            <Alert>{decide.error}</Alert>
            <Field label="Comment" hint="Required when rejecting">
              <textarea rows={3} value={comment} onChange={(e) => setComment(e.target.value)} />
            </Field>
            <div className="row" style={{ marginTop: 12 }}>
              <Button variant="primary" write onClick={() => decide.run(true)} busy={decide.busy}>
                {joint ? "Approve both" : "Approve"}
              </Button>
              <Button variant="danger" write disabled={!comment.trim()} onClick={() => decide.run(false)} busy={decide.busy}>
                {joint ? "Reject both" : "Reject"}
              </Button>
            </div>
          </Panel>
        ) : (
          <Panel title="Decision">
            <KV
              items={[
                ["Decision", change.decision === "APPROVED" ? "Approved" : change.decision === "REJECTED" ? "Rejected" : change.decision],
                ["Reviewer", change.reviewer ?? "—"],
                ["Delivery", sim ? "Never applied (simulation)" : DELIVERY[change.delivery]?.[1] ?? change.delivery],
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

function Queue({ changes, partnerOf, dlOf, onOpen }: { changes: Change[]; partnerOf: (c: Change) => Change | undefined;
  dlOf: (c: Change) => DeadlineEvidence | undefined; onOpen: (c: Change) => void }) {
  if (!changes.length) return <Empty>No recommendations are waiting for review. New ones appear here when live telemetry breaches the learned baseline.</Empty>;
  const shown = new Set<string>();
  return (
    <div className="c4-cards">
      {changes.map((c) => {
        if (shown.has(c.proposal.id)) return null;
        const partner = partnerOf(c);
        shown.add(c.proposal.id);
        if (partner) shown.add(partner.proposal.id);
        const p = c.proposal, x = explain(c, partner, dlOf(c));
        const parts = partner ? rateFirst([c, partner]) : [];
        return (
          <article key={p.id} className={`c4-card ${partner ? "joint" : ""}`}>
            <div className="c4-top">
              <div className="c4-title">
                <div className="c4-tags">{x.tags.map(([tone, t]) => <Tag key={t} tone={tone}>{t}</Tag>)}</div>
                <h3>{x.headline}</h3>
                <span className="c4-path">{partner ? `${parts[0].proposal.target} · ${parts[1].proposal.target}` : p.target}</span>
              </div>
              {!partner && (
                <div className="c4-change">
                  <span className="from">{value(p.currentValue, p.unit)}</span>
                  <span className="from" aria-hidden="true">→</span>
                  <span className="to">{value(p.proposedValue, p.unit)}</span>
                  <span className="pct">{delta(p)}</span>
                </div>
              )}
            </div>
            {partner && (
              <div className="c4-pair">
                {parts.map((part) => (
                  <div key={part.proposal.id} className="c4-cell">
                    <span className="k">{ACTION[part.proposal.action] ?? part.proposal.action}</span>
                    <span className="v">{value(part.proposal.currentValue, part.proposal.unit)} → {value(part.proposal.proposedValue, part.proposal.unit)}</span>
                  </div>
                ))}
              </div>
            )}
            {x.why && <p className="c4-why"><strong>Why: </strong>{x.why}</p>}
            <div className="c4-foot">
              <span className="c4-note">
                {partner ? "Approving or rejecting decides both changes. " : ""}Never approved automatically · <Ago at={p.generatedAt} />
              </span>
              <Button variant="primary" onClick={() => onOpen(c)}>
                Review
              </Button>
            </div>
          </article>
        );
      })}
    </div>
  );
}

/** Rate state per live service, in plain words (from rateEvidence). */
function ServicesNow({ rates }: { rates: Record<string, RateEvidence> }) {
  const rows = Object.entries(rates).sort(([a], [b]) => a.localeCompare(b));
  if (!rows.length) return <Empty>Waiting for the first complete telemetry window.</Empty>;
  const STATE: Record<string, [Tone, string]> = {
    APP_OVERLOADED: ["red", "App overloaded"], RATE_TOO_LOW: ["amber", "Rate too low"], HEALTHY: ["green", "Healthy"],
    WATCH: ["amber", "Queue filling"], UNKNOWN: ["neutral", "Learning"],
  };
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Service</th>
            <th>Health</th>
            <th>Signals</th>
            <th>Assessment</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([svc, r]) => {
            const i = r.inputs ?? {};
            const [tone, label] = STATE[r.diagnosis] ?? ["neutral", r.diagnosis];
            const app = num(i, "appMs"), p0 = num(i, "p0Ms"), shed = num(i, "shedPerSecond") ?? 0;
            const signals = [
              app !== undefined && `app time ${ms(app)}`, p0 !== undefined && `normal ${ms(p0)}`, num(i, "queuePercent") !== undefined && `queue ${Math.round(num(i, "queuePercent")!)}%`,
              num(i, "queueWaitMs") !== undefined && `queue wait ${ms(num(i, "queueWaitMs"))}`, shed > 0 && `turned away ${rps(shed)}`,
              num(i, "acceptedPerSecond") !== undefined && `handles ${rps(num(i, "acceptedPerSecond"))}`, r.rate !== undefined && `rate ${rps(r.rate)}`,
            ].filter(Boolean).join(" · ");
            const assessment =
              r.diagnosis === "APP_OVERLOADED" ? `The app itself is slow${app !== undefined && p0 ? `: ${Number((app / p0).toFixed(1))} × its normal time` : ""}. Our queue is not the cause.`
              : r.diagnosis === "RATE_TOO_LOW" ? `The app is fine; requests wait in our queue${shed > 0 ? ` and ${rps(shed)} are turned away` : ""}.`
              : r.diagnosis === "HEALTHY" ? "Healthy: queue calm, nothing turned away, app time normal."
              : r.diagnosis === "WATCH" ? "The queue is filling up; the app time is still normal."
              : `Not decided yet: ${readable(r.reason)}.`;
            return (
              <tr key={svc}>
                <td className="mono">{svc}</td>
                <td><Status tone={tone}>{label}</Status></td>
                <td className="muted" style={{ fontSize: 14 }}>{signals || "—"}</td>
                <td style={{ fontSize: 14, maxWidth: 420 }}>{assessment}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** Rate and retry changes C4 made automatically, each with its goal, reason and checked result. */
function AutoChanges({ changes, rates }: { changes: Change[]; rates: Record<string, RateEvidence> }) {
  if (!changes.length) return <Empty>No automatic changes yet. Rate and retry changes appear here when C4 applies them within the safety limits.</Empty>;
  const checks = new Map<string, RateCheck>();
  Object.values(rates).forEach((r) => r.checks?.forEach((c) => checks.set(c.proposalId, c)));
  return (
    <div className="c4-auto">
      {changes.map((c) => {
        const p = c.proposal, m = p.measurements, down = p.proposedValue < p.currentValue;
        const revert = c.reviewer === "c4-auto-revert", retry = p.action === "RETRY_BUDGET";
        const relax = !revert && !retry && down && p.evidence.some((e) => e.includes("too generous"));
        const check = checks.get(p.id);
        const goal = revert ? "Undo a change that did not help."
          : retry ? (down ? "Stop extra load while the service is overloaded." : "Allow retries again now that the service is healthy.")
          : relax ? "Bring a limit far above the traffic back down." : down ? "Protect the overloaded service so it can recover." : "Serve more users now that the service is healthy.";
        const app = num(m, "appMs"), p0 = num(m, "p0Ms");
        const because = revert ? `After the previous change the service got slower (${ms(num(m, "appBeforeMs"))} → ${ms(app)}).`
          : retry ? (down ? "The service was overloaded. Sending failed requests again would only add more load." : "The service is healthy again.")
          : relax ? `The limit was far above the traffic (${rps(num(m, "maxDemandPerSecond") ?? num(m, "demandPerSecond"))}).`
          : down ? `The service was slow: ${ms(app)} per request where ${ms(p0)} is normal${num(m, "errorsHigh") === 1 ? ", and it returned errors" : ""}.`
          : `${rps(num(m, "shedPerSecond"))} were turned away while the service was fast (${ms(app)}).`;
        const [tone, status, result]: [Tone, string, string] = revert ? ["neutral", "Undo", "Back to the previous value."]
          : retry ? ["blue", "Protection", "Nothing to measure: this only protects the service."]
          : check?.result === "kept" ? ["green", "Kept", `Service time ${ms(check.appBeforeMs)} → ${ms(check.appAfterMs)} (normal at most ${ms(check.normalMs)}).${check.shedAfterPerSecond != null && check.shedBeforePerSecond != null && check.shedAfterPerSecond !== check.shedBeforePerSecond ? ` Turned away ${rps(check.shedBeforePerSecond)} → ${rps(check.shedAfterPerSecond)}.` : ""}`]
          : check?.result === "undone" ? ["amber", "Undone", `The service got slower (${ms(check.appBeforeMs)} → ${ms(check.appAfterMs)}), so it was changed back.`]
          : check?.result === "inconclusive" ? ["green", "Kept", "No service time was measured afterwards, so it was kept."]
          : c.delivery === "APPLIED" ? ["blue", "Checking", "Being checked on the next windows."]
          : [DELIVERY[c.delivery]?.[0] ?? "neutral", DELIVERY[c.delivery]?.[1] ?? c.delivery, "Waiting to be applied."];
        return (
          <div key={p.id} className="c4-arow">
            <span className="when"><Ago at={p.generatedAt} /></span>
            <div className="body">
              <div className="head">
                <strong>{ACTION[p.action] ?? p.action} {value(p.currentValue, p.unit)} → {value(p.proposedValue, p.unit)} · {p.target}</strong>
                <Tag tone={tone}>{status}</Tag>
              </div>
              <span className="c4-goal"><b>Goal: </b>{goal}</span>
              <div className="c4-ev">
                <div className="c4-box"><span className="lab">We did this because</span><span className="txt">{because}</span></div>
                <div className="c4-box"><span className="lab">Result</span><span className="txt">{result}</span></div>
              </div>
            </div>
          </div>
        );
      })}
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

/** One short line for an audit detail; the full text stays in the database and shows on hover. */
function shortDetail(detail?: string) {
  if (!detail) return "";
  const text = detail.trim();
  if (text.startsWith("{")) {
    try {
      const d = JSON.parse(text);
      if (typeof d.guard === "string") return d.guard;
      if (d.decisionCase || d.holdReason) {
        const what = d.holdReason ? String(d.holdReason).toLowerCase().replace(/_/g, " ") : String(d.decisionCase);
        return d.proposedMs ? `${what}: ${d.currentMs} → ${d.proposedMs} ms` : what;
      }
      for (const key of ["reason", "detail", "rule"]) if (typeof d[key] === "string") return d[key];
    } catch {
      // not JSON: shortened below
    }
  }
  return text.length > 140 ? text.slice(0, 137) + "…" : text;
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
                  <td className="muted" style={{ fontSize: 14 }} title={e.detail}>
                    {e.policyVersion ? `${e.policyVersion} · ` : ""}
                    {shortDetail(e.detail)}
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

function practiceStatus(c: Change) {
  if (c.decision === "APPROVED") return `Simulation: approved by ${c.reviewer ?? "?"}, never applied`;
  if (c.decision === "REJECTED") return `Simulation: rejected by ${c.reviewer ?? "?"}`;
  return "waiting for a person";
}

function Simulation({ practice, joints, onOpen, onChanged }: { practice: Change[]; joints?: { jointId: string; parts: { proposalId: string }[] }[];
  onOpen: (c: Change) => void; onChanged: (msg: string) => void }) {
  const [scenario, setScenario] = useState("stressed");
  const run = useAction(() => api<Run>("POST", `/intel/demo/${scenario}`));
  const r = run.result;
  const toCards = useAction(async () => {
    const res = await api<{ practiceCards?: unknown[] }>("POST", `/intel/demo/${scenario}?to=queue`);
    onChanged(`Simulation: ${res.practiceCards?.length ?? 0} practice card(s) added from ${scenario}. Review them below; nothing is applied.`);
  });
  const clear = useAction(async () => {
    const res = await api<{ removed: number }>("POST", "/intel/practice/clear");
    onChanged(`Simulation: ${res.removed} practice card(s) cleared.`);
  });
  const practiceJoint = new Map<string, number>();
  joints?.forEach((j, i) => j.parts.forEach((p) => practiceJoint.set(p.proposalId, i + 1)));
  const practiceRows = [...practice.filter((c) => practiceJoint.has(c.proposal.id)).sort((a, b) => practiceJoint.get(a.proposal.id)! - practiceJoint.get(b.proposal.id)!),
    ...practice.filter((c) => !practiceJoint.has(c.proposal.id))];
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
          {r && (
            <div style={{ alignSelf: "flex-end" }}>
              <Button write onClick={() => toCards.run()} busy={toCards.busy}>
                Review these as cards
              </Button>
            </div>
          )}
        </div>
        <Alert>{run.error ?? toCards.error ?? clear.error}</Alert>
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
      <Panel
        title="Practice cards (simulation)"
        subtitle="Review a scenario's recommendations as cards. Approving or rejecting records your decision; nothing is applied."
        flush
        actions={
          <Button size="sm" write disabled={!practice.length} onClick={() => window.confirm("Remove all practice (simulation) cards? Real recommendations are not affected.") && clear.run()} busy={clear.busy}>
            Clear practice cards
          </Button>
        }
      >
        {practiceRows.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Target</th>
                  <th>Setting</th>
                  <th className="num">Current</th>
                  <th className="num">Proposed</th>
                  <th>Reason</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {practiceRows.map((c) => (
                  <tr key={c.proposal.id}>
                    <td className="mono">{c.proposal.target}</td>
                    <td>
                      <Tag>Simulation</Tag> {practiceJoint.has(c.proposal.id) && <><Tag tone="blue">joint</Tag>{" "}</>}{ACTION[c.proposal.action] ?? c.proposal.action}
                    </td>
                    <td className="num">{value(c.proposal.currentValue, c.proposal.unit)}</td>
                    <td className="num">
                      <strong>{value(c.proposal.proposedValue, c.proposal.unit)}</strong>
                    </td>
                    <td style={{ fontSize: 14, maxWidth: 360 }}>{readable(c.proposal.evidence[0])}</td>
                    <td>{practiceStatus(c)}</td>
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
        ) : (
          <Empty>No practice cards. Analyse a scenario, then "Review these as cards": you can approve or reject them, and nothing is applied.</Empty>
        )}
      </Panel>
    </div>
  );
}

type TabId = "queue" | "services" | "auto" | "health" | "reviewed" | "history" | "simulation";

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
  const practice = (intel.data?.status?.changes ?? []).filter((c) => c.proposal.source === "MOCK" && active(c));
  const assessments = (intel.data?.status?.assessments ?? []).filter((a) => a.source !== "MOCK");
  const stressed = assessments.filter((a) => a.health === "STRESSED" || a.health === "OVERLOADED").length;
  const live = (k: string) => conn.data?.[k]?.state === "LIVE_DATA";
  const collection = intel.data?.status?.collection;
  const rates = intel.data?.status?.rateEvidence ?? {};
  const all = intel.data?.status?.changes ?? [];
  // Rate and retry changes C4 made itself (approved by c4-auto, or undone by c4-auto-revert), newest first.
  const autos = changes.filter((c) => c.reviewer?.startsWith("c4-auto") && (c.proposal.action === "LEAKY_BUCKET" || c.proposal.action === "RETRY_BUDGET"))
    .sort((a, b) => b.proposal.generatedAt.localeCompare(a.proposal.generatedAt));
  const undone = Object.values(rates).reduce((n, r) => n + (r.checks?.filter((k) => k.result === "undone").length ?? 0), 0);
  // The other part of a joint recommendation (live or practice), and the deadline evidence of a chain card.
  const partnerOf = (c: Change) => {
    const j = intel.data?.status?.joints?.find((x) => x.parts.some((p) => p.proposalId === c.proposal.id));
    const other = j?.parts.find((p) => p.proposalId !== c.proposal.id);
    return other ? all.find((x) => x.proposal.id === other.proposalId) : undefined;
  };
  const dlOf = (c: Change) => (c.proposal.action === "CHAIN_DEADLINE" && c.proposal.source !== "MOCK" ? intel.data?.status?.deadlineEvidence?.[c.proposal.target] : undefined);

  return (
    <>
      <style>{C4_CSS}</style>
      <PageHeader title="Recommendations" description="Policy changes proposed from live telemetry. Rate and retry changes are applied automatically within safety limits and checked afterwards; deadline and joint changes wait for a reviewer." />
      <Alert>{intel.error ?? intel.data?.error}</Alert>
      {notice && <Alert kind="info">{notice} {!notice.startsWith("Simulation") && <a href="#/policies">View policies</a>}</Alert>}
      <div className="stack">
        <div className="kpis">
          <Kpi label="Engine" value={intel.data?.health?.status === "UP" ? <Status tone="green">Running</Status> : <Status tone="red">Unavailable</Status>} foot={collection?.enabled ? "Live collection" : "Collection disabled"} />
          <Kpi
            label="Telemetry"
            value={live("prometheus") && live("tempo") ? <Status tone="green">Live</Status> : <Status tone="amber">Partial</Status>}
            foot={`Metrics ${live("prometheus") ? "live" : "unavailable"} · Traces ${live("tempo") ? "live" : "unavailable"}`}
          />
          <Kpi label="Awaiting review" value={queue.length} />
          <Kpi label="Changed automatically" value={autos.length} foot={undone ? `${undone} undone` : "within safety limits"} />
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
            { id: "services", label: "Services now" },
            { id: "auto", label: `Automatic changes (${autos.length})` },
            { id: "health", label: "Health" },
            { id: "reviewed", label: `Reviewed (${reviewed.length})` },
            { id: "history", label: "Audit trail" },
            { id: "simulation", label: "Simulation" },
          ]}
          active={tab}
          onChange={setTab}
        />
        {tab === "queue" && <Queue changes={queue} partnerOf={partnerOf} dlOf={dlOf} onOpen={setOpen} />}
        {tab === "services" && (
          <Panel flush subtitle="Each service is compared with its own normal app time. Time waiting in our queue is measured separately, so a full queue is not mistaken for a slow app.">
            <ServicesNow rates={rates} />
          </Panel>
        )}
        {tab === "auto" && (
          <Panel flush subtitle="The rate limit is lowered to protect an overloaded service so it can recover, and raised again when the service is healthy. Every change is checked; if it does not help, it is undone.">
            <AutoChanges changes={autos} rates={rates} />
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
        {tab === "simulation" && <Simulation practice={practice} joints={intel.data?.status?.joints} onOpen={setOpen} onChanged={(m) => { setNotice(m); intel.reload(); }} />}
      </div>
      {open && (
        <Review
          change={open}
          partner={partnerOf(open)}
          dl={dlOf(open)}
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
