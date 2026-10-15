#!/usr/bin/env bash
# C3: PostgreSQL policy store - governance (approval required, append-only) and event notification.
source "$(dirname "$0")/lib.sh"
pg=$(k -n rpe-system get pod -l app=rpe-postgres -o jsonpath='{.items[0].metadata.name}')
psqlc() { k -n rpe-system exec "${pg}" -- psql -U rpe -d rpe -v ON_ERROR_STOP=1 -At "$@" 2>&1; }
tables=$(psqlc -c "select count(*) from information_schema.tables where table_schema='policy'")
assert_eq "${tables}" 4 "policy schema has 4 tables"
id="pol-e2e-$(date +%s)"
out=$(psqlc -c "insert into policy.policies(policy_id,version,epoch,scope,spec,source,approved_at) values ('${id}',1,'${id}-e1','{}','{}','admin',now())")
assert_contains "${out}" "null value" "FR10: a policy without a human approval record is rejected"
out=$(psqlc -c "listen rpe_policy_approved" -c "insert into policy.policies(policy_id,version,epoch,scope,spec,source,approved_by,approved_at) values ('${id}',1,'${id}-e1','{\"service\":\"payment-svc\"}','{\"deadlineMs\":800}','admin','sre@demo',now())")
assert_contains "${out}" 'Asynchronous notification "rpe_policy_approved"' "approved policy raises a NOTIFY event for the Operator"
out=$(psqlc -c "update policy.policies set spec='{\"deadlineMs\":1}' where policy_id='${id}'")
assert_contains "${out}" "append-only" "approved policies are immutable (append-only versions)"
events=$(psqlc -c "select count(*) from policy.policy_events where policy_id='${id}'")
assert_eq "${events}" 1 "policy_events records the approval for Operator catch-up"
finish
