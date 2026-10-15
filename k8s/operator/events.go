package main

import (
	"context"
	"encoding/json"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
)

// bus is the Redis fast path next to the PostgreSQL system of record:
//   - stream  rpe:events                 every policy / rollout / delivery event (bounded)
//   - hash    rpe:effective:<service>    the policy each service currently enforces
//   - hash    rpe:acks:<epoch>           per-pod delivery state for fast convergence counting
//   - pubsub  rpe:policy:changed         broadcast when a policy becomes active
//   - string  rpe:operator:last-event-id the Operator's catch-up checkpoint into policy_events
type bus struct{ rdb *redis.Client }

const (
	keyStream     = "rpe:events"
	keyEffective  = "rpe:effective:"
	keyAcks       = "rpe:acks:"
	keyCheckpoint = "rpe:operator:last-event-id"
	chanChanged   = "rpe:policy:changed"
)

func newBus(addr string) *bus { return &bus{redis.NewClient(&redis.Options{Addr: addr})} }

type Event struct {
	ID     string            `json:"id"`
	At     time.Time         `json:"at"`
	Type   string            `json:"type"`
	Fields map[string]string `json:"fields"`
}

func (b *bus) emit(ctx context.Context, typ string, fields map[string]string) {
	values := map[string]any{"type": typ, "at": time.Now().UTC().Format(time.RFC3339Nano)}
	for k, v := range fields {
		values[k] = v
	}
	_ = b.rdb.XAdd(ctx, &redis.XAddArgs{Stream: keyStream, MaxLen: 2000, Approx: true, Values: values}).Err()
}

func (b *bus) recent(ctx context.Context, n int64) ([]Event, error) {
	msgs, err := b.rdb.XRevRangeN(ctx, keyStream, "+", "-", n).Result()
	if err != nil {
		return nil, err
	}
	out := make([]Event, 0, len(msgs))
	for _, m := range msgs {
		e := Event{ID: m.ID, Fields: map[string]string{}}
		for k, v := range m.Values {
			s, _ := v.(string)
			switch k {
			case "type":
				e.Type = s
			case "at":
				e.At, _ = time.Parse(time.RFC3339Nano, s)
			default:
				e.Fields[k] = s
			}
		}
		out = append(out, e)
	}
	return out, nil
}

func (b *bus) ack(ctx context.Context, epoch, pod, state string) {
	_ = b.rdb.HSet(ctx, keyAcks+epoch, pod, state).Err()
	_ = b.rdb.Expire(ctx, keyAcks+epoch, 24*time.Hour).Err()
}

func (b *bus) setEffective(ctx context.Context, p Policy) {
	_ = b.rdb.HSet(ctx, keyEffective+p.Scope.Service, map[string]any{
		"policyId": p.PolicyID, "version": p.Version, "epoch": p.Epoch, "route": p.Scope.Route,
		"spec": string(p.Spec), "approvedBy": p.ApprovedBy, "updatedAt": time.Now().UTC().Format(time.RFC3339),
	}).Err()
	msg, _ := json.Marshal(map[string]any{"service": p.Scope.Service, "policyId": p.PolicyID, "version": p.Version, "epoch": p.Epoch})
	_ = b.rdb.Publish(ctx, chanChanged, msg).Err()
}

func (b *bus) effective(ctx context.Context) (map[string]map[string]string, error) {
	out := map[string]map[string]string{}
	iter := b.rdb.Scan(ctx, 0, keyEffective+"*", 100).Iterator()
	for iter.Next(ctx) {
		v, err := b.rdb.HGetAll(ctx, iter.Val()).Result()
		if err == nil {
			out[iter.Val()[len(keyEffective):]] = v
		}
	}
	return out, iter.Err()
}

func (b *bus) checkpoint(ctx context.Context) int64 {
	v, _ := b.rdb.Get(ctx, keyCheckpoint).Result()
	n, _ := strconv.ParseInt(v, 10, 64)
	return n
}

func (b *bus) setCheckpoint(ctx context.Context, id int64) {
	_ = b.rdb.Set(ctx, keyCheckpoint, id, 0).Err()
}

func (b *bus) ping(ctx context.Context) error { return b.rdb.Ping(ctx).Err() }
