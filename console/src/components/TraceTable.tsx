import { Fragment, useState } from "react";
import type { ChainResult, Hop } from "../api";
import { Status, Tag, httpTone } from "./ui";

/** One row per service visit: the X-RPE-Context it received and whether every protocol rule held. */
export function TraceTable({ result }: { result: ChainResult }) {
  const [open, setOpen] = useState<number | null>(null);
  if (result.error) return <div className="alert error">{result.error}</div>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Service</th>
            <th>Status</th>
            <th className="num">Latency</th>
            <th className="num">Deadline left</th>
            <th className="num">Retry budget</th>
            <th>Cancellation</th>
            <th>Policy epoch</th>
            <th>Protocol checks</th>
          </tr>
        </thead>
        <tbody>
          {result.hops.map((h) => (
            <Fragment key={h.id}>
              <tr className="clickable" onClick={() => setOpen(open === h.id ? null : h.id)}>
                <td>
                  <span className="hop-cell" style={{ paddingLeft: Math.max(0, h.depth - 1) * 18 }}>
                    {h.depth > 0 && <span className="hop-branch">└</span>}
                    <strong>{h.service || h.name}</strong>
                  </span>
                </td>
                <td>
                  <Status tone={h.depth === 0 ? httpTone(result.status) : httpTone(h.status)}>
                    {h.depth === 0 ? result.status : h.status}
                    {h.outcome && h.outcome !== "ok" ? ` · ${h.outcome}` : ""}
                  </Status>
                </td>
                <td className="num">{h.depth === 0 ? result.ms : h.ms} ms</td>
                <td className="num">{h.fields ? `${h.fields.ttl} / ${h.fields.bud} ms` : "—"}</td>
                <td className="num">{h.fields?.rb ?? "—"}</td>
                <td>{h.fields ? h.fields.cs : "—"}</td>
                <td className="mono">{h.fields?.pe || "—"}</td>
                <td>
                  <Checks hop={h} />
                </td>
              </tr>
              {open === h.id && (
                <tr>
                  <td colSpan={8} style={{ background: "var(--surface-2)" }}>
                    {h.context ? <pre className="code">X-RPE-Context: {h.context}</pre> : <div className="muted">No RPE context reached this service.</div>}
                    <div className="row" style={{ marginTop: 8 }}>
                      {h.checks.map((c) => (
                        <Tag key={c.rule} tone={c.rule === "unknown-edge" ? "amber" : c.pass ? "green" : "red"} title={c.detail}>
                          {c.rule === "unknown-edge" ? "untracked hop" : c.rule}: {c.detail}
                        </Tag>
                      ))}
                    </div>
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Checks({ hop }: { hop: Hop }) {
  if (hop.checks.some((c) => c.rule === "unknown-edge")) return <Status tone="amber">Untracked (no context)</Status>;
  if (hop.checks.length === 0) return <span className="muted">No response</span>;
  const failed = hop.checks.filter((c) => !c.pass);
  if (failed.length) return <Status tone="red">{failed.map((c) => c.rule).join(", ")} failed</Status>;
  return <Status tone="green">{hop.checks.length} passed</Status>;
}
