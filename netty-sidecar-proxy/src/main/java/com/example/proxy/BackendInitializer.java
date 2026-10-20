package com.example.proxy;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.opentelemetry.api.trace.Span;

public final class BackendInitializer extends ChannelInitializer<SocketChannel> {
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

    public BackendInitializer(Channel clientChannel, String requestId, long deadline,
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
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast("httpClientCodec", new HttpClientCodec())
                .addLast("aggregator", new HttpObjectAggregator(10 * 1024 * 1024))
                .addLast("backendResponseHandler", new BackendResponseHandler(
                        clientChannel, requestId, deadline, detectInboundDeadline, ownsContext,
                        gatewayChain, requestSpan,
                        inboundTracker, requestStartNanos, backpressure, peer, halfOpenProbe, hop));
    }
}
