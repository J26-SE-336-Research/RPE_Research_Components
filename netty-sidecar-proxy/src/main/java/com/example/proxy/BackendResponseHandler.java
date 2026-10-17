package com.example.proxy;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpUtil;
import io.opentelemetry.api.trace.Span;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BackendResponseHandler
        extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger LOGGER = Logger.getLogger(BackendResponseHandler.class.getName());

    private final Channel clientChannel;
    private final String requestId;
    private final long deadline;
    private final boolean detectInboundDeadline;
    private final boolean ownsContext;
    private final boolean gatewayChain;
    private final Span requestSpan;
    private final ProxyMetrics.InboundRequestTracker inboundTracker;
    private final long requestStartNanos;
    private final BackpressureController backpressure;
    private final String peer;
    private final boolean halfOpenProbe;
    private final RpeContractMetrics.Hop hop;
    private long upstreamStartNanos;
    private boolean receivedResponse;
    private boolean spanEnded;
    private boolean outcomeRecorded;
    private boolean outboundPermitReleased;

    public BackendResponseHandler(Channel clientChannel, String requestId, long deadline,
                                  boolean detectInboundDeadline, boolean ownsContext, boolean gatewayChain,
                                  Span requestSpan,
                                  ProxyMetrics.InboundRequestTracker inboundTracker, long requestStartNanos,
                                  BackpressureController backpressure, String peer, boolean halfOpenProbe,
                                  RpeContractMetrics.Hop hop) {
        this.clientChannel = clientChannel;
        this.requestId = requestId;
        this.deadline = deadline;
        this.detectInboundDeadline = detectInboundDeadline;
        this.ownsContext = ownsContext;
        this.gatewayChain = gatewayChain;
        this.requestSpan = requestSpan;
        this.inboundTracker = inboundTracker;
        this.requestStartNanos = requestStartNanos;
        this.backpressure = backpressure;
        this.peer = peer;
        this.halfOpenProbe = halfOpenProbe;
        this.hop = hop;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        upstreamStartNanos = System.nanoTime();
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext backendCtx, FullHttpResponse response) {
        receivedResponse = true;
        final int responseStatus = response.status().code();
        hop.complete(responseStatus, System.nanoTime() - upstreamStartNanos);
        // One backend connection per request: close it once its response has arrived, so idle upstream
        // connections do not accumulate. The client connection stays open (see channelInactive).
        backendCtx.close();
        boolean pressureSignal = BackpressureController.SIGNAL_VALUE.equalsIgnoreCase(
                response.headers().get(BackpressureController.SIGNAL_HEADER));
        Integer queueUtilizationPercent = BackpressureController.parseQueueUtilizationPercent(
                response.headers().get(BackpressureController.QUEUE_UTILIZATION_HEADER));
        backpressure.recordResponse(peer, responseStatus, pressureSignal,
                queueUtilizationPercent, halfOpenProbe);
        releaseOutboundPermit();
        outcomeRecorded = true;
        if (inboundTracker != null) {
            ProxyMetrics.recordInboundFailure(responseStatus, inboundTracker);
            ProxyMetrics.recordRequestDuration(System.nanoTime() - requestStartNanos, inboundTracker);
        }
        LOGGER.info(() -> "Backend response received requestId=" + requestId
                + " status=" + responseStatus
                + " contentBytes=" + response.content().readableBytes()
                + " backend=" + backendCtx.channel().remoteAddress());
        if (!clientChannel.isActive()) {
            LOGGER.warning(() -> "Client disconnected before backend response could be forwarded"
                    + " requestId=" + requestId + " client=" + clientChannel.remoteAddress());
            if (inboundTracker != null) inboundTracker.finish();
            finishSpan(responseStatus, null);
            return;
        }

        FullHttpResponse forwarded = response.retainedDuplicate();
        backpressure.applyInboundPressureSignal(forwarded.headers());

        // Example response-side modification.
        forwarded.headers().set("X-Proxy-Processed", "true");
        // chain state reported by the downstream proxy (cs member of X-RPE-Result)
        boolean[] downstreamState = RpeWire.readResponseState(response.headers());
        boolean downstreamExpired = downstreamState[0];
        boolean downstreamCancellation = downstreamState[1];
        if (downstreamExpired || downstreamCancellation) {
            ProxyHandler.markResponseStatus(requestId, downstreamExpired, downstreamCancellation);
            LOGGER.info(() -> "Received cancellation status from downstream for Request_id=" + requestId
                    + " (deadlineExceded=" + downstreamExpired
                    + ", cancellation_Triggered=" + downstreamCancellation + ")");
        }
        boolean responseLate = deadline <= System.currentTimeMillis();
        if (responseLate && !downstreamExpired) {
            ProxyHandler.ContextEntry entry = ProxyHandler.contextEntry(requestId);
            ProxyTelemetry.deadlineExceeded(requestSpan, requestId,
                    entry == null ? 0 : entry.budgetMillis(), deadline, "response_completed_after_deadline");
        }
        if (gatewayChain) ProxyHandler.completeGatewayChain(requestId, responseLate || downstreamExpired);
        io.netty.handler.codec.http.HttpHeaders chainContext = ProxyHandler.requestContext(requestId);
        boolean expired = downstreamExpired
                || responseLate
                || (ownsContext && detectInboundDeadline && deadline <= System.currentTimeMillis())
                || (chainContext != null && Boolean.parseBoolean(chainContext.get("deadlineExceded")));
        boolean cancellation = downstreamCancellation || expired || (chainContext != null
                && Boolean.parseBoolean(chainContext.get("cancellation_Triggered")));
        RpeWire.writeResponseState(forwarded.headers(), expired, cancellation);
        if (cancellation) {
            LOGGER.info(() -> "Forwarding cancellation status upstream for Request_id=" + requestId);
        }
        forwarded.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
        forwarded.headers().setInt(
                HttpHeaderNames.CONTENT_LENGTH,
                forwarded.content().readableBytes()
        );

        boolean keepClientOpen = HttpUtil.isKeepAlive(response);
        clientChannel.writeAndFlush(forwarded).addListener(writeFuture -> {
            if (!keepClientOpen) clientChannel.close();
            finishSpan(responseStatus, writeFuture.isSuccess() ? null : writeFuture.cause());
            if (writeFuture.isSuccess()) {
                if (inboundTracker != null) {
                    ProxyMetrics.recordInboundSuccessfulRequest(responseStatus, inboundTracker);
                }
                LOGGER.info(() -> "Backend response forwarded to client requestId=" + requestId
                        + " status=" + responseStatus
                        + " client=" + clientChannel.remoteAddress());
            } else {
                LOGGER.log(Level.SEVERE, "Forwarding backend response to client failed requestId="
                        + requestId + " client=" + clientChannel.remoteAddress(), writeFuture.cause());
            }
            if (inboundTracker != null) inboundTracker.finish();
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!receivedResponse) {
            if (inboundTracker != null) inboundTracker.finish();
            hop.complete(502);
            releaseOutboundPermit();
            if (!outcomeRecorded) backpressure.recordTransportFailure(peer, halfOpenProbe);
            finishSpan(502, null);
            LOGGER.warning(() -> "Backend connection closed before a response was received"
                    + " requestId=" + requestId + " backend=" + ctx.channel().remoteAddress());
            if (clientChannel.isActive()) {
                clientChannel.close();
            }
        }
        // after a response the backend connection is closed on purpose; the client connection stays usable
        // (the in-flight tracker is finished once the response has been written to the client)
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.log(Level.SEVERE, "Backend response pipeline failed requestId=" + requestId
                + " backend=" + ctx.channel().remoteAddress(), cause);
        if (!outcomeRecorded) {
            backpressure.recordTransportFailure(peer, halfOpenProbe);
            outcomeRecorded = true;
        }
        hop.complete(502);
        releaseOutboundPermit();
        finishSpan(502, cause);
        if (clientChannel.isActive()) {
            clientChannel.close();
        }
        if (inboundTracker != null) inboundTracker.finish();
        ctx.close();
    }

    private void finishSpan(int status, Throwable error) {
        if (spanEnded) return;
        spanEnded = true;
        ProxyTelemetry.endHop(requestSpan, status, error);
    }

    private void releaseOutboundPermit() {
        if (peer == null || outboundPermitReleased) return;
        outboundPermitReleased = true;
        backpressure.releaseOutbound(peer);
    }
}
