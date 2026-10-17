package com.example.proxy;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public final class ProxyInitializer extends ChannelInitializer<SocketChannel> {
    private final ProxyConfig config;

    public ProxyInitializer(ProxyConfig config) {
        this.config = config;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast("httpCodec", new HttpServerCodec())
                .addLast("aggregator", new HttpObjectAggregator(10 * 1024 * 1024))
                .addLast("proxyHandler", new ProxyHandler(config));
    }
}
