package com.engine.querylens.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Report generated when an anti-pattern rule is triggered.
 */
public record ViolationReport(
        String id,
        AntiPatternType type,
        Instant timestamp,
        String channelId,
        String transactionId,
        String title,
        String description,
        String impact,
        int repetitionCount,
        long durationMs,
        long rowCount,
        String parentQuery,
        String offendingQuery,
        long queryHash,
        String recommendation,
        String sourceLocation,
        String traceId
) {
    public ViolationReport {
        id = id != null ? id : UUID.randomUUID().toString();
        Objects.requireNonNull(type, "type must not be null");
        timestamp = timestamp != null ? timestamp : Instant.now();
        title = title != null ? title : type.getTitle();
        description = description != null ? description : type.getDescription();
        impact = impact != null ? impact : "";
        parentQuery = parentQuery != null ? parentQuery : "";
        offendingQuery = offendingQuery != null ? offendingQuery : "";
        recommendation = recommendation != null ? recommendation : "";
        sourceLocation = sourceLocation != null ? sourceLocation : "";
        traceId = traceId != null ? traceId : "";
    }

    public static Builder builder(AntiPatternType type) {
        return new Builder(type);
    }

    public static class Builder {
        private final AntiPatternType type;
        private String id;
        private Instant timestamp = Instant.now();
        private String channelId;
        private String transactionId;
        private String title;
        private String description;
        private String impact;
        private int repetitionCount;
        private long durationMs;
        private long rowCount;
        private String parentQuery;
        private String offendingQuery;
        private long queryHash;
        private String recommendation;
        private String sourceLocation;
        private String traceId;

        public Builder(AntiPatternType type) {
            this.type = type;
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder channelId(String channelId) { this.channelId = channelId; return this; }
        public Builder transactionId(String transactionId) { this.transactionId = transactionId; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder impact(String impact) { this.impact = impact; return this; }
        public Builder repetitionCount(int repetitionCount) { this.repetitionCount = repetitionCount; return this; }
        public Builder durationMs(long durationMs) { this.durationMs = durationMs; return this; }
        public Builder rowCount(long rowCount) { this.rowCount = rowCount; return this; }
        public Builder parentQuery(String parentQuery) { this.parentQuery = parentQuery; return this; }
        public Builder offendingQuery(String offendingQuery) { this.offendingQuery = offendingQuery; return this; }
        public Builder queryHash(long queryHash) { this.queryHash = queryHash; return this; }
        public Builder recommendation(String recommendation) { this.recommendation = recommendation; return this; }
        public Builder sourceLocation(String sourceLocation) { this.sourceLocation = sourceLocation; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }

        public ViolationReport build() {
            return new ViolationReport(
                    id, type, timestamp, channelId, transactionId, title, description, impact,
                    repetitionCount, durationMs, rowCount, parentQuery, offendingQuery, queryHash,
                    recommendation, sourceLocation, traceId
            );
        }
    }
}
