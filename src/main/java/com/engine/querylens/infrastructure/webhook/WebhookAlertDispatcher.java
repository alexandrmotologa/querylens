package com.engine.querylens.infrastructure.webhook;

import com.engine.querylens.domain.model.ViolationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asynchronously dispatches anti-pattern alert notifications to webhook endpoints
 * (Slack, Discord, Microsoft Teams, or generic HTTP collectors) using Virtual Threads.
 */
public class WebhookAlertDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookAlertDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String webhookUrl;
    private final HttpClient httpClient;

    public WebhookAlertDispatcher(String webhookUrl, HttpClient httpClient) {
        this.webhookUrl = webhookUrl;
        this.httpClient = httpClient;
    }

    public WebhookAlertDispatcher(String webhookUrl) {
        this(webhookUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build());
    }

    public void dispatch(ViolationReport report) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                String payload = buildPayload(report);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.debug("Dispatched alert webhook to {} (status: {})", webhookUrl, response.statusCode());
                } else {
                    log.warn("Webhook target returned non-2xx status code {}: {}", response.statusCode(), response.body());
                }
            } catch (Exception e) {
                log.warn("Failed to dispatch alert webhook to {}: {}", webhookUrl, e.getMessage());
            }
        });
    }

    private String buildPayload(ViolationReport report) throws Exception {
        Map<String, Object> payload = new HashMap<>();

        String alertTitle = "🚨 QueryLens Alert: " + report.title();
        payload.put("text", alertTitle);

        Map<String, Object> attachment = new HashMap<>();
        attachment.put("title", report.title());
        attachment.put("color", "#ef4444");

        StringBuilder fields = new StringBuilder();
        fields.append("*Pattern:* ").append(report.type()).append("\n");
        fields.append("*Repetitions:* ").append(report.repetitionCount()).append("x\n");
        fields.append("*Latency:* ").append(report.durationMs()).append(" ms\n");

        if (report.sourceLocation() != null && !report.sourceLocation().isBlank()) {
            fields.append("*Source:* `").append(report.sourceLocation()).append("`\n");
        }
        if (report.parentQuery() != null && !report.parentQuery().isBlank()) {
            fields.append("*Parent Query:* `").append(truncate(report.parentQuery(), 80)).append("`\n");
        }
        fields.append("*Offending Query:* `").append(truncate(report.offendingQuery(), 80)).append("`\n");
        fields.append("*Recommendation:* ").append(report.recommendation());

        attachment.put("text", fields.toString());
        payload.put("attachments", List.of(attachment));

        return MAPPER.writeValueAsString(payload);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }
}
