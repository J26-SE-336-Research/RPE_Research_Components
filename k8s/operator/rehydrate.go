package main

import (
	"context"
	"log"
	"strconv"
	"time"
)

// PostgreSQL is the system of record; Redis is a fast path (effective policies, events, acknowledgements) that is
// allowed to be empty after a restart or a flush. The effective-policy entries are the one part other components
// read from Redis (GET /api/k8s/effective: the hub and C4 build a new policy version from the current one), so they
// are rebuilt from PostgreSQL whenever a service that has an active policy has no entry.

// missingEffective returns, for every service that has an active policy but no effective-policy entry, the newest
// approved active policy of that service. active is ordered oldest approval first (store.active), so the last policy
// of a service wins. Entries that exist are never replaced: the one a rollout wrote is the policy last enforced.
func missingEffective(active []Policy, have map[string]map[string]string) []Policy {
	newest := map[string]Policy{}
	var order []string
	for _, p := range active {
		if _, seen := newest[p.Scope.Service]; !seen {
			order = append(order, p.Scope.Service)
		}
		newest[p.Scope.Service] = p
	}
	var out []Policy
	for _, service := range order {
		if _, ok := have[service]; !ok {
			out = append(out, newest[service])
		}
	}
	return out
}

// rehydrate checks every interval (and once at start) that Redis knows the effective policy of every service that
// has an active policy, and restores the entries it lost.
func rehydrate(ctx context.Context, interval time.Duration, st *store, b *bus) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		restoreMissing(ctx, st, b)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func restoreMissing(ctx context.Context, st *store, b *bus) {
	active, err := st.active(ctx)
	if err != nil {
		return
	}
	have, err := b.effective(ctx)
	if err != nil { // Redis unavailable: nothing to restore into yet
		return
	}
	var parts []Policy // a joint policy is the effective policy of each of its services, with that service's part
	for _, p := range active {
		parts = append(parts, p.projections()...)
	}
	restored := 0
	for _, p := range missingEffective(parts, have) {
		if err := b.restoreEffective(ctx, p); err != nil {
			log.Printf("rehydrate: cannot restore the effective policy of %s: %v", p.Scope.Service, err)
			continue
		}
		restored++
		log.Printf("rehydrate: restored the effective policy of %s (%s) from PostgreSQL", p.Scope.Service, p.Epoch)
	}
	if restored > 0 {
		b.emit(ctx, "effective-restored", map[string]string{"services": strconv.Itoa(restored)})
	}
}
