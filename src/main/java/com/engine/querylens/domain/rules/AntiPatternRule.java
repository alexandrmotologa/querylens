package com.engine.querylens.domain.rules;

import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.TransactionContext;
import com.engine.querylens.domain.model.ViolationReport;
import java.util.Optional;

/**
 * Strategy interface for checking query executions or transaction states against anti-patterns.
 */
public interface AntiPatternRule {
    String getRuleName();

    /**
     * Evaluates a completed query execution.
     *
     * @param execution the captured query execution
     * @param txContext active transaction context (may be null if outside transaction)
     * @return an optional violation report if an anti-pattern was detected
     */
    Optional<ViolationReport> evaluateQuery(QueryExecution execution, TransactionContext txContext);

    /**
     * Evaluates an ongoing transaction for hoarding or leaks.
     *
     * @param txContext active transaction context
     * @return an optional violation report if an anti-pattern was detected
     */
    default Optional<ViolationReport> evaluateTransaction(TransactionContext txContext) {
        return Optional.empty();
    }
}
