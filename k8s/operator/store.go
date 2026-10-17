package main

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// store is the PostgreSQL policy store: the system of record for approved policies (append-only,
// approval enforced by the schema), rollouts and per-pod deliveries.
type store struct{ pool *pgxpool.Pool }

type Policy struct {
	PolicyID         string          `json:"policyId"`
	Version          int             `json:"version"`
	Epoch            string          `json:"epoch"`
	Scope            Scope           `json:"scope"`
	Spec             json.RawMessage `json:"spec"`
	Source           string          `json:"source"`
	RecommendationID string          `json:"recommendationId,omitempty"`
	ApprovedBy       string          `json:"approvedBy"`
	ApprovedAt       time.Time       `json:"approvedAt"`
	Status           string          `json:"status"`
}

type Scope struct {
	Namespace string `json:"namespace,omitempty"`
	Service   string `json:"service"`
	Route     string `json:"route,omitempty"`
}

// Policy lifecycle statuses (policy.policies.status).
const (
	StatusPending    = "approved-pending-rollout"
	StatusRollingOut = "rolling-out"
	StatusActive     = "active"
	StatusSuperseded = "superseded"
	StatusFailed     = "rollout-failed"
)

var errNotFound = errors.New("not found")

func newStore(ctx context.Context, url string) (*store, error) {
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		return nil, err
	}
	return &store{pool}, nil
}

const policyCols = `policy_id, version, epoch, scope, spec, source, coalesce(recommendation_id,''), approved_by, approved_at, status`

func scanPolicy(row pgx.Row) (Policy, error) {
	var p Policy
	var scope []byte
	err := row.Scan(&p.PolicyID, &p.Version, &p.Epoch, &scope, &p.Spec, &p.Source, &p.RecommendationID, &p.ApprovedBy, &p.ApprovedAt, &p.Status)
	if errors.Is(err, pgx.ErrNoRows) {
		return p, errNotFound
	}
	_ = json.Unmarshal(scope, &p.Scope)
	return p, err
}

// create inserts the next immutable version. The schema rejects rows without an approver.
func (s *store) create(ctx context.Context, p Policy) (Policy, error) {
	if p.RecommendationID != "" { // idempotency for C4 deliveries
		if existing, err := scanPolicy(s.pool.QueryRow(ctx, `select `+policyCols+` from policy.policies where recommendation_id=$1`, p.RecommendationID)); err == nil {
			return existing, nil
		}
	}
	scope, _ := json.Marshal(p.Scope)
	var rec any
	if p.RecommendationID != "" {
		rec = p.RecommendationID
	}
	return scanPolicy(s.pool.QueryRow(ctx, `
		with next as (select coalesce(max(version),0)+1 as v from policy.policies where policy_id=$1)
		insert into policy.policies(policy_id, version, epoch, scope, spec, source, recommendation_id, approved_by, approved_at)
		select $1, next.v, $1 || '-v' || next.v, $2, $3, $4, $5, $6, now() from next
		returning `+policyCols, p.PolicyID, scope, p.Spec, p.Source, rec, p.ApprovedBy))
}

func (s *store) get(ctx context.Context, policyID string, version int) (Policy, error) {
	if version <= 0 {
		return scanPolicy(s.pool.QueryRow(ctx, `select `+policyCols+` from policy.policies where policy_id=$1 order by version desc limit 1`, policyID))
	}
	return scanPolicy(s.pool.QueryRow(ctx, `select `+policyCols+` from policy.policies where policy_id=$1 and version=$2`, policyID, version))
}

