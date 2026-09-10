package com.engine.querylens.domain.event;

import com.engine.querylens.domain.model.ViolationReport;
import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when an N+1 query pattern is identified in a transaction or sliding time window.
 */
public record NPlusOneDetectedEvent(
        Instant timestamp,
        String channelId,
        String transactionId,
        int repetitionCount,
        String parentQuery,
        String childQuery,
        long childQueryHash,
        ViolationReport report
) implements AntiPatternEvent {
    public NPlusOneDetectedEvent {
        timestamp = timestamp != null ? timestamp : Instant.now();
        Objects.requireNonNull(report, "report must not be null");
    }
}
