import type { Topology } from "../types";

const W = 196;
const H = 64;
const COL = 270;
const ROW = 92;

/**
 * The service graph rebuilt from in-band trails: columns are call depth, each edge carries its call count and p50.
 * A hop that does not speak RPE is drawn dashed (unknown edge): the trail knows it was called but not what it did.
 */
export function TrailGraph({ t }: { t: Topology }) {
  const incoming = new Map<string, number>();
  t.edges.forEach((e) => incoming.set(e.to, (incoming.get(e.to) ?? 0) + 1));
  const depth = new Map<string, number>();
  const roots = t.nodes.filter((n) => !incoming.has(n.service)).map((n) => n.service);
  const queue = [...roots];
  roots.forEach((r) => depth.set(r, 0));
  while (queue.length) {
    const cur = queue.shift()!;
    for (const e of t.edges.filter((x) => x.from === cur)) {
      if (!depth.has(e.to)) {
        depth.set(e.to, (depth.get(cur) ?? 0) + 1);
        queue.push(e.to);
      }
    }
  }
  t.nodes.forEach((n) => depth.has(n.service) || depth.set(n.service, 0));
  const columns: string[][] = [];
  t.nodes.forEach((n) => {
    const d = depth.get(n.service) ?? 0;
    (columns[d] ??= []).push(n.service);
  });
  const rows = Math.max(1, ...columns.map((c) => c?.length ?? 0));
  const height = rows * ROW + 16;
  const pos = new Map<string, [number, number]>();
  columns.forEach((col, d) =>
    col.forEach((name, i) => {
      const offset = ((rows - col.length) * ROW) / 2;
      pos.set(name, [8 + d * COL, 8 + offset + i * ROW]);
    }),
  );
  const width = Math.max(1, columns.length) * COL - (COL - W) + 16;
  const byName = new Map(t.nodes.map((n) => [n.service, n]));
  return (
    <svg className="tgraph" viewBox={`0 0 ${width} ${height}`} width="100%" style={{ maxWidth: width, display: "block", margin: "0 auto" }} role="img" aria-label="Topology rebuilt from trails">
      <defs>
        <marker id="tarr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M0 0L10 5L0 10z" fill="var(--border-strong)" />
        </marker>
      </defs>
      {t.edges.map((e) => {
        const a = pos.get(e.from);
        const b = pos.get(e.to);
        if (!a || !b) return null;
        const [x1, y1] = [a[0] + W, a[1] + H / 2];
        const [x2, y2] = [b[0] - 2, b[1] + H / 2];
        return (
          <g key={e.from + e.to}>
            <path className={`edge ${e.unknown ? "unknown" : ""}`} markerEnd="url(#tarr)" d={`M ${x1} ${y1} C ${x1 + 40} ${y1}, ${x2 - 40} ${y2}, ${x2} ${y2}`} />
            <text className="elabel" x={(x1 + x2) / 2} y={(y1 + y2) / 2 - 6} textAnchor="middle">
              {e.calls}× · p50 {e.p50Ms} ms
            </text>
          </g>
        );
      })}
      {[...pos.entries()].map(([name, [x, y]]) => {
        const n = byName.get(name);
        return (
          <g key={name} className={`node ${n?.unknown ? "unknown" : ""}`} transform={`translate(${x},${y})`}>
            <rect width={W} height={H} rx={8} />
            <text x={14} y={26} fontWeight={600} style={{ fontSize: 15.5 }}>
              {name}
            </text>
            <text x={14} y={47} style={{ fill: "var(--muted)", fontSize: 13 }}>
              {n?.unknown ? "no RPE: unknown hop" : `${n?.hops ?? 0} hops · own p50 ${n?.selfP50Ms ?? 0} ms`}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
