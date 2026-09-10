package com.engine.querylens.infrastructure.wire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Netty decoder for PostgreSQL v3.0 frontend messages.
 * Handles connection initiation (StartupMessage / SSLRequest) followed by standard typed frames.
 */
public class PostgresFrontendDecoder extends ByteToMessageDecoder {

    private boolean startupCompleted = false;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!startupCompleted) {
            decodeStartup(ctx, in, out);
        } else {
            decodeStandard(ctx, in, out);
        }
    }

    private void decodeStartup(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 8) {
            return;
        }

        in.markReaderIndex();
        int length = in.readInt();
        if (length < 8) {
            // Invalid startup packet
            in.resetReaderIndex();
            return;
        }

        if (in.readableBytes() < length - 4) {
            in.resetReaderIndex();
            return;
        }

        int protocolOrCode = in.readInt();
        if (protocolOrCode == PostgresConstants.SSL_REQUEST_CODE) {
            out.add(new PostgresFrontendMessage.SslRequest());
            // Client will receive 'N' and then send the actual StartupMessage
            return;
        }

        if (protocolOrCode == PostgresConstants.PROTOCOL_V3_0) {
            Map<String, String> parameters = new HashMap<>();
            ByteBuf payload = in.readSlice(length - 8);

            while (payload.isReadable()) {
                String key = PostgresWireUtils.readNullTerminatedString(payload);
                if (key == null || key.isEmpty()) {
                    break;
                }
                String value = PostgresWireUtils.readNullTerminatedString(payload);
                if (value != null) {
                    parameters.put(key, value);
                }
            }

            startupCompleted = true;
            out.add(new PostgresFrontendMessage.StartupMessage(parameters));
            return;
        }

        // Other code or cancel request
        in.skipBytes(length - 8);
        startupCompleted = true;
    }

    private void decodeStandard(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= 5) {
            in.markReaderIndex();
            byte type = in.readByte();
            int length = in.readInt();

            if (length < 4) {
                // Invalid frame length
                in.resetReaderIndex();
                return;
            }

            int payloadLength = length - 4;
            if (in.readableBytes() < payloadLength) {
                in.resetReaderIndex();
                return;
            }

            ByteBuf payload = in.readSlice(payloadLength);
            PostgresFrontendMessage message = parseMessage(type, payload, length);
            out.add(message);
        }
    }

    private PostgresFrontendMessage parseMessage(byte type, ByteBuf payload, int length) {
        return switch (type) {
            case PostgresConstants.FRONTEND_QUERY -> {
                String sql = PostgresWireUtils.readNullTerminatedString(payload);
                yield new PostgresFrontendMessage.SimpleQueryMessage(sql != null ? sql : "");
            }
            case PostgresConstants.FRONTEND_PARSE -> {
                String statementName = PostgresWireUtils.readNullTerminatedString(payload);
                String query = PostgresWireUtils.readNullTerminatedString(payload);
                yield new PostgresFrontendMessage.ParseMessage(
                        statementName != null ? statementName : "",
                        query != null ? query : ""
                );
            }
            case PostgresConstants.FRONTEND_BIND -> {
                String portalName = PostgresWireUtils.readNullTerminatedString(payload);
                String statementName = PostgresWireUtils.readNullTerminatedString(payload);
                yield new PostgresFrontendMessage.BindMessage(
                        portalName != null ? portalName : "",
                        statementName != null ? statementName : ""
                );
            }
            case PostgresConstants.FRONTEND_EXECUTE -> {
                String portalName = PostgresWireUtils.readNullTerminatedString(payload);
                int maxRows = payload.isReadable(4) ? payload.readInt() : 0;
                yield new PostgresFrontendMessage.ExecuteMessage(
                        portalName != null ? portalName : "",
                        maxRows
                );
            }
            case PostgresConstants.FRONTEND_DESCRIBE -> {
                char describeType = payload.isReadable() ? (char) payload.readByte() : 'S';
                String name = PostgresWireUtils.readNullTerminatedString(payload);
                yield new PostgresFrontendMessage.DescribeMessage(describeType, name != null ? name : "");
            }
            case PostgresConstants.FRONTEND_SYNC -> new PostgresFrontendMessage.SyncMessage();
            case PostgresConstants.FRONTEND_TERMINATE -> new PostgresFrontendMessage.TerminateMessage();
            default -> new PostgresFrontendMessage.GenericFrontendMessage(type, length);
        };
    }

    public void setStartupCompleted(boolean startupCompleted) {
        this.startupCompleted = startupCompleted;
    }
}
