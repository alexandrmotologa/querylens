package com.engine.querylens.domain;

import com.engine.querylens.application.service.SqlNormalizer;
import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.QueryFingerprint;
import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.domain.rules.SlowQueryRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SlowQueryRuleTest {

    private SlowQueryRule rule;
    private SqlNormalizer normalizer;

    @BeforeEach
    void setUp() {
        rule = new SlowQueryRule(100);
        normalizer = new SqlNormalizer();
    }

    @Test
    @DisplayName("Should flag query taking longer than threshold")
    void shouldFlagSlowQuery() {
        QueryFingerprint fp = normalizer.normalize("SELECT * FROM large_table WHERE data IS NOT NULL");
        QueryExecution exec = new QueryExecution(
                null, fp, "SELECT * FROM large_table WHERE data IS NOT NULL",
                "chan-1", Instant.now(), Duration.ofMillis(250), 500, null
        );

        Optional<ViolationReport> report = rule.evaluateQuery(exec, null);
        assertThat(report).isPresent();
        assertThat(report.get().type()).isEqualTo(AntiPatternType.SLOW_QUERY);
        assertThat(report.get().durationMs()).isEqualTo(250);
        assertThat(report.get().offendingQuery()).isEqualTo("SELECT * FROM large_table WHERE data IS NOT NULL");
    }

    @Test
    @DisplayName("Should ignore fast query below threshold")
    void shouldIgnoreFastQuery() {
        QueryFingerprint fp = normalizer.normalize("SELECT 1");
        QueryExecution exec = new QueryExecution(
                null, fp, "SELECT 1",
                "chan-1", Instant.now(), Duration.ofMillis(5), 1, null
        );

        Optional<ViolationReport> report = rule.evaluateQuery(exec, null);
        assertThat(report).isEmpty();
    }
}
