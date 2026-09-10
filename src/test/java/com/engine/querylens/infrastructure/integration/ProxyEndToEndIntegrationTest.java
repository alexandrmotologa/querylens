package com.engine.querylens.infrastructure.integration;

import com.engine.querylens.application.service.AdvisorService;
import com.engine.querylens.application.service.InMemoryQueryStore;
import com.engine.querylens.application.service.QueryAnalysisEngine;
import com.engine.querylens.application.service.SlidingWindowTracker;
import com.engine.querylens.application.service.SqlNormalizer;
import com.engine.querylens.domain.event.AntiPatternEvent;
import com.engine.querylens.domain.event.NPlusOneDetectedEvent;
import com.engine.querylens.domain.event.QueryCompletedEvent;
import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.domain.port.AlertPublisherPort;
import com.engine.querylens.domain.rules.NPlusOneRule;
import com.engine.querylens.domain.rules.SlowQueryRule;
import com.engine.querylens.infrastructure.MockPostgresServer;
import com.engine.querylens.infrastructure.wire.PostgresConstants;
import com.engine.querylens.infrastructure.wire.QueryLensProxyServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyEndToEndIntegrationTest {

    private MockPostgresServer mockServer;
    private QueryLensProxyServer proxyServer;
    private QueryAnalysisEngine engine;
    private InMemoryQueryStore queryStore;
    private List<AntiPatternEvent> violations;
    private EventLoopGroup clientGroup;

    @BeforeEach
    void setUp() throws Exception {
        violations = Collections.synchronizedList(new ArrayList<>());
        queryStore = new InMemoryQueryStore();

        AlertPublisherPort alertPublisher = new AlertPublisherPort() {
            @Override
            public void publishAntiPattern(AntiPatternEvent event) {
                violations.add(event);
            }

            @Override
            public void publishQueryCompleted(QueryCompletedEvent event) {}
        };

        engine = new QueryAnalysisEngine(
                new SqlNormalizer(),
                new SlidingWindowTracker(),
                new AdvisorService(),
                queryStore,
                alertPublisher,
                List.of(new NPlusOneRule(5), new SlowQueryRule(200))
        );

        // 1. Start mock Postgres server
        mockServer = new MockPostgresServer();
        mockServer.start();

        // 2. Start QueryLens proxy listening on random local port
        proxyServer = new QueryLensProxyServer(0, "127.0.0.1", mockServer.getPort(), engine);
        proxyServer.start();

        clientGroup = new NioEventLoopGroup();
    }

    @AfterEach
    void tearDown() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
        }
        if (proxyServer != null) {
            proxyServer.stop();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    @DisplayName("Should transparently proxy Postgres traffic and detect N+1 query cascades end-to-end")
    void shouldProxyTrafficAndDetectNPlusOne() throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch queriesDoneLatch = new CountDownLatch(8); // BEGIN + Parent + 6 children
        List<String> responses = Collections.synchronizedList(new ArrayList<>());

        Bootstrap clientBootstrap = new Bootstrap();
        clientBootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                while (msg.isReadable()) {
                                    byte type = msg.readByte();
                                    int len = msg.readInt();
                                    if (msg.readableBytes() < len - 4) break;
                                    ByteBuf payload = msg.readSlice(len - 4);

                                    if (type == PostgresConstants.BACKEND_READY_FOR_QUERY) {
                                        readyLatch.countDown();
                                        queriesDoneLatch.countDown();
                                    } else if (type == PostgresConstants.BACKEND_COMMAND_COMPLETE) {
                                        responses.add("COMPLETE");
                                    }
                                }
                            }
                        });
                    }
                });

        Channel clientChannel = clientBootstrap.connect("127.0.0.1", proxyServer.getListenPort()).sync().channel();

        // Send StartupMessage: length (int32) + protocol (int32) + "user\0test\0\0"
        ByteBuf startupBuf = Unpooled.buffer();
        byte[] params = "user\0test\0database\0testdb\0\0".getBytes(StandardCharsets.UTF_8);
        startupBuf.writeInt(8 + params.length);
        startupBuf.writeInt(PostgresConstants.PROTOCOL_V3_0);
        startupBuf.writeBytes(params);
        clientChannel.writeAndFlush(startupBuf);

        // Wait for ReadyForQuery from handshake
        assertThat(readyLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // 1. Send BEGIN
        sendQuery(clientChannel, "BEGIN");

        // 2. Send Parent Query
        sendQuery(clientChannel, "SELECT * FROM orders WHERE status = 'PENDING'");

        // 3. Send 6 Repeated Child Queries (Threshold is 5)
        for (int i = 1; i <= 6; i++) {
            sendQuery(clientChannel, "SELECT * FROM customer WHERE id = " + i);
        }

        // Wait for all queries to finish
        assertThat(queriesDoneLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify that QueryLens detected the N+1 cascade!
        assertThat(violations).hasSize(1);
        AntiPatternEvent event = violations.get(0);
        assertThat(event).isInstanceOf(NPlusOneDetectedEvent.class);

        NPlusOneDetectedEvent n1 = (NPlusOneDetectedEvent) event;
        assertThat(n1.repetitionCount()).isEqualTo(5);
        assertThat(n1.parentQuery()).isEqualTo("SELECT * FROM orders WHERE status = ?");
        assertThat(n1.childQuery()).isEqualTo("SELECT * FROM customer WHERE id = ?");

        ViolationReport report = n1.report();
        assertThat(report.type()).isEqualTo(AntiPatternType.N_PLUS_ONE);
        assertThat(report.recommendation()).contains("JOIN");

        // Verify total query metrics recorded
        assertThat(queryStore.getTotalQueryCount()).isGreaterThanOrEqualTo(8);
        assertThat(queryStore.getTotalViolationCount()).isEqualTo(1);

        clientChannel.close().sync();
    }

    private void sendQuery(Channel ch, String sql) {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(PostgresConstants.FRONTEND_QUERY);
        buf.writeInt(4 + sqlBytes.length + 1);
        buf.writeBytes(sqlBytes);
        buf.writeByte(0); // null terminator
        ch.writeAndFlush(buf);
    }
}
