package com.engine.querylens.infrastructure.wire;

import java.util.Map;

/**
 * Represents decoded messages transmitted from client frontend to PostgreSQL backend.
 */
public sealed interface PostgresFrontendMessage {

    record StartupMessage(Map<String, String> parameters) implements PostgresFrontendMessage {}

    record SslRequest() implements PostgresFrontendMessage {}

    record SimpleQueryMessage(String sql) implements PostgresFrontendMessage {}

    record ParseMessage(String statementName, String query) implements PostgresFrontendMessage {}

    record BindMessage(String portalName, String statementName) implements PostgresFrontendMessage {}

    record ExecuteMessage(String portalName, int maxRows) implements PostgresFrontendMessage {}

    record DescribeMessage(char type, String name) implements PostgresFrontendMessage {}

    record SyncMessage() implements PostgresFrontendMessage {}

    record TerminateMessage() implements PostgresFrontendMessage {}

    record GenericFrontendMessage(byte type, int length) implements PostgresFrontendMessage {}
}
