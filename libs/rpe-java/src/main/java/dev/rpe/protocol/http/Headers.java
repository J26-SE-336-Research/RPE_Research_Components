package dev.rpe.protocol.http;

import dev.rpe.protocol.ErrorCode;
import dev.rpe.protocol.ProtocolException;
import java.util.List;
import java.util.Map;

/** Reads one header value from any {@code Map<String, List<String>>}, ignoring case. */
final class Headers {
    private Headers() {}

    /** The value of {@code name}, or "" if missing. Sending it twice is a DUPLICATE_HEADER error. */
    static String single(Map<String, List<String>> headers, String name) {
        int count = 0;
        String value = "";
        boolean found = false;
        for (var e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                found = true;
                count += e.getValue().size();
                if (!e.getValue().isEmpty()) value = e.getValue().get(0);
            }
        }
        if (count > 1) throw new ProtocolException(ErrorCode.DUPLICATE_HEADER, name, "multiple values");
        if (count == 0 && found) throw new ProtocolException(ErrorCode.MISSING_HEADER, name, "header absent or has no value");
        return value;
    }
}
