package com.example.proxy;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;

/**
 * Pipeline of a new backend connection. The response handler belongs to one request; a pooled connection gets the
 * next request's handler in its place ({@link BackendConnectionPool}).
 */
public final class BackendInitializer extends ChannelInitializer<SocketChannel> {
    private final BackendResponseHandler responseHandler;

    public BackendInitializer(BackendResponseHandler responseHandler) {
        this.responseHandler = responseHandler;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast("httpClientCodec", new HttpClientCodec())
                .addLast("aggregator", new HttpObjectAggregator(10 * 1024 * 1024))
                .addLast(BackendConnectionPool.RESPONSE_HANDLER, responseHandler);
    }
}
