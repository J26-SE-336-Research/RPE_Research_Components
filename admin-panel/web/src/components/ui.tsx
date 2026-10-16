import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { User } from "../api";

export const UserContext = createContext<User | undefined>(undefined);

/** True when the signed-in role may change the system (admin, sre). */
export function useCanWrite() {
  const u = useContext(UserContext);
  return u?.Role === "admin" || u?.Role === "sre";
}

export function PageHeader(props: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div>
        <h1>{props.title}</h1>
        {props.description && <p>{props.description}</p>}
      </div>
      {props.actions && <div className="actions">{props.actions}</div>}
    </div>
  );
}

export function Panel(props: { title?: string; subtitle?: ReactNode; actions?: ReactNode; flush?: boolean; children?: ReactNode }) {
  return (
    <section className="panel">
      {(props.title || props.actions) && (
        <div className="panel-head">
          <div>
            {props.title && <h2>{props.title}</h2>}
            {props.subtitle && <div className="sub">{props.subtitle}</div>}
          </div>
          {props.actions && <div className="actions">{props.actions}</div>}
        </div>
      )}
      <div className={`panel-body ${props.flush ? "flush" : ""}`}>{props.children}</div>
    </section>
  );
}

export function Kpi(props: { label: string; value: ReactNode; foot?: ReactNode }) {
  return (
    <div className="panel kpi">
      <div className="label">{props.label}</div>
      <div className="value">{props.value}</div>
      {props.foot && <div className="foot">{props.foot}</div>}
    </div>
  );
}

export type Tone = "green" | "amber" | "red" | "blue" | "neutral";

export function Status({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className="status">
      <span className={`dot ${tone}`} />
      {children}
    </span>
  );
}

export function Tag({ tone = "neutral", children, title }: { tone?: Tone; children: ReactNode; title?: string }) {
  return (
    <span className={`tag ${tone}`} title={title}>
      {children}
    </span>
  );
}

export function httpTone(code: number): Tone {
  if (!code) return "red";
  if (code < 300) return "green";
  if (code < 500) return "amber";
  return "red";
}

export function Button(props: {
  onClick?: () => void;
  children: ReactNode;
  variant?: "primary" | "danger" | "ghost";
  size?: "sm";
  disabled?: boolean;
  busy?: boolean;
  write?: boolean;
  type?: "button" | "submit";
}) {
  const canWrite = useCanWrite();
  const blocked = props.write && !canWrite;
  return (
    <button
      type={props.type ?? "button"}
      className={`btn ${props.variant ?? ""} ${props.size ?? ""}`}
      onClick={props.onClick}
      disabled={props.disabled || props.busy || blocked}
      title={blocked ? "Read-only access" : undefined}
    >
      {props.busy ? "Working…" : props.children}
    </button>
  );
}

export function Alert({ kind = "error", children }: { kind?: "error" | "info"; children?: ReactNode }) {
  if (!children) return null;
  return <div className={`alert ${kind}`}>{children}</div>;
}

export function Field(props: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="field">
      {props.label}
      {props.children}
      {props.hint && <span className="muted" style={{ fontWeight: 400 }}>{props.hint}</span>}
    </label>
  );
}

export function KV({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="kv">
      {items.map(([k, v]) => (
        <div key={k} style={{ display: "contents" }}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Tabs<T extends string>(props: { tabs: { id: T; label: string }[]; active: T; onChange: (t: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {props.tabs.map((t) => (
        <button key={t.id} role="tab" aria-selected={props.active === t.id} className={`tab ${props.active === t.id ? "active" : ""}`} onClick={() => props.onChange(t.id)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

function useEscape(onClose: () => void) {
  useEffect(() => {
    const on = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", on);
    return () => window.removeEventListener("keydown", on);
  }, [onClose]);
}

export function Modal(props: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEscape(props.onClose);
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && props.onClose()}>
      <div className="modal" role="dialog" aria-label={props.title}>
        <div className="modal-head">
          <h3>{props.title}</h3>
          <button className="close" onClick={props.onClose} aria-label="Close">
            ×
          </button>
        </div>
        <div className="modal-body">{props.children}</div>
        {props.footer && <div className="modal-foot">{props.footer}</div>}
      </div>
    </div>
  );
}

export function Drawer(props: { title: string; subtitle?: ReactNode; onClose: () => void; children: ReactNode }) {
  useEscape(props.onClose);
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && props.onClose()}>
      <aside className="drawer" role="dialog" aria-label={props.title}>
        <div className="drawer-head">
          <div>
            <h3>{props.title}</h3>
            {props.subtitle && <div className="muted" style={{ fontSize: 14 }}>{props.subtitle}</div>}
          </div>
          <button className="close" onClick={props.onClose} aria-label="Close">
            ×
          </button>
        </div>
        <div className="drawer-body">{props.children}</div>
      </aside>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/** "3m ago" style relative time. */
export function Ago({ at }: { at: string }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => tick((x) => x + 1), 30000);
    return () => window.clearInterval(id);
  }, []);
  const s = Math.max(0, (Date.now() - new Date(at).getTime()) / 1000);
  const text = s < 60 ? "just now" : s < 3600 ? `${Math.floor(s / 60)}m ago` : s < 86400 ? `${Math.floor(s / 3600)}h ago` : `${Math.floor(s / 86400)}d ago`;
  return <span title={new Date(at).toLocaleString()}>{text}</span>;
}

export const fmtMs = (n?: number) => (n === undefined || Number.isNaN(n) ? "—" : n < 10 ? `${n.toFixed(1)} ms` : `${Math.round(n)} ms`);
export const fmtNum = (n?: number) => (n === undefined ? "—" : Intl.NumberFormat().format(n));
