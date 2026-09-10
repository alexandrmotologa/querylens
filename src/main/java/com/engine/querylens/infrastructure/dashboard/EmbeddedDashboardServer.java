package com.engine.querylens.infrastructure.dashboard;

import com.engine.querylens.application.service.ExplainPlanAnalyzer;
import com.engine.querylens.application.service.InMemoryQueryStore;
import com.engine.querylens.domain.port.QueryStoragePort;
import com.engine.querylens.infrastructure.metrics.PrometheusMetricsExporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server backed by Java 21 Virtual Threads,
 * serving the live query monitoring dashboard, SSE streams, Prometheus metrics, and REST telemetry endpoints.
 */
public class EmbeddedDashboardServer {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedDashboardServer.class);

    private final int port;
    private final QueryStoragePort storagePort;
    private final SseEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final PrometheusMetricsExporter prometheusExporter;
    private final ExplainPlanAnalyzer explainAnalyzer;

    private HttpServer server;
    private volatile boolean running = false;

    public EmbeddedDashboardServer(int port, QueryStoragePort storagePort, SseEventBroadcaster broadcaster) {
        this.port = port;
        this.storagePort = storagePort;
        this.broadcaster = broadcaster;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.prometheusExporter = new PrometheusMetricsExporter(storagePort);
        this.explainAnalyzer = new ExplainPlanAnalyzer();
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || "/dashboard".equals(path)) {
                serveResource(exchange, "/web/index.html", "text/html; charset=UTF-8");
            } else if ("/static/style.css".equals(path)) {
                serveResource(exchange, "/web/style.css", "text/css; charset=UTF-8");
            } else if ("/static/app.js".equals(path)) {
                serveResource(exchange, "/web/app.js", "application/javascript; charset=UTF-8");
            } else {
                sendResponse(exchange, 404, "Not Found", "text/plain");
            }
        });

        server.createContext("/api/events", new SseHandler());
        server.createContext("/api/stats", new StatsHandler());
        server.createContext("/api/violations", new ViolationsHandler());
        server.createContext("/api/queries", new QueriesHandler());
        server.createContext("/api/explain", new ExplainHandler());
        server.createContext("/metrics", new MetricsHandler());

        server.start();
        running = true;
        log.info("QueryLens web dashboard live at http://localhost:{}/dashboard", port);
        log.info("QueryLens Prometheus metrics available at http://localhost:{}/metrics", port);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        server.stop(0);
        running = false;
        log.info("QueryLens web dashboard stopped");
    }

    private void serveResource(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendResponse(exchange, 404, "Resource " + resourcePath + " not found", "text/plain");
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0); // Chunked transfer

            OutputStream os = exchange.getResponseBody();
            broadcaster.addClient(os);

            // Send initial ping
            os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = objectMapper.writeValueAsString(storagePort.getMetricsSnapshot());
            sendResponse(exchange, 200, json, "application/json; charset=UTF-8");
        }
    }

    private class ViolationsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = objectMapper.writeValueAsString(storagePort.getRecentViolations(50));
            sendResponse(exchange, 200, json, "application/json; charset=UTF-8");
        }
    }

    private class QueriesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (storagePort instanceof InMemoryQueryStore store) {
                String json = objectMapper.writeValueAsString(store.getTopQueries(50));
                sendResponse(exchange, 200, json, "application/json; charset=UTF-8");
            } else {
                sendResponse(exchange, 200, "[]", "application/json; charset=UTF-8");
            }
        }
    }

    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String metrics = prometheusExporter.export();
            sendResponse(exchange, 200, metrics, "text/plain; version=0.0.4; charset=UTF-8");
        }
    }

    private class ExplainHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed. Use POST.", "text/plain");
                return;
            }

            try (InputStream is = exchange.getRequestBody()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                JsonNode root = objectMapper.readTree(body);
                String plan = root.has("plan") ? root.get("plan").asText() : body;

                ExplainPlanAnalyzer.PlanAuditResult result = explainAnalyzer.analyze(plan);
                String json = objectMapper.writeValueAsString(result);
                sendResponse(exchange, 200, json, "application/json; charset=UTF-8");
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"error\": \"" + e.getMessage() + "\"}", "application/json; charset=UTF-8");
            }
        }
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }
}
