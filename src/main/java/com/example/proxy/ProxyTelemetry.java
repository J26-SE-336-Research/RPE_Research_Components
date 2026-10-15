package com.example.proxy;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
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

    private static volatile OpenTelemetrySdk sdk;
    private static volatile Tracer tracer;
    private static volatile String serviceName = "unknown-service";

    private ProxyTelemetry() {}

    public static synchronized void initialize(String name, String otlpEndpoint) {
        if (sdk != null) return;
        serviceName = name == null || name.isBlank() ? "unknown-service" : name;
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), serviceName,
                AttributeKey.stringKey("service.namespace"), "rpe")));
        var providerBuilder = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn());
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(otlpEndpoint)
                    .build();
            SpanProcessor processor = BatchSpanProcessor.builder(exporter)
                    .setScheduleDelay(Duration.ofMillis(500))
                    .build();
            providerBuilder.addSpanProcessor(processor);
            LOGGER.info("OTLP trace export enabled for service=" + serviceName + " endpoint=" + otlpEndpoint);
        } else {
            LOGGER.warning("OTLP trace export is disabled; set OTEL_EXPORTER_OTLP_ENDPOINT to enable traces");
        }
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(providerBuilder.build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        tracer = sdk.getTracer("com.example.netty-sidecar-proxy", "1.0.0");
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
        Span span = tracer.spanBuilder((outbound ? "HTTP client " : "HTTP server ") + method + " " + route)
                .setParent(parent == null ? Context.root() : parent)
                .setSpanKind(outbound ? SpanKind.CLIENT : SpanKind.SERVER)
                .startSpan();
        span.setAttribute("service.name", serviceName)
                .setAttribute("http.request.method", method)
                .setAttribute("rrpe.chain.route", route)
                .setAttribute("rrpe.request.id", requestId)
                .setAttribute("rrpe.deadline.budget_ms", budgetMillis)
                .setAttribute("rrpe.deadline.unix_ms", absoluteDeadlineMillis)
                .setAttribute("rrpe.proxy.direction", outbound ? "outbound" : "inbound");
        if (outbound && peerService != null && !peerService.isBlank()) {
            span.setAttribute("peer.service", peerService);
        }
        return span;
    }

    public static void deadlineExceeded(Span span, String requestId, long budgetMillis,
                                        long deadlineMillis, String source) {
        if (span == null || !span.getSpanContext().isValid()) return;
        span.addEvent("deadline.exceeded", Attributes.of(
                AttributeKey.stringKey("rrpe.request.id"), requestId,
                AttributeKey.stringKey("service.name"), serviceName,
                AttributeKey.longKey("rrpe.deadline.budget_ms"), budgetMillis,
                AttributeKey.longKey("rrpe.deadline.unix_ms"), deadlineMillis,
                AttributeKey.stringKey("rrpe.deadline.source"), source));
    }

    public static void propagatedDeadlineExceeded(Span span, String requestId) {
        if (span == null || !span.getSpanContext().isValid()) return;
        span.addEvent("deadline.exceeded.propagated", Attributes.of(
                AttributeKey.stringKey("rrpe.request.id"), requestId,
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
        if (sdk == null) return;
        try {
            sdk.close();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Could not flush proxy telemetry", ex);
        } finally {
            sdk = null;
            tracer = null;
        }
    }

    private static OpenTelemetry telemetry() {
        if (sdk == null) throw new IllegalStateException("Proxy telemetry has not been initialized");
        return sdk;
    }
}
