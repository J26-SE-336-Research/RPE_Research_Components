package rpe

import (
	"context"
	"net/http"
	"strings"
)

type requestContextKey struct{}

// WithRequestContext stores a validated value by copy, never in a global variable.
func WithRequestContext(ctx context.Context, c RequestContext) (context.Context, error) {
	if e := ValidateRequest(c); e != nil {
		return nil, e
	}
	return context.WithValue(ctx, requestContextKey{}, c), nil
}
func RequestContextFrom(ctx context.Context) (RequestContext, bool) {
	c, ok := ctx.Value(requestContextKey{}).(RequestContext)
	return c, ok
}

// InjectRequest copies the request and its headers. The caller must first derive
// a fresh child/attempt context and validate its allocation receipt.
func InjectRequest(req *http.Request, c RequestContext) (*http.Request, error) {
	h, e := SerializeRequest(c)
	if e != nil {
		return nil, e
	}
	copy := req.Clone(req.Context())
	if copy.Header == nil {
		copy.Header = make(http.Header)
	}
	for _, name := range requestHeaders {
		for k := range copy.Header {
			if equalHeader(k, name) {
				delete(copy.Header, k)
			}
		}
	}
	for k, v := range h {
		copy.Header[k] = append([]string{}, v...)
	}
	if e = checkEnvelope(copy.Header); e != nil {
		return nil, e
	}
	return copy, nil
}

func equalHeader(a, b string) bool { return strings.EqualFold(a, b) }
