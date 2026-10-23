package main

import (
	"fmt"
	"hash/crc32"
	"math"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
)

// registry is the service registry (R-P03): names never travel in the trail, only their 2-byte ids. Ids never change
// once given. The registry is kept in memory: the seed list (REGISTRY) always gets ids 1..n and version REGISTRY_VERSION,
// so the demo services keep the same ids after a restart. A service added later (by registration) gets the next free id
// and the version becomes a checksum of the whole name → id mapping. So the same mapping always has the same version, and
// if a restart gave a later service another id, the version would differ too and trails of the two mappings are never
// merged (a subtree of another registry version only marks the trail incomplete).
type registry struct {
	mu          sync.RWMutex
	seedVersion int64
	version     int64
	ids         map[string]int64
	names       map[int64]string
}

// maxRegistered is the largest trail id (svcId is 0..65535; 0 means not registered).
const maxRegistered = 65535

// serviceName is a Kubernetes-style name (DNS label): lowercase letters, digits and '-', 1..63 characters.
var serviceName = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)

func newRegistry(version int64, services []string) *registry {
	r := &registry{seedVersion: version, version: version, ids: map[string]int64{}, names: map[int64]string{}}
	for _, s := range services {
		if s = strings.TrimSpace(s); s != "" {
			if _, dup := r.ids[s]; !dup {
				id := int64(len(r.ids) + 1)
				r.ids[s], r.names[id] = id, s
			}
		}
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

// mappingVersion is the checksum version of a mapping: 2 .. 2^31-1, different from the seed version.
func mappingVersion(ids map[string]int64, seed int64) int64 {
	names := make([]string, 0, len(ids))
	for n := range ids {
		names = append(names, n)
	}
	sort.Strings(names)
	h := crc32.NewIEEE()
	for _, n := range names {
		fmt.Fprintf(h, "%s=%d;", n, ids[n])
	}
	v := 2 + int64(h.Sum32())%(math.MaxInt32-2)
	if v == seed {
		v++
	}
	return v
}

// add registers services that are not known yet (existing ids never change) and returns the registry. Invalid names
// are reported and not added.
func (r *registry) add(services []string) (int64, map[string]int64, error) {
	r.mu.Lock()
	changed := false
	var bad []string
	for _, s := range services {
		if _, ok := r.ids[s]; ok {
			continue
		}
		if !serviceName.MatchString(s) {
			bad = append(bad, s)
			continue
		}
		if len(r.ids) >= maxRegistered {
			bad = append(bad, s+" (registry full)")
			continue
		}
		id := int64(len(r.ids) + 1)
		r.ids[s], r.names[id] = id, s
		changed = true
	}
	if changed {
		r.version = mappingVersion(r.ids, r.seedVersion)
	}
	r.mu.Unlock()
	v, ids := r.snapshot()
	if len(bad) > 0 {
		return v, ids, fmt.Errorf("not registered (a service name is lowercase letters, digits and '-', at most 63 characters): %s", strings.Join(bad, ", "))
	}
	return v, ids, nil
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
