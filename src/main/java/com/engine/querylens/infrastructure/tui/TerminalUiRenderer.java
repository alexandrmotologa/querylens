package com.engine.querylens.infrastructure.tui;

import com.engine.querylens.domain.event.AntiPatternEvent;
import com.engine.querylens.domain.event.NPlusOneDetectedEvent;
import com.engine.querylens.domain.event.QueryCompletedEvent;
import com.engine.querylens.domain.event.SlowQueryAlertEvent;
import com.engine.querylens.domain.event.TransactionAlertEvent;
import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.domain.port.AlertPublisherPort;
import com.engine.querylens.infrastructure.dashboard.SseEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders live query completions and prominent anti-pattern alert banners to the terminal using ANSI escape codes.
 * Concurrently forwards events to the web dashboard SSE broadcaster.
 */
public class TerminalUiRenderer implements AlertPublisherPort {
    private static final Logger log = LoggerFactory.getLogger(TerminalUiRenderer.class);

    // ANSI Escape Sequences
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BG_RED = "\u001B[41m";
    private static final String WHITE_BOLD = "\u001B[1;37m";

    private final SseEventBroadcaster sseBroadcaster;
    private final boolean quiet;

    public TerminalUiRenderer(SseEventBroadcaster sseBroadcaster, boolean quiet) {
        this.sseBroadcaster = sseBroadcaster;
        this.quiet = quiet;
    }

    public TerminalUiRenderer(SseEventBroadcaster sseBroadcaster) {
        this(sseBroadcaster, false);
    }

    @Override
    public void publishAntiPattern(AntiPatternEvent event) {
        if (sseBroadcaster != null) {
            sseBroadcaster.broadcast("anti_pattern", event);
        }

        ViolationReport report = event.report();
        printAlertBanner(report);
    }

    @Override
    public void publishQueryCompleted(QueryCompletedEvent event) {
        if (sseBroadcaster != null) {
            sseBroadcaster.broadcast("query", event);
        }

        if (!quiet) {
            var exec = event.execution();
            long ms = exec.durationMs();
            String durationColor = ms > 100 ? YELLOW : GREEN;
            System.out.printf("[%s%s%s] (%s%d ms%s, %d rows) %s%n",
                    CYAN, exec.fingerprint().queryType(), RESET,
                    durationColor, ms, RESET,
                    exec.rowCount(),
                    truncate(exec.rawSql(), 85)
            );
        }
    }

    private void printAlertBanner(ViolationReport report) {
        System.out.println();
        System.out.println(BG_RED + WHITE_BOLD + " ===================== [QUERYLENS VIOLATION ALERT] ===================== " + RESET);
        System.out.printf("%s%sPattern:%s %s%n", BOLD, RED, RESET, report.title());
        System.out.printf("%sChannel:%s %s | %sTx:%s %s | %sRepeats:%s %dx%n",
                BOLD, RESET, report.channelId(),
                BOLD, RESET, report.transactionId(),
                BOLD, RESET, report.repetitionCount());

        if (report.parentQuery() != null && !report.parentQuery().isBlank()) {
            System.out.printf("%sParent Query:%s %s%n", BOLD, RESET, report.parentQuery());
        }
        System.out.printf("%sRepeating Query:%s %s%n", BOLD, RED, report.offendingQuery());
        System.out.printf("%s%sRecommendation:%s %s%n", BOLD, GREEN, RESET, report.recommendation());
        System.out.println(BG_RED + WHITE_BOLD + " ======================================================================= " + RESET);
        System.out.println();
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
