package com.engine.querylens.domain.port;

import com.engine.querylens.domain.event.AntiPatternEvent;
import com.engine.querylens.domain.event.QueryCompletedEvent;

/**
 * Port for broadcasting anti-pattern events and query completions to listeners (TUI, SSE, metrics).
 */
public interface AlertPublisherPort {
    void publishAntiPattern(AntiPatternEvent event);
    void publishQueryCompleted(QueryCompletedEvent event);
}
