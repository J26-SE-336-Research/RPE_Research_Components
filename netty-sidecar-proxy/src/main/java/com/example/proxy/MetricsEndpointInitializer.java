package com.example.proxy;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public final class MetricsEndpointInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast("httpCodec", new HttpServerCodec())
                .addLast("aggregator", new HttpObjectAggregator(1024))
                .addLast("metricsHandler", new MetricsEndpointHandler());
    }
}
