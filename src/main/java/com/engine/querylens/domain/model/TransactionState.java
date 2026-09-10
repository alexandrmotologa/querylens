package com.engine.querylens.domain.model;

/**
 * State of a database connection transaction.
 * Aligns with PostgreSQL ReadyForQuery indicator characters ('I', 'T', 'E').
 */
public enum TransactionState {
    IDLE('I', "Idle (Outside transaction block)"),
    IN_TRANSACTION('T', "In Transaction Block"),
    FAILED_TRANSACTION('E', "Failed Transaction Block (Commands ignored until ROLLBACK)");

    private final char indicator;
    private final String description;

    TransactionState(char indicator, String description) {
        this.indicator = indicator;
        this.description = description;
    }

    public char getIndicator() {
        return indicator;
    }

    public String getDescription() {
        return description;
    }

    public static TransactionState fromIndicator(byte indicatorByte) {
        return switch ((char) indicatorByte) {
            case 'T', 't' -> IN_TRANSACTION;
            case 'E', 'e' -> FAILED_TRANSACTION;
            default -> IDLE;
        };
    }
}
