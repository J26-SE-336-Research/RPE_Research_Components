export type ServiceNode = { name: string; pod: string; ready: boolean; sidecar: boolean; rpe: boolean; restarts: number };

const POS: Record<string, [number, number]> = {
  gateway: [8, 150],
  "order-svc": [228, 150],
  "inventory-svc": [470, 8],
  "payment-svc": [470, 102],
  "loyalty-svc": [470, 196],
  "legacy-svc": [470, 290],
  "shipping-svc": [712, 290],
  "fraud-check-svc": [712, 8],
};
const W = 196;
const H = 60;

export function serviceTone(s?: ServiceNode) {
  if (!s || !s.ready) return "red";
  if (!s.rpe) return "neutral";
  return s.sidecar ? "green" : "amber";
}

export function ServiceMap({ services, edges, onSelect }: { services: ServiceNode[]; edges: [string, string][]; onSelect?: (name: string) => void }) {
  const byName = Object.fromEntries(services.map((s) => [s.name, s]));
  const dot = { green: "var(--green)", amber: "var(--amber)", red: "var(--red)", neutral: "var(--muted)" } as const;
  return (
    <svg className="map" viewBox="0 0 916 358" width="100%" style={{ maxWidth: 916, display: "block", margin: "0 auto" }} role="img" aria-label="Service map">
      <defs>
        <marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M0 0L10 5L0 10z" fill="var(--border-strong)" />
        </marker>
      </defs>
      {edges.map(([a, b]) => {
        const [x1, y1] = POS[a];
        const [x2, y2] = POS[b];
        return (
          <path key={a + b} className="edge" markerEnd="url(#arr)"
            d={`M ${x1 + W} ${y1 + H / 2} C ${x1 + W + 28} ${y1 + H / 2}, ${x2 - 28} ${y2 + H / 2}, ${x2 - 2} ${y2 + H / 2}`} />
        );
      })}
      {Object.entries(POS).map(([name, [x, y]]) => {
        const s = byName[name];
        const tone = serviceTone(s);
        const sub = !s ? "not deployed" : !s.ready ? "not ready" : !s.rpe ? "unmanaged" : s.sidecar ? "proxy injected" : "proxy missing";
        return (
          <g key={name} className="node" transform={`translate(${x},${y})`} style={{ cursor: onSelect ? "pointer" : undefined }} onClick={() => onSelect?.(name)}>
            <rect width={W} height={H} />
            <circle cx={18} cy={23} r={5.5} fill={dot[tone]} />
            <text x={32} y={28} fontWeight={600} style={{ fontSize: 17 }}>{name}</text>
            <text x={32} y={48} style={{ fill: "var(--muted)", fontSize: 14.5 }}>{sub}</text>
          </g>
        );
      })}
    </svg>
  );
}
