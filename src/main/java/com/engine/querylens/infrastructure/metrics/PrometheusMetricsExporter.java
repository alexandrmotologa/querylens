package com.engine.querylens.infrastructure.metrics;

import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.domain.port.QueryStoragePort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes QueryLens telemetry in standard Prometheus / OpenMetrics text format.
 */
public class PrometheusMetricsExporter {

    private final QueryStoragePort storagePort;

    public PrometheusMetricsExporter(QueryStoragePort storagePort) {
        this.storagePort = storagePort;
    }

    public String export() {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> snapshot = storagePort.getMetricsSnapshot();

        // 1. Total queries
        sb.append("# HELP querylens_queries_total Total number of database queries processed\n");
        sb.append("# TYPE querylens_queries_total counter\n");
        sb.append("querylens_queries_total ").append(snapshot.getOrDefault("totalQueries", 0)).append("\n\n");

        // 2. Total violations breakdown by type
        sb.append("# HELP querylens_violations_total Total number of anti-pattern violations detected\n");
        sb.append("# TYPE querylens_violations_total counter\n");
        List<ViolationReport> violations = storagePort.getRecentViolations(500);
        Map<String, Integer> countsByType = new HashMap<>();
        for (ViolationReport v : violations) {
            countsByType.merge(v.type().name(), 1, Integer::sum);
        }
        if (countsByType.isEmpty()) {
            sb.append("querylens_violations_total 0\n");
        } else {
            for (Map.Entry<String, Integer> entry : countsByType.entrySet()) {
                sb.append("querylens_violations_total{type=\"")
                        .append(entry.getKey())
                        .append("\"} ")
                        .append(entry.getValue())
                        .append("\n");
            }
        }
        sb.append("\n");

        // 3. Uptime
        sb.append("# HELP querylens_uptime_seconds Server uptime in seconds\n");
        sb.append("# TYPE querylens_uptime_seconds gauge\n");
        sb.append("querylens_uptime_seconds ").append(snapshot.getOrDefault("uptimeSeconds", 0)).append("\n\n");

        // 4. QPS
        sb.append("# HELP querylens_qps Current queries per second throughput\n");
        sb.append("# TYPE querylens_qps gauge\n");
        sb.append("querylens_qps ").append(snapshot.getOrDefault("queriesPerSecond", 0.0)).append("\n\n");

        // 5. Quantiles
        sb.append("# HELP querylens_query_duration_ms Query duration quantiles in milliseconds\n");
        sb.append("# TYPE querylens_query_duration_ms summary\n");
        sb.append("querylens_query_duration_ms{quantile=\"0.5\"} ").append(snapshot.getOrDefault("globalP50Ms", 0)).append("\n");
        sb.append("querylens_query_duration_ms{quantile=\"0.9\"} ").append(snapshot.getOrDefault("globalP90Ms", 0)).append("\n");
        sb.append("querylens_query_duration_ms{quantile=\"0.99\"} ").append(snapshot.getOrDefault("globalP99Ms", 0)).append("\n\n");

        // 6. Unique fingerprints
        sb.append("# HELP querylens_unique_fingerprints Total unique normalized query templates\n");
        sb.append("# TYPE querylens_unique_fingerprints gauge\n");
        sb.append("querylens_unique_fingerprints ").append(snapshot.getOrDefault("uniqueFingerprints", 0)).append("\n");

        return sb.toString();
    }
}
