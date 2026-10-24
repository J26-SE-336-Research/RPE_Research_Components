package rpe

import (
	"strconv"
	"strings"
)

// X-RPE-Trail records every hop a request went through. Example:
//
//	X-RPE-Trail: rv=1, inc=?0, h="1:0:41250:F:0;2:1:39870:F:2;4:2:38110:X:5"
//
// Each entry is svcId:parent:elapsedMicros:decision:consumedMs.
//   - svcId is the service id from registry version rv, so names are never sent
//   - parent is the position of the calling hop's entry, starting at 1, or 0 for the root
//   - elapsedMicros is how long the hop took including its own calls, -1 while it is still running
//   - decision is what the hop did, see TrailDecision
//   - consumedMs is how much of the budget was already used when the hop got the request, so bud - ttl
//
// On a request the trail holds the path from the gateway down to the caller. On a response it holds the part
// below the hop that answered: its own entry first and then what its callees sent back. inc=?1 means the trail is
// missing something, like a hop without RPE, a cut off trail or a different registry version.

// TrailDecision is the decision code of a trail entry.
type TrailDecision byte

const (
	DecisionForward   TrailDecision = 'F' // served on time
	DecisionEarly     TrailDecision = 'E' // answered before reaching the app, like a late retry or a bad context
	DecisionExpired   TrailDecision = 'X' // served after the deadline, still served
	DecisionCancelled TrailDecision = 'C' // the chain was cancelled and the hop skipped its work
	DecisionRetry     TrailDecision = 'R' // a retry attempt (att >= 2) that was served
	DecisionActive    TrailDecision = 'A' // still running, used on the request side
	DecisionPartial   TrailDecision = 'P' // fan-out completed without its optional children
	DecisionOverload  TrailDecision = 'O' // dropped by backpressure, a 429 or 503
	DecisionUnknown   TrailDecision = 'U' // the callee sent no trail back, so it doesn't speak RPE
)

func validDecision(d TrailDecision) bool { return strings.IndexByte("FEXCRAPOU", byte(d)) >= 0 }

// Size limits for a trail: at most MaxTrailEntries entries and MaxTrailBytes bytes.
const (
	MaxTrailEntries = MaxTopologyRecords
	MaxTrailBytes   = MaxTopologyTrailBytes
	// maxConsumedMillis leaves room for late hops, where ttl can go down to -MaxRequestDeadlineMillis.
	maxConsumedMillis = 2 * MaxRequestDeadlineMillis
)

// TrailEntry is one hop of a trail.
type TrailEntry struct {
	Service       int64
	Parent        int
	ElapsedMicros int64
	Decision      TrailDecision
	ConsumedMs    int64
}

func (e TrailEntry) wire() string {
	return strconv.FormatInt(e.Service, 10) + ":" + strconv.Itoa(e.Parent) + ":" + strconv.FormatInt(e.ElapsedMicros, 10) + ":" +
		string(rune(e.Decision)) + ":" + strconv.FormatInt(e.ConsumedMs, 10)
}

// Trail is the decoded X-RPE-Trail.
type Trail struct {
	RegistryVersion int64
	Incomplete      bool
	Entries         []TrailEntry
}

// DecodeTrail parses and checks an X-RPE-Trail value. Unknown members are ignored because every hop rebuilds
// the trail anyway.
func DecodeTrail(raw string) (Trail, error) {
	if len(raw) > MaxTrailBytes {
		return Trail{}, problem(CodeContextTooLarge, HeaderTrail, "trail exceeds 1400 bytes")
	}
	members, err := parseDictionary(HeaderTrail, raw)
	if err != nil {
		return Trail{}, err
	}
	var t Trail
	var h string
	seenRV, seenInc := false, false
	for _, m := range members {
		switch m.key {
		case "rv":
			seenRV = true
			if m.item.kind != 'i' || m.item.i < MinTopologyRegistryVersion || m.item.i > MaxTopologyRegistryVersion {
				return Trail{}, problem(CodeOutOfRange, HeaderTrail, "rv must be an integer from 1")
			}
			t.RegistryVersion = m.item.i
		case "inc":
			seenInc = true
			if m.item.kind != 'b' {
				return Trail{}, problem(CodeInvalidSyntax, HeaderTrail, "inc must be a boolean")
			}
			t.Incomplete = m.item.b
		case "h":
			if m.item.kind != 's' {
				return Trail{}, problem(CodeInvalidSyntax, HeaderTrail, "h must be a string")
			}
			h = m.item.s
		}
	}
	if !seenRV || !seenInc {
		return Trail{}, problem(CodeMissingHeader, HeaderTrail, "rv and inc are required")
	}
	if h == "" {
		return t, nil
	}
	parts := strings.Split(h, ";")
	if len(parts) > MaxTrailEntries {
		return Trail{}, problem(CodeOutOfRange, HeaderTrail, "more than 16 entries")
	}
	for i, p := range parts {
		e, err := parseTrailEntry(p, i+1)
		if err != nil {
			return Trail{}, err
		}
		t.Entries = append(t.Entries, e)
	}
	return t, nil
}

