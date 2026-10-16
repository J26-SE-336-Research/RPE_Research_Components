package dev.rpe.sidecar;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.pool.AbstractChannelPoolHandler;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelPoolMap;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;

/**
 * Pooled keep-alive HTTP/1.1 connections per upstream host:port. Replaces the legacy proxy's
 * "new TCP connection per request", which dominated its overhead.
 */
public final class UpstreamPool {
    /** Upstream response plus the time spent waiting for it (used to compute sidecar overhead). */
    public record Upstream(FullHttpResponse response, long upstreamNanos) {}

    private static final AttributeKey<Promise<Upstream>> PROMISE = AttributeKey.valueOf("rpe.promise");
    private static final AttributeKey<Long> STARTED = AttributeKey.valueOf("rpe.started");
    private static final AttributeKey<FixedChannelPool> POOL = AttributeKey.valueOf("rpe.pool");

    private final ChannelPoolMap<InetSocketAddress, FixedChannelPool> pools;

    public UpstreamPool(EventLoopGroup group, Class<? extends SocketChannel> channelClass, SidecarConfig cfg) {
        Bootstrap base = new Bootstrap().group(group).channel(channelClass)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, cfg.connectTimeoutMillis())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true);
        this.pools = new AbstractChannelPoolMap<>() {
            @Override
            protected FixedChannelPool newPool(InetSocketAddress key) {
                return new FixedChannelPool(base.clone().remoteAddress(key), new AbstractChannelPoolHandler() {
                    @Override
                    public void channelCreated(Channel ch) {
                        ch.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(cfg.maxBodyBytes()), new ResponseHandler());
                    }
                }, cfg.upstreamPoolSize());
            }
        };
    }

    /** Sends the request on a pooled connection. The returned future owns one reference of the response. */
    public Future<Upstream> forward(InetSocketAddress target, FullHttpRequest request, Promise<Upstream> promise) {
        FixedChannelPool pool = pools.get(target);
        pool.acquire().addListener((Future<Channel> acquired) -> {
            if (!acquired.isSuccess()) {
                request.release();
                promise.tryFailure(acquired.cause());
                return;
            }
            Channel ch = acquired.getNow();
            ch.attr(PROMISE).set(promise);
            ch.attr(POOL).set(pool);
            ch.attr(STARTED).set(System.nanoTime());
            ch.writeAndFlush(request).addListener(w -> {
                if (!w.isSuccess()) {
                    fail(ch, w.cause());
                }
            });
        });
        return promise;
    }

    private static void fail(Channel ch, Throwable cause) {
        Promise<Upstream> p = ch.attr(PROMISE).getAndSet(null);
        FixedChannelPool pool = ch.attr(POOL).getAndSet(null);
        ch.close();
        if (pool != null) {
            pool.release(ch);
        }
        if (p != null) {
            p.tryFailure(cause);
        }
    }

    private static final class ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            Channel ch = ctx.channel();
            Promise<Upstream> p = ch.attr(PROMISE).getAndSet(null);
            FixedChannelPool pool = ch.attr(POOL).getAndSet(null);
            Long started = ch.attr(STARTED).get();
            if (!HttpUtil.isKeepAlive(response)) {
                ch.close();
            }
            if (pool != null) {
                pool.release(ch);
            }
            if (p != null && !p.trySuccess(new Upstream(response.retain(), System.nanoTime() - (started == null ? 0 : started)))) {
                response.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Promise<Upstream> p = ctx.channel().attr(PROMISE).get();
            if (p != null) {
                fail(ctx.channel(), new IllegalStateException("upstream closed the connection"));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            fail(ctx.channel(), cause);
        }
    }
}
