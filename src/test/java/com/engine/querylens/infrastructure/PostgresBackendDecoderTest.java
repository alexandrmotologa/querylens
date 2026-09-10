package com.engine.querylens.infrastructure;

import com.engine.querylens.infrastructure.wire.PostgresBackendDecoder;
import com.engine.querylens.infrastructure.wire.PostgresBackendMessage;
import com.engine.querylens.infrastructure.wire.PostgresConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresBackendDecoderTest {

    @Test
    @DisplayName("Should decode CommandComplete message and parse affected rows")
    void shouldDecodeCommandComplete() {
        EmbeddedChannel channel = new EmbeddedChannel(new PostgresBackendDecoder());

        String tag = "SELECT 42";
        byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);

        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(PostgresConstants.BACKEND_COMMAND_COMPLETE);
        buf.writeInt(4 + tagBytes.length + 1);
        buf.writeBytes(tagBytes);
        buf.writeByte(0);

        channel.writeInbound(buf);

        Object msg = channel.readInbound();
        assertThat(msg).isInstanceOf(PostgresBackendMessage.CommandCompleteMessage.class);
        PostgresBackendMessage.CommandCompleteMessage cmd = (PostgresBackendMessage.CommandCompleteMessage) msg;
        assertThat(cmd.tag()).isEqualTo("SELECT 42");
        assertThat(cmd.affectedRows()).isEqualTo(42);
    }

    @Test
    @DisplayName("Should decode ReadyForQuery transaction state indicator")
    void shouldDecodeReadyForQuery() {
        EmbeddedChannel channel = new EmbeddedChannel(new PostgresBackendDecoder());

        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(PostgresConstants.BACKEND_READY_FOR_QUERY);
        buf.writeInt(5);
        buf.writeByte(PostgresConstants.TX_IN_TRANSACTION); // 'T'

        channel.writeInbound(buf);

        Object msg = channel.readInbound();
        assertThat(msg).isInstanceOf(PostgresBackendMessage.ReadyForQueryMessage.class);
        PostgresBackendMessage.ReadyForQueryMessage rdy = (PostgresBackendMessage.ReadyForQueryMessage) msg;
        assertThat(rdy.txStatus()).isEqualTo(PostgresConstants.TX_IN_TRANSACTION);
    }
}
