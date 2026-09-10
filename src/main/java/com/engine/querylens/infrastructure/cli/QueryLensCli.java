package com.engine.querylens.infrastructure.cli;

import com.engine.querylens.application.service.AdvisorService;
import com.engine.querylens.application.service.InMemoryQueryStore;
import com.engine.querylens.application.service.QueryAnalysisEngine;
import com.engine.querylens.application.service.SlidingWindowTracker;
import com.engine.querylens.application.service.SqlNormalizer;
import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.domain.rules.AntiPatternRule;
import com.engine.querylens.domain.rules.CartesianProductRule;
import com.engine.querylens.domain.rules.LongTransactionRule;
import com.engine.querylens.domain.rules.NPlusOneRule;
import com.engine.querylens.domain.rules.SlowQueryRule;
import com.engine.querylens.infrastructure.dashboard.EmbeddedDashboardServer;
import com.engine.querylens.infrastructure.dashboard.SseEventBroadcaster;
import com.engine.querylens.infrastructure.tui.TerminalUiRenderer;
import com.engine.querylens.infrastructure.wire.QueryLensProxyServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Command line interface for starting QueryLens proxy, dashboard, and headless inspection modes.
 */
@Command(
        name = "querylens",
        description = "Real-Time PostgreSQL Wire Protocol Proxy & N+1 Anti-Pattern Hunter",
        mixinStandardHelpOptions = true,
        version = "QueryLens 1.0.0",
        subcommands = {QueryLensCli.ProxyCommand.class}
)
public class QueryLensCli implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "proxy", description = "Start the transparent PostgreSQL wire protocol proxy and analyzer", mixinStandardHelpOptions = true)
    public static class ProxyCommand implements Callable<Integer> {
        private static final Logger log = LoggerFactory.getLogger(ProxyCommand.class);

        @Option(names = {"-l", "--listen-port"}, description = "Port to listen for incoming client database connections (default: 5433)", defaultValue = "5433")
        private int listenPort;

        @Option(names = {"-H", "--target-host"}, description = "Target PostgreSQL server hostname (default: 127.0.0.1)", defaultValue = "127.0.0.1")
        private String targetHost;

        @Option(names = {"-p", "--target-port"}, description = "Target PostgreSQL server port (default: 5432)", defaultValue = "5432")
        private int targetPort;

        @Option(names = {"-d", "--dashboard-port"}, description = "Port for the live web dashboard and SSE stream (default: 8080)", defaultValue = "8080")
        private int dashboardPort;

        @Option(names = {"-n", "--nplusone-threshold"}, description = "Number of repeated queries to flag an N+1 cascade (default: 5)", defaultValue = "5")
        private int nPlusOneThreshold;

        @Option(names = {"-s", "--slow-threshold-ms"}, description = "Execution latency threshold in ms to flag a slow query (default: 100)", defaultValue = "100")
        private long slowThresholdMs;

        @Option(names = {"-m", "--max-tx-duration-ms"}, description = "Maximum idle duration in ms before flagging a long transaction (default: 5000)", defaultValue = "5000")
        private long maxTxDurationMs;

        @Option(names = {"-f", "--fail-on-violation"}, description = "Exit with non-zero status code on shutdown if violations occurred (CI/CD gate)")
        private boolean failOnViolation;

        @Option(names = {"-r", "--report-json"}, description = "File path to export detected violations report in JSON format upon exit")
        private String reportJsonPath;

        @Option(names = {"-q", "--quiet"}, description = "Suppress live query console ticker output")
        private boolean quiet;

        @Override
        public Integer call() throws Exception {
            printBanner();

            SqlNormalizer normalizer = new SqlNormalizer();
            SlidingWindowTracker windowTracker = new SlidingWindowTracker();
            AdvisorService advisorService = new AdvisorService();
            InMemoryQueryStore queryStore = new InMemoryQueryStore();
            SseEventBroadcaster sseBroadcaster = new SseEventBroadcaster();
            TerminalUiRenderer tuiRenderer = new TerminalUiRenderer(sseBroadcaster, quiet);

            List<AntiPatternRule> rules = List.of(
                    new NPlusOneRule(nPlusOneThreshold),
                    new SlowQueryRule(slowThresholdMs),
                    new LongTransactionRule(maxTxDurationMs),
                    new CartesianProductRule(10_000)
            );

            QueryAnalysisEngine engine = new QueryAnalysisEngine(
                    normalizer, windowTracker, advisorService, queryStore, tuiRenderer, rules
            );

            EmbeddedDashboardServer dashboardServer = new EmbeddedDashboardServer(dashboardPort, queryStore, sseBroadcaster);
            dashboardServer.start();

            QueryLensProxyServer proxyServer = new QueryLensProxyServer(listenPort, targetHost, targetPort, engine);
            proxyServer.start();

            System.out.printf("QueryLens Proxy listening on port %d -> forwarding to %s:%d%n", listenPort, targetHost, targetPort);
            System.out.printf("Web Dashboard active at http://localhost:%d/dashboard%n", dashboardPort);
            System.out.println("Ready to intercept queries. Press Ctrl+C to terminate.");
            System.out.println();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down QueryLens...");
                proxyServer.stop();
                dashboardServer.stop();

                if (reportJsonPath != null && !reportJsonPath.isBlank()) {
                    exportJsonReport(queryStore, reportJsonPath);
                }

                if (failOnViolation && queryStore.getTotalViolationCount() > 0) {
                    System.err.printf("[FAILURE] %d anti-pattern violations detected in CI gate mode!%n",
                            queryStore.getTotalViolationCount());
                    System.exit(1);
                }
            }));

            // Keep process running
            while (proxyServer.isRunning()) {
                Thread.sleep(1000);
                engine.checkLongRunningTransactions();
            }

            return 0;
        }

        private void exportJsonReport(InMemoryQueryStore queryStore, String path) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());
                List<ViolationReport> violations = queryStore.getRecentViolations(1000);
                Map<String, Object> summary = queryStore.getMetricsSnapshot();

                Map<String, Object> report = Map.of(
                        "summary", summary,
                        "violations", violations
                );

                File file = new File(path);
                mapper.writerWithDefaultPrettyPrinter().writeValue(file, report);
                System.out.printf("Exported %d violations to %s%n", violations.size(), file.getAbsolutePath());
            } catch (Exception e) {
                log.error("Failed to export JSON violation report to {}", path, e);
            }
        }

        private void printBanner() {
            try (InputStream in = getClass().getResourceAsStream("/banner.txt")) {
                if (in != null) {
                    String banner = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    System.out.println(banner);
                }
            } catch (Exception ignored) {}
        }
    }
}
