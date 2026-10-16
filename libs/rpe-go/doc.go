// Package rpe implements the RPE resilience protocol for Go services.
//
// The current header set (spec section 2) is in:
//
//	context.go            X-RPE-Context, X-RPE-Hop and X-RPE-Result: decoding, encoding, the context guard
//	structured_fields.go  the RFC 8941 dictionary subset the headers use
//	http.go               Middleware (inbound) and Transport (outbound), and the fan-out budget split
//
// The original prototype encoded each field as its own HTTP header. That encoding is kept in the
// perfield_*.go files as a candidate for the wire-format study. deadline.go, ledger.go, state.go,
// retry.go and topology.go hold the building blocks of that prototype (monotonic deadlines, the
// retry-budget ledger, cancellation state and the per-hop topology trail).
package rpe
