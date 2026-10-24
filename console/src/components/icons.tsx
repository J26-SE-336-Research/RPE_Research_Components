// Minimal outline icon set (24×24, stroke-based).
const P = (d: string) => () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d={d} />
  </svg>
);

export const Icons = {
  overview: P("M3 13h8V3H3v10Zm10 8h8V11h-8v10ZM3 21h8v-6H3v6Zm10-18v6h8V3h-8Z"),
  services: P("M4 6h16M4 12h16M4 18h16M7 6v.01M7 12v.01M7 18v.01"),
  inspector: P("M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm10 2-4.35-4.35M8 11h6M11 8v6"),
  policies: P("M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9l-6-6Zm0 0v6h6M8 13h8M8 17h5"),
  recommendations: P("M9 18h6M10 22h4M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2Z"),
  rollouts: P("M12 2v13M7 7l5-5 5 5M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"),
  proxies: P("M5 12h14M12 5l7 7-7 7M3 5v14"),
  workloads: P("M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16ZM3.3 7 12 12l8.7-5M12 22V12"),
  faults: P("M13 2 3 14h9l-1 8 10-12h-9l1-8Z"),
  backpressure: P("M12 14l4-4M3.34 19a10 10 0 1 1 17.32 0"),
  topology: P("M6 3a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm12 0a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM12 15a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM8.5 7.5l2.5 8M15.5 7.5l-2.5 8M9 6h6"),
  tools: P("M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4 2.6-2.6Z"),
};
