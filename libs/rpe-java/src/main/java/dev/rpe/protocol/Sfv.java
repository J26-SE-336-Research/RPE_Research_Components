package dev.rpe.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The part of RFC 8941 dictionaries our headers use: integers, strings, tokens and booleans, with no parameters or
 * inner lists. Duplicate keys are an error, which is stricter than the RFC, because two values for the same field
 * would be confusing. It behaves exactly like the Go version, the conformance kit checks that.
 */
final class Sfv {
    enum Kind { INTEGER, STRING, TOKEN, BOOLEAN }

    /** One value. {@code raw} is the original text, kept so unknown members can be passed on unchanged. */
    record Item(Kind kind, long integer, String string, boolean bool, String raw) {}

    record Member(String key, Item item) {}

    private Sfv() {}

    static List<Member> parseDictionary(String header, String input) {
        String s = trim(input);
        if (s.isEmpty()) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "empty dictionary");
        }
        List<Member> members = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (!s.isEmpty()) {
            int k = keyEnd(header, s);
            String key = s.substring(0, k);
            String rest = s.substring(k);
            if (!seen.add(key)) {
                throw new ProtocolException(ErrorCode.DUPLICATE_HEADER, header, "duplicate member " + key);
            }
            Item item;
            if (rest.startsWith("=")) {
                Parsed p = parseBareItem(header, rest.substring(1));
                item = p.item;
                rest = p.rest;
            } else {
                item = new Item(Kind.BOOLEAN, 0, "", true, "?1");
            }
            if (rest.startsWith(";")) {
                throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "parameters are not supported in RPE v1");
            }
            members.add(new Member(key, item));
            rest = trimLeft(rest);
            if (rest.isEmpty()) {
                break;
            }
            if (rest.charAt(0) != ',') {
                throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "expected ',' between members");
            }
            s = trimLeft(rest.substring(1));
            if (s.isEmpty()) {
                throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "trailing comma");
            }
        }
        return members;
    }

    private record Parsed(Item item, String rest) {}

    private static int keyEnd(String header, String s) {
        if (s.isEmpty() || !(isLowerAlpha(s.charAt(0)) || s.charAt(0) == '*')) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "member key must start with a lowercase letter");
        }
        int i = 1;
        while (i < s.length() && (isLowerAlpha(s.charAt(i)) || isDigit(s.charAt(i)) || "_-.*".indexOf(s.charAt(i)) >= 0)) {
            i++;
        }
        return i;
    }

    private static Parsed parseBareItem(String header, String s) {
        if (s.isEmpty()) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "missing value");
        }
        char c = s.charAt(0);
        if (c == '-' || isDigit(c)) {
            int i = 1;
            while (i < s.length() && isDigit(s.charAt(i))) {
                i++;
            }
            if (i < s.length() && s.charAt(i) == '.') {
                throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "decimals are not used in RPE v1");
            }
            String digits = s.substring(0, i);
            String unsigned = digits.startsWith("-") ? digits.substring(1) : digits;
            if (digits.equals("-") || unsigned.length() > 15) {
                throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "invalid integer");
            }
            return new Parsed(new Item(Kind.INTEGER, Long.parseLong(digits), "", false, digits), s.substring(i));
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            for (int i = 1; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\\') {
                    if (i + 1 >= s.length() || (s.charAt(i + 1) != '"' && s.charAt(i + 1) != '\\')) {
                        throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "invalid string escape");
                    }
                    b.append(s.charAt(i + 1));
                    i++;
                } else if (ch == '"') {
                    return new Parsed(new Item(Kind.STRING, 0, b.toString(), false, s.substring(0, i + 1)), s.substring(i + 1));
                } else if (ch < 0x20 || ch > 0x7e) {
                    throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "string must be printable ASCII");
                } else {
                    b.append(ch);
                }
            }
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "unterminated string");
        }
        if (c == '?') {
            if (s.length() < 2 || (s.charAt(1) != '0' && s.charAt(1) != '1')) {
                throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "invalid boolean");
            }
            return new Parsed(new Item(Kind.BOOLEAN, 0, "", s.charAt(1) == '1', s.substring(0, 2)), s.substring(2));
        }
        if (isAlpha(c) || c == '*') {
            int i = 1;
            while (i < s.length() && isTokenChar(s.charAt(i))) {
                i++;
            }
            String token = s.substring(0, i);
            return new Parsed(new Item(Kind.TOKEN, 0, token, false, token), s.substring(i));
        }
        throw new ProtocolException(ErrorCode.INVALID_SYNTAX, header, "unsupported value type");
    }

    /** Writes members in a fixed order: known keys in the order written, then unknown ones sorted by key. */
    static final class Writer {
        private final List<String> parts = new ArrayList<>();

        Writer integer(String key, long v) { parts.add(key + "=" + v); return this; }
        Writer token(String key, String v) { parts.add(key + "=" + v); return this; }
        Writer string(String key, String v) { parts.add(key + "=" + quote(v)); return this; }
        Writer bool(String key, boolean v) { parts.add(key + (v ? "=?1" : "=?0")); return this; }

        Writer unknown(Map<String, String> members) {
            if (members != null) {
                new TreeMap<>(members).forEach((k, v) -> parts.add(k + "=" + v));
            }
            return this;
        }

        @Override public String toString() { return String.join(", ", parts); }
    }

    static String quote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) a++;
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
        return s.substring(a, b);
    }

    static String trimLeft(String s) {
        int a = 0;
        while (a < s.length() && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) a++;
        return s.substring(a);
    }

    static boolean isLowerAlpha(char c) { return c >= 'a' && c <= 'z'; }
    static boolean isAlpha(char c) { return isLowerAlpha(c) || (c >= 'A' && c <= 'Z'); }
    static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    static boolean isTokenChar(char c) { return isAlpha(c) || isDigit(c) || "!#$%&'*+-.^_`|~:/".indexOf(c) >= 0; }

    // helpers to read typed members, used by the header classes

    static long wantInt(Member m, long lo, long hi) {
        if (m.item().kind() != Kind.INTEGER) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, RpeHeaders.CONTEXT, m.key() + " must be an integer");
        }
        if (m.item().integer() < lo || m.item().integer() > hi) {
            throw new ProtocolException(ErrorCode.OUT_OF_RANGE, RpeHeaders.CONTEXT, m.key() + " out of range");
        }
        return m.item().integer();
    }

    static String wantString(Member m, int minLen, int maxLen) {
        if (m.item().kind() != Kind.STRING) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, RpeHeaders.CONTEXT, m.key() + " must be a string");
        }
        int n = m.item().string().length();
        if (n < minLen || n > maxLen) {
            throw new ProtocolException(ErrorCode.OUT_OF_RANGE, RpeHeaders.CONTEXT, m.key() + " length out of range");
        }
        return m.item().string();
    }

    static String wantId(Member m, int length, boolean allowZero) {
        String raw = wantString(m, length, length);
        String s = trim(raw);
        if (s.isEmpty()) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, RpeHeaders.CONTEXT, "empty value");
        }
        if (s.length() != length) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, RpeHeaders.CONTEXT, "incorrect ID length");
        }
        boolean zero = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new ProtocolException(ErrorCode.INVALID_SYNTAX, RpeHeaders.CONTEXT, "expected lowercase hexadecimal");
            }
            if (c != '0') zero = false;
        }
        if (zero && !allowZero) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, RpeHeaders.CONTEXT, "zero ID prohibited");
        }
        return s;
    }
}
