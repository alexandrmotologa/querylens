package com.engine.querylens.infrastructure.wire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Netty decoder for PostgreSQL v3.0 backend messages.
 * Intercepts CommandComplete, DataRow, and ReadyForQuery packets.
 */
public class PostgresBackendDecoder extends ByteToMessageDecoder {

    private boolean awaitingSslResponse = false;

    public PostgresBackendDecoder(boolean awaitingSslResponse) {
        this.awaitingSslResponse = awaitingSslResponse;
    }

    public PostgresBackendDecoder() {
        this(false);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (awaitingSslResponse) {
            if (in.isReadable()) {
                byte sslAnswer = in.readByte(); // 'S' or 'N'
                awaitingSslResponse = false;
                // SSL answer forwarded to client
                return;
            }
            return;
        }

        while (in.readableBytes() >= 5) {
            in.markReaderIndex();
            byte type = in.readByte();
            int length = in.readInt();

            if (length < 4) {
                in.resetReaderIndex();
                return;
            }

            int payloadLength = length - 4;
            if (in.readableBytes() < payloadLength) {
                in.resetReaderIndex();
                return;
            }

            ByteBuf payload = in.readSlice(payloadLength);
            PostgresBackendMessage message = parseMessage(type, payload, length);
            out.add(message);
        }
    }

    private PostgresBackendMessage parseMessage(byte type, ByteBuf payload, int length) {
        return switch (type) {
            case PostgresConstants.BACKEND_COMMAND_COMPLETE -> {
                String tag = PostgresWireUtils.readNullTerminatedString(payload);
                long rows = PostgresWireUtils.parseAffectedRows(tag);
                yield new PostgresBackendMessage.CommandCompleteMessage(tag != null ? tag : "", rows);
            }
            case PostgresConstants.BACKEND_DATA_ROW -> {
                int columns = payload.isReadable(2) ? payload.readShort() : 0;
                yield new PostgresBackendMessage.DataRowMessage(columns);
            }
            case PostgresConstants.BACKEND_READY_FOR_QUERY -> {
                char txStatus = payload.isReadable() ? (char) payload.readByte() : PostgresConstants.TX_IDLE;
                yield new PostgresBackendMessage.ReadyForQueryMessage(txStatus);
            }
            case PostgresConstants.BACKEND_AUTH -> {
                int authType = payload.isReadable(4) ? payload.readInt() : 0;
                yield new PostgresBackendMessage.AuthenticationMessage(authType);
            }
            case PostgresConstants.BACKEND_ERROR_RESPONSE -> {
                String severity = "ERROR";
                String code = "UNKNOWN";
                String msg = "";
                while (payload.isReadable()) {
                    byte fieldType = payload.readByte();
                    if (fieldType == 0) break;
                    String val = PostgresWireUtils.readNullTerminatedString(payload);
                    if (fieldType == 'S') severity = val;
                    else if (fieldType == 'C') code = val;
                    else if (fieldType == 'M') msg = val;
                }
                yield new PostgresBackendMessage.ErrorMessage(severity, code, msg);
            }
            default -> new PostgresBackendMessage.GenericBackendMessage(type, length);
        };
    }

    public void setAwaitingSslResponse(boolean awaitingSslResponse) {
        this.awaitingSslResponse = awaitingSslResponse;
    }
}
