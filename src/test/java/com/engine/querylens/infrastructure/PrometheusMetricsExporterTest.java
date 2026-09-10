package com.engine.querylens.infrastructure;

import com.engine.querylens.application.service.InMemoryQueryStore;
import com.engine.querylens.application.service.SqlNormalizer;
import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.infrastructure.metrics.PrometheusMetricsExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusMetricsExporterTest {

    @Test
    @DisplayName("Should export valid Prometheus format metrics")
    void shouldExportPrometheusMetrics() {
        InMemoryQueryStore store = new InMemoryQueryStore();
        SqlNormalizer normalizer = new SqlNormalizer();

        store.recordExecution(new QueryExecution(
                null, normalizer.normalize("SELECT * FROM users"), "SELECT * FROM users",
                "c1", null, Duration.ofMillis(12), 5, null
        ));

        store.recordViolation(ViolationReport.builder(AntiPatternType.N_PLUS_ONE)
                .title("N+1 Detected")
                .description("5 repeats")
                .repetitionCount(5)
                .build());

        PrometheusMetricsExporter exporter = new PrometheusMetricsExporter(store);
        String metrics = exporter.export();

        assertThat(metrics).contains("# HELP querylens_queries_total");
        assertThat(metrics).contains("querylens_queries_total 1");
        assertThat(metrics).contains("querylens_violations_total{type=\"N_PLUS_ONE\"} 1");
        assertThat(metrics).contains("querylens_query_duration_ms{quantile=\"0.99\"}");
    }
}
