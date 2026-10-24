package rpe

type ErrorCode string

const (
	CodeMissingHeader          ErrorCode = "MISSING_HEADER"
	CodeDuplicateHeader        ErrorCode = "DUPLICATE_HEADER"
	CodeInvalidSyntax          ErrorCode = "INVALID_SYNTAX"
	CodeOutOfRange             ErrorCode = "OUT_OF_RANGE"
	CodeUnsupportedVersion     ErrorCode = "UNSUPPORTED_VERSION"
	CodeInvalidFlags           ErrorCode = "INVALID_FLAGS"
	CodeInvalidTransition      ErrorCode = "INVALID_TRANSITION"
	CodeUnknownRoute           ErrorCode = "UNKNOWN_ROUTE"
	CodeContextTooLarge        ErrorCode = "CONTEXT_TOO_LARGE"
	CodeInvalidResponseContext ErrorCode = "INVALID_RESPONSE_CONTEXT"
)

type ProtocolError struct {
	Code           ErrorCode
	Header, Detail string
	// Rule is the spec rule that was broken, like "R-T02".
	Rule string
}

func (e *ProtocolError) Error() string {
	s := string(e.Code)
	if e.Rule != "" {
		s = e.Rule + " " + s
	}
	if e.Header != "" {
		s += ": " + e.Header
	}
	if e.Detail != "" {
		s += ": " + e.Detail
	}
	return s
}
func problem(code ErrorCode, header, detail string) error {
	return &ProtocolError{Code: code, Header: header, Detail: detail}
}

func ruleProblem(rule string, code ErrorCode, header, detail string) error {
	return &ProtocolError{Code: code, Header: header, Detail: detail, Rule: rule}
}
