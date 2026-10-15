package dev.rpe.sidecar;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** RPE sidecar entry point: outbound (15001), inbound (15006), admin (15020) and metrics (15090) listeners. */
public final class SidecarServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(SidecarServer.class.getName());

    private final SidecarConfig cfg;
    private final EventLoopGroup boss;
    private final EventLoopGroup workers;
    private final Class<? extends ServerSocketChannel> serverChannel;
    private final List<Channel> channels = new ArrayList<>();
    final Metrics metrics;
    final PolicyStore policies = new PolicyStore();

    public SidecarServer(SidecarConfig cfg) {
        this.cfg = cfg;
        this.metrics = new Metrics(cfg.serviceName(), cfg.podName());
        boolean epoll = Epoll.isAvailable();
        if (cfg.inboundMode() == SidecarConfig.InboundMode.TPROXY && !epoll) {
            throw new IllegalStateException("TPROXY inbound mode requires Linux native epoll", Epoll.unavailabilityCause());
        }
        this.boss = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        this.workers = epoll ? new EpollEventLoopGroup() : new NioEventLoopGroup();
        this.serverChannel = epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
        Class<? extends SocketChannel> clientChannel = epoll ? EpollSocketChannel.class : NioSocketChannel.class;
        UpstreamPool upstream = new UpstreamPool(workers, clientChannel, cfg);

        bind(cfg.outboundPort(), false, () -> new ProxyHandler(ProxyHandler.Direction.OUTBOUND, cfg, upstream, metrics));
        bind(cfg.inboundPort(), cfg.inboundMode() == SidecarConfig.InboundMode.TPROXY,
                () -> new ProxyHandler(ProxyHandler.Direction.INBOUND, cfg, upstream, metrics));
        bind(cfg.adminPort(), false, () -> new AdminHandler(cfg, policies, metrics, false));
        bind(cfg.metricsPort(), false, () -> new AdminHandler(cfg, policies, metrics, true));
        LOG.info(() -> String.format("rpe-sidecar %s (%s, inbound=%s, app port %d) listening on %d/%d/%d/%d using %s",
                cfg.serviceName(), cfg.role(), cfg.inboundMode(), cfg.appPort(), cfg.outboundPort(), cfg.inboundPort(),
                cfg.adminPort(), cfg.metricsPort(), epoll ? "epoll" : "nio"));
    }

    private void bind(int port, boolean transparent, Supplier<ChannelHandler> handler) {
        ServerBootstrap b = new ServerBootstrap().group(boss, workers).channel(serverChannel)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(cfg.maxBodyBytes()), handler.get());
                    }
                });
        if (transparent) {
            b.option(EpollChannelOption.IP_TRANSPARENT, true);
        }
        channels.add(b.bind("0.0.0.0", port).syncUninterruptibly().channel());
    }

    @Override
    public void close() {
        channels.forEach(c -> c.close().syncUninterruptibly());
        boss.shutdownGracefully();
        workers.shutdownGracefully();
    }

    public static void main(String[] args) throws InterruptedException {
        SidecarServer server = new SidecarServer(SidecarConfig.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}
