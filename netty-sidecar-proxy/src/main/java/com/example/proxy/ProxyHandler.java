package com.example.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProxyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger LOGGER = Logger.getLogger(ProxyHandler.class.getName());
    private static final String DEADLINE = "deadlinevalue";
    private static final String EXCEEDED = "deadlineExceded";
    private static final String CANCELLATION = "cancellation_Triggered";
    private static final String REQUEST_ID = "Request_id";
    private static final String ROUTE = "Request_route";
    private static final String BUDGET = "Request_deadline_millis";
    private static final String CHAIN_METHOD = "Request_method";
    static record ContextEntry(HttpHeaders headers, Object generation, boolean inboundRequest,
                                boolean gatewayChain, boolean deadlineMetricRecorded,
                                boolean cancellationMetricRecorded, Span span, String serviceName,
                                String method, String route, long budgetMillis) {}

    private static final ConcurrentHashMap<String, ContextEntry> REQUEST_CONTEXTS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService CONTEXT_EXPIRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "request-context-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private final ProxyConfig config;
    private final BackpressureController backpressure;

    private static void expireRequest(String requestId, Object generation) {
        REQUEST_CONTEXTS.computeIfPresent(requestId, (id, entry) -> {
            if (entry.generation() != generation) return entry;
            LOGGER.info(() -> "Request context TTL expired for Request_id=" + requestId);
            return null;
        });
    }

    static HttpHeaders requestContext(String requestId) {
        ContextEntry entry = REQUEST_CONTEXTS.get(requestId);
        return entry == null ? null : entry.headers().copy();
    }

    private static void markDeadlineExceeded(String requestId, Object generation) {
        REQUEST_CONTEXTS.computeIfPresent(requestId, (id, entry) -> {
            if (entry.generation() != generation) return entry;
            HttpHeaders existing = entry.headers();
            boolean alreadyExceeded = Boolean.parseBoolean(existing.get(EXCEEDED));
            boolean alreadyCancelled = Boolean.parseBoolean(existing.get(CANCELLATION));
            HttpHeaders updated = existing.copy();
            updated.set(EXCEEDED, "true");
            updated.set(CANCELLATION, "true");
            if (!alreadyExceeded) {
                LOGGER.info(() -> "Deadline exceeded for Request_id=" + requestId + " service=" + entry.serviceName()
                        + "; cancellation_Triggered=true");
                ProxyTelemetry.deadlineExceeded(entry.span(), requestId, entry.budgetMillis(),
                        parseLong(existing.get(DEADLINE)), "local_timer");
                ProxyMetrics.recordDeadlineDetected(entry.serviceName(), entry.method(), entry.route(), entry.budgetMillis());
            }
            boolean deadlineMetricRecorded = entry.deadlineMetricRecorded();
            boolean cancellationMetricRecorded = entry.cancellationMetricRecorded();
            if (entry.inboundRequest() && !deadlineMetricRecorded) {
                deadlineMetricRecorded = true;
            }
            if (entry.inboundRequest() && !alreadyCancelled && !cancellationMetricRecorded) {
                ProxyMetrics.recordCancellation(entry.serviceName(), entry.method(), entry.route(), entry.budgetMillis());
                cancellationMetricRecorded = true;
            }
            return new ContextEntry(updated, entry.generation(), entry.inboundRequest(),
                    entry.gatewayChain(), deadlineMetricRecorded, cancellationMetricRecorded, entry.span(),
                    entry.serviceName(), entry.method(), entry.route(), entry.budgetMillis());
        });
    }

    static void markResponseStatus(String requestId, boolean deadlineExceeded, boolean cancellationTriggered) {
        REQUEST_CONTEXTS.computeIfPresent(requestId, (id, entry) -> {
            HttpHeaders existing = entry.headers();
            HttpHeaders updated = existing.copy();
            boolean alreadyExceeded = Boolean.parseBoolean(existing.get(EXCEEDED));
            boolean alreadyCancelled = Boolean.parseBoolean(existing.get(CANCELLATION));
            boolean nowExceeded = alreadyExceeded || deadlineExceeded;
            boolean nowCancelled = alreadyCancelled || cancellationTriggered || deadlineExceeded;
            updated.set(EXCEEDED, Boolean.toString(nowExceeded));
            updated.set(CANCELLATION, Boolean.toString(nowCancelled));
            boolean deadlineMetricRecorded = entry.deadlineMetricRecorded();
            boolean cancellationMetricRecorded = entry.cancellationMetricRecorded();
            if (!alreadyExceeded && nowExceeded) {
                ProxyTelemetry.propagatedDeadlineExceeded(entry.span(), requestId);
            }
            if (entry.inboundRequest() && nowCancelled && !cancellationMetricRecorded) {
                ProxyMetrics.recordCancellation(entry.serviceName(), entry.method(), entry.route(), entry.budgetMillis());
                cancellationMetricRecorded = true;
            }
            return new ContextEntry(updated, entry.generation(), entry.inboundRequest(),
                    entry.gatewayChain(), deadlineMetricRecorded, cancellationMetricRecorded, entry.span(),
                    entry.serviceName(), entry.method(), entry.route(), entry.budgetMillis());
        });
    }

    static ContextEntry contextEntry(String requestId) { return REQUEST_CONTEXTS.get(requestId); }

    static void completeGatewayChain(String requestId, boolean responseLate) {
        REQUEST_CONTEXTS.computeIfPresent(requestId, (id, entry) -> {
            if (!entry.gatewayChain()) return entry;
            HttpHeaders updated = entry.headers().copy();
            boolean alreadyExceeded = Boolean.parseBoolean(updated.get(EXCEEDED));
            boolean exceeded = alreadyExceeded || responseLate;
            if (exceeded && !alreadyExceeded) {
                updated.set(EXCEEDED, "true");
                LOGGER.info("End-to-end deadline exceeded for Request_id=" + requestId
                        + " service=" + entry.serviceName() + " at gateway response");
                ProxyTelemetry.deadlineExceeded(entry.span(), requestId, entry.budgetMillis(),
                        parseLong(updated.get(DEADLINE)), "gateway_response");
                ProxyMetrics.recordDeadlineDetected(entry.serviceName(), entry.method(), entry.route(), entry.budgetMillis());
            }
            ProxyMetrics.recordGatewayChainCompleted(entry.serviceName(), entry.method(), entry.route(),
                    entry.budgetMillis(), exceeded);
            return new ContextEntry(updated, entry.generation(), entry.inboundRequest(), entry.gatewayChain(),
                    entry.deadlineMetricRecorded() || exceeded, entry.cancellationMetricRecorded(), entry.span(),
                    entry.serviceName(), entry.method(), entry.route(), entry.budgetMillis());
        });
    }

    private static long parseLong(String value) {
        try { return value == null ? 0 : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public ProxyHandler(ProxyConfig config) {
        this.config = config;
        this.backpressure = BackpressureController.instance(config);
    }

    private static void rejectMissingContext(ChannelHandlerContext ctx, FullHttpRequest request,
                                             String missing,
                                             ProxyMetrics.InboundRequestTracker inboundTracker) {
        LOGGER.warning(() -> "Rejecting " + request.method() + " " + requestPath(request.uri())
                + " from " + ctx.channel().remoteAddress() + ": missing " + missing);
        sendError(ctx, HttpResponseStatus.BAD_REQUEST,
                "Missing required propagation header or context: " + missing, inboundTracker);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, FullHttpRequest request) {
        long requestStartNanos = System.nanoTime();
        if (!(clientCtx.channel().localAddress() instanceof InetSocketAddress localAddress)
                || localAddress.isUnresolved()) {
            sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                    "Could not determine intercepted connection destination", null);
            return;
        }

        boolean outboundRedirect = localAddress.getPort() == config.listenPort();
        RpeContractMetrics.Hop hop = RpeContractMetrics.begin(outboundRedirect ? "outbound" : "inbound", requestStartNanos);
        InetSocketAddress destination;
        InetSocketAddress originalDestination = localAddress;
        String uri = request.uri();
        if (outboundRedirect) {
            try {
                OutboundTarget target = outboundTarget(request);
                destination = target.address();
                uri = target.requestUri();
            } catch (IllegalArgumentException invalidTarget) {
                hop.complete(400);
                sendError(clientCtx, HttpResponseStatus.BAD_REQUEST, invalidTarget.getMessage(), null);
                return;
            }
        } else {
            // The intercepted inbound destination is this pod's application port.
            // Connecting back to the pod IP can re-enter pod routing and TPROXY,
            // causing the same request to be intercepted repeatedly. Containers in
            // a pod share a network namespace, so loopback reaches the app directly.
            // A connection REDIRECTed to the separate inbound listener has lost its
            // original port, so it goes to the configured application port.
            int applicationPort = config.integration().inboundListenPort() != 0
                    && localAddress.getPort() == config.integration().inboundListenPort()
                    ? config.integration().appPort() : localAddress.getPort();
            destination = new InetSocketAddress("127.0.0.1", applicationPort);
        }
        // X-RPE-Context (Component 1 wire format) becomes the internal chain headers read below.
        RpeWire.Identity incomingIdentity = RpeWire.readRequest(request.headers(), System.currentTimeMillis());
        boolean gatewaySidecar = config.gatewaySidecar();
        // Treat every request entering the gateway pod as a new trust boundary.
        // Never let an external caller choose the chain ID or deadline/status.
        // The gateway's outbound interception is a different hop and must reuse
        // the context created here.
        boolean gatewayInbound = gatewaySidecar && !outboundRedirect;
        String requestId = request.headers().get(REQUEST_ID);
        boolean suppliedRequestId = requestId != null && !requestId.isBlank();
        if (gatewayInbound) {
            requestId = RpeWire.newRequestId();
            suppliedRequestId = false;
        }
        if (!suppliedRequestId && !gatewaySidecar) {
            if (config.integration().missingContextPassthrough()) {
                // not part of an RPE request chain (probe, admin call, caller without a sidecar)
                PassthroughForwarder.forward(clientCtx, request, destination, uri, hop);
                return;
            }
            hop.reject("invalid_context");
            rejectMissingContext(clientCtx, request, REQUEST_ID, null);
            return;
        }
        if (!suppliedRequestId) requestId = RpeWire.newRequestId();
        final String activeRequestId = requestId;
        final String logRequestUri = uri;
        LOGGER.info(() -> "Intercepted " + (outboundRedirect ? "outbound" : "inbound")
                + " " + request.method() + " " + requestPath(logRequestUri)
                + " requestId=" + activeRequestId
                + " client=" + clientCtx.channel().remoteAddress()
                + " originalDestination=" + originalDestination
                + " forwardingTo=" + destination
                + " contentBytes=" + request.content().readableBytes());
        ContextEntry storedEntry = REQUEST_CONTEXTS.get(requestId);
        HttpHeaders storedContext = storedEntry == null ? null : storedEntry.headers();
        boolean detectThisRequestDeadline = config.detectInboundDeadline() && !outboundRedirect;
        boolean requestIdOnly = suppliedRequestId
                && !request.headers().contains(DEADLINE)
                && !request.headers().contains(EXCEEDED)
                && !request.headers().contains(CANCELLATION);

        // Outbound service calls carry only Request_id. Their sidecar must find
        // the full chain context saved from that service's inbound request.
        if (requestIdOnly && storedContext == null && !gatewaySidecar) {
            hop.reject("invalid_context");
            rejectMissingContext(clientCtx, request, "stored context for " + REQUEST_ID, null);
            return;
        }

        GatewayDeadlineRules.Selection selection = gatewayInbound
                ? ActivePolicy.gatewayRules(config).select(request.method().name(), uri, config.defaultDeadlineMillis())
                : null;
        long budgetMillis = selection != null ? selection.deadlineMillis()
                : storedContext != null ? parseLong(storedContext.get(BUDGET), config.defaultDeadlineMillis())
                : parseLong(request.headers().get(BUDGET), config.defaultDeadlineMillis());
        String route = selection != null ? selection.route()
                : storedContext != null && storedContext.get(ROUTE) != null ? storedContext.get(ROUTE)
                : request.headers().get(ROUTE);
        if (route == null || route.isBlank()) route = request.method().name() + " /_unmatched";
        String chainMethod = storedContext != null && storedContext.get(CHAIN_METHOD) != null
                ? storedContext.get(CHAIN_METHOD)
                : request.headers().get(CHAIN_METHOD) == null ? request.method().name() : request.headers().get(CHAIN_METHOD);

        String deadlineValue = gatewayInbound ? null : request.headers().get(DEADLINE);
        if (requestIdOnly && storedContext != null) deadlineValue = storedContext.get(DEADLINE);
        if ((deadlineValue == null || deadlineValue.isBlank()) && gatewaySidecar) {
            deadlineValue = Long.toString(System.currentTimeMillis() + budgetMillis);
        }
        if (deadlineValue == null || deadlineValue.isBlank()) {
            hop.reject("invalid_context");
            rejectMissingContext(clientCtx, request, DEADLINE, null);
            return;
        }
        long deadline;
        try {
            deadline = Long.parseLong(deadlineValue);
        } catch (NumberFormatException invalidDeadline) {
            hop.reject("invalid_context");
            sendError(clientCtx, HttpResponseStatus.BAD_REQUEST, "Invalid " + DEADLINE + " header",
                    null);
            return;
        }

        boolean expired = (storedContext != null && Boolean.parseBoolean(storedContext.get(EXCEEDED)))
                || (detectThisRequestDeadline && deadline <= System.currentTimeMillis())
                || (!gatewayInbound && Boolean.parseBoolean(request.headers().get(EXCEEDED)));
        ProxyMetrics.InboundRequestTracker inboundTracker = outboundRedirect ? null
                : ProxyMetrics.beginInboundRequest(config.serviceName(), chainMethod, route, budgetMillis,
                        gatewayInbound);
        if (!requestIdOnly && !gatewaySidecar
                && (!request.headers().contains(EXCEEDED) || !request.headers().contains(CANCELLATION))) {
            hop.reject("invalid_context");
            rejectMissingContext(clientCtx, request, "deadline and cancellation flags", inboundTracker);
            return;
        }
        // Identity of the X-RPE-Context this request carries on: the received one; for a call that sent only
        // Request_id, a child of the chain's stored context; at the gateway trust boundary, a new root.
        RpeWire.Identity identity = gatewayInbound ? null : incomingIdentity;
        if (identity == null) {
            RpeWire.Identity stored = gatewayInbound || storedContext == null ? null
                    : RpeWire.restore(storedContext.get(RpeWire.STORED_CONTEXT));
            identity = stored != null ? RpeWire.child(stored)
                    : RpeWire.root(requestId, chainMethod, route, budgetMillis,
                            config.integration().rootRetryBudget(), ActivePolicy.epoch());
        }
        if (gatewaySidecar && outboundRedirect && identity != null && storedContext != null) {
            // Component 3 per-request consistency: the epoch pinned when the request entered the gateway proxy is
            // the chain's epoch; the application's own (polled, possibly lagging) value must not replace it.
            // With no policy active the proxy stamped no epoch, and the application's configured one stands.
            RpeWire.Identity root = RpeWire.restore(storedContext.get(RpeWire.STORED_CONTEXT));
            if (root != null && !root.context().policyEpoch().isEmpty()) {
                identity = RpeWire.withEpoch(identity, root.context().policyEpoch());
            }
        }
        final RpeWire.Identity activeIdentity = identity;
        HttpHeaders contextHeaders = new DefaultHttpHeaders();
        contextHeaders.set(REQUEST_ID, requestId);
        contextHeaders.set(DEADLINE, Long.toString(deadline));
        contextHeaders.set(EXCEEDED, Boolean.toString(expired));
        boolean incomingCancellation = (storedContext != null
                && Boolean.parseBoolean(storedContext.get(CANCELLATION)))
                || (!gatewayInbound && Boolean.parseBoolean(request.headers().get(CANCELLATION)));
        boolean cancellation = expired || incomingCancellation;
        contextHeaders.set(CANCELLATION, Boolean.toString(cancellation));
        contextHeaders.set(ROUTE, route);
        contextHeaders.set(BUDGET, Long.toString(budgetMillis));
        contextHeaders.set(CHAIN_METHOD, chainMethod);
        String storedIdentity = RpeWire.store(identity);
        if (storedIdentity != null) contextHeaders.set(RpeWire.STORED_CONTEXT, storedIdentity);
        if (expired) {
            LOGGER.info("Inbound deadline already exceeded for Request_id=" + requestId
                    + "; forwarding with cancellation_Triggered=true");
        }
        // If this request belongs to a chain already known by this sidecar,
        // keep the inbound request as the owner of the saved context.
        Context parentContext = outboundRedirect
                ? storedEntry != null && storedEntry.span() != null
                    ? Context.root().with(storedEntry.span()) : ProxyTelemetry.extract(request.headers())
                : gatewayInbound ? Context.root() : ProxyTelemetry.extract(request.headers());
        // A trace started at the gateway uses the request id as its trace-id (trace binding).
        Span requestSpan = ProxyTelemetry.startHop(request.method().name(), route, requestId, budgetMillis,
                deadline, outboundRedirect, outboundRedirect ? destination.getHostString() : "", parentContext,
                RpeWire.isRequestId(requestId) ? requestId : null);
        if (!outboundRedirect) {
            // Component 3 per-request policy consistency: this hop serves the request under the policy epoch it
            // carries (pe) when held here; the pin is released when the hop completes.
            String pinnedEpoch = ActivePolicy.pin(hop,
                    activeIdentity == null ? "" : activeIdentity.context().policyEpoch(), gatewayInbound);
            if (!pinnedEpoch.isEmpty()) requestSpan.setAttribute("rpe.policy_epoch", pinnedEpoch);
        }
        boolean ownsContext = false;
        Object contextGeneration = null;
        if (storedContext == null) {
            boolean inboundRequest = !outboundRedirect;
            boolean deadlineMetricRecorded = inboundRequest && expired;
            boolean cancellationMetricRecorded = inboundRequest && cancellation;
            ContextEntry candidate = new ContextEntry(contextHeaders.copy(), new Object(), inboundRequest,
                    gatewayInbound, deadlineMetricRecorded, cancellationMetricRecorded, requestSpan,
                    config.serviceName(), chainMethod, route, budgetMillis);
            ContextEntry existing = REQUEST_CONTEXTS.putIfAbsent(requestId, candidate);
            if (existing == null) {
                if (candidate.deadlineMetricRecorded()) {
                    ProxyMetrics.recordDeadlineDetected(config.serviceName(), chainMethod, route, budgetMillis);
                    ProxyTelemetry.deadlineExceeded(requestSpan, requestId, budgetMillis, deadline, "inbound_header");
                }
                if (candidate.cancellationMetricRecorded()) {
                    ProxyMetrics.recordCancellation(config.serviceName(), chainMethod, route, budgetMillis);
                }
                ownsContext = true;
                contextGeneration = candidate.generation();
                CONTEXT_EXPIRY_EXECUTOR.schedule(
                        () -> expireRequest(activeRequestId, candidate.generation()),
                        config.requestContextTtlMillis(), TimeUnit.MILLISECONDS);
            } else {
                storedContext = existing.headers();
                contextHeaders = storedContext.copy();
            }
        }
        final long activeDeadline = deadline;
        final Object activeContextGeneration = contextGeneration;
        final boolean activeOwnsContext = ownsContext;
        if (ownsContext && detectThisRequestDeadline) {
            long delayMillis = Math.max(0, deadline - System.currentTimeMillis());
            clientCtx.executor().schedule(
                    () -> markDeadlineExceeded(activeRequestId, activeContextGeneration), delayMillis, TimeUnit.MILLISECONDS);
        }

        FullHttpRequest forwarded = new DefaultFullHttpRequest(
                request.protocolVersion(),
                request.method(),
                uri,
                request.content().retainedDuplicate(),
                request.headers().copy(),
                request.trailingHeaders().copy()
        );

        String peer = peerKey(destination);
        if (outboundRedirect) {
            // This short-lived random token lets the receiver send feedback to this
            // sidecar without requiring a shared Kubernetes Secret.
            String callerToken = backpressure.registerOutboundSignalToken(peer);
            if (callerToken != null) {
                forwarded.headers().set(BackpressureController.PEER_KEY_HEADER, peer);
                forwarded.headers().set(BackpressureController.CALLER_TOKEN_HEADER, callerToken);
            } else {
                forwarded.headers().remove(BackpressureController.PEER_KEY_HEADER);
                forwarded.headers().remove(BackpressureController.CALLER_TOKEN_HEADER);
            }
        } else {
            String callerPeer = request.headers().get(BackpressureController.PEER_KEY_HEADER);
            String callerToken = request.headers().get(BackpressureController.CALLER_TOKEN_HEADER);
            if (!gatewayInbound && suppliedRequestId
                    && clientCtx.channel().remoteAddress() instanceof InetSocketAddress callerAddress
                    && callerAddress.getAddress() != null) {
                backpressure.noteInboundCaller(callerAddress.getAddress(), callerPeer, callerToken);
            }
            // Keep the internal routing key out of the application request.
            forwarded.headers().remove(BackpressureController.PEER_KEY_HEADER);
            forwarded.headers().remove(BackpressureController.CALLER_TOKEN_HEADER);
        }

        // Example proxy-side modification.
        forwarded.headers().set("X-My-Proxy", "Netty-Sidecar-Proxy");
        forwarded.headers().set(REQUEST_ID, requestId);
        forwarded.headers().set(DEADLINE, Long.toString(deadline));
        forwarded.headers().set(EXCEEDED, Boolean.toString(expired));
        forwarded.headers().set(CANCELLATION, contextHeaders.get(CANCELLATION));
        forwarded.headers().set(ROUTE, route);
        forwarded.headers().set(BUDGET, Long.toString(budgetMillis));
        forwarded.headers().set(CHAIN_METHOD, chainMethod);
        // A matching request id lets calls passing through this proxy reuse the
        // original chain's metadata instead of creating a new deadline.
        if (storedContext != null) {
            storedContext.forEach(entry -> forwarded.headers().set(entry.getKey(), entry.getValue()));
        }
        contextHeaders.forEach(entry -> forwarded.headers().set(entry.getKey(), entry.getValue()));
        ProxyTelemetry.inject(requestSpan, forwarded.headers());
        if (activeIdentity != null) RpeWire.bindTraceparent(forwarded.headers(), requestId);

        // chain state echoed on a backpressure rejection
        final HttpHeaders rejectionState = contextHeaders.copy();
        boolean originalKeepAlive = HttpUtil.isKeepAlive(request);
        BackpressureController.OutboundPermit outboundPermit = outboundRedirect
                ? backpressure.admitOutbound(peer)
                : new BackpressureController.OutboundPermit(true, false);
        if (!outboundPermit.admitted()) {
            forwarded.release();
            ProxyTelemetry.endHop(requestSpan, 503, null);
            hop.reject("pressure_fail_fast");
            sendBackpressure(clientCtx, rejectionState, originalKeepAlive, null, "circuit-open");
            return;
        }

        // The queued task owns this retained request after this method returns.
        Runnable forwardingWork = () -> {
        if (!clientCtx.channel().isActive()) {
            forwarded.release();
            if (outboundPermit.halfOpenProbe()) backpressure.cancelProbe(peer);
            if (outboundRedirect) backpressure.releaseOutbound(peer);
            ProxyTelemetry.endHop(requestSpan, 499, null);
            hop.complete(499);
            if (inboundTracker != null) inboundTracker.finish();
            return;
        }
        String poolKey = BackendConnectionPool.key(destination);
        BackendResponseHandler responseHandler = new BackendResponseHandler(clientCtx.channel(), activeRequestId,
                activeDeadline, detectThisRequestDeadline, activeOwnsContext, gatewayInbound, requestSpan,
                inboundTracker, requestStartNanos, backpressure, outboundRedirect ? peer : null,
                outboundPermit.halfOpenProbe(), hop, poolKey);
        // Sends the request on an open backend connection: a pooled one or a newly opened one, the same way.
        java.util.function.Consumer<Channel> send = backendChannel -> {
                    HttpHeaders latestContext = requestContext(activeRequestId);
                    if (latestContext != null) {
                        latestContext.forEach(entry -> forwarded.headers().set(entry.getKey(), entry.getValue()));
                    }
                    boolean activeCancellation = latestContext != null
                            && Boolean.parseBoolean(latestContext.get(CANCELLATION));
                    // Internal chain headers -> X-RPE-Context (Request_id stays only on the hop to the local app).
                    RpeWire.writeRequest(forwarded.headers(), activeIdentity, !outboundRedirect,
                            System.currentTimeMillis());
                    backendChannel.writeAndFlush(forwarded).addListener(writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            ProxyTelemetry.endHop(requestSpan, 502, writeFuture.cause());
                            hop.complete(502);
                            LOGGER.log(Level.SEVERE, "Backend request write failed requestId="
                                    + activeRequestId + " destination=" + destination, writeFuture.cause());
                            backendChannel.close();
                            sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                                    "Could not send request to backend", inboundTracker);
                        } else {
                            LOGGER.info(() -> "Backend request write succeeded requestId="
                                    + activeRequestId + " destination=" + destination);
                            if (activeCancellation) {
                                LOGGER.info(() -> "Forwarded cancellation_Triggered=true to "
                                        + destination + " for Request_id=" + activeRequestId);
                            }
                        }
                    });
        };

        Channel pooled = BackendConnectionPool.acquire(clientCtx.channel().eventLoop(), poolKey);
        if (pooled != null) {
            pooled.pipeline().replace(BackendConnectionPool.RESPONSE_HANDLER, BackendConnectionPool.RESPONSE_HANDLER,
                    responseHandler);
            LOGGER.info(() -> "Reusing backend connection requestId=" + activeRequestId
                    + " destination=" + pooled.remoteAddress());
            send.accept(pooled);
            return;
        }

        Bootstrap backendBootstrap = new Bootstrap();
        backendBootstrap.group(clientCtx.channel().eventLoop())
                .channel(clientCtx.channel().getClass())
                .handler(new BackendInitializer(responseHandler))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

        LOGGER.info(() -> "Connecting to backend requestId=" + activeRequestId
                + " destination=" + destination);
        backendBootstrap.connect(destination)
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        if (outboundRedirect) {
                            backpressure.recordTransportFailure(peer, outboundPermit.halfOpenProbe());
                            backpressure.releaseOutbound(peer);
                        }
                        ProxyTelemetry.endHop(requestSpan, 502, future.cause());
                        hop.complete(502);
                        LOGGER.log(Level.SEVERE, "Backend connect failed requestId=" + activeRequestId
                                + " destination=" + destination, future.cause());
                        sendError(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                                "Could not connect to backend: " + future.cause().getMessage(), inboundTracker);
                        return;
                    }
                    BackendConnectionPool.opened();
                    Channel backendChannel = future.channel();
                    LOGGER.info(() -> "Backend connected requestId=" + activeRequestId
                            + " destination=" + backendChannel.remoteAddress());
                    send.accept(backendChannel);
                });
        };

        if (!outboundRedirect) {
            boolean queued = backpressure.enqueueInbound(
                    () -> {
                        try {
                            clientCtx.executor().execute(forwardingWork);
                        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                            forwarded.release();
                            ProxyTelemetry.endHop(requestSpan, 499, shuttingDown);
                            hop.complete(499);
                            if (inboundTracker != null) inboundTracker.finish();
                        }
                    });
            if (!queued) {
                forwarded.release();
                ProxyTelemetry.endHop(requestSpan, 503, null);
                hop.reject("overload");
                ProxyMetrics.recordInboundFailure(503, inboundTracker);
                ProxyMetrics.recordRequestDuration(System.nanoTime() - requestStartNanos, inboundTracker);
                if (inboundTracker != null) inboundTracker.finish();
                sendBackpressure(clientCtx, rejectionState, originalKeepAlive,
                        backpressure.inboundQueueUtilizationPercent(), "overload");
            }
        } else {
            Runnable dispatch = () -> {
                try {
                    clientCtx.executor().execute(forwardingWork);
                } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                    forwarded.release();
                    if (outboundPermit.halfOpenProbe()) backpressure.cancelProbe(peer);
                    backpressure.releaseOutbound(peer);
                    ProxyTelemetry.endHop(requestSpan, 499, shuttingDown);
                    hop.complete(499);
                    if (inboundTracker != null) inboundTracker.finish();
                }
            };
            // reason: overload when the peer's waiting queue is full, circuit-open when a queued call is
            // rejected because the peer's circuit opened meanwhile
            java.util.function.Consumer<String> rejectWith = reason -> {
                if (outboundPermit.halfOpenProbe()) backpressure.cancelProbe(peer);
                forwarded.release();
                ProxyTelemetry.endHop(requestSpan, 503, null);
                hop.reject(reason.equals("overload") ? "overload" : "pressure_fail_fast");
                if (inboundTracker != null) {
                    ProxyMetrics.recordInboundFailure(503, inboundTracker);
                    ProxyMetrics.recordRequestDuration(System.nanoTime() - requestStartNanos, inboundTracker);
                    inboundTracker.finish();
                }
                sendBackpressure(clientCtx, rejectionState, originalKeepAlive, null, reason);
            };
            if (!backpressure.enqueueOutbound(peer, dispatch, () -> rejectWith.accept("circuit-open"))) {
                rejectWith.accept("overload");
            }
        }
    }

    private static String requestPath(String uri) {
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query) + "?[query redacted]";
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.log(Level.SEVERE, "Proxy request pipeline failed client="
                + ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    private static OutboundTarget outboundTarget(FullHttpRequest request) {
        URI uri;
        try {
            uri = URI.create(request.uri());
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException("Invalid outbound HTTP request URI");
        }

        String host;
        int port;
        String backendUri;
        if (uri.isAbsolute()) {
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("Outbound proxy supports absolute http:// request URIs only");
            }
            host = uri.getHost();
            port = uri.getPort() >= 0 ? uri.getPort() : 80;
            backendUri = originForm(uri);
        } else {
            String authority = request.headers().get(HttpHeaderNames.HOST);
            if (authority == null || authority.isBlank()) {
                throw new IllegalArgumentException("Outbound HTTP request is missing the Host header");
            }
            URI hostUri;
            try {
                hostUri = URI.create("http://" + authority);
            } catch (IllegalArgumentException invalidAuthority) {
                throw new IllegalArgumentException("Invalid outbound HTTP Host header");
            }
            if (hostUri.getHost() == null || hostUri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("Invalid outbound HTTP Host header");
            }
            host = hostUri.getHost();
            port = hostUri.getPort() >= 0 ? hostUri.getPort() : 80;
            backendUri = request.uri();
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid outbound HTTP destination port");
        }
        return new OutboundTarget(InetSocketAddress.createUnresolved(host, port), backendUri);
    }

    private static String originForm(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private record OutboundTarget(InetSocketAddress address, String requestUri) {}

    private static String peerKey(InetSocketAddress address) {
        return address.getHostString().toLowerCase(java.util.Locale.ROOT) + ":" + address.getPort();
    }

    private static void sendBackpressure(ChannelHandlerContext ctx, HttpHeaders chainState, boolean keepAlive,
                                         Integer queueUtilizationPercent, String reason) {
        if (!ctx.channel().isActive()) return;
        byte[] body = "Request rejected due to downstream backpressure".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                io.netty.buffer.Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(BackpressureController.SIGNAL_HEADER, BackpressureController.SIGNAL_VALUE);
        if (queueUtilizationPercent != null) {
            response.headers().set(BackpressureController.QUEUE_UTILIZATION_HEADER,
                    Integer.toString(queueUtilizationPercent));
        }
        response.headers().set(HttpHeaderNames.RETRY_AFTER, "1");
        // the chain state goes upstream in X-RPE-Result (cs member), with the rejection reason
        response.headers().set(RpeWire.RESULT, RpeWire.rejectionResult(reason, queueUtilizationPercent,
                Boolean.parseBoolean(chainState.get(EXCEEDED)), Boolean.parseBoolean(chainState.get(CANCELLATION))));
        if (!keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        }
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status,
                                  String message, ProxyMetrics.InboundRequestTracker inboundTracker) {
        if (!ctx.channel().isActive()) {
            if (inboundTracker != null) inboundTracker.finish();
            return;
        }

        if (inboundTracker != null) {
            ProxyMetrics.recordInboundFailure(status.code(), inboundTracker);
        }

        LOGGER.warning(() -> "Sending proxy error status=" + status.code()
                + " client=" + ctx.channel().remoteAddress() + " reason=" + message);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                ctx.alloc().buffer().writeBytes(message.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(writeFuture -> {
            if (writeFuture.isSuccess() && inboundTracker != null) {
                ProxyMetrics.recordInboundSuccessfulRequest(status.code(), inboundTracker);
            }
            if (inboundTracker != null) inboundTracker.finish();
            ctx.close();
        });
    }
}
