import { useContext, useState } from "react";
import { api, useAction, useData } from "../api";
import { Ago, Alert, Button, Empty, Modal, PageHeader, Panel, Tabs, Tag, UserContext, type Tone } from "../components/ui";
import { EVENT_LABEL, eventSubject, type PoliciesData, type PolicyRow } from "../types";

const STATUS: Record<string, [Tone, string]> = {
  "approved-pending-rollout": ["neutral", "Not rolled out"],
  "rolling-out": ["blue", "Rolling out"],
  active: ["green", "Active"],
  superseded: ["neutral", "Superseded"],
  "rollout-failed": ["red", "Rollout failed"],
};

function specSummary(p: PolicyRow): string {
  // a joint decision: one version with a part per service
  if (p.spec.parts?.length) {
    return p.spec.parts.map((part) => `${part.scope.service}: ${specSummary({ ...p, scope: part.scope, spec: part.spec })}`).join(" | ");
  }
  const parts: string[] = [];
  // The retry budget is not shown: the sidecar does not enforce it yet (C2, planned for PP2).
  if (p.spec.deadlineMs) parts.push(`deadline ${p.spec.deadlineMs} ms`);
  if (p.spec.leakyBucket?.ratePerSec) parts.push(`rate ${p.spec.leakyBucket.ratePerSec}/s`);
  Object.entries(p.spec.routes ?? {}).forEach(([route, r]) => {
    // the route this policy is scoped to is already shown as "deadline"
    if (r.deadlineMs && !(route === p.scope.route && r.deadlineMs === p.spec.deadlineMs)) parts.push(`${route} ${r.deadlineMs} ms`);
  });
  return parts.join(" · ") || "—";
}

/**
 * Undo: an admin deliberately delivers an earlier approved version again (for example when an approved
 * recommendation turned out badly). It is not part of the normal flow, where every approved change is delivered
 * automatically. The admin who reverts is recorded as the requester of the rollout.
 */
function RevertButton({ p, current, onDone }: { p: PolicyRow; current?: PolicyRow; onDone: (msg: string) => void }) {
  const [confirming, setConfirming] = useState(false);
  const run = useAction(async () => {
    await api("POST", "/rollouts", { policyId: p.policyId, version: p.version, mode: "two-phase" });
    setConfirming(false);
    onDone(`Reverting ${p.policyId} to v${p.version}: it is being delivered again.`);
  });
  return (
    <>
      <Button size="sm" write onClick={() => setConfirming(true)}>
        Revert to this version
      </Button>
      {confirming && (
        <Modal
          title={`Revert ${p.policyId} to v${p.version}?`}
          onClose={() => setConfirming(false)}
          footer={
            <>
              <Button onClick={() => setConfirming(false)}>Cancel</Button>
              <Button variant="primary" write onClick={() => run.run()} busy={run.busy}>
                Revert
              </Button>
            </>
          }
        >
          <Alert>{run.error}</Alert>
          <p style={{ marginTop: 0 }}>
            This delivers <strong>v{p.version}</strong> ({specSummary(p)}) again to its sidecars
            {current ? (
              <>
                {" "}
                and replaces the version in force, <strong>v{current.version}</strong> ({specSummary(current)})
              </>
            ) : null}
            .
          </p>
          <p className="muted" style={{ marginBottom: 0 }}>
            Use it only to undo a change that turned out badly. You are recorded as the admin who reverted.
          </p>
        </Modal>
      )}
    </>
  );
}

export function PoliciesPage() {
  const { data, error, reload } = useData<PoliciesData>("/policies", 3000);
  const [tab, setTab] = useState<"policies" | "activity">("policies");
  const [notice, setNotice] = useState<string | null>(null);
  const isAdmin = useContext(UserContext)?.Role === "admin";

  return (
    <>
      <PageHeader
        title="Policies"
        description="Approved resilience policies. Values come from Component 4's recommendations after a human approved them in Recommendations; versions are immutable, and each change is a new version."
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
                          {isAdmin && p.status === "superseded" && (
                            <RevertButton
                              p={p}
                              current={data.policies.find((q) => q.policyId === p.policyId && q.status === "active")}
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
            <Empty>No policies yet. They appear when a Component 4 recommendation is approved in Recommendations.</Empty>
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
    </>
  );
}
