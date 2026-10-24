package rpe

import (
	"sort"
	"strconv"
	"strings"
)

// sfItem is one value in an RFC 8941 dictionary. Our headers only use integers, strings, tokens and booleans,
// with no parameters or inner lists.
type sfItem struct {
	kind byte // 'i' integer, 's' string, 't' token, 'b' boolean
	i    int64
	s    string
	b    bool
	raw  string // the original text, so unknown members can be passed on unchanged
}

type sfMember struct {
	key  string
	item sfItem
}

// parseDictionary parses the part of RFC 8941 our headers use. Member order is kept. Duplicate keys are an error,
// which is stricter than the RFC (it keeps the last one), because two different deadlines would be confusing.
func parseDictionary(header, input string) ([]sfMember, error) {
	s := strings.Trim(input, " \t")
	if s == "" {
		return nil, problem(CodeInvalidSyntax, header, "empty dictionary")
	}
	var members []sfMember
	seen := map[string]bool{}
	for len(s) > 0 {
		key, rest, err := parseKey(header, s)
		if err != nil {
			return nil, err
		}
		if seen[key] {
			return nil, problem(CodeDuplicateHeader, header, "duplicate member "+key)
		}
		seen[key] = true
		var item sfItem
		if strings.HasPrefix(rest, "=") {
			item, rest, err = parseBareItem(header, rest[1:])
			if err != nil {
				return nil, err
			}
		} else {
			item = sfItem{kind: 'b', b: true, raw: "?1"}
		}
		if strings.HasPrefix(rest, ";") {
			return nil, problem(CodeInvalidSyntax, header, "parameters are not supported in RPE v1")
		}
		members = append(members, sfMember{key, item})
		rest = strings.TrimLeft(rest, " \t")
		if rest == "" {
			break
		}
		if rest[0] != ',' {
			return nil, problem(CodeInvalidSyntax, header, "expected ',' between members")
		}
		s = strings.TrimLeft(rest[1:], " \t")
		if s == "" {
			return nil, problem(CodeInvalidSyntax, header, "trailing comma")
		}
	}
	return members, nil
}

func parseKey(header, s string) (string, string, error) {
	if s == "" || !(isLowerAlpha(s[0]) || s[0] == '*') {
		return "", "", problem(CodeInvalidSyntax, header, "member key must start with a lowercase letter")
	}
	i := 1
	for i < len(s) && (isLowerAlpha(s[i]) || isDigit(s[i]) || strings.IndexByte("_-.*", s[i]) >= 0) {
		i++
	}
	return s[:i], s[i:], nil
}

func parseBareItem(header, s string) (sfItem, string, error) {
	if s == "" {
		return sfItem{}, "", problem(CodeInvalidSyntax, header, "missing value")
	}
	switch c := s[0]; {
	case c == '-' || isDigit(c):
		i := 1
		for i < len(s) && isDigit(s[i]) {
			i++
		}
		if i < len(s) && s[i] == '.' {
			return sfItem{}, "", problem(CodeInvalidSyntax, header, "decimals are not used in RPE v1")
		}
		digits := s[:i]
		if digits == "-" || len(strings.TrimPrefix(digits, "-")) > 15 {
			return sfItem{}, "", problem(CodeInvalidSyntax, header, "invalid integer")
		}
		v, err := strconv.ParseInt(digits, 10, 64)
		if err != nil {
			return sfItem{}, "", problem(CodeInvalidSyntax, header, "invalid integer")
		}
		return sfItem{kind: 'i', i: v, raw: digits}, s[i:], nil
	case c == '"':
		var b strings.Builder
		for i := 1; i < len(s); i++ {
			switch ch := s[i]; {
			case ch == '\\':
				if i+1 >= len(s) || (s[i+1] != '"' && s[i+1] != '\\') {
					return sfItem{}, "", problem(CodeInvalidSyntax, header, "invalid string escape")
				}
				b.WriteByte(s[i+1])
				i++
			case ch == '"':
				return sfItem{kind: 's', s: b.String(), raw: s[:i+1]}, s[i+1:], nil
			case ch < 0x20 || ch > 0x7e:
				return sfItem{}, "", problem(CodeInvalidSyntax, header, "string must be printable ASCII")
			default:
				b.WriteByte(ch)
			}
		}
		return sfItem{}, "", problem(CodeInvalidSyntax, header, "unterminated string")
	case c == '?':
		if len(s) < 2 || (s[1] != '0' && s[1] != '1') {
			return sfItem{}, "", problem(CodeInvalidSyntax, header, "invalid boolean")
		}
		return sfItem{kind: 'b', b: s[1] == '1', raw: s[:2]}, s[2:], nil
	case isAlpha(c) || c == '*':
		i := 1
		for i < len(s) && isTokenChar(s[i]) {
			i++
		}
		return sfItem{kind: 't', s: s[:i], raw: s[:i]}, s[i:], nil
	}
	return sfItem{}, "", problem(CodeInvalidSyntax, header, "unsupported value type")
}

// dictWriter writes members in a fixed order: the known keys first in the given order, then unknown ones sorted.
type dictWriter struct{ parts []string }

func (w *dictWriter) int(key string, v int64) {
	w.parts = append(w.parts, key+"="+strconv.FormatInt(v, 10))
}
func (w *dictWriter) token(key, v string) { w.parts = append(w.parts, key+"="+v) }
func (w *dictWriter) str(key, v string)   { w.parts = append(w.parts, key+"="+quoteSF(v)) }
func (w *dictWriter) boolean(key string, v bool) {
	if v {
		w.parts = append(w.parts, key+"=?1")
	} else {
		w.parts = append(w.parts, key+"=?0")
	}
}
func (w *dictWriter) unknown(m map[string]string) {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		w.parts = append(w.parts, k+"="+m[k])
	}
}
func (w *dictWriter) String() string { return strings.Join(w.parts, ", ") }

func quoteSF(v string) string {
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(v) + `"`
}

func isLowerAlpha(c byte) bool { return c >= 'a' && c <= 'z' }
func isAlpha(c byte) bool      { return isLowerAlpha(c) || (c >= 'A' && c <= 'Z') }
func isDigit(c byte) bool      { return c >= '0' && c <= '9' }
func isTokenChar(c byte) bool {
	return isAlpha(c) || isDigit(c) || strings.IndexByte("!#$%&'*+-.^_`|~:/", c) >= 0
}
