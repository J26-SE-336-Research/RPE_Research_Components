import type { ServiceNode } from "./components/ServiceMap";

export type OverviewData = {
  services: ServiceNode[];
  edges: [string, string][];
  coverage: { injected: number; eligible: number };
  platform: Record<string, boolean>;
  error?: string;
};

export type ProxyStats = {
  requests: Record<string, Record<string, number>>;
  p50Ms: Record<string, number>;
  p95Ms: Record<string, number>;
  overheadAvgMs: number;
  inflight: number;
};

export type ProxyPolicy = { epochs: { epoch: string; version: number; hash: string; state: string }[]; activeEpoch: string };

export type Proxy = {
  pod: string;
  service: string;
  ip: string;
  health?: { status: string; role: string; activeEpoch: string };
  policy?: ProxyPolicy;
  stats: ProxyStats;
  error?: string;
};

export type Faults = { latencyMs?: number; jitterMs?: number; errorRate?: number; cpuBurnMs?: number; clockOffsetMs?: number };
export type ServiceState = { name: string; faults: Faults; counters: Record<string, number>; error?: string };

export type PolicyRow = {
  policyId: string;
  version: number;
  epoch: string;
  scope: { namespace?: string; service?: string; route?: string };
  spec: {
    deadlineMs?: number;
    retry?: { budget?: number; budgetRatio?: number };
    leakyBucket?: { ratePerSec?: number };
    routes?: Record<string, { deadlineMs?: number }>;
    parts?: { scope: { namespace?: string; service?: string; route?: string }; spec: PolicyRow["spec"] }[];
  };
  source: string;
  recommendationId?: string;
  approvedBy: string;
  approvedAt: string;
  status: string;
};
export type PolicyEvent = { id: string; at: string; type: string; fields: Record<string, string> };
export type PoliciesData = { policies: PolicyRow[]; events: PolicyEvent[] };

export type FleetPod = {
  service: string;
  pod: string;
  ready: boolean;
  containers: string[];
  initContainers: string[] | null;
  injected: boolean;
  sidecar: boolean;
  optedOut: boolean;
  sidecarUid: number | null;
  initExitCode: number | null;
  role: string;
  inboundMode: string;
};
export type FleetData = { namespaces: { name: string; injection: boolean }[]; pods: FleetPod[]; coverage: { eligible: number; injected: number } };

export type RolloutPod = { name: string; service: string; role: string; state: string; error?: string; hash?: string; verified?: boolean; confirmedAt?: string; part?: string; activeAt?: string };
export type PartPlan = { service: string; kind: "loosen" | "tighten" | "unchanged"; step: number; changes?: string[] };
export type Rollout = {
  metadata: { name: string; creationTimestamp: string };
  spec: { policyId: string; version: number; epoch: string; service: string; mode: string; previousEpoch?: string; requestedBy?: string; skewMs?: number; services?: string[]; order?: string };
  status: {
    phase?: string;
    message?: string;
    targetPods?: number;
    staged?: number;
    active?: number;
    startedAt?: string;
    stagedAt?: string;
    activatedAt?: string;
    completedAt?: string;
    convergenceMs?: number;
    drainMs?: number;
    inflightPrevious?: number;
    mixedPolicyRequests?: number;
    plan?: PartPlan[];
    step?: number;
    gateMs?: number;
    settled?: boolean;
    pods?: RolloutPod[];
  };
};

export type HeldEpoch = { epoch: string; version: number; hash: string; state: string; inflight: number };
export type AuditPod = {
  namespace: string;
  pod: string;
  service: string;
  role: string;
  phase: string;
  createdAt: string;
  owner?: string;
  sidecar: boolean;
  injected: boolean;
  optedOut: boolean;
  gap?: string;
  activeEpoch?: string;
  epochs?: HeldEpoch[];
  drift?: string[];
  error?: string;
};
export type FleetAudit = {
  auditedAt: string;
  namespaces: { name: string; eligible: number; injected: number; gaps: number }[];
  coverage: { eligible: number; injected: number; ratio: number };
  gaps: AuditPod[] | null;
  pods: AuditPod[] | null;
  policies: { policyId: string; epoch: string; service: string; hash: string; driftPods: number }[] | null;
  driftPods: number;
};

