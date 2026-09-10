package com.engine.querylens.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Encapsulates a single captured query execution intercepted from the database wire.
 */
public record QueryExecution(
        String executionId,
        QueryFingerprint fingerprint,
        String rawSql,
        String channelId,
        Instant startTime,
        Duration duration,
        long rowCount,
        String transactionId
) {
    public QueryExecution {
        executionId = executionId != null ? executionId : UUID.randomUUID().toString();
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        rawSql = rawSql != null ? rawSql : fingerprint.normalizedSql();
        channelId = channelId != null ? channelId : "unknown";
        startTime = startTime != null ? startTime : Instant.now();
        duration = duration != null ? duration : Duration.ZERO;
    }

    public long durationMs() {
        return duration.toMillis();
    }

    public double durationMicros() {
        return duration.toNanos() / 1_000.0;
    }
}
