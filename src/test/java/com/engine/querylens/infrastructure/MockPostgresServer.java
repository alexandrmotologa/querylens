package com.engine.querylens.infrastructure;

import com.engine.querylens.infrastructure.wire.PostgresConstants;
import com.engine.querylens.infrastructure.wire.PostgresWireUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * In-process mock PostgreSQL v3.0 server fixture for deterministic unit and integration testing.
 */
public class MockPostgresServer {
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private Channel serverChannel;
    private int port = 0;

    public void start() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new MockPostgresHandler());
                    }
                });

        serverChannel = b.bind(0).sync().channel();
        port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    public int getPort() {
        return port;
    }

    private static class MockPostgresHandler extends ChannelInboundHandlerAdapter {
        private boolean startupDone = false;
        private char txState = PostgresConstants.TX_IDLE;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf in) {
                try {
                    while (in.isReadable()) {
                        if (!startupDone) {
                            if (in.readableBytes() < 8) return;
                            in.markReaderIndex();
                            int length = in.readInt();
                            int code = in.readInt();

                            if (code == PostgresConstants.SSL_REQUEST_CODE) {
                                // Deny SSL: write single byte 'N'
                                ByteBuf reply = ctx.alloc().buffer(1);
                                reply.writeByte('N');
                                ctx.writeAndFlush(reply);
                                return;
                            }

                            if (code == PostgresConstants.PROTOCOL_V3_0) {
                                in.skipBytes(length - 8);
                                startupDone = true;
                                sendAuthAndReady(ctx);
                                return;
                            }
                        }

                        if (in.readableBytes() < 5) return;
                        in.markReaderIndex();
                        byte type = in.readByte();
                        int length = in.readInt();

                        if (in.readableBytes() < length - 4) {
                            in.resetReaderIndex();
                            return;
                        }

                        ByteBuf payload = in.readSlice(length - 4);
                        handleFrontendMessage(ctx, type, payload);
                    }
                } finally {
                    in.release();
                }
            }
        }

        private void sendAuthAndReady(ChannelHandlerContext ctx) {
            ByteBuf buf = ctx.alloc().buffer();

            // AuthenticationOk ('R', length 8, int 0)
            buf.writeByte(PostgresConstants.BACKEND_AUTH);
            buf.writeInt(8);
            buf.writeInt(0);

            // ReadyForQuery ('Z', length 5, indicator 'I')
            buf.writeByte(PostgresConstants.BACKEND_READY_FOR_QUERY);
            buf.writeInt(5);
            buf.writeByte(PostgresConstants.TX_IDLE);

            ctx.writeAndFlush(buf);
        }

        private void handleFrontendMessage(ChannelHandlerContext ctx, byte type, ByteBuf payload) {
            switch (type) {
                case PostgresConstants.FRONTEND_QUERY -> {
                    String sql = PostgresWireUtils.readNullTerminatedString(payload);
                    handleQuery(ctx, sql != null ? sql.trim().toUpperCase() : "");
                }
                case PostgresConstants.FRONTEND_PARSE -> {
                    // Send ParseComplete ('1', length 4)
                    ByteBuf reply = ctx.alloc().buffer();
                    reply.writeByte(PostgresConstants.BACKEND_PARSE_COMPLETE);
                    reply.writeInt(4);
                    ctx.writeAndFlush(reply);
                }
                case PostgresConstants.FRONTEND_BIND -> {
                    // Send BindComplete ('2', length 4)
                    ByteBuf reply = ctx.alloc().buffer();
                    reply.writeByte(PostgresConstants.BACKEND_BIND_COMPLETE);
                    reply.writeInt(4);
                    ctx.writeAndFlush(reply);
                }
                case PostgresConstants.FRONTEND_EXECUTE -> {
                    // Send CommandComplete and DataRow
                    ByteBuf reply = ctx.alloc().buffer();
                    writeCommandComplete(reply, "SELECT 1");
                    ctx.writeAndFlush(reply);
                }
                case PostgresConstants.FRONTEND_SYNC -> {
                    // Send ReadyForQuery
                    ByteBuf reply = ctx.alloc().buffer();
                    reply.writeByte(PostgresConstants.BACKEND_READY_FOR_QUERY);
                    reply.writeInt(5);
                    reply.writeByte(txState);
                    ctx.writeAndFlush(reply);
                }
                case PostgresConstants.FRONTEND_TERMINATE -> ctx.close();
                default -> {}
            }
        }

        private void handleQuery(ChannelHandlerContext ctx, String sql) {
            ByteBuf reply = ctx.alloc().buffer();

            if (sql.startsWith("BEGIN") || sql.startsWith("START TRANSACTION")) {
                txState = PostgresConstants.TX_IN_TRANSACTION;
                writeCommandComplete(reply, "BEGIN");
            } else if (sql.startsWith("COMMIT")) {
                txState = PostgresConstants.TX_IDLE;
                writeCommandComplete(reply, "COMMIT");
            } else if (sql.startsWith("ROLLBACK")) {
                txState = PostgresConstants.TX_IDLE;
                writeCommandComplete(reply, "ROLLBACK");
            } else if (sql.startsWith("SELECT")) {
                // Write 1 DataRow ('D')
                reply.writeByte(PostgresConstants.BACKEND_DATA_ROW);
                reply.writeInt(11); // length
                reply.writeShort(1); // 1 column
                reply.writeInt(1);  // column value length: 1 byte
                reply.writeByte('1'); // value '1'

                writeCommandComplete(reply, "SELECT 1");
            } else {
                writeCommandComplete(reply, "OK");
            }

            // Append ReadyForQuery ('Z')
            reply.writeByte(PostgresConstants.BACKEND_READY_FOR_QUERY);
            reply.writeInt(5);
            reply.writeByte(txState);

            ctx.writeAndFlush(reply);
        }

        private void writeCommandComplete(ByteBuf buf, String tag) {
            byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
            buf.writeByte(PostgresConstants.BACKEND_COMMAND_COMPLETE);
            buf.writeInt(4 + tagBytes.length + 1);
            buf.writeBytes(tagBytes);
            buf.writeByte(0); // Null terminator
        }
    }
}
