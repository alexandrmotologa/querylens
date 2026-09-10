package com.engine.querylens.domain.port;

import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.ViolationReport;
import java.util.List;
import java.util.Map;

/**
 * Storage and query interface for in-memory or persisted telemetry.
 */
public interface QueryStoragePort {
    void recordExecution(QueryExecution execution);
    void recordViolation(ViolationReport report);
    List<ViolationReport> getRecentViolations(int limit);
    List<QueryExecution> getRecentExecutions(int limit);
    long getTotalQueryCount();
    long getTotalViolationCount();
    Map<Long, Integer> getFingerprintFrequencies();
    Map<String, Object> getMetricsSnapshot();
}
