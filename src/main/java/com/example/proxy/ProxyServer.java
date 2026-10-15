package com.example.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;

public final class ProxyServer {
    private ProxyServer() {}

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.fromEnvironment();
        BackpressureController backpressure = BackpressureController.instance(config);
        ProxyMetrics.configureBackpressure(backpressure);
        ProxyTelemetry.initialize(config.serviceName(), config.otelExporterOtlpEndpoint());
        if (config.gatewaySidecar()) {
            ProxyMetrics.configureGatewayDeadlineBudgets(
                    config.gatewayDeadlineRules().metricBudgets());
        }

        if (!Epoll.isAvailable()) {
            throw new IllegalStateException("This TPROXY sidecar requires Netty native epoll on Linux",
                    Epoll.unavailabilityCause());
        }
        EventLoopGroup boss = new EpollEventLoopGroup(1);
        EventLoopGroup workers = new EpollEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, workers);
            bootstrap.channel(EpollServerSocketChannel.class)
                    .option(EpollChannelOption.IP_TRANSPARENT, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ProxyInitializer(config));

            Channel channel = bootstrap.bind(config.listenHost(), config.listenPort()).sync().channel();
            Channel metricsChannel = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(EpollServerSocketChannel.class)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new MetricsEndpointInitializer())
                    .bind(config.metricsListenHost(), config.metricsListenPort())
                    .sync()
                    .channel();

            System.out.printf("TPROXY sidecar listening on %s:%d; forwarding to each connection's original destination%n",
                    config.listenHost(), config.listenPort());
            System.out.printf("Metrics endpoint listening on http://%s:%d/metrics%n",
                    config.metricsListenHost(), config.metricsListenPort());

            try {
                channel.closeFuture().sync();
            } finally {
                metricsChannel.close().syncUninterruptibly();
            }
        } finally {
            ProxyTelemetry.shutdown();
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
    }
}
