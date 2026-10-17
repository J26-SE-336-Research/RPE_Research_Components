package com.example.proxy;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public final class AdminEndpointInitializer extends ChannelInitializer<SocketChannel> {
    private final ProxyConfig config;
    private final PolicyStore policies;

    AdminEndpointInitializer(ProxyConfig config, PolicyStore policies) {
        this.config = config;
        this.policies = policies;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast("httpCodec", new HttpServerCodec())
                .addLast("aggregator", new HttpObjectAggregator(1024 * 1024))
                .addLast("adminHandler", new AdminEndpointHandler(config, policies));
    }
}
