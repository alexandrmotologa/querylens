package com.engine.querylens.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks query history, fingerprint repetitions, and execution state within a transaction scope.
 */
public class TransactionContext {
    private final String transactionId;
    private final String channelId;
    private final Instant startTime;
    private volatile Instant lastActivityTime;
    private volatile TransactionState state;
    private final List<QueryExecution> executions = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, Integer> fingerprintCounts = new ConcurrentHashMap<>();
    private final Set<Long> alertedFingerprints = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile QueryExecution lastQuery;

    public TransactionContext(String transactionId, String channelId) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId must not be null");
        this.channelId = Objects.requireNonNull(channelId, "channelId must not be null");
        this.startTime = Instant.now();
        this.lastActivityTime = this.startTime;
        this.state = TransactionState.IN_TRANSACTION;
    }

    public void recordExecution(QueryExecution execution) {
        Objects.requireNonNull(execution, "execution must not be null");
        this.lastActivityTime = Instant.now();
        this.executions.add(execution);
        this.fingerprintCounts.merge(execution.fingerprint().hash(), 1, Integer::sum);
        this.lastQuery = execution;
    }

    public int getCount(long fingerprintHash) {
        return fingerprintCounts.getOrDefault(fingerprintHash, 0);
    }

    public boolean markAlerted(long fingerprintHash) {
        return alertedFingerprints.add(fingerprintHash);
    }

    public boolean hasAlerted(long fingerprintHash) {
        return alertedFingerprints.contains(fingerprintHash);
    }

    public Duration getOpenDuration() {
        return Duration.between(startTime, Instant.now());
    }

    public Duration getIdleDuration() {
        return Duration.between(lastActivityTime, Instant.now());
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getChannelId() {
        return channelId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getLastActivityTime() {
        return lastActivityTime;
    }

    public TransactionState getState() {
        return state;
    }

    public void setState(TransactionState state) {
        this.state = state;
        this.lastActivityTime = Instant.now();
    }

    public List<QueryExecution> getExecutions() {
        return Collections.unmodifiableList(new ArrayList<>(executions));
    }

    public Map<Long, Integer> getFingerprintCounts() {
        return Collections.unmodifiableMap(fingerprintCounts);
    }

    public QueryExecution getLastQuery() {
        return lastQuery;
    }
}