export const EVENT_LABEL: Record<string, string> = {
  "policy-approved": "Policy approved",
  "rollout-started": "Rollout started",
  "rollout-staged": "Version staged on all proxies",
  "rollout-activated": "Version activated",
  "rollout-complete": "Rollout complete",
  "rollout-failed": "Rollout failed",
  "drift-repaired": "Drift repaired",
  "rollout-settled": "Rollout measured",
  "coverage-gap": "Pod without proxy",
  "coverage-reinjected": "Pod recreated for injection",
  "effective-restored": "Policy cache rebuilt from PostgreSQL",
};

export function eventSubject(e: PolicyEvent) {
  return e.fields.epoch ?? e.fields.rollout ?? e.fields.policyId ?? "";
}

export const total = (m?: Record<string, number>) => Object.values(m ?? {}).reduce((a, b) => a + b, 0);
export const failed = (m?: Record<string, number>) => total(m) - (m?.ok ?? 0);

export type ConsistencyConfig = {
  baseDeadlineMs: number;
  newDeadlineMs: number;
  baseRate: number;
  newRate: number;
  burst: number;
  burstEveryMs: number;
  warmupMs: number;
  tailMs: number;
};
export type ConsistencySample = { atMs: number; ms: number; status: number; cs: string; bud: number; pe: string };
export type ConsistencyRun = {
  id: string;
  mode: string;
  order?: string;
  skewMs: number;
  config: ConsistencyConfig;
  state: "running" | "done" | "failed";
  stage: string;
  error?: string;
  startedAt: string;
  finishedAt?: string;
  requestedBy: string;
  rollout?: string;
  epoch?: string;
  previousEpoch?: string;
  requests: number;
  during: number;
  expired: number;
  expiredOldDeadline: number;
  expiredBefore: number;
  failed: number;
  mixedPolicyRequests: number;
  convergenceMs: number;
  gateMs: number;
  drainMs: number;
  completedAtMs: number;
  arrivals: { service: string; pod: string; part: string; atMs: number }[];
  plan?: PartPlan[];
  samples: ConsistencySample[];
};
export type ConsistencyState = { busy: boolean; defaults: ConsistencyConfig; runs: ConsistencyRun[] };

// ---------- C1 protocol service ----------
export type AttributedHop = {
  index: number;
  parent: number;
  depth: number;
  service: string;
  serviceId: number;
  decision: string;
  label: string;
  startMs: number;
  elapsedMs: number;
  selfMs: number;
  unknown: boolean;
};
export type Attribution = {
  rid: string;
  route: string;
  budgetMs: number;
  epoch?: string;
  status: number;
  at: string;
  trail: string;
  bytes: number;
  incomplete: boolean;
  totalMs: number;
  late: boolean;
  bottleneck: number;
  hops: AttributedHop[];
};
export type TrailSummary = {
  rid: string;
  route: string;
  at: string;
  status: number;
  hops: number;
  bytes: number;
  incomplete: boolean;
  late: boolean;
  totalMs: number;
  budgetMs: number;
  bottleneck: string;
};
export type TrailList = { trails: TrailSummary[]; received: number; rejected: number; held: number };
export type TopologyNode = { service: string; serviceId: number; hops: number; p50Ms: number; p95Ms: number; selfP50Ms: number; unknown: boolean; decisions: Record<string, number> };
export type TopologyEdge = { from: string; to: string; calls: number; p50Ms: number; p95Ms: number; unknown: boolean; decisions: Record<string, number> };
export type Topology = {
  windowSec: number;
  trails: number;
  incomplete: number;
  avgBytes: number;
  maxBytes: number;
  registryVersion: number;
  routes: string[];
  nodes: TopologyNode[];
  edges: TopologyEdge[];
};
export type Comparison = { truth: string[]; rebuilt: string[]; matched: string[]; missing: string[]; extra: string[]; precision: number; recall: number; note: string };
export type TopologyData = { topology: Topology; comparison: Comparison };

export type Verdict = { impl: string; valid: boolean; fields?: Record<string, string>; canonical?: string; error?: { code: string; rule?: string; detail?: string }; failure?: string };
export type CompareResult = { kind: string; go: Verdict; java: Verdict; agree: boolean };
export type MatrixCase = { name: string; go: boolean; java: boolean | null; goDetail?: string; javaDetail?: string };
export type Matrix = { at: string; files: { file: string; description: string; cases: MatrixCase[] }[]; total: number; goPassed: number; javaPassed: number; javaError?: string };

export const DECISIONS: Record<string, string> = {
  F: "served",
  E: "refused before the application",
  X: "served late",
  C: "skipped (cancelled)",
  R: "retry served",
  A: "active",
  P: "partial",
  O: "shed (overload)",
  U: "no RPE (unknown hop)",
};
