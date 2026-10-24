package dev.rpe.protocol;

/**
 * A protocol error with its code, the header it is about and, if a spec rule was broken, that rule
 * (like {@code R-T02}).
 */
public final class ProtocolException extends IllegalArgumentException {
    private final ErrorCode code;
    private final String header;
    private final String detail;
    private final String rule;

    public ProtocolException(ErrorCode code, String header, String detail) {
        this(null, code, header, detail);
    }

    public ProtocolException(String rule, ErrorCode code, String header, String detail) {
        super(message(rule, code, header, detail));
        this.code = code;
        this.header = header;
        this.detail = detail;
        this.rule = rule;
    }

    private static String message(String rule, ErrorCode code, String header, String detail) {
        StringBuilder sb = new StringBuilder();
        if (rule != null && !rule.isEmpty()) {
            sb.append(rule).append(' ');
        }
        sb.append(code.name());
        if (header != null && !header.isEmpty()) {
            sb.append(": ").append(header);
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }

    public ErrorCode code() { return code; }
    public String header() { return header; }
    public String detail() { return detail; }
    /** The spec rule, or null if the error isn't about a rule. */
    public String rule() { return rule; }
}
