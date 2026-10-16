package dev.rpe.protocol;

/** Cancellation state carried in the context; terminal once set (R-C01, R-T04). */
public enum CancelState {
    NONE("none"), CANCEL("cancel"), EXPIRED("expired");

    private final String wire;

    CancelState(String wire) { this.wire = wire; }

    public String wire() { return wire; }

    static CancelState fromWire(String s) {
        for (CancelState c : values()) {
            if (c.wire.equals(s)) return c;
        }
        throw new ProtocolException(ErrorCode.INVALID_SYNTAX, RpeHeaders.CONTEXT, "cs must be none, cancel or expired");
    }
}
