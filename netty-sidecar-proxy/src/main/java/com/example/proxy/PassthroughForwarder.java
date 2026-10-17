package com.example.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Forwards a request that carries no request-chain context unchanged (enabled with
 * {@code PROXY_MISSING_CONTEXT=passthrough}). Such traffic is not part of an RPE request chain, for example kubelet
 * probes, administrative calls or calls from a service without a sidecar; it gets no deadline, cancellation or
 * backpressure handling and is only counted in the request metrics.
 */
final class PassthroughForwarder {
    private static final Logger LOGGER = Logger.getLogger(PassthroughForwarder.class.getName());
    private static final long WARNING_INTERVAL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
    private static final java.util.concurrent.atomic.AtomicLong LAST_WARNING = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SINCE_LAST_WARNING = new java.util.concurrent.atomic.AtomicLong();

    private PassthroughForwarder() {}

    static void forward(ChannelHandlerContext clientCtx, FullHttpRequest request, InetSocketAddress destination,
                        String uri, RpeContractMetrics.Hop hop) {
        hop.passthrough();
        warnAtMostOncePerMinute(request.method().name() + " " + uri.split("\\?", 2)[0]);
        boolean clientKeepAlive = HttpUtil.isKeepAlive(request);
        FullHttpRequest forwarded = new DefaultFullHttpRequest(request.protocolVersion(), request.method(), uri,
                request.content().retainedDuplicate(), request.headers().copy(), request.trailingHeaders().copy());
        // proxy-internal backpressure headers never reach an application
        forwarded.headers().remove(BackpressureController.PEER_KEY_HEADER);
        forwarded.headers().remove(BackpressureController.CALLER_TOKEN_HEADER);
        Channel client = clientCtx.channel();
        new Bootstrap()
                .group(client.eventLoop())
                .channel(client.getClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(10 * 1024 * 1024))
                                .addLast(new ResponseRelay(client, clientKeepAlive, hop));
                    }
                })
                .connect(destination)
                .addListener((ChannelFutureListener) connect -> {
                    if (!connect.isSuccess()) {
                        forwarded.release();
                        LOGGER.log(Level.FINE, "Passthrough connect failed destination=" + destination, connect.cause());
                        hop.complete(502);
                        sendBadGateway(client);
                        return;
                    }
                    connect.channel().writeAndFlush(forwarded).addListener(write -> {
                        // closing makes ResponseRelay.channelInactive answer the client with 502
                        if (!write.isSuccess()) connect.channel().close();
                    });
                });
    }

    /**
     * Makes forgotten Request_id propagation visible without flooding the log (kubelet probes arrive every few
     * seconds); every such request is also counted in rpe_passthrough_requests_total.
     */
    private static void warnAtMostOncePerMinute(String example) {
        long count = SINCE_LAST_WARNING.incrementAndGet();
        long now = System.nanoTime();
        long last = LAST_WARNING.get();
        if ((last == 0 || now - last >= WARNING_INTERVAL_NANOS) && LAST_WARNING.compareAndSet(last, now)) {
            SINCE_LAST_WARNING.addAndGet(-count);
            LOGGER.warning(() -> "Forwarded " + count + " request(s) without an RPE request context unchanged"
                    + " (no deadline or cancellation handling), for example " + example
                    + ". If this is application traffic, propagate Request_id or X-RPE-Context on outbound calls.");
        }
    }

    private static void sendBadGateway(Channel client) {
        if (!client.isActive()) return;
        byte[] body = "Could not reach the destination".getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        client.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /** Relays the one response of this connection to the client, then closes the upstream connection. */
    private static final class ResponseRelay extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Channel client;
        private final boolean clientKeepAlive;
        private final RpeContractMetrics.Hop hop;
        private long upstreamStartNanos;
        private boolean responded;

        ResponseRelay(Channel client, boolean clientKeepAlive, RpeContractMetrics.Hop hop) {
            this.client = client;
            this.clientKeepAlive = clientKeepAlive;
            this.hop = hop;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            upstreamStartNanos = System.nanoTime();
            super.channelActive(ctx);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext upstream, FullHttpResponse response) {
            responded = true;
            hop.complete(response.status().code(), System.nanoTime() - upstreamStartNanos);
            upstream.close();
            if (!client.isActive()) return;
            FullHttpResponse forwarded = response.retainedDuplicate();
            forwarded.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            forwarded.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, forwarded.content().readableBytes());
            boolean keepAlive = clientKeepAlive && HttpUtil.isKeepAlive(response);
            HttpUtil.setKeepAlive(forwarded, keepAlive);
            if (keepAlive) {
                client.writeAndFlush(forwarded);
            } else {
                client.writeAndFlush(forwarded).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!responded) {
                hop.complete(502);
                sendBadGateway(client);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.log(Level.FINE, "Passthrough upstream failed", cause);
            ctx.close(); // channelInactive answers the client when no response was relayed
        }
    }
}
