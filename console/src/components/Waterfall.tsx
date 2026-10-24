import type { Attribution } from "../types";
import { Tag } from "./ui";

/**
 * Per-hop deadline use of one request, rebuilt from its X-RPE-Trail: where each hop started in the chain budget, how
 * long it took (including its callees) and its own share. The dashed line is the chain deadline.
 */
export function Waterfall({ a }: { a: Attribution }) {
  const scale = Math.max(a.budgetMs, a.totalMs, 1) * 1.05;
  const pct = (ms: number) => `${Math.min(100, (ms / scale) * 100)}%`;
  return (
    <div className="wf" role="table" aria-label="Deadline used per hop">
      <div className="wf-head">Hop</div>
      <div className="wf-head">
        0 ms … deadline {a.budgetMs} ms <span style={{ color: "var(--red)" }}>┆</span>
      </div>
      <div className="wf-head" style={{ textAlign: "right" }}>
        took / own time
      </div>
      {a.hops.map((h) => {
        const hot = h.index === a.bottleneck;
        const late = h.decision === "X" || h.startMs + h.elapsedMs > a.budgetMs;
        return (
          <div key={h.index} style={{ display: "contents" }} role="row">
            <div className="wf-name" style={{ paddingLeft: h.depth * 14 }} title={h.label}>
              {h.depth > 0 && <span className="hop-branch">└</span>}
              <strong>{h.service}</strong>
              {h.unknown ? (
                <Tag tone="amber">no RPE</Tag>
              ) : h.decision !== "F" ? (
                <Tag tone={h.decision === "X" ? "amber" : h.decision === "E" ? "red" : "neutral"}>{h.label}</Tag>
              ) : null}
            </div>
            <div className="wf-track">
              <div className="wf-deadline" style={{ left: pct(a.budgetMs) }} title={`deadline ${a.budgetMs} ms`} />
              <div
                className={`wf-bar ${h.unknown ? "unknown" : late ? "late" : ""} ${hot ? "hot" : ""}`}
                style={{ left: pct(h.startMs), width: pct(Math.max(h.elapsedMs, 0.5)) }}
                title={`${h.service}: received at ${h.startMs} ms into the budget, took ${h.elapsedMs} ms (own ${h.selfMs} ms)`}
              />
            </div>
            <div className="wf-num">
              {h.elapsedMs} / <strong>{h.selfMs}</strong> ms
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function WaterfallSummary({ a }: { a: Attribution }) {
  const b = a.hops[a.bottleneck - 1];
  return (
    <p className="muted" style={{ margin: "0 0 14px" }}>
      {b ? (
        <>
          <strong style={{ color: "var(--text)" }}>{b.service}</strong> used the most of the deadline ({b.selfMs} ms of its own time).{" "}
        </>
      ) : null}
      The chain took {a.totalMs} ms of a {a.budgetMs} ms budget{a.late ? ", so it finished late (signalled as cs=expired, never rejected)" : ""}.
      {a.incomplete ? " The trail is marked incomplete: a hop on the path does not speak RPE." : ""} Trail size: {a.bytes} bytes.
    </p>
  );
}
