package com.engine.querylens.domain;

import com.engine.querylens.application.service.SqlNormalizer;
import com.engine.querylens.domain.model.*;
import com.engine.querylens.domain.rules.NPlusOneRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneRuleTest {

    private NPlusOneRule rule;
    private SqlNormalizer normalizer;

    @BeforeEach
    void setUp() {
        rule = new NPlusOneRule(5);
        normalizer = new SqlNormalizer();
    }

    @Test
    @DisplayName("Should not alert when repetitions are below threshold")
    void shouldNotAlertBelowThreshold() {
        TransactionContext tx = new TransactionContext("tx-1", "chan-1");
        QueryFingerprint childFp = normalizer.normalize("SELECT * FROM customer WHERE id = 10");

        for (int i = 0; i < 4; i++) {
            QueryExecution exec = new QueryExecution(
                    null, childFp, "SELECT * FROM customer WHERE id = " + i,
                    "chan-1", Instant.now(), Duration.ofMillis(2), 1, "tx-1"
            );
            tx.recordExecution(exec);
            Optional<ViolationReport> violation = rule.evaluateQuery(exec, tx);
            assertThat(violation).isEmpty();
        }
    }

    @Test
    @DisplayName("Should detect N+1 violation when repeated query reaches threshold within transaction")
    void shouldDetectNPlusOneAtThreshold() {
        TransactionContext tx = new TransactionContext("tx-1", "chan-1");

        // 1. Parent driving query
        QueryFingerprint parentFp = normalizer.normalize("SELECT * FROM orders WHERE status = 'PENDING'");
        QueryExecution parentExec = new QueryExecution(
                null, parentFp, "SELECT * FROM orders WHERE status = 'PENDING'",
                "chan-1", Instant.now(), Duration.ofMillis(15), 20, "tx-1"
        );
        tx.recordExecution(parentExec);

        // 2. Loop of N child queries
        QueryFingerprint childFp = normalizer.normalize("SELECT * FROM customer WHERE id = 1");

        Optional<ViolationReport> lastViolation = Optional.empty();
        for (int i = 1; i <= 5; i++) {
            QueryExecution childExec = new QueryExecution(
                    null, childFp, "SELECT * FROM customer WHERE id = " + i,
                    "chan-1", Instant.now(), Duration.ofMillis(3), 1, "tx-1"
            );
            tx.recordExecution(childExec);
            Optional<ViolationReport> report = rule.evaluateQuery(childExec, tx);
            if (report.isPresent()) {
                lastViolation = report;
            }
        }

        assertThat(lastViolation).isPresent();
        ViolationReport violation = lastViolation.get();
        assertThat(violation.type()).isEqualTo(AntiPatternType.N_PLUS_ONE);
        assertThat(violation.repetitionCount()).isEqualTo(5);
        assertThat(violation.parentQuery()).isEqualTo("SELECT * FROM orders WHERE status = ?");
        assertThat(violation.offendingQuery()).isEqualTo("SELECT * FROM customer WHERE id = ?");
        assertThat(violation.recommendation()).contains("JOIN", "WHERE id IN");
    }

    @Test
    @DisplayName("Should not duplicate alert for the same repeating fingerprint within the same transaction")
    void shouldNotDuplicateAlertInSameTransaction() {
        TransactionContext tx = new TransactionContext("tx-1", "chan-1");
        QueryFingerprint childFp = normalizer.normalize("SELECT * FROM customer WHERE id = 1");

        int alertCount = 0;
        for (int i = 1; i <= 10; i++) {
            QueryExecution childExec = new QueryExecution(
                    null, childFp, "SELECT * FROM customer WHERE id = " + i,
                    "chan-1", Instant.now(), Duration.ofMillis(3), 1, "tx-1"
            );
            tx.recordExecution(childExec);
            if (rule.evaluateQuery(childExec, tx).isPresent()) {
                alertCount++;
            }
        }

        assertThat(alertCount).isEqualTo(1);
    }
}
