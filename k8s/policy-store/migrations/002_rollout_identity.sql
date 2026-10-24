-- A rollout is an RPERollout custom resource, so each row also stores the resource and who requested it.
alter table policy.rollouts add column if not exists name text unique;
alter table policy.rollouts add column if not exists requested_by text;
alter table policy.rollouts add column if not exists message text;
