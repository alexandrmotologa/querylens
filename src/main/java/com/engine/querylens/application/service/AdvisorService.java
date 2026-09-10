package com.engine.querylens.application.service;

import com.engine.querylens.domain.model.QueryFingerprint;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates actionable schema, indexing, and query rewrites for detected database anti-patterns.
 */
public class AdvisorService {

    private static final Pattern WHERE_COLUMN_PATTERN = Pattern.compile(
            "(?i)\\bWHERE\\s+([a-zA-Z0-9_]+)\\s*(?:=|IN|LIKE|<|>|<=|>=)"
    );

    private static final Pattern AND_COLUMN_PATTERN = Pattern.compile(
            "(?i)\\bAND\\s+([a-zA-Z0-9_]+)\\s*(?:=|IN|LIKE|<|>|<=|>=)"
    );

    /**
     * Suggests a PostgreSQL index definition based on predicates in a slow query.
     */
    public String recommendIndex(QueryFingerprint fingerprint) {
        List<String> tables = fingerprint.tables();
        if (tables.isEmpty()) {
            return "Ensure filter columns are backed by indexes and examine EXPLAIN ANALYZE output.";
        }

        String table = tables.get(0);
        String sql = fingerprint.normalizedSql();

        Matcher whereMatcher = WHERE_COLUMN_PATTERN.matcher(sql);
        if (whereMatcher.find()) {
            String primaryCol = whereMatcher.group(1);
            Matcher andMatcher = AND_COLUMN_PATTERN.matcher(sql);
            if (andMatcher.find()) {
                String secondaryCol = andMatcher.group(1);
                String indexName = "idx_" + sanitize(table) + "_" + sanitize(primaryCol) + "_" + sanitize(secondaryCol);
                return "CREATE INDEX CONCURRENTLY " + indexName + " ON " + table + " (" + primaryCol + ", " + secondaryCol + ");";
            }
            String indexName = "idx_" + sanitize(table) + "_" + sanitize(primaryCol);
            return "CREATE INDEX CONCURRENTLY " + indexName + " ON " + table + " (" + primaryCol + ");";
        }

        return "Analyze execution plan with EXPLAIN (ANALYZE, BUFFERS) to identify sequential scans on " + table + ".";
    }

    /**
     * Recommends a JOIN or batch fetch rewrite for N+1 query patterns.
     */
    public String recommendJoinRewrite(String parentQuery, String childQuery) {
        return "Combine parent and child queries using an INNER/LEFT JOIN or batch fetch with WHERE id IN (...). In Hibernate/JPA, use @EntityGraph or JOIN FETCH.";
    }

    private String sanitize(String identifier) {
        return identifier.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }
}
