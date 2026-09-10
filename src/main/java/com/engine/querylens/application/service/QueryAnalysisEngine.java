package com.engine.querylens.application.service;

import com.engine.querylens.domain.event.AntiPatternEvent;
import com.engine.querylens.domain.event.NPlusOneDetectedEvent;
import com.engine.querylens.domain.event.QueryCompletedEvent;
import com.engine.querylens.domain.event.SlowQueryAlertEvent;
import com.engine.querylens.domain.event.TransactionAlertEvent;
import com.engine.querylens.domain.model.*;
import com.engine.querylens.domain.port.AlertPublisherPort;
import com.engine.querylens.domain.port.QueryStoragePort;
import com.engine.querylens.domain.rules.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central analysis engine that processes intercepted queries, manages transaction scopes,
 * runs anti-pattern rules, and notifies observers.
 */
public class QueryAnalysisEngine {
    private static final Logger log = LoggerFactory.getLogger(QueryAnalysisEngine.class);

    private final SqlNormalizer normalizer;
    private final SlidingWindowTracker slidingWindowTracker;
    private final AdvisorService advisorService;
    private final QueryStoragePort storagePort;
    private final AlertPublisherPort alertPublisherPort;
    private final List<AntiPatternRule> rules;

    private final Map<String, TransactionContext> activeTransactions = new ConcurrentHashMap<>();

