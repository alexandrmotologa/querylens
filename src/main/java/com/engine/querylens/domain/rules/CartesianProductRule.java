package com.engine.querylens.domain.rules;

import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.TransactionContext;
import com.engine.querylens.domain.model.ViolationReport;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Flags queries returning an excessive number of rows without a LIMIT clause,
 * typical of missing JOIN conditions or unintentional full table scans.
 */
public class CartesianProductRule implements AntiPatternRule {
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\b(LIMIT|FETCH\\s+(FIRST|NEXT))\\b", Pattern.CASE_INSENSITIVE);
    private final long rowCountThreshold;

    public CartesianProductRule(long rowCountThreshold) {
        if (rowCountThreshold <= 0) {
            throw new IllegalArgumentException("Row count threshold must be positive");
        }
        this.rowCountThreshold = rowCountThreshold;
    }

    public CartesianProductRule() {
        this(10_000);
    }

    @Override
    public String getRuleName() {
        return "Cartesian Product / Unbounded Scan Detector";
    }

    @Override
    public Optional<ViolationReport> evaluateQuery(QueryExecution execution, TransactionContext txContext) {
        if (execution.rowCount() >= rowCountThreshold) {
            String sql = execution.fingerprint().normalizedSql();
            if (!LIMIT_PATTERN.matcher(sql).find()) {
                String txId = txContext != null ? txContext.getTransactionId() : "none";
                String recommendation = "Add an explicit LIMIT clause or verify table join criteria to avoid unconstrained Cartesian row returns.";

                ViolationReport report = ViolationReport.builder(AntiPatternType.CARTESIAN_PRODUCT)
                        .channelId(execution.channelId())
                        .transactionId(txId)
                        .title("Unbounded Large Result Set (" + execution.rowCount() + " rows)")
                        .description("Query returned " + execution.rowCount() + " rows without pagination, exceeding threshold (" + rowCountThreshold + ")")
                        .impact("Excessive memory pressure on both application and database, high serialization latency")
                        .durationMs(execution.durationMs())
                        .rowCount(execution.rowCount())
                        .offendingQuery(sql)
                        .queryHash(execution.fingerprint().hash())
                        .recommendation(recommendation)
                        .build();

                return Optional.of(report);
            }
        }
        return Optional.empty();
    }

    public long getRowCountThreshold() {
        return rowCountThreshold;
    }
}
