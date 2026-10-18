import { useState } from "react";
import { api, useAction, useData } from "../api";
import { Ago, Alert, Button, Empty, Field, Modal, PageHeader, Panel, Tabs, Tag, type Tone } from "../components/ui";
import { EVENT_LABEL, eventSubject, type PoliciesData, type PolicyRow } from "../types";

const SERVICES = ["gateway", "order-svc", "inventory-svc", "payment-svc", "loyalty-svc", "fraud-check-svc", "shipping-svc"];

const STATUS: Record<string, [Tone, string]> = {
  "approved-pending-rollout": ["neutral", "Not rolled out"],
  "rolling-out": ["blue", "Rolling out"],
  active: ["green", "Active"],
  superseded: ["neutral", "Superseded"],
  "rollout-failed": ["red", "Rollout failed"],
};

type Created = { epoch: string; notification: { channel: string; payload: string } | null; rollout?: { metadata?: { name: string } } };

function specSummary(p: PolicyRow) {
  const parts: string[] = [];
  if (p.spec.deadlineMs) parts.push(`deadline ${p.spec.deadlineMs} ms`);
  if (p.spec.retry?.budget !== undefined) parts.push(`retry budget ${p.spec.retry.budget}`);
  if (p.spec.retry?.budgetRatio) parts.push(`retry ratio ${p.spec.retry.budgetRatio}`);
  if (p.spec.leakyBucket?.ratePerSec) parts.push(`rate ${p.spec.leakyBucket.ratePerSec}/s`);
  Object.entries(p.spec.routes ?? {}).forEach(([route, r]) => {
    // the route this policy is scoped to is already shown as "deadline"
    if (r.deadlineMs && !(route === p.scope.route && r.deadlineMs === p.spec.deadlineMs)) parts.push(`${route} ${r.deadlineMs} ms`);
  });
  return parts.join(" · ") || "—";
}

type Control = "chain-deadline" | "rate-limit" | "retry-ratio";
const CONTROL_LABEL: Record<Control, string> = { "chain-deadline": "Chain deadline (gateway)", "rate-limit": "Rate limit (service)", "retry-ratio": "Retry ratio (service)" };

function NewPolicy({ onClose, onCreated }: { onClose: () => void; onCreated: (c: Created) => void }) {
  const [f, setF] = useState({ control: "rate-limit" as Control, policyId: "", service: "payment-svc", route: "GET /api/orders", value: 150, rollout: "two-phase" });
  const create = useAction(async () => {
    const body: Record<string, unknown> = { control: f.control, rollout: f.rollout };
    if (f.policyId) body.policyId = f.policyId;
    if (f.control === "chain-deadline") {
      body.route = f.route;
      body.deadlineMs = f.value;
    } else {
      body.service = f.service;
      if (f.control === "rate-limit") body.ratePerSec = f.value;
      else body.retryRatio = f.value;
    }
    const r = await api<Created>("POST", "/policies", body);
    onCreated(r);
    onClose();
    return r;
  });
  const unit = f.control === "chain-deadline" ? "ms" : f.control === "rate-limit" ? "requests per second" : "0 to 1";
  return (
    <Modal
      title="New policy version"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" write onClick={() => create.run()} busy={create.busy}>
            {f.rollout ? "Approve and deliver" : "Approve"}
          </Button>
        </>
      }
    >
      <Alert>{create.error}</Alert>
      <div className="form-grid">
        <Field label="Control" hint={f.control === "chain-deadline" ? "Delivered to the gateway sidecars only" : "Delivered to the target service's sidecars only"}>
          <select
            value={f.control}
            onChange={(e) => {
              const control = e.target.value as Control;
              setF({ ...f, control, value: control === "chain-deadline" ? 1000 : control === "rate-limit" ? 150 : 0.1 });
            }}
          >
            {(Object.keys(CONTROL_LABEL) as Control[]).map((k) => (
              <option key={k} value={k}>
                {CONTROL_LABEL[k]}
              </option>
            ))}
          </select>
        </Field>
        {f.control === "chain-deadline" ? (
          <Field label="Route" hint="Budget the gateway stamps for this route">
            <input value={f.route} onChange={(e) => setF({ ...f, route: e.target.value })} />
          </Field>
        ) : (
          <Field label="Target service">
            <select value={f.service} onChange={(e) => setF({ ...f, service: e.target.value })}>
              {SERVICES.filter((s) => s !== "gateway").map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </Field>
        )}
        <Field label={`New value (${unit})`}>
          <input type="number" min={f.control === "retry-ratio" ? 0 : 1} max={f.control === "retry-ratio" ? 1 : undefined} step={f.control === "retry-ratio" ? 0.05 : 1} value={f.value} onChange={(e) => setF({ ...f, value: Number(e.target.value) })} />
        </Field>
        <Field label="Policy name" hint="Leave empty to extend the service's current policy">
          <input value={f.policyId} placeholder="current policy" onChange={(e) => setF({ ...f, policyId: e.target.value.toLowerCase() })} />
        </Field>
        <Field label="Delivery">
          <select value={f.rollout} onChange={(e) => setF({ ...f, rollout: e.target.value })}>
            <option value="two-phase">Verify, then activate (recommended)</option>
            <option value="naive">Immediate</option>
            <option value="">Don't deliver yet</option>
          </select>
        </Field>
      </div>
      <p className="muted" style={{ marginBottom: 0 }}>
        You are recorded as the approver. Each change publishes a new immutable version; the service's other settings are kept.
      </p>
    </Modal>
  );
}

