-- Rollouts are driven by RPERollout custom resources; link each row to its resource and requester.
alter table policy.rollouts add column if not exists name text unique;
alter table policy.rollouts add column if not exists requested_by text;
alter table policy.rollouts add column if not exists message text;
