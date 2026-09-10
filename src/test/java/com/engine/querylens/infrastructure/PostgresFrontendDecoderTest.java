package com.engine.querylens.infrastructure;

import com.engine.querylens.infrastructure.wire.PostgresConstants;
import com.engine.querylens.infrastructure.wire.PostgresFrontendDecoder;
import com.engine.querylens.infrastructure.wire.PostgresFrontendMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresFrontendDecoderTest {

    @Test
    @DisplayName("Should decode SSLRequest packet")
    void shouldDecodeSslRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(new PostgresFrontendDecoder());

        ByteBuf buf = Unpooled.buffer(8);
        buf.writeInt(8); // total length
        buf.writeInt(PostgresConstants.SSL_REQUEST_CODE);

        channel.writeInbound(buf);

        Object msg = channel.readInbound();
        assertThat(msg).isInstanceOf(PostgresFrontendMessage.SslRequest.class);
    }

    @Test
    @DisplayName("Should decode standard Simple Query message")
    void shouldDecodeSimpleQuery() {
        PostgresFrontendDecoder decoder = new PostgresFrontendDecoder();
        decoder.setStartupCompleted(true);
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        String sql = "SELECT * FROM users WHERE id = 1";
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);

        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(PostgresConstants.FRONTEND_QUERY);
        buf.writeInt(4 + sqlBytes.length + 1);
        buf.writeBytes(sqlBytes);
        buf.writeByte(0); // null terminator

        channel.writeInbound(buf);

        Object msg = channel.readInbound();
        assertThat(msg).isInstanceOf(PostgresFrontendMessage.SimpleQueryMessage.class);
        PostgresFrontendMessage.SimpleQueryMessage q = (PostgresFrontendMessage.SimpleQueryMessage) msg;
        assertThat(q.sql()).isEqualTo(sql);
    }

    @Test
    @DisplayName("Should decode Extended Query Parse and Execute messages")
    void shouldDecodeParseAndExecute() {
        PostgresFrontendDecoder decoder = new PostgresFrontendDecoder();
        decoder.setStartupCompleted(true);
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        // 1. Parse statement
        String stmtName = "stmt1";
        String query = "SELECT id FROM orders WHERE status = $1";
        ByteBuf parseBuf = Unpooled.buffer();
        parseBuf.writeByte(PostgresConstants.FRONTEND_PARSE);

        byte[] stmtBytes = stmtName.getBytes(StandardCharsets.UTF_8);
        byte[] queryBytes = query.getBytes(StandardCharsets.UTF_8);
        int parseLen = 4 + stmtBytes.length + 1 + queryBytes.length + 1 + 2; // + 2 for 0 params
        parseBuf.writeInt(parseLen);
        parseBuf.writeBytes(stmtBytes);
        parseBuf.writeByte(0);
        parseBuf.writeBytes(queryBytes);
        parseBuf.writeByte(0);
        parseBuf.writeShort(0); // 0 parameter types

        channel.writeInbound(parseBuf);

        Object parseMsg = channel.readInbound();
        assertThat(parseMsg).isInstanceOf(PostgresFrontendMessage.ParseMessage.class);
        PostgresFrontendMessage.ParseMessage parse = (PostgresFrontendMessage.ParseMessage) parseMsg;
        assertThat(parse.statementName()).isEqualTo("stmt1");
        assertThat(parse.query()).isEqualTo(query);
    }
}