func (s *store) list(ctx context.Context) ([]Policy, error) {
	rows, err := s.pool.Query(ctx, `select `+policyCols+` from policy.policies order by created_at desc limit 200`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Policy{}
	for rows.Next() {
		p, err := scanPolicy(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// active returns the currently active version of every policy (used for drift repair).
func (s *store) active(ctx context.Context) ([]Policy, error) {
	rows, err := s.pool.Query(ctx, `select `+policyCols+` from policy.policies where status=$1`, StatusActive)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Policy
	for rows.Next() {
		p, err := scanPolicy(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// rollingOut returns the policies that have a rollout in progress (their proxies are being moved on purpose).
func (s *store) rollingOut(ctx context.Context) (map[string]bool, error) {
	rows, err := s.pool.Query(ctx, `select distinct policy_id from policy.policies where status=$1`, StatusRollingOut)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out[id] = true
	}
	return out, rows.Err()
}

func (s *store) activeEpoch(ctx context.Context, policyID string) string {
	var epoch string
	_ = s.pool.QueryRow(ctx, `select epoch from policy.policies where policy_id=$1 and status=$2 order by version desc limit 1`, policyID, StatusActive).Scan(&epoch)
	return epoch
}

func (s *store) setStatus(ctx context.Context, policyID string, version int, status string) error {
	_, err := s.pool.Exec(ctx, `update policy.policies set status=$3 where policy_id=$1 and version=$2`, policyID, version, status)
	return err
}

// activate marks a version active and every other active version of the same policy superseded.
func (s *store) activate(ctx context.Context, policyID string, version int) error {
	_, err := s.pool.Exec(ctx, `update policy.policies set status = case when version=$2 then $3 else $4 end
		where policy_id=$1 and (version=$2 or status=$3)`, policyID, version, StatusActive, StatusSuperseded)
	return err
}

func (s *store) recordRollout(ctx context.Context, name string, spec RolloutSpec) error {
	_, err := s.pool.Exec(ctx, `insert into policy.rollouts(name, policy_id, version, mode, state, started_at, requested_by)
		values ($1,$2,$3,$4,'Staging',now(),$5) on conflict (name) do nothing`, name, spec.PolicyID, spec.Version, spec.Mode, spec.RequestedBy)
	return err
}

func (s *store) updateRollout(ctx context.Context, name, state, message string, done bool) error {
	_, err := s.pool.Exec(ctx, `update policy.rollouts set state=$2, message=$3, finished_at = case when $4 then now() else finished_at end where name=$1`,
		name, state, message, done)
	return err
}

func (s *store) recordDelivery(ctx context.Context, pod, service, epoch string, version int, hash, state, errMsg string) error {
	var e any
	if errMsg != "" {
		e = errMsg
	}
	_, err := s.pool.Exec(ctx, `insert into policy.deliveries(pod, service, epoch, version, hash, state, acked_at, error)
		values ($1,$2,$3,$4,$5,$6,now(),$7)
		on conflict (pod, epoch) do update set state=excluded.state, hash=excluded.hash, acked_at=now(), error=excluded.error`,
		pod, service, epoch, version, hash, state, e)
	return err
}

// confirm records a proxy's own acknowledgement (POST /api/k8s/acks): the state and the content hash the
// proxy computed. The Operator's expected hash stays in `hash`, so verified = (confirmed_hash = hash).
func (s *store) confirm(ctx context.Context, pod, service, epoch string, version int, hash, state string) error {
	_, err := s.pool.Exec(ctx, `insert into policy.deliveries(pod, service, epoch, version, hash, state, acked_at, confirmed_state, confirmed_hash, confirmed_at)
		values ($1,$2,$3,$4,$5,$6,now(),$6,$5,now())
		on conflict (pod, epoch) do update set confirmed_state=excluded.confirmed_state, confirmed_hash=excluded.confirmed_hash, confirmed_at=now()`,
		pod, service, epoch, version, hash, state)
	return err
}

type confirmation struct {
	Hash, State string
	At          time.Time
}

// confirmations returns, per pod, the latest acknowledgement a proxy sent itself for epoch.
func (s *store) confirmations(ctx context.Context, epoch string) (map[string]confirmation, error) {
	rows, err := s.pool.Query(ctx, `select pod, confirmed_hash, confirmed_state, confirmed_at from policy.deliveries
		where epoch=$1 and confirmed_at is not null`, epoch)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]confirmation{}
	for rows.Next() {
		var pod string
		var c confirmation
		if err := rows.Scan(&pod, &c.Hash, &c.State, &c.At); err != nil {
			return nil, err
		}
		out[pod] = c
	}
	return out, rows.Err()
}

// recordMeasurements stores the consistent-rollout measurements of a finished rollout (experiment E4).
func (s *store) recordMeasurements(ctx context.Context, name string, st RolloutStatus, skewMs int) error {
	_, err := s.pool.Exec(ctx, `update policy.rollouts set skew_ms=$2, mixed_policy_requests=$3, convergence_ms=$4, drain_ms=$5 where name=$1`,
		name, skewMs, st.MixedPolicyRequests, st.ConvergenceMs, st.DrainMs)
	return err
}

type approvalEvent struct {
	ID       int64
	PolicyID string
	Version  int
	Epoch    string
}

func (s *store) approvalsSince(ctx context.Context, id int64) ([]approvalEvent, error) {
	rows, err := s.pool.Query(ctx, `select id, policy_id, version, coalesce(payload->>'epoch','') from policy.policy_events
		where kind='approved' and id > $1 order by id`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []approvalEvent
	for rows.Next() {
		var e approvalEvent
		if err := rows.Scan(&e.ID, &e.PolicyID, &e.Version, &e.Epoch); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// listen streams PostgreSQL NOTIFY rpe_policy_approved payloads until ctx is done, reconnecting on errors.
func (s *store) listen(ctx context.Context, url string, onNotify func(payload string), onConnect func()) {
	for ctx.Err() == nil {
		conn, err := pgx.Connect(ctx, url)
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}
		if _, err := conn.Exec(ctx, "listen rpe_policy_approved"); err != nil {
			conn.Close(context.Background())
			time.Sleep(2 * time.Second)
			continue
		}
		onConnect() // catch up on approvals missed while disconnected
		for ctx.Err() == nil {
			n, err := conn.WaitForNotification(ctx)
			if err != nil {
				break
			}
			onNotify(n.Payload)
		}
		conn.Close(context.Background())
	}
}
