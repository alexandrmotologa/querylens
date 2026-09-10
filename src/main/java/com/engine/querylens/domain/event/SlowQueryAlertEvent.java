package com.engine.querylens.domain.event;

import com.engine.querylens.domain.model.ViolationReport;
import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when an executed query exceeds the slow query duration threshold.
 */
public record SlowQueryAlertEvent(
        Instant timestamp,
        String channelId,
        String transactionId,
        long durationMs,
        long thresholdMs,
        String sql,
        long queryHash,
        ViolationReport report
) implements AntiPatternEvent {
    public SlowQueryAlertEvent {
        timestamp = timestamp != null ? timestamp : Instant.now();
        Objects.requireNonNull(report, "report must not be null");
    }
}
