package com.engine.querylens.domain.rules;

import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.TransactionContext;
import com.engine.querylens.domain.model.ViolationReport;

import java.util.Optional;

/**
 * Flags queries with execution latency exceeding the configured threshold in milliseconds.
 */
public class SlowQueryRule implements AntiPatternRule {
    private final long thresholdMs;

    public SlowQueryRule(long thresholdMs) {
        if (thresholdMs <= 0) {
            throw new IllegalArgumentException("Slow query threshold must be positive");
        }
        this.thresholdMs = thresholdMs;
    }

    public SlowQueryRule() {
        this(100);
    }

    @Override
    public String getRuleName() {
        return "Slow Query Detector";
    }

    @Override
    public Optional<ViolationReport> evaluateQuery(QueryExecution execution, TransactionContext txContext) {
        if (execution.durationMs() >= thresholdMs) {
            String txId = txContext != null ? txContext.getTransactionId() : "none";
            String recommendation = "Run EXPLAIN ANALYZE on this statement and ensure query predicates are backed by appropriate indexes.";

            ViolationReport report = ViolationReport.builder(AntiPatternType.SLOW_QUERY)
                    .channelId(execution.channelId())
                    .transactionId(txId)
                    .title("Slow Query Execution (" + execution.durationMs() + " ms)")
                    .description("Query execution time (" + execution.durationMs() + " ms) exceeded limit (" + thresholdMs + " ms)")
                    .impact("Increased application latency, potential connection pool starvation")
                    .repetitionCount(1)
                    .durationMs(execution.durationMs())
                    .rowCount(execution.rowCount())
                    .offendingQuery(execution.fingerprint().normalizedSql())
                    .queryHash(execution.fingerprint().hash())
                    .recommendation(recommendation)
                    .build();

            return Optional.of(report);
        }

        return Optional.empty();
    }

    public long getThresholdMs() {
        return thresholdMs;
    }
}
