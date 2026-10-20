package com.example.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
public final class MetricsEndpointHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        HttpResponseStatus status;
        byte[] body;

        if (request.uri().equals("/backpressure/config")) {
            handleRateUpdate(context, request);
            return;
        } else if (request.uri().equals("/backpressure/signal")) {
            handlePressureSignal(context, request);
            return;
        } else if (!request.method().name().equals("GET")) {
            status = HttpResponseStatus.METHOD_NOT_ALLOWED;
            body = new byte[0];
        } else if (!request.uri().equals("/metrics")) {
            status = HttpResponseStatus.NOT_FOUND;
            body = new byte[0];
        } else {
            status = HttpResponseStatus.OK;
            body = ProxyMetrics.prometheusText();
        }

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                "text/plain; version=0.0.4; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);

        boolean keepAlive = io.netty.handler.codec.http.HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            context.writeAndFlush(response);
        } else {
            context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static void handlePressureSignal(ChannelHandlerContext context, FullHttpRequest request) {
        BackpressureController controller = BackpressureController.current();
        HttpResponseStatus status;
        String message;
        if (controller == null) {
            status = HttpResponseStatus.SERVICE_UNAVAILABLE;
            message = "Backpressure controller unavailable";
        } else if (!request.method().name().equals("POST")) {
            status = HttpResponseStatus.METHOD_NOT_ALLOWED;
            message = "Use POST";
        } else {
            String token = request.headers().get(BackpressureController.SIGNAL_TOKEN_HEADER);
            String rawUtilization = request.headers().get(BackpressureController.SIGNAL_UTILIZATION_HEADER);
            String rawActive = request.headers().get(BackpressureController.SIGNAL_ACTIVE_HEADER);
            Integer utilization = BackpressureController.parseQueueUtilizationPercent(rawUtilization);
            boolean active;
            if (token == null || utilization == null || rawActive == null
                    || !(rawActive.equalsIgnoreCase("true") || rawActive.equalsIgnoreCase("false"))) {
                controller.recordRejectedSignal();
                status = HttpResponseStatus.BAD_REQUEST;
                message = "Signal requires a valid callback token, utilization (0-100), and active field";
            } else {
                active = Boolean.parseBoolean(rawActive);
                if (!controller.receivePressureSignal(token, utilization, active)) {
                    controller.recordRejectedSignal();
                    status = HttpResponseStatus.UNAUTHORIZED;
                    message = "Unknown or expired callback token";
                } else {
                    status = HttpResponseStatus.OK;
                    message = "Pressure signal accepted";
                }
            }
        }
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void handleRateUpdate(ChannelHandlerContext context, FullHttpRequest request) {
        BackpressureController controller = BackpressureController.current();
        HttpResponseStatus status;
        String message;
        if (controller == null || !controller.controlUpdatesEnabled()) {
            status = HttpResponseStatus.NOT_FOUND;
            message = "Runtime rate updates require BACKPRESSURE_CONTROL_TOKEN";
        } else if (!request.method().name().equals("POST")) {
            status = HttpResponseStatus.METHOD_NOT_ALLOWED;
            message = "Use POST";
        } else {
            try {
                String body = request.content().toString(StandardCharsets.UTF_8);
                double rate = Double.NaN;
                int queueCapacity = -1;
                for (String line : body.split("\\R")) {
                    String[] pair = line.trim().split("=", 2);
                    if (pair.length != 2) continue;
                    if (pair[0].equals("rate")) rate = Double.parseDouble(pair[1]);
                    if (pair[0].equals("queue_capacity")) queueCapacity = Integer.parseInt(pair[1]);
                }
                if (rate <= 0 || !Double.isFinite(rate) || rate > 1_000_000
                        || queueCapacity <= 0 || queueCapacity > 100_000) {
                    status = HttpResponseStatus.BAD_REQUEST;
                    message = "Body must contain rate (>0 and <=1000000) and queue_capacity (1-100000)";
                } else if (!controller.updateRate(request.headers().get("X-Backpressure-Token"), rate, queueCapacity)) {
                    status = HttpResponseStatus.UNAUTHORIZED;
                    message = "Invalid control token";
                } else {
                    status = HttpResponseStatus.OK;
                    message = "Backpressure rate updated";
                }
            } catch (NumberFormatException invalid) {
                status = HttpResponseStatus.BAD_REQUEST;
                message = "Body must contain numeric rate and queue_capacity values";
            }
        }
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
