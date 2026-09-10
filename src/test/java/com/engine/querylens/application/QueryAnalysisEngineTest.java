package com.engine.querylens.application;

import com.engine.querylens.application.service.AdvisorService;
import com.engine.querylens.application.service.InMemoryQueryStore;
import com.engine.querylens.application.service.QueryAnalysisEngine;
import com.engine.querylens.application.service.SlidingWindowTracker;
import com.engine.querylens.application.service.SqlNormalizer;
import com.engine.querylens.domain.event.AntiPatternEvent;
import com.engine.querylens.domain.event.NPlusOneDetectedEvent;
import com.engine.querylens.domain.event.QueryCompletedEvent;
import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.QueryExecution;
import com.engine.querylens.domain.model.TransactionState;
import com.engine.querylens.domain.port.AlertPublisherPort;
import com.engine.querylens.domain.rules.NPlusOneRule;
import com.engine.querylens.domain.rules.SlowQueryRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryAnalysisEngineTest {

    private QueryAnalysisEngine engine;
    private InMemoryQueryStore queryStore;
    private List<AntiPatternEvent> emittedEvents;

    @BeforeEach
    void setUp() {
        SqlNormalizer normalizer = new SqlNormalizer();
        SlidingWindowTracker tracker = new SlidingWindowTracker();
        AdvisorService advisorService = new AdvisorService();
        queryStore = new InMemoryQueryStore();
        emittedEvents = new ArrayList<>();

        AlertPublisherPort publisher = new AlertPublisherPort() {
            @Override
            public void publishAntiPattern(AntiPatternEvent event) {
                emittedEvents.add(event);
            }

            @Override
            public void publishQueryCompleted(QueryCompletedEvent event) {}
        };

        engine = new QueryAnalysisEngine(
                normalizer, tracker, advisorService, queryStore, publisher,
                List.of(new NPlusOneRule(5), new SlowQueryRule(100))
        );
    }

    @Test
    @DisplayName("Should track queries, identify N+1 in transaction, and store violation")
    void shouldTrackQueriesAndDetectNPlusOne() {
        String channelId = "test-client";
        engine.onTransactionStatus(channelId, TransactionState.IN_TRANSACTION);

        // Parent query
        engine.onQueryCompleted(channelId, "SELECT * FROM orders WHERE status = 'PENDING'", Duration.ofMillis(10), 10);

        // 5 repeated child queries
        for (int i = 1; i <= 5; i++) {
            engine.onQueryCompleted(channelId, "SELECT * FROM customer WHERE id = " + i, Duration.ofMillis(2), 1);
        }

        assertThat(emittedEvents).hasSize(1);
        AntiPatternEvent event = emittedEvents.get(0);
        assertThat(event).isInstanceOf(NPlusOneDetectedEvent.class);
        NPlusOneDetectedEvent n1 = (NPlusOneDetectedEvent) event;
        assertThat(n1.repetitionCount()).isEqualTo(5);
        assertThat(n1.parentQuery()).isEqualTo("SELECT * FROM orders WHERE status = ?");
        assertThat(n1.childQuery()).isEqualTo("SELECT * FROM customer WHERE id = ?");

        assertThat(queryStore.getTotalViolationCount()).isEqualTo(1);
        assertThat(queryStore.getTotalQueryCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("Should detect slow queries outside transaction")
    void shouldDetectSlowQueryOutsideTransaction() {
        String channelId = "test-client-2";
        QueryExecution exec = engine.onQueryCompleted(
                channelId, "SELECT * FROM big_data", Duration.ofMillis(250), 1000
        );

        assertThat(exec.durationMs()).isEqualTo(250);
        assertThat(emittedEvents).hasSize(1);
        assertThat(emittedEvents.get(0).report().type()).isEqualTo(AntiPatternType.SLOW_QUERY);
        assertThat(queryStore.getTotalViolationCount()).isEqualTo(1);
    }
}
