package com.engine.querylens.domain.rules;

import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.TransactionContext;
import com.engine.querylens.domain.model.ViolationReport;

import java.util.List;
import java.util.Optional;

/**
 * Detects N+1 query cascades within transaction scopes.
 * Flags queries whose normalized fingerprint repeats more than the configured threshold.
 */
public class NPlusOneRule implements AntiPatternRule {
    private final int threshold;

    public NPlusOneRule(int threshold) {
        if (threshold < 2) {
            throw new IllegalArgumentException("N+1 threshold must be at least 2");
        }
        this.threshold = threshold;
    }

    public NPlusOneRule() {
        this(5);
    }

    @Override
    public String getRuleName() {
        return "N+1 Query Detector";
    }

    @Override
    public Optional<ViolationReport> evaluateQuery(QueryExecution execution, TransactionContext txContext) {
        if (txContext == null) {
            return Optional.empty();
        }

        long hash = execution.fingerprint().hash();
        int count = txContext.getCount(hash);

        if (count >= threshold && txContext.markAlerted(hash)) {
            String parentQuery = findCausalParentQuery(txContext, hash);
            String childQuery = execution.fingerprint().normalizedSql();

            String recommendation = buildRecommendation(parentQuery, childQuery);

            ViolationReport report = ViolationReport.builder(AntiPatternType.N_PLUS_ONE)
                    .channelId(execution.channelId())
                    .transactionId(txContext.getTransactionId())
                    .title("N+1 Query Cascade Detected")
                    .description("Query executed " + count + " times inside transaction " + txContext.getTransactionId())
                    .impact("High network round-trip latency overhead and database connection contention")
                    .repetitionCount(count)
                    .durationMs(execution.durationMs())
                    .rowCount(execution.rowCount())
                    .parentQuery(parentQuery)
                    .offendingQuery(childQuery)
                    .queryHash(hash)
                    .recommendation(recommendation)
                    .build();

            return Optional.of(report);
        }

        return Optional.empty();
    }

    private String findCausalParentQuery(TransactionContext txContext, long childHash) {
        List<QueryExecution> executions = txContext.getExecutions();
        for (int i = executions.size() - 1; i >= 0; i--) {
            QueryExecution candidate = executions.get(i);
            if (candidate.fingerprint().hash() != childHash) {
                return candidate.fingerprint().normalizedSql();
            }
        }
        return "Unknown parent driving query";
    }

    private String buildRecommendation(String parentQuery, String childQuery) {
        return "Batch child fetches using JOIN or WHERE id IN (...) rather than executing individual queries in a loop.";
    }

    public int getThreshold() {
        return threshold;
    }
}
