package com.example.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;

/**
 * Control API used by Component 3 (Operator, Admin Panel) on the admin port (contract A3.5): health, and staged /
 * active policy epochs.
 */
public final class AdminEndpointHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String POLICY = "/rpe/v1/policy";
    private final ProxyConfig config;
    private final PolicyStore policies;

    AdminEndpointHandler(ProxyConfig config, PolicyStore policies) {
        this.config = config;
        this.policies = policies;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = request.uri().split("\\?", 2)[0];
        HttpMethod method = request.method();
        String body = request.content().toString(StandardCharsets.UTF_8);
        FullHttpResponse response;
        if (path.equals("/rpe/v1/health") && method.equals(HttpMethod.GET)) {
            ObjectNode health = PolicyStore.JSON.createObjectNode()
                    .put("status", "ok")
                    .put("service", config.serviceName())
                    .put("role", config.gatewaySidecar() ? "gateway" : "service")
                    .put("activeEpoch", policies.activeEpoch());
            response = json(HttpResponseStatus.OK, health.toString());
        } else if (path.equals(POLICY + "/active") && method.equals(HttpMethod.GET)) {
            // the active document, so the co-located application (for example a gateway) can follow it
            PolicyStore.Entry active = policies.active();
            response = active != null ? json(HttpResponseStatus.OK, active.document())
                    : json(HttpResponseStatus.NOT_FOUND, "{\"error\":\"no active policy\"}");
        } else if (path.equals(POLICY) && method.equals(HttpMethod.GET)) {
            response = json(HttpResponseStatus.OK, policies.toJson());
        } else if (path.equals(POLICY) && method.equals(HttpMethod.PUT)) {
            response = put(body);
        } else if (path.equals(POLICY + "/activate") && method.equals(HttpMethod.POST)) {
            String epoch = text(body, "epoch");
            response = epoch != null && policies.activate(epoch) ? json(HttpResponseStatus.OK, policies.toJson())
                    : json(HttpResponseStatus.NOT_FOUND, "{\"error\":\"unknown epoch\"}");
        } else if (path.startsWith(POLICY + "/") && method.equals(HttpMethod.DELETE)) {
            String epoch = path.substring(POLICY.length() + 1);
            response = policies.retire(epoch) ? json(HttpResponseStatus.OK, policies.toJson())
                    : json(HttpResponseStatus.CONFLICT, "{\"error\":\"epoch is active or unknown\"}");
        } else {
            response = json(HttpResponseStatus.NOT_FOUND, "{\"error\":\"not found\"}");
        }
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);
        if (keepAlive) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private FullHttpResponse put(String body) {
        JsonNode document = parse(body);
        String epoch = document == null ? null : string(document.get("epoch"));
        String state = document == null ? null : string(document.get("state"));
        if (epoch == null || state == null) {
            return json(HttpResponseStatus.BAD_REQUEST, "{\"error\":\"epoch and state are required\"}");
        }
        PolicyStore.State parsed;
        try {
            parsed = PolicyStore.State.valueOf(state.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return json(HttpResponseStatus.BAD_REQUEST, "{\"error\":\"state must be staged or active\"}");
        }
        JsonNode version = document.get("version");
        policies.put(epoch, version != null && version.isNumber() ? version.asLong() : 0,
                string(document.get("hash")), parsed, body);
        return json(HttpResponseStatus.OK, policies.toJson());
    }

    private static String text(String body, String field) {
        JsonNode document = parse(body);
        return document == null ? null : string(document.get(field));
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = PolicyStore.JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (java.io.IOException invalid) {
            return null;
        }
    }

    private static String string(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static FullHttpResponse json(HttpResponseStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return response;
    }
}