    public QueryAnalysisEngine(
            SqlNormalizer normalizer,
            SlidingWindowTracker slidingWindowTracker,
            AdvisorService advisorService,
            QueryStoragePort storagePort,
            AlertPublisherPort alertPublisherPort,
            List<AntiPatternRule> rules
    ) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
        this.slidingWindowTracker = Objects.requireNonNull(slidingWindowTracker, "slidingWindowTracker must not be null");
        this.advisorService = Objects.requireNonNull(advisorService, "advisorService must not be null");
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.alertPublisherPort = Objects.requireNonNull(alertPublisherPort, "alertPublisherPort must not be null");
        this.rules = rules != null ? new ArrayList<>(rules) : defaultRules();
    }

    private static List<AntiPatternRule> defaultRules() {
        return List.of(
                new NPlusOneRule(5),
                new SlowQueryRule(100),
                new LongTransactionRule(5000),
                new CartesianProductRule(10_000)
        );
    }

    public QueryExecution onQueryCompleted(String channelId, String rawSql, Duration duration, long rowCount) {
        QueryFingerprint fingerprint = normalizer.normalize(rawSql);

        TransactionContext txContext = activeTransactions.get(channelId);
        String txId = txContext != null ? txContext.getTransactionId() : null;

        QueryExecution execution = new QueryExecution(
                UUID.randomUUID().toString(),
                fingerprint,
                rawSql,
                channelId,
                Instant.now().minus(duration),
                duration,
                rowCount,
                txId
        );

        if (txContext != null) {
            txContext.recordExecution(execution);
        } else {
            slidingWindowTracker.record(execution);
        }

        storagePort.recordExecution(execution);
        alertPublisherPort.publishQueryCompleted(new QueryCompletedEvent(Instant.now(), execution));

        // Evaluate active rules
        for (AntiPatternRule rule : rules) {
            Optional<ViolationReport> reportOpt = rule.evaluateQuery(execution, txContext);
            reportOpt.ifPresent(report -> handleViolation(report, channelId, txId));
        }

        // Check sliding window N+1 if outside transaction
        if (txContext == null) {
            checkSlidingWindowNPlusOne(execution, channelId);
        }

        return execution;
    }

    private void checkSlidingWindowNPlusOne(QueryExecution execution, String channelId) {
        long hash = execution.fingerprint().hash();
        int count = slidingWindowTracker.getCountInWindow(channelId, hash);
        if (count >= 5) {
            Optional<QueryExecution> parentOpt = slidingWindowTracker.findPrecedingDifferentQuery(channelId, hash);
            String parentSql = parentOpt.map(p -> p.fingerprint().normalizedSql()).orElse("Preceding driving query");
            String childSql = execution.fingerprint().normalizedSql();

            ViolationReport report = ViolationReport.builder(AntiPatternType.N_PLUS_ONE)
                    .channelId(channelId)
                    .transactionId("none")
                    .title("N+1 Query Cascade Detected (Sliding Window)")
                    .description("Query repeated " + count + " times within a 2-second window on connection " + channelId)
                    .impact("High latency and CPU overhead caused by loop iteration over database connection")
                    .repetitionCount(count)
                    .durationMs(execution.durationMs())
                    .rowCount(execution.rowCount())
                    .parentQuery(parentSql)
                    .offendingQuery(childSql)
                    .queryHash(hash)
                    .recommendation(advisorService.recommendJoinRewrite(parentSql, childSql))
                    .build();

            handleViolation(report, channelId, null);
        }
    }

    private void handleViolation(ViolationReport report, String channelId, String txId) {
        storagePort.recordViolation(report);

        AntiPatternEvent event = switch (report.type()) {
            case N_PLUS_ONE -> new NPlusOneDetectedEvent(
                    report.timestamp(), channelId, txId, report.repetitionCount(),
                    report.parentQuery(), report.offendingQuery(), report.queryHash(), report
            );
            case SLOW_QUERY -> new SlowQueryAlertEvent(
                    report.timestamp(), channelId, txId, report.durationMs(),
                    100L, report.offendingQuery(), report.queryHash(), report
            );
            case LONG_TRANSACTION -> new TransactionAlertEvent(
                    report.timestamp(), channelId, txId, report.durationMs(),
                    5000L, report
            );
            case CARTESIAN_PRODUCT -> new SlowQueryAlertEvent(
                    report.timestamp(), channelId, txId, report.durationMs(),
                    0L, report.offendingQuery(), report.queryHash(), report
            );
        };

        alertPublisherPort.publishAntiPattern(event);
    }

    public void onTransactionStatus(String channelId, TransactionState state) {
        if (state == TransactionState.IN_TRANSACTION) {
            activeTransactions.computeIfAbsent(channelId, k ->
                    new TransactionContext("tx-" + UUID.randomUUID().toString().substring(0, 8), channelId)
            );
        } else if (state == TransactionState.IDLE) {
            activeTransactions.remove(channelId);
        }
    }

    public void onTransactionStart(String channelId, String txId) {
        String id = txId != null ? txId : "tx-" + UUID.randomUUID().toString().substring(0, 8);
        activeTransactions.put(channelId, new TransactionContext(id, channelId));
        log.debug("Started transaction {} on channel {}", id, channelId);
    }

    public void onTransactionEnd(String channelId) {
        TransactionContext removed = activeTransactions.remove(channelId);
        if (removed != null) {
            log.debug("Completed transaction {} on channel {}", removed.getTransactionId(), channelId);
        }
    }

    public void checkLongRunningTransactions() {
        for (TransactionContext context : activeTransactions.values()) {
            for (AntiPatternRule rule : rules) {
                rule.evaluateTransaction(context).ifPresent(report ->
                        handleViolation(report, context.getChannelId(), context.getTransactionId())
                );
            }
        }
    }

    public void onChannelClosed(String channelId) {
        activeTransactions.remove(channelId);
        slidingWindowTracker.removeChannel(channelId);
    }

    public TransactionContext getTransaction(String channelId) {
        return activeTransactions.get(channelId);
    }

    public Map<String, TransactionContext> getActiveTransactions() {
        return Collections.unmodifiableMap(activeTransactions);
    }

    public SqlNormalizer getNormalizer() {
        return normalizer;
    }

    public QueryStoragePort getStoragePort() {
        return storagePort;
    }

    public AdvisorService getAdvisorService() {
        return advisorService;
    }
}
