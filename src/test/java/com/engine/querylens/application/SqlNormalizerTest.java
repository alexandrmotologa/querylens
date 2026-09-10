package com.engine.querylens.application;

import com.engine.querylens.application.service.SqlNormalizer;
import com.engine.querylens.domain.model.QueryFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlNormalizerTest {

    private SqlNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new SqlNormalizer();
    }

    @Test
    @DisplayName("Should normalize numeric and string literals into question mark placeholders")
    void shouldNormalizeLiterals() {
        String sql = "SELECT id, name FROM users WHERE id = 42 AND status = 'ACTIVE' AND balance > 100.50";
        QueryFingerprint fp = normalizer.normalize(sql);

        assertThat(fp.normalizedSql())
                .isEqualTo("SELECT id, name FROM users WHERE id = ? AND status = ? AND balance > ?");
        assertThat(fp.queryType()).isEqualTo("SELECT");
        assertThat(fp.tables()).containsExactly("users");
        assertThat(fp.hash()).isNotZero();
    }

    @Test
    @DisplayName("Should normalize multiple IN clause arguments into a single placeholder")
    void shouldNormalizeInClause() {
        String sql = "SELECT * FROM orders WHERE customer_id IN (10, 20, 30, 40)";
        QueryFingerprint fp = normalizer.normalize(sql);

        assertThat(fp.normalizedSql())
                .isEqualTo("SELECT * FROM orders WHERE customer_id IN (?)");
        assertThat(fp.tables()).containsExactly("orders");
    }

    @Test
    @DisplayName("Should produce the exact same fingerprint hash for queries differing only in literal values")
    void shouldProduceSameHashForDifferentLiterals() {
        QueryFingerprint fp1 = normalizer.normalize("SELECT * FROM customer WHERE id = 1");
        QueryFingerprint fp2 = normalizer.normalize("SELECT * FROM customer WHERE id = 999");
        QueryFingerprint fp3 = normalizer.normalize("SELECT * FROM customer WHERE id = 42");

        assertThat(fp1.hash()).isEqualTo(fp2.hash());
        assertThat(fp2.hash()).isEqualTo(fp3.hash());
        assertThat(fp1.normalizedSql()).isEqualTo(fp2.normalizedSql());
    }

    @Test
    @DisplayName("Should extract multiple tables joined together")
    void shouldExtractJoinedTables() {
        String sql = "SELECT o.id, c.name FROM orders o JOIN customer c ON o.customer_id = c.id WHERE o.total > 500";
        QueryFingerprint fp = normalizer.normalize(sql);

        assertThat(fp.tables()).contains("orders", "customer");
        assertThat(fp.queryType()).isEqualTo("SELECT");
    }

    @Test
    @DisplayName("Should handle empty and whitespace queries gracefully")
    void shouldHandleEmptyQueries() {
        QueryFingerprint fp = normalizer.normalize("   ");
        assertThat(fp.hash()).isZero();
        assertThat(fp.normalizedSql()).isEmpty();
        assertThat(fp.queryType()).isEqualTo("EMPTY");
    }
}
