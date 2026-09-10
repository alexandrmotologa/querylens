package com.engine.querylens.domain.event;

import com.engine.querylens.domain.model.QueryExecution;
import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when any query finishes execution successfully or with an error.
 */
public record QueryCompletedEvent(
        Instant timestamp,
        QueryExecution execution
) {
    public QueryCompletedEvent {
        timestamp = timestamp != null ? timestamp : Instant.now();
        Objects.requireNonNull(execution, "execution must not be null");
    }
}
