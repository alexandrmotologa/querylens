package com.engine.querylens.domain.event;

import com.engine.querylens.domain.model.ViolationReport;
import java.time.Instant;

/**
 * Common contract for anti-pattern events emitted by the QueryLens analysis engine.
 */
public sealed interface AntiPatternEvent permits NPlusOneDetectedEvent, SlowQueryAlertEvent, TransactionAlertEvent {
    Instant timestamp();
    ViolationReport report();
    String channelId();
}
