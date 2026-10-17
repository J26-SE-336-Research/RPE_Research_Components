package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
)

// specHash is the content hash of a policy spec: the first 16 hex digits of SHA-256 over its canonical JSON
// (keys sorted, no whitespace, integral numbers without a fraction). The sidecar computes the same hash of the
// policy it receives (netty-sidecar-proxy PolicyStore.contentHash), so a matching hash in an ack or in
// GET /rpe/v1/policy proves which policy a proxy holds.
func specHash(spec json.RawMessage) string {
	var v any
	if len(spec) == 0 || json.Unmarshal(spec, &v) != nil {
		v = nil
	}
	var b strings.Builder
	canonical(&b, v)
	sum := sha256.Sum256([]byte(b.String()))
	return hex.EncodeToString(sum[:])[:16]
}

func canonical(b *strings.Builder, v any) {
	switch x := v.(type) {
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		b.WriteByte('{')
		for i, k := range keys {
			if i > 0 {
				b.WriteByte(',')
			}
			quote(b, k)
			b.WriteByte(':')
			canonical(b, x[k])
		}
		b.WriteByte('}')
	case []any:
		b.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				b.WriteByte(',')
			}
			canonical(b, e)
		}
		b.WriteByte(']')
	case string:
		quote(b, x)
	case float64:
		if x == math.Trunc(x) && math.Abs(x) < 1e15 {
			b.WriteString(strconv.FormatInt(int64(x), 10))
		} else {
			b.WriteString(strconv.FormatFloat(x, 'f', -1, 64))
		}
	case bool:
		b.WriteString(strconv.FormatBool(x))
	default:
		b.WriteString("null")
	}
}

func quote(b *strings.Builder, s string) {
	b.WriteByte('"')
	for _, r := range s {
		switch {
		case r == '"' || r == '\\':
			b.WriteByte('\\')
			b.WriteRune(r)
		case r < 0x20:
			fmt.Fprintf(b, "\\u%04x", r)
		default:
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
}
