package rpe

import (
	"strconv"
	"strings"
)

func normalizeScalar(header, raw string) (string, error) {
	if len(raw) > MaxHeaderValueBytes {
		return "", problem(CodeContextTooLarge, header, "value exceeds scalar limit")
	}
	s := strings.Trim(raw, " \t")
	if s == "" {
		return "", problem(CodeInvalidSyntax, header, "empty value")
	}
	for i := 0; i < len(s); i++ {
		if s[i] < 32 || s[i] > 126 {
			return "", problem(CodeInvalidSyntax, header, "expected printable ASCII")
		}
	}
	return s, nil
}
func parseInteger(header, raw string, min, max int64) (int64, error) {
	s, e := normalizeScalar(header, raw)
	if e != nil {
		return 0, e
	}
	if len(s) > 1 && s[0] == '0' {
		return 0, problem(CodeInvalidSyntax, header, "leading zeros")
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return 0, problem(CodeInvalidSyntax, header, "expected unsigned decimal")
		}
	}
	v, e := strconv.ParseInt(s, 10, 64)
	if e != nil || v < min || v > max {
		return 0, problem(CodeOutOfRange, header, "integer outside bounds")
	}
	return v, nil
}
func parseBoolean(header, raw string) (bool, error) {
	s, e := normalizeScalar(header, raw)
	if e != nil {
		return false, e
	}
	switch s {
	case "true":
		return true, nil
	case "false":
		return false, nil
	}
	return false, problem(CodeInvalidSyntax, header, "expected true or false")
}
func parseID(header, raw string, length int, allowZero bool) (string, error) {
	s, e := normalizeScalar(header, raw)
	if e != nil {
		return "", e
	}
	if len(s) != length {
		return "", problem(CodeInvalidSyntax, header, "incorrect ID length")
	}
	zero := true
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return "", problem(CodeInvalidSyntax, header, "expected lowercase hexadecimal")
		}
		if c != '0' {
			zero = false
		}
	}
	if zero && !allowZero {
		return "", problem(CodeInvalidSyntax, header, "zero ID prohibited")
	}
	return s, nil
}
