package com.example.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import java.util.ArrayList;
import java.util.List;

public final class ProxyServer implements AutoCloseable {
    private final EventLoopGroup boss;
    private final EventLoopGroup workers;
    private final List<Channel> channels = new ArrayList<>();
    private final Channel proxyChannel;

    /** Binds every listener of the proxy; {@link #close()} releases them. */
    ProxyServer(ProxyConfig config) throws InterruptedException {
        BackpressureController backpressure = BackpressureController.instance(config);
        ProxyMetrics.configureBackpressure(backpressure);
        RpeContractMetrics.configure(config.serviceName(), config.integration().podName());
        ProxyTelemetry.initialize(config.serviceName(), config.otelExporterOtlpEndpoint());
        if (config.gatewaySidecar()) {
            ProxyMetrics.configureGatewayDeadlineBudgets(
                    config.gatewayDeadlineRules().metricBudgets());
        }
        PolicyStore policies = new PolicyStore(active -> ActivePolicy.apply(config, active));
        ActivePolicy.track(policies);
        PolicyAcks acks = PolicyAcks.from(System.getenv(), config);

        if (!Epoll.isAvailable()) {
            throw new IllegalStateException("This TPROXY sidecar requires Netty native epoll on Linux",
                    Epoll.unavailabilityCause());
        }
        boss = new EpollEventLoopGroup(1);
        workers = new EpollEventLoopGroup();
        try {
            ProxyConfig.Integration integration = config.integration();
            boolean separateInbound = integration.inboundListenPort() != 0;
            // Without a separate inbound listener, the one listener receives TPROXY inbound traffic (original setup).
            proxyChannel = bind(config.listenHost(), config.listenPort(), !separateInbound, new ProxyInitializer(config));
            System.out.printf("TPROXY sidecar listening on %s:%d; forwarding to each connection's original destination%n",
                    config.listenHost(), config.listenPort());
            if (separateInbound) {
                bind(config.listenHost(), integration.inboundListenPort(), integration.inboundTransparent(),
                        new ProxyInitializer(config));
                System.out.printf("Inbound listener on %s:%d (%s; application port %d for redirected traffic)%n",
                        config.listenHost(), integration.inboundListenPort(),
                        integration.inboundTransparent() ? "TPROXY" : "REDIRECT", integration.appPort());
            }
            bind(config.metricsListenHost(), config.metricsListenPort(), false, new MetricsEndpointInitializer());
            System.out.printf("Metrics endpoint listening on http://%s:%d/metrics%n",
                    config.metricsListenHost(), config.metricsListenPort());
            if (integration.adminListenPort() != 0) {
                bind(config.metricsListenHost(), integration.adminListenPort(), false,
                        new AdminEndpointInitializer(config, policies, acks));
                System.out.printf("Control API listening on http://%s:%d/rpe/v1/%n",
                        config.metricsListenHost(), integration.adminListenPort());
            }
        } catch (InterruptedException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private Channel bind(String host, int port, boolean transparent, ChannelHandler initializer)
            throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, workers)
                .channel(EpollServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(initializer);
        if (transparent) bootstrap.option(EpollChannelOption.IP_TRANSPARENT, true);
        Channel channel = bootstrap.bind(host, port).sync().channel();
        channels.add(channel);
        return channel;
    }

    /** Local port of the main proxy listener (useful when it was bound to port 0). */
    int proxyPort() {
        return ((java.net.InetSocketAddress) proxyChannel.localAddress()).getPort();
    }

    @Override
    public void close() {
        channels.forEach(channel -> channel.close().syncUninterruptibly());
        channels.clear();
        if (boss != null) boss.shutdownGracefully();
        if (workers != null) workers.shutdownGracefully();
    }

    public static void main(String[] args) throws Exception {
        ProxyServer server = new ProxyServer(ProxyConfig.fromEnvironment());
        try {
            server.proxyChannel.closeFuture().sync();
        } finally {
            server.close();
            ProxyTelemetry.shutdown();
        }
    }
}
