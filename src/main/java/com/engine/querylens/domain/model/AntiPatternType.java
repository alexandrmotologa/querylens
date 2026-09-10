package com.engine.querylens.domain.model;

/**
 * Categorizes database performance anti-patterns detected by QueryLens.
 */
public enum AntiPatternType {
    N_PLUS_ONE("N+1 Query Cascade", "Application executes N separate queries in a loop instead of a batched query or JOIN"),
    SLOW_QUERY("Slow Query Execution", "Query execution latency exceeded the configured threshold"),
    LONG_TRANSACTION("Long-Running Transaction", "Transaction remained open and idle, holding database connection resources"),
    CARTESIAN_PRODUCT("Unbounded Result Set", "Query returned an excessive number of rows without a LIMIT clause");

    private final String title;
    private final String description;

    AntiPatternType(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }
}
