package com.engine.querylens.domain.event;

import com.engine.querylens.domain.model.ViolationReport;
import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when a transaction remains open and idle beyond the configured threshold.
 */
public record TransactionAlertEvent(
        Instant timestamp,
        String channelId,
        String transactionId,
        long idleDurationMs,
        long thresholdMs,
        ViolationReport report
) implements AntiPatternEvent {
    public TransactionAlertEvent {
        timestamp = timestamp != null ? timestamp : Instant.now();
        Objects.requireNonNull(report, "report must not be null");
    }
}
