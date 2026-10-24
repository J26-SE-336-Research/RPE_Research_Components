-- C4's audit trail: each recommendation, human decision and delivery result. Append-only, like policy.policies.
create schema if not exists intel;

create table if not exists intel.audit (
    id              bigserial primary key,
    at              timestamptz not null,
    kind            text not null,          -- proposed | approved | rejected | delivery-<state>
    proposal_id     uuid not null,
    target          text not null,          -- service or "METHOD /route"
    action          text not null,          -- LEAKY_BUCKET | RETRY_BUDGET | CHAIN_DEADLINE
    source          text not null,          -- PROMETHEUS | TEMPO | MOCK
    current_value   double precision,
    proposed_value  double precision,
    unit            text,
    decision        text,
    actor           text,                   -- reviewer for decisions, component-3 for deliveries
    delivery        text,
    detail          text,
    policy_version  text
);

create index if not exists audit_proposal_idx on intel.audit (proposal_id);

create or replace function intel.audit_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'intel.audit is append-only';
end $$;

drop trigger if exists audit_append_only on intel.audit;
create trigger audit_append_only before update or delete on intel.audit
    for each row execute function intel.audit_append_only();
