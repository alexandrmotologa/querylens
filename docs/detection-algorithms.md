# Detection Algorithms

QueryLens analyzes database traffic using a combination of transaction boundaries and sliding-window statistics.

## SQL Normalization and Fingerprinting

Before queries can be analyzed for patterns, literals must be abstracted.

Given the raw query:
```sql
SELECT id, email, created_at FROM customers WHERE tenant_id = 42 AND status = 'ACTIVE';
```

The normalizer performs the following steps:
1. Strips string literals delimited by single quotes (`'ACTIVE'` -> `?`).
2. Strips numeric literals (`42` -> `?`).
3. Replaces boolean and null literals when used in value positions.
4. Collapses multiple whitespace characters into single spaces.
5. Converts SQL keywords to upper case while preserving column/table identifiers.

Normalized output:
```sql
SELECT id, email, created_at FROM customers WHERE tenant_id = ? AND status = ?;
```

A 64-bit cryptographic Murmur3 hash is computed over the normalized SQL string to produce the query fingerprint.

## N+1 Query Detection

An N+1 query pattern occurs when an application executes a driving query to fetch N records, followed by N separate queries to retrieve associated entities:

```
Query 1 (Parent): SELECT id FROM orders WHERE status = 'PENDING'; (returns 25 rows)
Query 2 (Child):  SELECT * FROM customer WHERE id = 101;
Query 3 (Child):  SELECT * FROM customer WHERE id = 102;
...
Query 26 (Child): SELECT * FROM customer WHERE id = 125;
```

### Detection rules

1. Transaction-scoped tracking:
   - When a transaction opens (`BEGIN` or `ReadyForQuery` with indicator `'T'`), QueryLens initializes a `TransactionContext`.
   - Each query executed within the transaction increments a counter for its fingerprint.
   - If any single query fingerprint repeats more than `nplusone-threshold` times (default: 5) within the transaction, an `NPlusOneDetectedEvent` is emitted.
   - The engine identifies the parent query by recording the query that ran immediately prior to the repeating series.

2. Sliding-window tracking:
   - For applications running without explicit multi-statement transactions (auto-commit mode), QueryLens tracks query fingerprints within a rolling 2-second sliding window per connection.
   - If a fingerprint repeats more than `nplusone-threshold` times within the window, a violation is flagged.

## Slow Query Detection

Queries with execution duration exceeding `slow-threshold-ms` (default: 100 ms) emit a `SlowQueryAlertEvent`.

QueryLens maintains latency histograms per query fingerprint using streaming reservoir sampling to report:
- P50 (median latency)
- P90 (90th percentile latency)
- P99 (tail latency)

## Long-Running Transaction Detection

Transactions left uncommitted for longer than 5 seconds trigger a `TransactionAlertEvent`. This identifies application blocks where network calls or heavy background processing stall database connections.

## Cartesian Product and Result Size Warnings

Queries returning more than 10,000 rows without an explicit `LIMIT` or `FETCH FIRST` clause trigger a `CartesianProductAlertEvent`.
