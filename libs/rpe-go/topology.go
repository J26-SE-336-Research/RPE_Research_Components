package rpe

import (
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
)

type TopologyRecord struct {
	InvocationID, ParentInvocationID string
	ServiceID, ElapsedMicros         int64
	Outcome                          string
}
type Topology struct {
	Enabled         bool
	RegistryVersion int64
	Incomplete      bool
	Records         []TopologyRecord
}

func validateRecord(r TopologyRecord) error {
	if _, e := parseID(HeaderInvocationID, r.InvocationID, 16, false); e != nil {
		return e
	}
	if _, e := parseID(HeaderParentInvocationID, r.ParentInvocationID, 16, true); e != nil {
		return e
	}
	if r.ServiceID < 0 || r.ServiceID > 65535 || r.ElapsedMicros < -1 || r.ElapsedMicros > MaxTopologyElapsedMicros {
		return problem(CodeOutOfRange, HeaderTopologyTrail, "invalid record bounds")
	}
	if r.Outcome == "active" {
		if r.ElapsedMicros != -1 {
			return problem(CodeInvalidSyntax, HeaderTopologyTrail, "active duration must be -1")
		}
	} else if !validOutcome(Outcome(r.Outcome)) || r.ElapsedMicros < 0 {
		return problem(CodeInvalidSyntax, HeaderTopologyTrail, "invalid completed record")
	}
	return nil
}
func recordWire(r TopologyRecord) string {
	return fmt.Sprintf("%s:%s:%d:%d:%s", r.InvocationID, r.ParentInvocationID, r.ServiceID, r.ElapsedMicros, r.Outcome)
}

