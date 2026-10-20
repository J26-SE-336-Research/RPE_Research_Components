-- Sidecar-initiated acknowledgements (contract A3.5 v0.3): the state and the content hash the proxy computed
-- from the policy it holds. `hash` stays the approved spec's hash, so a delivery is verified when they match.
alter table policy.deliveries add column if not exists confirmed_state text;
alter table policy.deliveries add column if not exists confirmed_hash text;
alter table policy.deliveries add column if not exists confirmed_at timestamptz;

-- Consistent-rollout measurements per rollout (experiment E4: naive vs two-phase).
alter table policy.rollouts add column if not exists skew_ms int;
alter table policy.rollouts add column if not exists mixed_policy_requests bigint;
alter table policy.rollouts add column if not exists convergence_ms bigint;
alter table policy.rollouts add column if not exists drain_ms bigint;
