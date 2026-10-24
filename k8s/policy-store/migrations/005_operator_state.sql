-- The Operator keeps its own state here and not in Redis: how far it has read policy.policy_events, so it can
-- catch up after downtime. Redis is only a fast copy and can be flushed at any time.
create table if not exists policy.operator_state (
    key        text        primary key,
    value      text        not null,
    updated_at timestamptz not null default now()
);
