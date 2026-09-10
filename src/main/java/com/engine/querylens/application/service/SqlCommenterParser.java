package com.engine.querylens.application.service;

import com.engine.querylens.domain.model.SqlMetadata;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Google Cloud SQLCommenter and OpenTelemetry tags embedded in SQL comments.
 * Enables attributing database queries directly to the application source code (controller, action, file, line).
 */
public class SqlCommenterParser {

    // Regex for block comments: /* ... */
    private static final Pattern BLOCK_COMMENT_PATTERN = Pattern.compile("/\\*+(.*?)\\*+/", Pattern.DOTALL);

    // Regex for key-value pairs within comment: key='value', key="value", or key=value
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("([a-zA-Z0-9_]+)\\s*=\\s*(?:'([^']*)'|\"([^\"]*)\"|([^,\\s]+))");

    public record ParseResult(SqlMetadata metadata, String cleanSql) {}

    public ParseResult parse(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return new ParseResult(SqlMetadata.EMPTY, "");
        }

        Map<String, String> tags = new HashMap<>();
        Matcher commentMatcher = BLOCK_COMMENT_PATTERN.matcher(rawSql);

        while (commentMatcher.find()) {
            String commentContent = commentMatcher.group(1);
            Matcher kvMatcher = KEY_VALUE_PATTERN.matcher(commentContent);
            while (kvMatcher.find()) {
                String key = kvMatcher.group(1).toLowerCase();
                String val = kvMatcher.group(2);
                if (val == null) val = kvMatcher.group(3);
                if (val == null) val = kvMatcher.group(4);

                if (val != null) {
                    tags.put(key, urlDecode(val));
                }
            }
        }

        // Strip comments from SQL for clean execution and normalization
        String cleanSql = commentMatcher.replaceAll("").trim();

        if (tags.isEmpty()) {
            return new ParseResult(SqlMetadata.EMPTY, cleanSql);
        }

        String controller = tags.remove("controller");
        String action = tags.remove("action");
        String route = tags.remove("route");
        String file = tags.remove("file");
        String framework = tags.remove("framework");
        String traceparent = tags.remove("traceparent");

        Integer line = null;
        String lineStr = tags.remove("line");
        if (lineStr != null) {
            try {
                line = Integer.parseInt(lineStr);
            } catch (NumberFormatException ignored) {}
        }

        // If file contains a line suffix like "OrderService.java:42"
        if (file != null && file.contains(":") && line == null) {
            String[] parts = file.split(":", 2);
            file = parts[0];
            try {
                line = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }

        SqlMetadata metadata = new SqlMetadata(
                controller, action, route, file, line, framework, traceparent, tags
        );

        return new ParseResult(metadata, cleanSql);
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }
}
