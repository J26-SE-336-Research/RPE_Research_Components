package com.example.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the sidecar's backend connections (to the co-located application and to downstream sidecars) open after a
 * response and reuses them for the next request to the same destination, instead of opening and closing a TCP
 * connection per request (user decision, 21 Oct 2026). Under heavy load that per-request churn runs into the kernel's
 * connection limits (accept backlog, TIME_WAIT ports) before RPE sees a request.
 *
 * <p>Only the connection handling changes: deadline, cancellation and backpressure handling run exactly as before
 * for every request. A connection is pooled per event loop and destination and is only used by its own event loop,
 * one request at a time (HTTP/1.1), so no locking is needed. It is returned only after its response was read
 * completely and only when the response allows keep-alive; a dead or long-idle connection is never reused.
 * {@code PROXY_BACKEND_POOL=off} restores one connection per request.
 */
final class BackendConnectionPool {
    static final String RESPONSE_HANDLER = "backendResponseHandler";
    private static final AttributeKey<Long> IDLE_SINCE = AttributeKey.valueOf("rpe.backendPool.idleSince");
    private static final AttributeKey<String> TRACKED = AttributeKey.valueOf("rpe.backendPool.tracked");
    private static final long MAX_IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int MAX_IDLE_PER_DESTINATION = maxIdle(System.getenv("PROXY_BACKEND_POOL_MAX_IDLE"));
    private static volatile boolean enabled = !"off".equalsIgnoreCase(System.getenv("PROXY_BACKEND_POOL"));

    /** Idle connections per event loop and destination; the inner map is only touched on its own event loop. */
    private static final ConcurrentHashMap<EventLoop, Map<String, ArrayDeque<Channel>>> IDLE = new ConcurrentHashMap<>();
    private static final AtomicLong OPENED = new AtomicLong();
    private static final AtomicLong REUSED = new AtomicLong();
    private static final AtomicLong IDLE_COUNT = new AtomicLong();

    private BackendConnectionPool() {}

    static String key(InetSocketAddress destination) {
        return destination.getHostString() + ":" + destination.getPort();
    }

    /** An open idle connection to the destination owned by this event loop, or null. Call on that event loop. */
    static Channel acquire(EventLoop loop, String key) {
        if (!enabled) return null;
        Map<String, ArrayDeque<Channel>> byKey = IDLE.get(loop);
        ArrayDeque<Channel> idle = byKey == null ? null : byKey.get(key);
        long now = System.nanoTime();
        while (idle != null && !idle.isEmpty()) {
            Channel channel = idle.pollLast(); // the most recently used connection first
            IDLE_COUNT.decrementAndGet();
            Long since = channel.attr(IDLE_SINCE).getAndSet(null);
            if (channel.isActive() && since != null && now - since < MAX_IDLE_NANOS) {
                REUSED.incrementAndGet();
                return channel;
            }
            channel.close();
        }
        return null;
    }

    /** Counts a newly opened backend connection. */
    static void opened() {
        OPENED.incrementAndGet();
    }

    /**
     * Hands back a connection whose response was read completely: kept for the next request when it is open and
     * the response allows keep-alive, otherwise closed. Call on the connection's event loop.
     */
    static void release(Channel channel, String key, boolean keepAlive) {
        if (!enabled || !keepAlive || key == null || !channel.isActive()) {
            channel.close();
            return;
        }
        ArrayDeque<Channel> idle = IDLE.computeIfAbsent(channel.eventLoop(), loop -> new HashMap<>())
                .computeIfAbsent(key, ignored -> new ArrayDeque<>());
        if (idle.size() >= MAX_IDLE_PER_DESTINATION) {
            channel.close();
            return;
        }
        // nothing of the finished request stays on the connection while it is idle
        channel.pipeline().replace(RESPONSE_HANDLER, RESPONSE_HANDLER, new IdleHandler());
        channel.attr(IDLE_SINCE).set(System.nanoTime());
        if (channel.attr(TRACKED).setIfAbsent(key) == null) {
            channel.closeFuture().addListener(closed -> forget(channel, key));
        }
        idle.addLast(channel);
        IDLE_COUNT.incrementAndGet();
    }

    /** Drops a closed connection from the idle list (runs on its event loop). */
    private static void forget(Channel channel, String key) {
        Map<String, ArrayDeque<Channel>> byKey = IDLE.get(channel.eventLoop());
        ArrayDeque<Channel> idle = byKey == null ? null : byKey.get(key);
        if (idle != null && idle.remove(channel)) IDLE_COUNT.decrementAndGet();
    }

    static String prometheusText() {
        return "# HELP rpe_backend_connections_opened_total Backend connections the sidecar opened (to the application or a downstream sidecar).\n"
                + "# TYPE rpe_backend_connections_opened_total counter\nrpe_backend_connections_opened_total " + OPENED.get() + "\n"
                + "# HELP rpe_backend_connections_reused_total Requests sent over an already open, pooled backend connection.\n"
                + "# TYPE rpe_backend_connections_reused_total counter\nrpe_backend_connections_reused_total " + REUSED.get() + "\n"
                + "# HELP rpe_backend_connections_idle Open backend connections waiting in the pool.\n"
                + "# TYPE rpe_backend_connections_idle gauge\nrpe_backend_connections_idle " + Math.max(0, IDLE_COUNT.get()) + "\n";
    }

    static void setEnabledForTest(boolean on) {
        enabled = on;
    }

    private static int maxIdle(String value) {
        try {
            int parsed = value == null ? 512 : Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : 512;
        } catch (NumberFormatException invalid) {
            return 512;
        }
    }

    /** Sits on an idle connection: anything arriving there is unexpected, so the connection is closed. */
    private static final class IdleHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