// ParseTopology reports diagnostic errors separately. Callers must retain valid
// core context, discard unusable optional records, and propagate Incomplete.
func ParseTopology(h http.Header) (Topology, error) {
	t := Topology{}
	present := false
	for k := range h {
		if strings.EqualFold(k, HeaderTopologyTrail) || strings.EqualFold(k, HeaderTopologyRegistryVersion) || strings.EqualFold(k, HeaderTopologyIncomplete) {
			present = true
		}
	}
	if !present {
		return t, nil
	}
	t.Enabled = true
	t.Incomplete = true
	rv, e := single(h, HeaderTopologyRegistryVersion, true)
	if e != nil {
		return t, e
	}
	if t.RegistryVersion, e = parseInteger(HeaderTopologyRegistryVersion, rv, 1, MaxTopologyRegistryVersion); e != nil {
		return t, e
	}
	iv, e := single(h, HeaderTopologyIncomplete, true)
	if e != nil {
		return t, e
	}
	inc, e := parseBoolean(HeaderTopologyIncomplete, iv)
	if e != nil {
		return t, e
	}
	trail, e := single(h, HeaderTopologyTrail, false)
	if e != nil {
		return t, e
	}
	if trail == "" {
		t.Incomplete = inc
		return t, nil
	}
	if len(trail) > MaxTopologyTrailBytes {
		return t, problem(CodeContextTooLarge, HeaderTopologyTrail, "trail exceeds byte cap")
	}
	records := []TopologyRecord{}
	for _, raw := range strings.Split(trail, ";") {
		parts := strings.Split(raw, ":")
		if len(parts) != 5 {
			return t, problem(CodeInvalidSyntax, HeaderTopologyTrail, "expected five record fields")
		}
		r := TopologyRecord{InvocationID: parts[0], ParentInvocationID: parts[1], Outcome: parts[4]}
		if r.ServiceID, e = parseInteger(HeaderTopologyTrail, parts[2], 0, 65535); e != nil {
			return t, e
		}
		if parts[3] == "-1" {
			r.ElapsedMicros = -1
		} else {
			if r.ElapsedMicros, e = parseInteger(HeaderTopologyTrail, parts[3], 0, MaxTopologyElapsedMicros); e != nil {
				return t, e
			}
		}
		if e = validateRecord(r); e != nil {
			return t, e
		}
		// Record grammar has no whitespace; scalar helpers' OWS trimming is not used.
		if recordWire(r) != raw {
			return t, problem(CodeInvalidSyntax, HeaderTopologyTrail, "noncanonical record")
		}
		records = append(records, r)
	}
	t.Incomplete = inc
	t.Records = records
	return MergeTopology(Topology{Enabled: true, RegistryVersion: t.RegistryVersion}, t, ""), nil
}
func MergeTopology(local, incoming Topology, currentID string) Topology {
	out := Topology{Enabled: local.Enabled || incoming.Enabled, RegistryVersion: local.RegistryVersion, Incomplete: local.Incomplete || incoming.Incomplete}
	if !out.Enabled {
		return out
	}
	if out.RegistryVersion == 0 {
		out.RegistryVersion = incoming.RegistryVersion
	}
	all := append([]TopologyRecord{}, local.Records...)
	if incoming.Enabled && incoming.RegistryVersion != out.RegistryVersion {
		out.Incomplete = true
	} else {
		all = append(all, incoming.Records...)
	}
	records := map[string]TopologyRecord{}
	conflict := map[string]bool{}
	for _, r := range all {
		if validateRecord(r) != nil {
			out.Incomplete = true
			continue
		}
		if conflict[r.InvocationID] {
			continue
		}
		old, ok := records[r.InvocationID]
		if !ok {
			records[r.InvocationID] = r
			continue
		}
		if old == r {
			continue
		}
		if old.ParentInvocationID != r.ParentInvocationID || old.ServiceID != r.ServiceID || (old.Outcome != "active" && r.Outcome != "active") {
			delete(records, r.InvocationID)
			conflict[r.InvocationID] = true
			out.Incomplete = true
			continue
		}
		if old.Outcome == "active" {
			records[r.InvocationID] = r
		}
	}
	for _, r := range records {
		out.Records = append(out.Records, r)
	}
	rank := func(r TopologyRecord) int {
		if r.InvocationID == currentID {
			return 0
		}
		if currentID != "" && r.ParentInvocationID == currentID {
			return 1
		}
		return 2
	}
	sort.Slice(out.Records, func(i, j int) bool {
		a, b := out.Records[i], out.Records[j]
		if rank(a) != rank(b) {
			return rank(a) < rank(b)
		}
		return a.InvocationID < b.InvocationID
	})
	kept := []TopologyRecord{}
	size := 0
	for _, r := range out.Records {
		n := len(recordWire(r))
		if len(kept) > 0 {
			n++
		}
		if len(kept) >= MaxTopologyRecords || size+n > MaxTopologyTrailBytes {
			out.Incomplete = true
			continue
		}
		size += n
		kept = append(kept, r)
	}
	out.Records = kept
	return out
}
func SerializeTopology(t Topology, currentID string) (http.Header, error) {
	h := make(http.Header)
	if !t.Enabled {
		return h, nil
	}
	if t.RegistryVersion < 1 || t.RegistryVersion > MaxTopologyRegistryVersion {
		return nil, problem(CodeOutOfRange, HeaderTopologyRegistryVersion, "invalid registry version")
	}
	for _, r := range t.Records {
		if e := validateRecord(r); e != nil {
			return nil, e
		}
	}
	t = MergeTopology(Topology{Enabled: true, RegistryVersion: t.RegistryVersion}, t, currentID)
	h.Set(HeaderTopologyRegistryVersion, strconv.FormatInt(t.RegistryVersion, 10))
	h.Set(HeaderTopologyIncomplete, strconv.FormatBool(t.Incomplete))
	parts := []string{}
	for _, r := range t.Records {
		parts = append(parts, recordWire(r))
	}
	if len(parts) > 0 {
		h.Set(HeaderTopologyTrail, strings.Join(parts, ";"))
	}
	return h, nil
}
