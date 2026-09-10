package com.engine.querylens.infrastructure.wire;

import com.engine.querylens.application.service.QueryAnalysisEngine;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles inbound traffic from client applications, establishes the upstream connection to PostgreSQL,
 * and extracts SQL queries in flight without mutating packet payloads.
 */
public class ProxyFrontendHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ProxyFrontendHandler.class);

    public record PendingQuery(String sql, Instant startTime) {}

    private final String targetHost;
    private final int targetPort;
    private final QueryAnalysisEngine engine;

    private Channel clientChannel;
    private Channel outboundChannel;
    private final Map<String, String> statements = new ConcurrentHashMap<>();
    private final Map<String, String> portals = new ConcurrentHashMap<>();

    private final Queue<PendingQuery> pendingQueries = new ConcurrentLinkedQueue<>();
    private final AtomicLong activeRowCount = new AtomicLong(0);
    private boolean startupDone = false;

    public ProxyFrontendHandler(String targetHost, int targetPort, QueryAnalysisEngine engine) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.engine = engine;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        final Channel inboundChannel = ctx.channel();
        this.clientChannel = inboundChannel;
        inboundChannel.config().setAutoRead(false);

        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(ctx.channel().getClass())
                .handler(new ProxyBackendHandler(inboundChannel, this, engine))
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture f = b.connect(targetHost, targetPort);
        outboundChannel = f.channel();
        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                inboundChannel.config().setAutoRead(true);
            } else {
                log.warn("Failed to connect to target PostgreSQL at {}:{}", targetHost, targetPort, future.cause());
                inboundChannel.close();
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) {
            try {
                inspectFrontendBuffer(buf.duplicate());
            } catch (Exception e) {
                log.debug("Error inspecting frontend packet: {}", e.getMessage());
            }

            if (outboundChannel != null && outboundChannel.isActive()) {
                outboundChannel.writeAndFlush(buf).addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        log.warn("Failed to forward bytes to backend", future.cause());
                        ctx.close();
                    }
                });
            } else {
                buf.release();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void inspectFrontendBuffer(ByteBuf buf) {
        if (!startupDone) {
            if (buf.readableBytes() < 8) return;
            buf.markReaderIndex();
            int length = buf.readInt();
            int code = buf.readInt();
            if (code == PostgresConstants.SSL_REQUEST_CODE) {
                return;
            }
            if (code == PostgresConstants.PROTOCOL_V3_0) {
                startupDone = true;
            }
            buf.resetReaderIndex();
            return;
        }

        while (buf.readableBytes() >= 5) {
            buf.markReaderIndex();
            byte type = buf.readByte();
            int length = buf.readInt();

            if (length < 4 || buf.readableBytes() < length - 4) {
                buf.resetReaderIndex();
                break;
            }

            ByteBuf payload = buf.readSlice(length - 4);
            processFrontendMessage(type, payload);
        }
    }

    private void processFrontendMessage(byte type, ByteBuf payload) {
        switch (type) {
            case PostgresConstants.FRONTEND_QUERY -> {
                String sql = PostgresWireUtils.readNullTerminatedString(payload);
                if (sql != null && !sql.isBlank()) {
                    pendingQueries.add(new PendingQuery(sql, Instant.now()));
                }
            }
            case PostgresConstants.FRONTEND_PARSE -> {
                String statementName = PostgresWireUtils.readNullTerminatedString(payload);
                String sql = PostgresWireUtils.readNullTerminatedString(payload);
                if (sql != null && !sql.isBlank()) {
                    statements.put(statementName != null ? statementName : "", sql);
                }
            }
            case PostgresConstants.FRONTEND_BIND -> {
                String portalName = PostgresWireUtils.readNullTerminatedString(payload);
                String statementName = PostgresWireUtils.readNullTerminatedString(payload);
                String sql = statements.get(statementName != null ? statementName : "");
                if (sql != null) {
                    portals.put(portalName != null ? portalName : "", sql);
                }
            }
            case PostgresConstants.FRONTEND_EXECUTE -> {
                String portalName = PostgresWireUtils.readNullTerminatedString(payload);
                String sql = portals.get(portalName != null ? portalName : "");
                if (sql == null) {
                    sql = statements.get(""); // Unnamed statement fallback
                }
                if (sql != null && !sql.isBlank()) {
                    pendingQueries.add(new PendingQuery(sql, Instant.now()));
                }
            }
            default -> {}
        }
    }

    public void onBackendRowReceived() {
        activeRowCount.incrementAndGet();
    }

    public void onBackendCommandComplete(String commandTag, long affectedRows) {
        PendingQuery pending = pendingQueries.poll();
        if (pending != null) {
            Duration duration = Duration.between(pending.startTime(), Instant.now());
            long rows = activeRowCount.getAndSet(0);
            long totalRows = rows > 0 ? rows : affectedRows;
            String channelId = clientChannel != null ? clientChannel.id().asShortText() : "local";
            engine.onQueryCompleted(channelId, pending.sql(), duration, totalRows);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
        String channelId = ctx.channel().id().asShortText();
        engine.onChannelClosed(channelId);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception caught on client connection", cause);
        closeOnFlush(ctx.channel());
    }

    static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
            ch.writeAndFlush(io.netty.buffer.Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
