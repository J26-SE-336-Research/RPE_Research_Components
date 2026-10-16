package dev.rpe.protocol;

/** Header names and protocol limits of the RPE v1 header set . */
public final class RpeHeaders {
    /** End-to-end request context. */
    public static final String CONTEXT = "X-RPE-Context";
    /** Hop-scoped header: rewritten at every hop and never delivered to the application. */
    public static final String HOP = "X-RPE-Hop";
    /** Optional in-band per-hop trail. */
    public static final String TRAIL = "X-RPE-Trail";
    /** Response context. */
    public static final String RESULT = "X-RPE-Result";
    public static final String TRACEPARENT = "traceparent";

    public static final long SUPPORTED_VERSION = 1;
    public static final int REQUEST_ID_LENGTH = 32;
    public static final int INVOCATION_ID_LENGTH = 16;
    public static final String ROOT_PARENT_ID = "0000000000000000";
    public static final long MIN_DEADLINE_MILLIS = 1;
    public static final long MAX_DEADLINE_MILLIS = 600_000;
    public static final long MIN_RETRY_BUDGET = 0;
    public static final long MAX_RETRY_BUDGET = 255;
    public static final long MIN_BACKPRESSURE_LEVEL = 0;
    public static final long MAX_BACKPRESSURE_LEVEL = 3;
    public static final int MIN_ROUTE_BYTES = 1;
    public static final int MAX_ROUTE_BYTES = 128;
    public static final int MAX_POLICY_VERSION_BYTES = 64;
    public static final int MAX_HEADER_VALUE_BYTES = 256;

    private RpeHeaders() {}
}
