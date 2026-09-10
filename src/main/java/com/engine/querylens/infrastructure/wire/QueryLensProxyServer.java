package com.engine.querylens.infrastructure.wire;

import com.engine.querylens.application.service.QueryAnalysisEngine;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Netty server for the QueryLens PostgreSQL wire-protocol proxy.
 */
public class QueryLensProxyServer {
    private static final Logger log = LoggerFactory.getLogger(QueryLensProxyServer.class);

    private final int listenPort;
    private final String targetHost;
    private final int targetPort;
    private final QueryAnalysisEngine engine;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    public QueryLensProxyServer(int listenPort, String targetHost, int targetPort, QueryAnalysisEngine engine) {
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.engine = engine;
    }

    public synchronized void start() throws InterruptedException {
        if (running) {
            return;
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ProxyFrontendHandler(targetHost, targetPort, engine));
                    }
                })
                .childOption(ChannelOption.AUTO_READ, false)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture future = b.bind(listenPort).sync();
        serverChannel = future.channel();
        running = true;

        log.info("QueryLens proxy listening on port {} -> forwarding to {}:{}",
                listenPort, targetHost, targetPort);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }

        log.info("Stopping QueryLens proxy server on port {}", listenPort);
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        running = false;
        log.info("QueryLens proxy server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int getListenPort() {
        if (serverChannel != null && serverChannel.localAddress() instanceof InetSocketAddress addr) {
            return addr.getPort();
        }
        return listenPort;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }
}
