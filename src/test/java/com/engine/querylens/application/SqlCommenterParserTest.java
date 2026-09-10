package com.engine.querylens.application;

import com.engine.querylens.application.service.SqlCommenterParser;
import com.engine.querylens.domain.model.SqlMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlCommenterParserTest {

    private SqlCommenterParser parser;

    @BeforeEach
    void setUp() {
        parser = new SqlCommenterParser();
    }

    @Test
    @DisplayName("Should extract controller, action, file, line, and framework from SQL comment")
    void shouldExtractSqlCommenterTags() {
        String rawSql = "/*controller='OrderController',action='listOrders',file='OrderService.java:54',framework='spring-boot'*/ " +
                "SELECT * FROM orders WHERE status = 'PENDING'";

        SqlCommenterParser.ParseResult result = parser.parse(rawSql);

        assertThat(result.cleanSql()).isEqualTo("SELECT * FROM orders WHERE status = 'PENDING'");

        SqlMetadata meta = result.metadata();
        assertThat(meta.controller()).isEqualTo("OrderController");
        assertThat(meta.action()).isEqualTo("listOrders");
        assertThat(meta.file()).isEqualTo("OrderService.java");
        assertThat(meta.line()).isEqualTo(54);
        assertThat(meta.framework()).isEqualTo("spring-boot");
        assertThat(meta.hasSourceInfo()).isTrue();
        assertThat(meta.sourceSummary()).isEqualTo("OrderService.java:54");
    }

    @Test
    @DisplayName("Should extract W3C distributed traceparent header")
    void shouldExtractTraceparent() {
        String rawSql = "/*traceparent='00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'*/ SELECT 1";

        SqlCommenterParser.ParseResult result = parser.parse(rawSql);
        assertThat(result.metadata().traceparent()).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    @DisplayName("Should return EMPTY metadata when no comments are present")
    void shouldHandleQueriesWithoutComments() {
        String rawSql = "SELECT id, name FROM users";
        SqlCommenterParser.ParseResult result = parser.parse(rawSql);

        assertThat(result.cleanSql()).isEqualTo("SELECT id, name FROM users");
        assertThat(result.metadata().hasSourceInfo()).isFalse();
    }
}
