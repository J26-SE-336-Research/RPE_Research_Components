package dev.rpe.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-band per-hop trail {@code X-RPE-Trail} (spec §12, rules R-P01..R-P05), verbose v1 encoding:
 * {@code rv=1, inc=?0, h="1:0:41250:F:0;2:1:39870:F:2"}. Each entry is
 * {@code svcId:parent:elapsedMicros:decision:consumedMs}: the service's registry id, the 1-based index of the calling
 * hop's entry (0 for a root), the time the hop spent including its callees (-1 while active), the decision code and
 * the part of the chain budget already used when the hop received the request.
 *
 * @param registryVersion service-registry version ({@code rv}) the ids belong to
 * @param incomplete      the trail is known to miss hops ({@code inc}): a hop without RPE, truncation, registry mismatch
 * @param entries         the entries in order
 */
public record Trail(long registryVersion, boolean incomplete, List<Entry> entries) {
    /** Decision codes: forward, early-reject, expired, cancelled, retry, active, partial, overload, unknown hop. */
    public static final String DECISIONS = "FEXCRAPOU";
    public static final int MAX_ENTRIES = 16;
    public static final int MAX_BYTES = 1400;
    static final long MAX_ELAPSED_MICROS = 999_999_999_999_999L;
    static final long MAX_CONSUMED_MILLIS = 2 * RpeHeaders.MAX_DEADLINE_MILLIS;

    public record Entry(long service, int parent, long elapsedMicros, char decision, long consumedMs) {
        String wire() {
            return service + ":" + parent + ":" + elapsedMicros + ":" + decision + ":" + consumedMs;
        }
    }

    public Trail {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public static Trail decode(String raw) {
        String h = RpeHeaders.TRAIL;
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ProtocolException(ErrorCode.CONTEXT_TOO_LARGE, h, "trail exceeds 1400 bytes");
        }
        long rv = 0;
        boolean inc = false, seenRv = false, seenInc = false;
        String list = "";
        for (Sfv.Member m : Sfv.parseDictionary(h, raw)) {
            switch (m.key()) {
                case "rv" -> {
                    seenRv = true;
                    if (m.item().kind() != Sfv.Kind.INTEGER || m.item().integer() < 1 || m.item().integer() > Integer.MAX_VALUE) {
                        throw new ProtocolException(ErrorCode.OUT_OF_RANGE, h, "rv must be an integer from 1");
                    }
                    rv = m.item().integer();
                }
                case "inc" -> {
                    seenInc = true;
                    if (m.item().kind() != Sfv.Kind.BOOLEAN) {
                        throw new ProtocolException(ErrorCode.INVALID_SYNTAX, h, "inc must be a boolean");
                    }
                    inc = m.item().bool();
                }
                case "h" -> {
                    if (m.item().kind() != Sfv.Kind.STRING) {
                        throw new ProtocolException(ErrorCode.INVALID_SYNTAX, h, "h must be a string");
                    }
                    list = m.item().string();
                }
                default -> { }
            }
        }
        if (!seenRv || !seenInc) {
            throw new ProtocolException(ErrorCode.MISSING_HEADER, h, "rv and inc are required");
        }
        List<Entry> entries = new ArrayList<>();
        if (!list.isEmpty()) {
            String[] parts = list.split(";", -1);
            if (parts.length > MAX_ENTRIES) {
                throw new ProtocolException(ErrorCode.OUT_OF_RANGE, h, "more than 16 entries");
            }
            for (int i = 0; i < parts.length; i++) {
                entries.add(parseEntry(parts[i], i + 1));
            }
        }
        return new Trail(rv, inc, entries);
    }

    private static long num(String s, long lo, long hi, String detail) {
        try {
            long v = Long.parseLong(s);
            if (v >= lo && v <= hi) return v;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new ProtocolException(ErrorCode.OUT_OF_RANGE, RpeHeaders.TRAIL, detail);
    }

    private static Entry parseEntry(String raw, int index) {
        String h = RpeHeaders.TRAIL;
        String[] f = raw.split(":", -1);
        if (f.length != 5) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, h, "an entry has five fields");
        }
        long svc = num(f[0], 0, 65_535, "svcId must be 0..65535");
        int parent = (int) num(f[1], 0, index - 1, "parent must refer to an earlier entry");
        long elapsed = num(f[2], -1, MAX_ELAPSED_MICROS, "elapsedMicros out of range");
        if (f[3].length() != 1 || DECISIONS.indexOf(f[3].charAt(0)) < 0) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, h, "unknown decision code");
        }
        char decision = f[3].charAt(0);
        if ((decision == 'A') != (elapsed == -1)) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, h, "only an active entry has elapsed -1");
        }
        long consumed = num(f[4], 0, MAX_CONSUMED_MILLIS, "consumedMs out of range");
        Entry e = new Entry(svc, parent, elapsed, decision, consumed);
        if (!e.wire().equals(raw)) {
            throw new ProtocolException(ErrorCode.INVALID_SYNTAX, h, "noncanonical entry");
        }
        return e;
    }

    public String encode() {
        Sfv.Writer w = new Sfv.Writer().integer("rv", registryVersion).bool("inc", incomplete);
        if (!entries.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) {
                if (!sb.isEmpty()) sb.append(';');
                sb.append(e.wire());
            }
            w.string("h", sb.toString());
        }
        return w.toString();
    }

    /**
     * Appends {@code sub} under the entry at 1-based index {@code parent} (0 adds it as a new root), re-indexing its
     * parents. Entries beyond the bounds are dropped with their descendants and the result is marked incomplete
     * (R-P02); a subtree of another registry version is not merged and only marks the result incomplete (R-P03).
     */
    public Trail append(Trail sub, int parent) {
        List<Entry> out = new ArrayList<>(entries);
        boolean inc = incomplete || sub.incomplete();
        if (sub.registryVersion() != registryVersion) {
            return new Trail(registryVersion, true, out);
        }
        int size = new Trail(registryVersion, false, List.of()).encode().length() + ", h=\"\"".length() - 1;
        for (Entry e : out) size += e.wire().length() + 1;
        int[] newIndex = new int[sub.entries().size() + 1];
        for (int i = 0; i < sub.entries().size(); i++) {
            Entry e = sub.entries().get(i);
            int p = parent;
            if (e.parent() > 0) {
                p = newIndex[e.parent()];
                if (p == 0) {
                    inc = true; // its parent was dropped
                    continue;
                }
            }
            Entry moved = new Entry(e.service(), p, e.elapsedMicros(), e.decision(), e.consumedMs());
            int n = moved.wire().length() + 1;
            if (out.size() >= MAX_ENTRIES || size + n > MAX_BYTES) {
                inc = true;
                continue;
            }
            size += n;
            out.add(moved);
            newIndex[i + 1] = out.size();
        }
        return new Trail(registryVersion, inc, out);
    }
}
