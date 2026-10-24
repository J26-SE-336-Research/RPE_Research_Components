-- Acknowledgements the sidecar sends itself (contract A3.5): its state and the hash it computed from the policy it
-- holds. `hash` stays the approved spec's hash, so a delivery counts as verified when the two match.
alter table policy.deliveries add column if not exists confirmed_state text;
alter table policy.deliveries add column if not exists confirmed_hash text;
alter table policy.deliveries add column if not exists confirmed_at timestamptz;

-- Measurements for each rollout (experiment E4, naive against two-phase) This is the part i am going to prove.
alter table policy.rollouts add column if not exists skew_ms int;
alter table policy.rollouts add column if not exists mixed_policy_requests bigint;
alter table policy.rollouts add column if not exists convergence_ms bigint;
alter table policy.rollouts add column if not exists drain_ms bigint;