function RolloutButton({ p, onDone }: { p: PolicyRow; onDone: (msg: string) => void }) {
  const run = useAction(async () => {
    await api("POST", "/rollouts", { policyId: p.policyId, version: p.version, mode: "two-phase" });
    onDone(`Rollout of ${p.epoch} started.`);
  });
  const label = p.status === "superseded" ? "Restore" : p.status === "rollout-failed" ? "Retry" : "Roll out";
  return (
    <Button size="sm" write onClick={() => run.run()} busy={run.busy}>
      {label}
    </Button>
  );
}

export function PoliciesPage() {
  const { data, error, reload } = useData<PoliciesData>("/policies", 3000);
  const [tab, setTab] = useState<"policies" | "activity">("policies");
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <>
      <PageHeader
        title="Policies"
        description="Approved resilience policies. Versions are immutable; changing a policy publishes a new version."
        actions={
          <Button variant="primary" write onClick={() => setCreating(true)}>
            New policy
          </Button>
        }
      />
      <Alert>{error}</Alert>
      {notice && (
        <Alert kind="info">
          {notice} <a href="#/rollouts">View rollouts</a>
        </Alert>
      )}
      <Tabs
        tabs={[
          { id: "policies", label: `Versions${data ? ` (${data.policies.length})` : ""}` },
          { id: "activity", label: "Activity" },
        ]}
        active={tab}
        onChange={setTab}
      />
      {tab === "policies" ? (
        <Panel flush>
          {data?.policies.length ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Policy</th>
                    <th>Version</th>
                    <th>Scope</th>
                    <th>Settings</th>
                    <th>Approved by</th>
                    <th>Approved</th>
                    <th>Status</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {data.policies.map((p) => {
                    const [tone, label] = STATUS[p.status] ?? ["neutral", p.status];
                    return (
                      <tr key={p.epoch}>
                        <td>
                          <strong>{p.policyId}</strong>
                        </td>
                        <td>v{p.version}</td>
                        <td>
                          {p.scope.service ?? "—"}
                          {p.scope.route && <div className="muted mono">{p.scope.route}</div>}
                        </td>
                        <td>{specSummary(p)}</td>
                        <td>
                          {p.approvedBy}
                          {p.source === "c4-recommendation" && (
                            <div>
                              <Tag tone="blue" title={p.recommendationId}>
                                Recommendation
                              </Tag>
                            </div>
                          )}
                        </td>
                        <td className="muted nowrap">
                          <Ago at={p.approvedAt} />
                        </td>
                        <td>
                          <Tag tone={tone}>{label}</Tag>
                        </td>
                        <td style={{ textAlign: "right" }}>
                          {p.status !== "active" && p.status !== "rolling-out" && (
                            <RolloutButton
                              p={p}
                              onDone={(m) => {
                                setNotice(m);
                                reload();
                              }}
                            />
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <Empty>No policies yet. Create one to get started.</Empty>
          )}
        </Panel>
      ) : (
        <Panel flush>
          {data?.events.length ? (
            <table>
              <thead>
                <tr>
                  <th>Event</th>
                  <th>Subject</th>
                  <th>Details</th>
                  <th>When</th>
                </tr>
              </thead>
              <tbody>
                {data.events.map((e) => (
                  <tr key={e.id}>
                    <td>
                      <Tag tone={e.type.includes("failed") ? "red" : e.type.includes("drift") ? "amber" : "green"}>{EVENT_LABEL[e.type] ?? e.type}</Tag>
                    </td>
                    <td className="mono">{eventSubject(e)}</td>
                    <td className="muted">
                      {[e.fields.approvedBy && `by ${e.fields.approvedBy}`, e.fields.pod, e.fields.targets && `${e.fields.targets} proxies`, e.fields.mode, e.fields.message]
                        .filter(Boolean)
                        .join(" · ")}
                    </td>
                    <td className="muted nowrap">
                      <Ago at={e.at} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <Empty>No activity.</Empty>
          )}
        </Panel>
      )}
      {creating && (
        <NewPolicy
          onClose={() => setCreating(false)}
          onCreated={(c) => {
            setNotice(c.rollout ? `Published ${c.epoch} and started its rollout.` : `Published ${c.epoch}.`);
            reload();
          }}
        />
      )}
    </>
  );
}
