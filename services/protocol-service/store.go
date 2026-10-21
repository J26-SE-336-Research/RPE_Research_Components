package main

import (
	"sort"
	"sync"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// registry is the service registry (R-P03): names never travel in the trail, only their 2-byte ids.
type registry struct {
	mu      sync.RWMutex
	version int64
	ids     map[string]int64
	names   map[int64]string
}

func newRegistry(version int64, services []string) *registry {
	r := &registry{version: version, ids: map[string]int64{}, names: map[int64]string{}}
	for i, s := range services {
		r.ids[s] = int64(i + 1)
		r.names[int64(i+1)] = s
	}
	return r
}

func (r *registry) snapshot() (int64, map[string]int64) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make(map[string]int64, len(r.ids))
	for k, v := range r.ids {
		out[k] = v
	}
	return r.version, out
}

// add registers new services (existing ids never change) and bumps the version when anything was added.
func (r *registry) add(services []string) (int64, map[string]int64) {
	r.mu.Lock()
	changed := false
	for _, s := range services {
		if _, ok := r.ids[s]; !ok && s != "" {
			id := int64(len(r.ids) + 1)
			r.ids[s], r.names[id] = id, s
			changed = true
		}
	}
	if changed {
		r.version++
	}
	r.mu.Unlock()
	return r.snapshot()
}

func (r *registry) name(id int64) string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if n, ok := r.names[id]; ok {
		return n
	}
	return "unregistered"
}

// stored is one completed trail.
type stored struct {
	rec   rpe.TrailRecord
	trail rpe.Trail
	bytes int
}

// store keeps the most recent completed trails (a ring) with an index by request id.
type store struct {
	mu       sync.Mutex
	capacity int
	ring     []*stored
	next     int
	byRID    map[string]*stored
	received int64
	rejected int64
}

func newStore(capacity int) *store {
	return &store{capacity: capacity, ring: make([]*stored, 0, capacity), byRID: map[string]*stored{}}
}

// add validates and stores a trail; it returns false for a trail that does not decode.
func (s *store) add(rec rpe.TrailRecord) bool {
	t, err := rpe.DecodeTrail(rec.Trail)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.received++
	if err != nil || rec.RID == "" {
		s.rejected++
		return false
	}
	if rec.At.IsZero() {
		rec.At = time.Now().UTC()
	}
	item := &stored{rec: rec, trail: t, bytes: len(rec.Trail)}
	if len(s.ring) < s.capacity {
		s.ring = append(s.ring, item)
	} else {
		delete(s.byRID, s.ring[s.next].rec.RID)
		s.ring[s.next] = item
		s.next = (s.next + 1) % s.capacity
	}
	s.byRID[rec.RID] = item
	return true
}

func (s *store) get(rid string) *stored {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.byRID[rid]
}

// since returns the trails received after t, newest first.
func (s *store) since(t time.Time) []*stored {
	s.mu.Lock()
	out := make([]*stored, 0, len(s.ring))
	for _, it := range s.ring {
		if !it.rec.At.Before(t) {
			out = append(out, it)
		}
	}
	s.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].rec.At.After(out[j].rec.At) })
	return out
}

func (s *store) counts() (int64, int64, int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.received, s.rejected, len(s.ring)
}
