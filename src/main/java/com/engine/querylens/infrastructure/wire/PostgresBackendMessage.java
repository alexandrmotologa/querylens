package com.engine.querylens.infrastructure.wire;

/**
 * Represents decoded messages transmitted from PostgreSQL backend to client application.
 */
public sealed interface PostgresBackendMessage {

    record AuthenticationMessage(int authType) implements PostgresBackendMessage {}

    record CommandCompleteMessage(String tag, long affectedRows) implements PostgresBackendMessage {}

    record DataRowMessage(int columnCount) implements PostgresBackendMessage {}

    record ReadyForQueryMessage(char txStatus) implements PostgresBackendMessage {}

    record ErrorMessage(String severity, String code, String message) implements PostgresBackendMessage {}

    record GenericBackendMessage(byte type, int length) implements PostgresBackendMessage {}
}
