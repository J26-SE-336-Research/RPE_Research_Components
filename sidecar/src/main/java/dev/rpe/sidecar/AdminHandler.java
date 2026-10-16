package dev.rpe.sidecar;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/** Control API on port 15020 and the Prometheus endpoint on port 15090. */
public final class AdminHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final SidecarConfig cfg;
    private final PolicyStore policies;
    private final Metrics metrics;
    private final boolean metricsOnly;

    public AdminHandler(SidecarConfig cfg, PolicyStore policies, Metrics metrics, boolean metricsOnly) {
        this.cfg = cfg;
        this.policies = policies;
        this.metrics = metrics;
        this.metricsOnly = metricsOnly;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String path = req.uri().split("\\?", 2)[0];
        HttpMethod m = req.method();
        String body = req.content().toString(CharsetUtil.UTF_8);
        FullHttpResponse resp;
        if (metricsOnly) {
            resp = path.equals("/metrics") ? text(HttpResponseStatus.OK, "text/plain; version=0.0.4", metrics.render())
                    : text(HttpResponseStatus.NOT_FOUND, "text/plain", "not found\n");
        } else if (path.equals("/rpe/v1/health") && m.equals(HttpMethod.GET)) {
            resp = json(HttpResponseStatus.OK, "{\"status\":\"ok\",\"service\":" + Json.quote(cfg.serviceName())
                    + ",\"role\":" + Json.quote(cfg.role()) + ",\"activeEpoch\":" + Json.quote(policies.activeEpoch()) + "}");
        } else if (path.equals("/rpe/v1/policy/active") && m.equals(HttpMethod.GET)) {
            // The active policy document, so the co-located application (e.g. a gateway) can follow it.
            PolicyStore.Entry active = policies.active();
            resp = active != null ? json(HttpResponseStatus.OK, active.policyJson())
                    : json(HttpResponseStatus.NOT_FOUND, "{\"error\":\"no active policy\"}");
        } else if (path.equals("/rpe/v1/policy") && m.equals(HttpMethod.GET)) {
            resp = json(HttpResponseStatus.OK, policies.toJson());
        } else if (path.equals("/rpe/v1/policy") && m.equals(HttpMethod.PUT)) {
            String epoch = Json.string(body, "epoch");
            String state = Json.string(body, "state");
            if (epoch == null || state == null) {
                resp = json(HttpResponseStatus.BAD_REQUEST, "{\"error\":\"epoch and state are required\"}");
            } else {
                // TODO: parse the policy spec, swap it in atomically and acknowledge it to C3 (POST /api/k8s/acks).
                policies.put(epoch, Json.number(body, "version", 0), Json.string(body, "hash"),
                        PolicyStore.State.valueOf(state.toUpperCase()), body);
                resp = json(HttpResponseStatus.OK, policies.toJson());
            }
        } else if (path.equals("/rpe/v1/policy/activate") && m.equals(HttpMethod.POST)) {
            String epoch = Json.string(body, "epoch");
            resp = epoch != null && policies.activate(epoch) ? json(HttpResponseStatus.OK, policies.toJson())
                    : json(HttpResponseStatus.NOT_FOUND, "{\"error\":\"unknown epoch\"}");
        } else if (path.startsWith("/rpe/v1/policy/") && m.equals(HttpMethod.DELETE)) {
            String epoch = path.substring("/rpe/v1/policy/".length());
            resp = policies.retire(epoch) ? json(HttpResponseStatus.OK, policies.toJson())
                    : json(HttpResponseStatus.CONFLICT, "{\"error\":\"epoch is active or unknown\"}");
        } else {
            resp = json(HttpResponseStatus.NOT_FOUND, "{\"error\":\"not found\"}");
        }
        ProxyHandler.reply(ctx, resp, HttpUtil.isKeepAlive(req));
    }

    private static FullHttpResponse json(HttpResponseStatus status, String body) {
        return text(status, "application/json", body);
    }

    private static FullHttpResponse text(HttpResponseStatus status, String type, String body) {
        FullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        r.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        return r;
    }
}
