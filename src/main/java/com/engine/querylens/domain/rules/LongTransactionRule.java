package com.engine.querylens.domain.rules;

import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.TransactionContext;
import com.engine.querylens.domain.model.TransactionState;
import com.engine.querylens.domain.model.ViolationReport;

import java.util.Optional;

/**
 * Detects transactions held open and idle longer than the configured threshold.
 */
public class LongTransactionRule implements AntiPatternRule {
    private final long maxDurationMs;

    public LongTransactionRule(long maxDurationMs) {
        if (maxDurationMs <= 0) {
            throw new IllegalArgumentException("Max duration must be positive");
        }
        this.maxDurationMs = maxDurationMs;
    }

    public LongTransactionRule() {
        this(5000); // 5 seconds default
    }

    @Override
    public String getRuleName() {
        return "Long-Running Transaction Detector";
    }

    @Override
    public Optional<ViolationReport> evaluateQuery(QueryExecution execution, TransactionContext txContext) {
        // Query executions evaluate via evaluateTransaction
        return Optional.empty();
    }

    @Override
    public Optional<ViolationReport> evaluateTransaction(TransactionContext txContext) {
        if (txContext == null || txContext.getState() != TransactionState.IN_TRANSACTION) {
            return Optional.empty();
        }

        long openDurationMs = txContext.getOpenDuration().toMillis();
        long idleDurationMs = txContext.getIdleDuration().toMillis();

        if (openDurationMs >= maxDurationMs && !txContext.hasAlerted(0L)) {
            txContext.markAlerted(0L); // 0L represents the transaction itself

            String recommendation = "Commit or roll back transactions promptly. Avoid performing network calls or computationally intensive work within an open database transaction.";

            ViolationReport report = ViolationReport.builder(AntiPatternType.LONG_TRANSACTION)
                    .channelId(txContext.getChannelId())
                    .transactionId(txContext.getTransactionId())
                    .title("Long-Running Transaction (" + openDurationMs + " ms)")
                    .description("Transaction has remained open for " + openDurationMs + " ms with idle time of " + idleDurationMs + " ms")
                    .impact("Locks held against database rows, prevents vacuuming of dead tuples, exhausts connection pools")
                    .durationMs(openDurationMs)
                    .repetitionCount(txContext.getExecutions().size())
                    .recommendation(recommendation)
                    .build();

            return Optional.of(report);
        }

        return Optional.empty();
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }
}
