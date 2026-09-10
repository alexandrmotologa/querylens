package com.engine.querylens.infrastructure.wire;

import com.engine.querylens.application.service.QueryAnalysisEngine;
import com.engine.querylens.domain.model.TransactionState;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles inbound traffic from upstream PostgreSQL server, extracts response metadata
 * (row counts, command completion, transaction states), and forwards frames back to the client.
 */
public class ProxyBackendHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ProxyBackendHandler.class);

    private final Channel inboundChannel;
    private final ProxyFrontendHandler frontendHandler;
    private final QueryAnalysisEngine engine;

    public ProxyBackendHandler(Channel inboundChannel, ProxyFrontendHandler frontendHandler, QueryAnalysisEngine engine) {
        this.inboundChannel = inboundChannel;
        this.frontendHandler = frontendHandler;
        this.engine = engine;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) {
            try {
                inspectBackendBuffer(buf.duplicate());
            } catch (Exception e) {
                log.debug("Error inspecting backend packet: {}", e.getMessage());
            }

            inboundChannel.writeAndFlush(buf).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    log.warn("Failed to forward bytes to client", future.cause());
                    future.channel().close();
                }
            });
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void inspectBackendBuffer(ByteBuf buf) {
        while (buf.readableBytes() >= 5) {
            buf.markReaderIndex();
            byte type = buf.readByte();
            int length = buf.readInt();

            if (length < 4 || buf.readableBytes() < length - 4) {
                buf.resetReaderIndex();
                break;
            }

            ByteBuf payload = buf.readSlice(length - 4);
            processBackendMessage(type, payload);
        }
    }

    private void processBackendMessage(byte type, ByteBuf payload) {
        switch (type) {
            case PostgresConstants.BACKEND_DATA_ROW -> frontendHandler.onBackendRowReceived();
            case PostgresConstants.BACKEND_COMMAND_COMPLETE -> {
                String commandTag = PostgresWireUtils.readNullTerminatedString(payload);
                long affectedRows = PostgresWireUtils.parseAffectedRows(commandTag);
                frontendHandler.onBackendCommandComplete(commandTag, affectedRows);
            }
            case PostgresConstants.BACKEND_READY_FOR_QUERY -> {
                if (payload.isReadable()) {
                    byte indicator = payload.readByte();
                    TransactionState state = TransactionState.fromIndicator(indicator);
                    String channelId = inboundChannel.id().asShortText();
                    engine.onTransactionStatus(channelId, state);
                }
            }
            default -> {}
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ProxyFrontendHandler.closeOnFlush(inboundChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception caught on backend connection", cause);
        ProxyFrontendHandler.closeOnFlush(ctx.channel());
    }
}
