package com.engine.querylens.infrastructure.wire;

/**
 * Protocol identifiers and message type bytes for PostgreSQL v3.0 wire protocol.
 */
public final class PostgresConstants {
    private PostgresConstants() {}

    // Frontend message types
    public static final byte FRONTEND_QUERY = 'Q';
    public static final byte FRONTEND_PARSE = 'P';
    public static final byte FRONTEND_BIND = 'B';
    public static final byte FRONTEND_EXECUTE = 'E';
    public static final byte FRONTEND_DESCRIBE = 'D';
    public static final byte FRONTEND_SYNC = 'S';
    public static final byte FRONTEND_TERMINATE = 'X';
    public static final byte FRONTEND_PASSWORD = 'p';

    // Special connection handshake codes
    public static final int SSL_REQUEST_CODE = 80877103;     // 1234.5679
    public static final int CANCEL_REQUEST_CODE = 80877102;  // 1234.5678
    public static final int PROTOCOL_V3_0 = 196608;          // 3.0 (0x00030000)

    // Backend message types
    public static final byte BACKEND_AUTH = 'R';
    public static final byte BACKEND_KEY_DATA = 'K';
    public static final byte BACKEND_PARAM_STATUS = 'S';
    public static final byte BACKEND_PARSE_COMPLETE = '1';
    public static final byte BACKEND_BIND_COMPLETE = '2';
    public static final byte BACKEND_COMMAND_COMPLETE = 'C';
    public static final byte BACKEND_ROW_DESCRIPTION = 'T';
    public static final byte BACKEND_DATA_ROW = 'D';
    public static final byte BACKEND_READY_FOR_QUERY = 'Z';
    public static final byte BACKEND_ERROR_RESPONSE = 'E';
    public static final byte BACKEND_NOTICE_RESPONSE = 'N';
    public static final byte BACKEND_NO_DATA = 'n';
    public static final byte BACKEND_EMPTY_QUERY = 'I';

    // Transaction indicators in ReadyForQuery
    public static final char TX_IDLE = 'I';
    public static final char TX_IN_TRANSACTION = 'T';
    public static final char TX_FAILED_TRANSACTION = 'E';
}
