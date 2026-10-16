package dev.rpe.protocol;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Random identifiers (lowercase hex) and W3C trace binding (spec §5). */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private Ids() {}

    /** A random non-zero identifier of {@code bytes} bytes as lowercase hex. */
    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        do {
            RANDOM.nextBytes(b);
        } while (allZero(b));
        return HEX.formatHex(b);
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    /** The trace-id of a W3C traceparent header, or "" when absent or invalid. */
    public static String traceIdFromTraceparent(String traceparent) {
        if (traceparent == null) return "";
        String[] parts = traceparent.split("-", -1);
        if (parts.length != 4 || parts[1].length() != 32) return "";
        for (int i = 0; i < 32; i++) {
            if (Character.digit(parts[1].charAt(i), 16) < 0) return "";
        }
        if (parts[1].equals("0".repeat(32))) return "";
        return parts[1].toLowerCase();
    }

    /** A traceparent for the request id {@code rid} with a fresh span id. */
    public static String newTraceparent(String rid) {
        return "00-" + rid + "-" + random(8) + "-01";
    }
}