func parseTrailEntry(raw string, index int) (TrailEntry, error) {
	f := strings.Split(raw, ":")
	if len(f) != 5 {
		return TrailEntry{}, problem(CodeInvalidSyntax, HeaderTrail, "an entry has five fields")
	}
	num := func(s string, lo, hi int64) (int64, bool) {
		v, err := strconv.ParseInt(s, 10, 64)
		return v, err == nil && v >= lo && v <= hi
	}
	var e TrailEntry
	var ok bool
	if e.Service, ok = num(f[0], MinTopologyServiceID, MaxTopologyServiceID); !ok {
		return TrailEntry{}, problem(CodeOutOfRange, HeaderTrail, "svcId must be 0..65535")
	}
	parent, ok := num(f[1], 0, int64(index-1))
	if !ok {
		return TrailEntry{}, problem(CodeOutOfRange, HeaderTrail, "parent must refer to an earlier entry")
	}
	e.Parent = int(parent)
	if e.ElapsedMicros, ok = num(f[2], TopologyActiveElapsedMicros, MaxTopologyElapsedMicros); !ok {
		return TrailEntry{}, problem(CodeOutOfRange, HeaderTrail, "elapsedMicros out of range")
	}
	if len(f[3]) != 1 || !validDecision(TrailDecision(f[3][0])) {
		return TrailEntry{}, problem(CodeInvalidSyntax, HeaderTrail, "unknown decision code")
	}
	e.Decision = TrailDecision(f[3][0])
	if (e.Decision == DecisionActive) != (e.ElapsedMicros == TopologyActiveElapsedMicros) {
		return TrailEntry{}, problem(CodeInvalidSyntax, HeaderTrail, "only an active entry has elapsed -1")
	}
	if e.ConsumedMs, ok = num(f[4], 0, maxConsumedMillis); !ok {
		return TrailEntry{}, problem(CodeOutOfRange, HeaderTrail, "consumedMs out of range")
	}
	if e.wire() != raw {
		return TrailEntry{}, problem(CodeInvalidSyntax, HeaderTrail, "noncanonical entry")
	}
	return e, nil
}

// Encode writes the trail back as a header value.
func (t Trail) Encode() string {
	var w dictWriter
	w.int("rv", t.RegistryVersion)
	w.boolean("inc", t.Incomplete)
	if len(t.Entries) > 0 {
		parts := make([]string, len(t.Entries))
		for i, e := range t.Entries {
			parts[i] = e.wire()
		}
		w.str("h", strings.Join(parts, ";"))
	}
	return w.String()
}

// Append puts sub under the entry at position parent, counting from 1. parent 0 adds it as a new root. The parent
// numbers inside sub are shifted to match. Entries that don't fit in the size limits are dropped along with
// everything below them, and the trail is marked incomplete. A sub from another registry version is not merged,
// since its ids mean different services, it only marks the trail incomplete.
func (t Trail) Append(sub Trail, parent int) Trail {
	out := Trail{RegistryVersion: t.RegistryVersion, Incomplete: t.Incomplete || sub.Incomplete,
		Entries: append([]TrailEntry(nil), t.Entries...)}
	if sub.RegistryVersion != t.RegistryVersion {
		out.Incomplete = true
		return out
	}
	// size so far: rv, inc, the h member and the entries joined by ';'
	size := len(Trail{RegistryVersion: out.RegistryVersion}.Encode()) + len(`, h=""`) - 1
	for _, e := range out.Entries {
		size += len(e.wire()) + 1
	}
	newIndex := make([]int, len(sub.Entries)+1) // position in sub -> position in out, 0 if dropped
	for i, e := range sub.Entries {
		p := parent
		if e.Parent > 0 {
			if p = newIndex[e.Parent]; p == 0 {
				out.Incomplete = true // its parent was dropped
				continue
			}
		}
		e.Parent = p
		n := len(e.wire()) + 1
		if len(out.Entries) >= MaxTrailEntries || size+n > MaxTrailBytes {
			out.Incomplete = true
			continue
		}
		size += n
		out.Entries = append(out.Entries, e)
		newIndex[i+1] = len(out.Entries)
	}
	return out
}
