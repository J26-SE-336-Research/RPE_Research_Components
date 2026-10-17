package com.example.proxy;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.netty.handler.codec.http.HttpHeaders;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Creates one span for each HTTP hop observed by this sidecar and propagates W3C trace context. */
public final class ProxyTelemetry {
    private static final Logger LOGGER = Logger.getLogger(ProxyTelemetry.class.getName());
    private static final TextMapGetter<HttpHeaders> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(HttpHeaders carrier) {
            return carrier.names();
        }
        @Override public String get(HttpHeaders carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };
    private static final TextMapSetter<HttpHeaders> SETTER = (carrier, key, value) -> {
        if (carrier != null) carrier.set(key, value);
    };

    private static volatile OpenTelemetry telemetry;
    private static volatile OpenTelemetrySdk sdk;
    private static volatile Tracer tracer;
    private static volatile String serviceName = "unknown-service";
    private static final PresetTraceIdGenerator ID_GENERATOR = new PresetTraceIdGenerator();

    private ProxyTelemetry() {}

    public static synchronized void initialize(String name, String otlpEndpoint) {
        if (telemetry != null) return;
        serviceName = name == null || name.isBlank() ? "unknown-service" : name;
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            // Without an exporter no span would ever be stored, so the proxy must not insert its own span ids into
            // traceparent either: a no-op tracer leaves the application's trace context unchanged.
            LOGGER.warning("OTLP trace export is disabled; set OTEL_EXPORTER_OTLP_ENDPOINT to enable traces");
            telemetry = OpenTelemetry.noop();
            tracer = telemetry.getTracer("com.example.netty-sidecar-proxy", "1.0.0");
            return;
        }
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), serviceName,
                AttributeKey.stringKey("service.namespace"), "rpe")));
        String endpoint = tracesEndpoint(otlpEndpoint);
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();
        SpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setScheduleDelay(Duration.ofMillis(500))
                .build();
        LOGGER.info("OTLP trace export enabled for service=" + serviceName + " endpoint=" + endpoint);
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setResource(resource)
                        .setSampler(Sampler.alwaysOn())
                        .setIdGenerator(ID_GENERATOR)
                        .addSpanProcessor(processor)
                        .build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        telemetry = sdk;
        tracer = sdk.getTracer("com.example.netty-sidecar-proxy", "1.0.0");
    }

    /** OTLP/HTTP traces URL; an endpoint without a path (for example http://tempo:4318) gets /v1/traces. */
    static String tracesEndpoint(String endpoint) {
        String trimmed = endpoint.trim();
        try {
            java.net.URI uri = java.net.URI.create(trimmed);
            String path = uri.getRawPath();
            if (uri.getScheme() != null && uri.getHost() != null && (path == null || path.isEmpty() || path.equals("/"))) {
                return trimmed.replaceAll("/+$", "") + "/v1/traces";
            }
        } catch (IllegalArgumentException invalid) {
            // the exporter reports an invalid endpoint itself
        }
        return trimmed;
    }

    public static String serviceName() { return serviceName; }

    public static Context extract(HttpHeaders headers) {
        return telemetry().getPropagators().getTextMapPropagator().extract(Context.root(), headers, GETTER);
    }

    public static void inject(Span span, HttpHeaders headers) {
        telemetry().getPropagators().getTextMapPropagator().inject(Context.root().with(span), headers, SETTER);
    }

    public static Span startHop(String method, String route, String requestId, long budgetMillis,
                                long absoluteDeadlineMillis, boolean outbound, String peerService, Context parent) {
        return startHop(method, route, requestId, budgetMillis, absoluteDeadlineMillis, outbound, peerService, parent, null);
    }

    /**
     * Like {@link #startHop(String, String, String, long, long, boolean, String, Context)}; when the span starts a
     * new trace, {@code rootTraceId} (a valid W3C trace-id, such as the request id) becomes its trace-id.
     */
    public static Span startHop(String method, String route, String requestId, long budgetMillis,
                                long absoluteDeadlineMillis, boolean outbound, String peerService, Context parent,
                                String rootTraceId) {
        Context effectiveParent = parent == null ? Context.root() : parent;
        var builder = tracer.spanBuilder((outbound ? "HTTP client " : "HTTP server ") + method + " " + route)
                .setParent(effectiveParent)
                .setSpanKind(outbound ? SpanKind.CLIENT : SpanKind.SERVER);
        Span span;
        if (rootTraceId != null && TraceId.isValid(rootTraceId)
                && !Span.fromContext(effectiveParent).getSpanContext().isValid()) {
            span = ID_GENERATOR.withTraceId(rootTraceId, builder::startSpan);
        } else {
            span = builder.startSpan();
        }
        span.setAttribute("service.name", serviceName)
                .setAttribute("http.request.method", method)
                .setAttribute("rpe.chain.route", route)
                .setAttribute("rpe.rid", requestId)
                .setAttribute("rpe.deadline.budget_ms", budgetMillis)
                .setAttribute("rpe.deadline.unix_ms", absoluteDeadlineMillis)
                .setAttribute("rpe.direction", outbound ? "outbound" : "inbound");
        if (outbound && peerService != null && !peerService.isBlank()) {
            span.setAttribute("peer.service", peerService);
        }
        return span;
    }

    public static void deadlineExceeded(Span span, String requestId, long budgetMillis,
                                        long deadlineMillis, String source) {
        if (span == null || !span.getSpanContext().isValid()) return;
        span.addEvent("deadline.exceeded", Attributes.of(
                AttributeKey.stringKey("rpe.rid"), requestId,
                AttributeKey.stringKey("service.name"), serviceName,
                AttributeKey.longKey("rpe.deadline.budget_ms"), budgetMillis,
                AttributeKey.longKey("rpe.deadline.unix_ms"), deadlineMillis,
                AttributeKey.stringKey("rpe.deadline.source"), source));
    }

    public static void propagatedDeadlineExceeded(Span span, String requestId) {
        if (span == null || !span.getSpanContext().isValid()) return;
        span.addEvent("deadline.exceeded.propagated", Attributes.of(
                AttributeKey.stringKey("rpe.rid"), requestId,
                AttributeKey.stringKey("service.name"), serviceName));
    }

    public static void endHop(Span span, int statusCode, Throwable failure) {
        if (span == null || !span.getSpanContext().isValid()) return;
        if (statusCode > 0) span.setAttribute("http.response.status_code", statusCode);
        if (failure != null) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getClass().getSimpleName());
        } else if (statusCode >= 500) {
            span.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
        }
        span.end();
    }

    public static synchronized void shutdown() {
        try {
            if (sdk != null) sdk.close();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Could not flush proxy telemetry", ex);
        } finally {
            sdk = null;
            telemetry = null;
            tracer = null;
        }
    }

    private static OpenTelemetry telemetry() {
        OpenTelemetry current = telemetry;
        if (current == null) throw new IllegalStateException("Proxy telemetry has not been initialized");
        return current;
    }

    /** Random ids, except that a root span started through {@link #withTraceId} uses the given trace-id. */
    private static final class PresetTraceIdGenerator implements IdGenerator {
        private final ThreadLocal<String> next = new ThreadLocal<>();

        Span withTraceId(String traceId, java.util.function.Supplier<Span> start) {
            next.set(traceId);
            try {
                return start.get();
            } finally {
                next.remove();
            }
        }

        @Override public String generateSpanId() {
            return IdGenerator.random().generateSpanId();
        }

        @Override public String generateTraceId() {
            String preset = next.get();
            return preset != null ? preset : IdGenerator.random().generateTraceId();
        }
    }
}
