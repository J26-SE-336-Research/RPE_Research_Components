package main

import (
	"context"
	"net/http"
	"net/url"
	"os"
	"time"

	rpe "github.com/J26-SE-336-Research/rpe/libs/rpe-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// setupTracing exports spans to OTEL_EXPORTER_OTLP_ENDPOINT (OTLP/HTTP, e.g. http://rpe-tempo.rpe-system:4318).
// Every request is traced (no sampling) so request-chain evidence is complete. Without an endpoint tracing is off.
func setupTracing(service string) func() {
	otel.SetTextMapPropagator(propagation.TraceContext{})
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		return func() {}
	}
	u, err := url.Parse(endpoint)
	if err != nil || u.Host == "" {
		return func() {}
	}
	opts := []otlptracehttp.Option{otlptracehttp.WithEndpoint(u.Host)}
	if u.Scheme != "https" {
		opts = append(opts, otlptracehttp.WithInsecure())
	}
	exp, err := otlptracehttp.New(context.Background(), opts...)
	if err != nil {
		return func() {}
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exp, sdktrace.WithBatchTimeout(time.Second)),
		sdktrace.WithResource(resource.NewSchemaless(attribute.String("service.name", service))),
		sdktrace.WithSampler(sdktrace.AlwaysSample()),
	)
	otel.SetTracerProvider(tp)
	return func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = tp.Shutdown(ctx)
	}
}

var tracer = otel.Tracer("rpe-demo-app")

// traceServer opens the inbound server span before the RPE middleware and binds the incoming
// traceparent to it, so the gateway's root RPE context uses the trace id as rid (trace binding).
func traceServer(service string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx := otel.GetTextMapPropagator().Extract(r.Context(), propagation.HeaderCarrier(r.Header))
		ctx, span := tracer.Start(ctx, r.Method+" "+r.URL.Path, trace.WithSpanKind(trace.SpanKindServer),
			trace.WithAttributes(attribute.String("http.request.method", r.Method), attribute.String("rpe.chain.route", r.URL.Path),
				attribute.String("rpe.direction", "inbound")))
		defer span.End()
		otel.GetTextMapPropagator().Inject(ctx, propagation.HeaderCarrier(r.Header))
		rec := &statusRecorder{ResponseWriter: w, code: http.StatusOK}
		next.ServeHTTP(rec, r.WithContext(ctx))
		span.SetAttributes(attribute.Int("rpe.status_code", rec.code))
		if rec.code >= 500 {
			span.SetStatus(codes.Error, http.StatusText(rec.code))
		}
	})
}

// annotateSpan records the RPE context on the server span (called inside the RPE middleware).
func annotateSpan(r *http.Request) {
	in, ok := rpe.FromContext(r.Context())
	if !ok {
		return
	}
	trace.SpanFromContext(r.Context()).SetAttributes(
		attribute.String("rpe.rid", in.Ctx.RID),
		attribute.Int64("rpe.ttl_ms", in.Ctx.TTLMillis),
		attribute.Int64("rpe.deadline.budget_ms", in.Ctx.BudgetMillis),
		attribute.Int64("rpe.rb", in.Ctx.RetryBudget),
		attribute.String("rpe.policy_epoch", in.Ctx.PolicyEpoch),
	)
}

// startClientSpan opens an outbound span for a downstream call and injects its traceparent.
func startClientSpan(ctx context.Context, req *http.Request) trace.Span {
	ctx, span := tracer.Start(ctx, req.Method+" "+req.URL.Host+req.URL.Path, trace.WithSpanKind(trace.SpanKindClient),
		trace.WithAttributes(attribute.String("rpe.direction", "outbound"), attribute.String("peer.service", req.URL.Hostname()),
			attribute.String("http.request.method", req.Method)))
	otel.GetTextMapPropagator().Inject(ctx, propagation.HeaderCarrier(req.Header))
	return span
}

type statusRecorder struct {
	http.ResponseWriter
	code int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.code = code
	s.ResponseWriter.WriteHeader(code)
}
