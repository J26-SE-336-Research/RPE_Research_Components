package dev.rpe.sidecar;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Proxies one HTTP exchange for the inbound (15006) or outbound (15001) listener.
 *
 * <p>This is the pass-through skeleton. The resilience mechanisms (context handling via
 * rpe-java, deadline, cancellation, leaky bucket, backpressure, retries and the coordinated decision function)
 * plug in at the marked extension points.
 */
public final class ProxyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    public enum Direction { INBOUND, OUTBOUND }

    static final String HOP_HEADER = "X-RPE-Hop";
    private static final Logger LOG = Logger.getLogger(ProxyHandler.class.getName());

    private final Direction direction;
    private final SidecarConfig cfg;
    private final UpstreamPool upstream;
    private final Metrics metrics;

    public ProxyHandler(Direction direction, SidecarConfig cfg, UpstreamPool upstream, Metrics metrics) {
        this.direction = direction;
        this.cfg = cfg;
        this.upstream = upstream;
        this.metrics = metrics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        long start = System.nanoTime();
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        Target target;
        try {
            target = direction == Direction.INBOUND ? inboundTarget(ctx, request) : outboundTarget(request);
        } catch (IllegalArgumentException e) {
            reply(ctx, error(HttpResponseStatus.BAD_REQUEST, e.getMessage()), keepAlive);
            return;
        }
        FullHttpRequest forwarded = new DefaultFullHttpRequest(request.protocolVersion(), request.method(), target.uri(),
                request.content().retain(), request.headers().copy(), request.trailingHeaders().copy());
        if (direction == Direction.INBOUND) {
            forwarded.headers().remove(HOP_HEADER); // hop-scoped header never reaches the application
        }
        HttpUtil.setKeepAlive(forwarded, true);
        // TODO: parse/validate X-RPE-Context, admission (expired / insufficient time /
        // leaky bucket), outbound decision function and retries happen here before forwarding.

        metrics.begin();
        ctx.channel().config().setAutoRead(false);
        upstream.forward(target.address(), forwarded, ctx.executor().newPromise()).addListener(f -> {
            ctx.channel().config().setAutoRead(true);
            double total = (System.nanoTime() - start) / 1e9;
            if (!f.isSuccess()) {
                LOG.log(Level.FINE, "upstream failure for " + target.address(), f.cause());
                metrics.record(dir(), "failed", total, total);
                reply(ctx, error(HttpResponseStatus.BAD_GATEWAY, "rpe-sidecar: upstream unavailable"), keepAlive);
                return;
            }
            UpstreamPool.Upstream up = (UpstreamPool.Upstream) f.getNow();
            FullHttpResponse response = up.response();
            int code = response.status().code();
            metrics.record(dir(), code < 400 ? "ok" : (code == 429 || code == 504 ? "rejected" : "failed"),
                    total, Math.max(0, total - up.upstreamNanos() / 1e9));
            String result = response.headers().get("X-RPE-Result");
            if (direction == Direction.INBOUND && cfg.gateway() && result != null) {
                // Chain-level outcome at the trust boundary: one count per root request (used by C4).
                metrics.chain(request.method().name(), path(request.uri()), code == 504 || result.contains("rsn=deadline"));
            }
            reply(ctx, response, keepAlive);
        });
    }

    private String dir() {
        return direction == Direction.INBOUND ? "inbound" : "outbound";
    }

    record Target(InetSocketAddress address, String uri) {}

    /** Inbound traffic always goes to the local application over loopback (never re-intercepted). */
    Target inboundTarget(ChannelHandlerContext ctx, FullHttpRequest request) {
        int port = cfg.appPort();
        if (cfg.inboundMode() == SidecarConfig.InboundMode.TPROXY
                && ctx.channel().localAddress() instanceof InetSocketAddress local
                && local.getPort() != cfg.inboundPort()) {
            port = local.getPort(); // TPROXY keeps the original destination port
        }
        return new Target(new InetSocketAddress("127.0.0.1", port), originForm(request.uri()));
    }

    /** Outbound traffic was REDIRECTed, so the destination comes from the absolute URI or Host header. */
    static Target outboundTarget(FullHttpRequest request) {
        String uri = request.uri();
        String host;
        int port;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            URI u = URI.create(uri);
            if (!"http".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) {
                throw new IllegalArgumentException("only plain http upstreams are supported");
            }
            host = u.getHost();
            port = u.getPort() > 0 ? u.getPort() : 80;
            uri = originForm(uri);
        } else {
            String hostHeader = request.headers().get(HttpHeaderNames.HOST);
            if (hostHeader == null || hostHeader.isBlank()) {
                throw new IllegalArgumentException("outbound request has no Host header");
            }
            URI u = URI.create("http://" + hostHeader.trim());
            if (u.getHost() == null || u.getRawUserInfo() != null) {
                throw new IllegalArgumentException("invalid Host header");
            }
            host = u.getHost();
            port = u.getPort() > 0 ? u.getPort() : 80;
        }
        return new Target(InetSocketAddress.createUnresolved(host, port), uri);
    }

    static String path(String uri) {
        String origin = originForm(uri);
        int q = origin.indexOf('?');
        return q < 0 ? origin : origin.substring(0, q);
    }

    static String originForm(String uri) {
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            return uri.isEmpty() ? "/" : uri;
        }
        URI u = URI.create(uri);
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
    }

    private static FullHttpResponse error(HttpResponseStatus status, String message) {
        FullHttpResponse r = new DefaultFullHttpResponse(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(message + "\n", CharsetUtil.UTF_8));
        r.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        return r;
    }

    static void reply(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        HttpUtil.setKeepAlive(response, keepAlive);
        var f = ctx.writeAndFlush(response);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.FINE, "client connection error", cause);
        ctx.close();
    }
}
