-- The Operator's own state lives in the system of record, not in Redis: the position it has reached in
-- policy.policy_events (change catch-up after downtime). Redis stays a fast path that can be flushed at any time.
create table if not exists policy.operator_state (
    key        text        primary key,
    value      text        not null,
    updated_at timestamptz not null default now()
);
