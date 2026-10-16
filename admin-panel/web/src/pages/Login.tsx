import { useState } from "react";
import { api, type User } from "../api";
import { Alert } from "../components/ui";

export function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const r = await api<{ username: string; role: User["Role"] }>("POST", "/login", { username, password });
      onLogin({ Name: r.username, Role: r.role });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="login-page">
      <form className="panel login-card" onSubmit={submit}>
        <div className="panel-body">
          <div className="logo" style={{ width: "auto" }}>
            <span className="logo-mark">RPE</span>
            Resilience Control Plane
          </div>
          <div>
            <h1>Sign in</h1>
            <p className="muted" style={{ margin: "4px 0 0" }}>Use your administrator account.</p>
          </div>
          <Alert>{error}</Alert>
          <label className="field">
            Username
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus />
          </label>
          <label className="field">
            Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
          </label>
          <button className="btn primary" style={{ justifyContent: "center" }} disabled={busy || !username}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </div>
      </form>
    </div>
  );
}
