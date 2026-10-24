import { useEffect, useRef, useState, type ComponentType } from "react";
import { api, type User } from "./api";
import { Icons } from "./components/icons";
import { UserContext } from "./components/ui";
import { BackpressurePage } from "./pages/Backpressure";
import { FaultInjectionPage } from "./pages/FaultInjection";
import { InspectorPage } from "./pages/Inspector";
import { Login } from "./pages/Login";
import { OverviewPage } from "./pages/Overview";
import { PoliciesPage } from "./pages/Policies";
import { ProtocolToolsPage } from "./pages/ProtocolTools";
import { ProxiesPage } from "./pages/Proxies";
import { RecommendationsPage } from "./pages/Recommendations";
import { RolloutsPage } from "./pages/Rollouts";
import { ServicesPage } from "./pages/Services";
import { TopologyPage } from "./pages/Topology";
import { WorkloadsPage } from "./pages/Workloads";

type Route = { id: string; label: string; section: string; icon: ComponentType; page: ComponentType };

const ROUTES: Route[] = [
  { id: "overview", label: "Overview", section: "Monitor", icon: Icons.overview, page: OverviewPage },
  { id: "services", label: "Services", section: "Monitor", icon: Icons.services, page: ServicesPage },
  { id: "inspector", label: "Request Inspector", section: "Monitor", icon: Icons.inspector, page: InspectorPage },
  { id: "backpressure", label: "Backpressure", section: "Monitor", icon: Icons.backpressure, page: BackpressurePage },
  { id: "topology", label: "Topology", section: "Monitor", icon: Icons.topology, page: TopologyPage },
  { id: "policies", label: "Policies", section: "Configuration", icon: Icons.policies, page: PoliciesPage },
  { id: "rollouts", label: "Rollouts", section: "Configuration", icon: Icons.rollouts, page: RolloutsPage },
  { id: "recommendations", label: "Recommendations", section: "Configuration", icon: Icons.recommendations, page: RecommendationsPage },
  { id: "proxies", label: "Proxies", section: "Infrastructure", icon: Icons.proxies, page: ProxiesPage },
  { id: "workloads", label: "Workloads", section: "Infrastructure", icon: Icons.workloads, page: WorkloadsPage },
  { id: "faults", label: "Fault Injection", section: "Testing", icon: Icons.faults, page: FaultInjectionPage },
  { id: "tools", label: "Protocol Tools", section: "Testing", icon: Icons.tools, page: ProtocolToolsPage },
];

function useRoute() {
  const read = () => window.location.hash.replace(/^#\/?/, "").split("?")[0] || "overview";
  const [route, setRoute] = useState(read);
  useEffect(() => {
    const on = () => setRoute(read());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  return route;
}

function UserMenu({ user, onSignOut }: { user: User; onSignOut: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const on = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    document.addEventListener("mousedown", on);
    return () => document.removeEventListener("mousedown", on);
  }, []);
  return (
    <div className="user-menu" ref={ref}>
      <button className="user-btn" onClick={() => setOpen(!open)} aria-haspopup="menu" aria-expanded={open}>
        <span className="avatar">{user.Name.slice(0, 2)}</span>
        <span>{user.Name}</span>
      </button>
      {open && (
        <div className="menu" role="menu">
          <div className="menu-head">
            <div style={{ fontWeight: 600 }}>{user.Name}</div>
            <div className="muted" style={{ fontSize: 13.5 }}>{user.Role === "viewer" ? "Read-only access" : `${user.Role === "admin" ? "Administrator" : "Site reliability engineer"}`}</div>
          </div>
          <button role="menuitem" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

export function App() {
  const [user, setUser] = useState<User | null | undefined>(undefined);
  const route = useRoute();
  useEffect(() => {
    api<User>("GET", "/me").then(setUser, () => setUser(null));
    const onUnauthorized = () => setUser(null);
    window.addEventListener("rpe:unauthorized", onUnauthorized);
    return () => window.removeEventListener("rpe:unauthorized", onUnauthorized);
  }, []);
  if (user === undefined) return null;
  if (user === null) return <Login onLogin={setUser} />;

  const current = ROUTES.find((r) => r.id === route) ?? ROUTES[0];
  const Page = current.page;
  const sections = [...new Set(ROUTES.map((r) => r.section))];
  const signOut = async () => {
    await api("POST", "/logout").catch(() => undefined);
    setUser(null);
  };

  return (
    <UserContext.Provider value={user}>
      <div className="shell">
        <header className="topbar">
          <div className="logo">
            <span className="logo-mark">RPE</span>
            Resilience Control Plane
          </div>
          <div className="crumbs">
            {current.section} / <strong>{current.label}</strong>
          </div>
          <div className="spacer" />
          <div className="env" title="Cluster and application namespace">
            <span className="dot green" />
            kind-rpe · rpe-demo
          </div>
          <UserMenu user={user} onSignOut={signOut} />
        </header>
        <nav className="nav" aria-label="Main">
          {sections.map((s) => (
            <div key={s}>
              <div className="nav-section">{s}</div>
              {ROUTES.filter((r) => r.section === s).map((r) => (
                <a key={r.id} href={`#/${r.id}`} className={`nav-link ${r.id === current.id ? "active" : ""}`} aria-current={r.id === current.id ? "page" : undefined}>
                  <r.icon />
                  {r.label}
                </a>
              ))}
            </div>
          ))}
        </nav>
        <main className="content">
          <Page key={current.id} />
        </main>
      </div>
    </UserContext.Provider>
  );
}
