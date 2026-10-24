package dev.rpe.protocol;

/** Protocol error codes, the same strings as in the Go library. */
public enum ErrorCode {
    MISSING_HEADER, DUPLICATE_HEADER, INVALID_SYNTAX, OUT_OF_RANGE, UNSUPPORTED_VERSION, INVALID_FLAGS,
    INVALID_TRANSITION, UNKNOWN_ROUTE, CONTEXT_TOO_LARGE, INVALID_RESPONSE_CONTEXT
}
