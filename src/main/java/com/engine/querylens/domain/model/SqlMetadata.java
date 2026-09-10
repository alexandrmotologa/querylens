package com.engine.querylens.domain.model;

import java.util.Collections;
import java.util.Map;

/**
 * Metadata extracted from SQL comments (SQLCommenter, OpenTelemetry, or custom framework annotations).
 */
public record SqlMetadata(
        String controller,
        String action,
        String route,
        String file,
        Integer line,
        String framework,
        String traceparent,
        Map<String, String> customTags
) {
    public static final SqlMetadata EMPTY = new SqlMetadata(
            null, null, null, null, null, null, null, Collections.emptyMap()
    );

    public SqlMetadata {
        customTags = customTags != null ? Collections.unmodifiableMap(customTags) : Collections.emptyMap();
    }

    public boolean hasSourceInfo() {
        return file != null || controller != null || action != null || route != null;
    }

    public String sourceSummary() {
        if (file != null && line != null) {
            return file + ":" + line;
        }
        if (file != null) {
            return file;
        }
        if (controller != null && action != null) {
            return controller + "#" + action;
        }
        if (controller != null) {
            return controller;
        }
        if (route != null) {
            return route;
        }
        return "Unknown source";
    }
}
