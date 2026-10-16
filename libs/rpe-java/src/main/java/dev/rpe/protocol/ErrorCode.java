package dev.rpe.protocol;

/** Protocol error codes (identical strings in every RPE implementation). */
public enum ErrorCode {
    MISSING_HEADER, DUPLICATE_HEADER, INVALID_SYNTAX, OUT_OF_RANGE, UNSUPPORTED_VERSION, INVALID_FLAGS,
    INVALID_TRANSITION, UNKNOWN_ROUTE, CONTEXT_TOO_LARGE, INVALID_RESPONSE_CONTEXT
}
