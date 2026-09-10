package com.engine.querylens.application.service;

import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.domain.port.QueryStoragePort;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory metrics and violation store with running percentile calculations.
 */
public class InMemoryQueryStore implements QueryStoragePort {
    private final int maxExecutions;
    private final int maxViolations;

    private final AtomicLong totalQueryCount = new AtomicLong(0);
    private final AtomicLong totalViolationCount = new AtomicLong(0);

    private final Deque<QueryExecution> recentExecutions = new ConcurrentLinkedDeque<>();
    private final Deque<ViolationReport> recentViolations = new ConcurrentLinkedDeque<>();

    private final Map<Long, AtomicLong> fingerprintFrequencies = new ConcurrentHashMap<>();
    private final Map<Long, List<Long>> latencySamples = new ConcurrentHashMap<>();
    private final Map<Long, QueryExecution> fingerprintTemplates = new ConcurrentHashMap<>();

    private final Instant startTime = Instant.now();

    public InMemoryQueryStore(int maxExecutions, int maxViolations) {
        this.maxExecutions = maxExecutions;
        this.maxViolations = maxViolations;
    }

    public InMemoryQueryStore() {
        this(500, 200);
    }

    @Override
    public void recordExecution(QueryExecution execution) {
        totalQueryCount.incrementAndGet();

        recentExecutions.addFirst(execution);
        while (recentExecutions.size() > maxExecutions) {
            recentExecutions.pollLast();
        }

        long hash = execution.fingerprint().hash();
        fingerprintFrequencies.computeIfAbsent(hash, k -> new AtomicLong(0)).incrementAndGet();
        fingerprintTemplates.putIfAbsent(hash, execution);

        // Keep rolling latency samples (up to 100 samples per fingerprint)
        latencySamples.compute(hash, (k, samples) -> {
            if (samples == null) {
                samples = Collections.synchronizedList(new ArrayList<>());
            }
            if (samples.size() >= 100) {
                samples.remove(0);
            }
            samples.add(execution.durationMs());
            return samples;
        });
    }

    @Override
    public void recordViolation(ViolationReport report) {
        totalViolationCount.incrementAndGet();

        recentViolations.addFirst(report);
        while (recentViolations.size() > maxViolations) {
            recentViolations.pollLast();
        }
    }

    @Override
    public List<ViolationReport> getRecentViolations(int limit) {
        List<ViolationReport> list = new ArrayList<>(recentViolations);
        if (limit > 0 && list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
    }

    @Override
    public List<QueryExecution> getRecentExecutions(int limit) {
        List<QueryExecution> list = new ArrayList<>(recentExecutions);
        if (limit > 0 && list.size() > limit) {
            return list.subList(0, limit);
        }
        return list;
    }

    @Override
    public long getTotalQueryCount() {
        return totalQueryCount.get();
    }

    @Override
    public long getTotalViolationCount() {
        return totalViolationCount.get();
    }

    @Override
    public Map<Long, Integer> getFingerprintFrequencies() {
        Map<Long, Integer> result = new HashMap<>();
        fingerprintFrequencies.forEach((hash, count) -> result.put(hash, (int) count.get()));
        return result;
    }

    public List<Map<String, Object>> getTopQueries(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();

        List<Map.Entry<Long, AtomicLong>> sorted = new ArrayList<>(fingerprintFrequencies.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));

        int count = 0;
        for (Map.Entry<Long, AtomicLong> entry : sorted) {
            if (count++ >= limit) break;
            long hash = entry.getKey();
            QueryExecution template = fingerprintTemplates.get(hash);
            List<Long> samples = latencySamples.get(hash);

            long p50 = computePercentile(samples, 50);
            long p90 = computePercentile(samples, 90);
            long p99 = computePercentile(samples, 99);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hash", Long.toHexString(hash));
            item.put("sql", template != null ? template.fingerprint().normalizedSql() : "");
            item.put("type", template != null ? template.fingerprint().queryType() : "SELECT");
            item.put("count", entry.getValue().get());
            item.put("p50Ms", p50);
            item.put("p90Ms", p90);
            item.put("p99Ms", p99);
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> getMetricsSnapshot() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        long totalQueries = totalQueryCount.get();
        long totalViolations = totalViolationCount.get();
        long uptimeSeconds = Math.max(1, Duration.between(startTime, Instant.now()).toSeconds());
        double qps = (double) totalQueries / uptimeSeconds;

        List<Long> allLatencies = new ArrayList<>();
        latencySamples.values().forEach(samples -> {
            synchronized (samples) {
                allLatencies.addAll(samples);
            }
        });

        metrics.put("totalQueries", totalQueries);
        metrics.put("totalViolations", totalViolations);
        metrics.put("uptimeSeconds", uptimeSeconds);
        metrics.put("queriesPerSecond", Math.round(qps * 100.0) / 100.0);
        metrics.put("uniqueFingerprints", fingerprintFrequencies.size());
        metrics.put("globalP50Ms", computePercentile(allLatencies, 50));
        metrics.put("globalP90Ms", computePercentile(allLatencies, 90));
        metrics.put("globalP99Ms", computePercentile(allLatencies, 99));

        return metrics;
    }

    private long computePercentile(List<Long> samples, int percentile) {
        if (samples == null || samples.isEmpty()) {
            return 0L;
        }
        List<Long> copy;
        synchronized (samples) {
            copy = new ArrayList<>(samples);
        }
        if (copy.isEmpty()) return 0L;
        Collections.sort(copy);
        int index = (int) Math.ceil((percentile / 100.0) * copy.size()) - 1;
        index = Math.max(0, Math.min(index, copy.size() - 1));
        return copy.get(index);
    }
}
