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

function NewPolicy({ onClose, onCreated }: { onClose: () => void; onCreated: (c: Created) => void }) {
  const [f, setF] = useState({ policyId: "", service: "payment-svc", route: "GET /api/orders", deadlineMs: 1000, retryBudget: 2, retryRatio: 0.1, ratePerSec: 100, rollout: "two-phase" });
  const create = useAction(async () => {
    const r = await api<Created>("POST", "/policies", { ...f, policyId: f.policyId || `${f.service}-resilience` });
    onCreated(r);
    onClose();
    return r;
  });
  const num = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: Number(e.target.value) });
  return (
    <Modal
      title="New policy"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" write onClick={() => create.run()} busy={create.busy}>
            {f.rollout ? "Approve and roll out" : "Approve"}
          </Button>
        </>
      }
    >
      <Alert>{create.error}</Alert>
      <div className="form-grid">
        <Field label="Policy name" hint="Lowercase, digits and dashes">
          <input value={f.policyId} placeholder={`${f.service}-resilience`} onChange={(e) => setF({ ...f, policyId: e.target.value.toLowerCase() })} />
        </Field>
        <Field label="Service">
          <select value={f.service} onChange={(e) => setF({ ...f, service: e.target.value })}>
            {SERVICES.map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Route">
          <input value={f.route} onChange={(e) => setF({ ...f, route: e.target.value })} />
        </Field>
        <Field label="Deadline (ms)">
          <input type="number" min={1} value={f.deadlineMs} onChange={num("deadlineMs")} />
        </Field>
        <Field label="Retry budget">
          <input type="number" min={0} max={255} value={f.retryBudget} onChange={num("retryBudget")} />
        </Field>
        <Field label="Retry ratio">
          <input type="number" min={0} max={1} step={0.05} value={f.retryRatio} onChange={num("retryRatio")} />
        </Field>
        <Field label="Rate limit (req/s)">
          <input type="number" min={1} value={f.ratePerSec} onChange={num("ratePerSec")} />
        </Field>
        <Field label="Rollout">
          <select value={f.rollout} onChange={(e) => setF({ ...f, rollout: e.target.value })}>
            <option value="two-phase">Two-phase (recommended)</option>
            <option value="naive">Immediate</option>
            <option value="">Don't roll out yet</option>
          </select>
        </Field>
      </div>
      <p className="muted" style={{ marginBottom: 0 }}>
        You are recorded as the approver. Each change publishes a new immutable version.
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
