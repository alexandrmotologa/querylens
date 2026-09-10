package com.engine.querylens.domain.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Normalized representation and cryptographic identity of an SQL query.
 */
public record QueryFingerprint(
        long hash,
        String normalizedSql,
        String queryType,
        List<String> tables
) {
    public QueryFingerprint {
        Objects.requireNonNull(normalizedSql, "normalizedSql must not be null");
        queryType = queryType != null ? queryType.toUpperCase() : "UNKNOWN";
        tables = tables != null ? Collections.unmodifiableList(tables) : List.of();
    }

    public String hashHex() {
        return Long.toHexString(hash);
    }
}
