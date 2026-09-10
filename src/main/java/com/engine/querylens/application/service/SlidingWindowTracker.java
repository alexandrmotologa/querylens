package com.engine.querylens.application.service;

import com.engine.querylens.domain.model.QueryExecution;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks query executions within a rolling time window per connection channel.
 * Detects repeated queries in auto-commit mode where no explicit BEGIN/COMMIT transaction boundaries exist.
 */
public class SlidingWindowTracker {
    private final Duration windowDuration;
    private final Map<String, Deque<QueryExecution>> channelWindows = new ConcurrentHashMap<>();

    public SlidingWindowTracker(Duration windowDuration) {
        this.windowDuration = Objects.requireNonNull(windowDuration, "windowDuration must not be null");
    }

    public SlidingWindowTracker() {
        this(Duration.ofMillis(2000)); // 2-second default rolling window
    }

    public synchronized void record(QueryExecution execution) {
        String channelId = execution.channelId();
        Deque<QueryExecution> window = channelWindows.computeIfAbsent(channelId, k -> new ArrayDeque<>());
        window.addLast(execution);
        pruneOldExecutions(window, Instant.now());
    }

    public synchronized int getCountInWindow(String channelId, long fingerprintHash) {
        Deque<QueryExecution> window = channelWindows.get(channelId);
        if (window == null || window.isEmpty()) {
            return 0;
        }
        pruneOldExecutions(window, Instant.now());
        int count = 0;
        for (QueryExecution execution : window) {
            if (execution.fingerprint().hash() == fingerprintHash) {
                count++;
            }
        }
        return count;
    }

    public synchronized Optional<QueryExecution> findPrecedingDifferentQuery(String channelId, long fingerprintHash) {
        Deque<QueryExecution> window = channelWindows.get(channelId);
        if (window == null || window.isEmpty()) {
            return Optional.empty();
        }
        Iterator<QueryExecution> it = window.descendingIterator();
        while (it.hasNext()) {
            QueryExecution execution = it.next();
            if (execution.fingerprint().hash() != fingerprintHash) {
                return Optional.of(execution);
            }
        }
        return Optional.empty();
    }

    public synchronized void removeChannel(String channelId) {
        channelWindows.remove(channelId);
    }

    private void pruneOldExecutions(Deque<QueryExecution> window, Instant now) {
        Instant cutoff = now.minus(windowDuration);
        while (!window.isEmpty() && window.peekFirst().startTime().isBefore(cutoff)) {
            window.pollFirst();
        }
    }

    public Duration getWindowDuration() {
        return windowDuration;
    }
}
