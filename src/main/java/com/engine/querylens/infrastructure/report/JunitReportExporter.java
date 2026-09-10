package com.engine.querylens.infrastructure.report;

import com.engine.querylens.domain.model.ViolationReport;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exports detected anti-patterns as standard JUnit XML test reports for integration with CI/CD platforms.
 */
public class JunitReportExporter {

    public static void export(List<ViolationReport> violations, File targetFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        int totalTests = Math.max(1, violations.size());
        int failures = violations.size();

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"QueryLens Database Anti-Pattern Audit\" tests=\"")
                .append(totalTests)
                .append("\" failures=\"")
                .append(failures)
                .append("\" errors=\"0\" time=\"0.0\">\n");

        if (violations.isEmpty()) {
            sb.append("    <testcase classname=\"com.engine.querylens.audit\" name=\"DatabasePerformanceCheck\" time=\"0.0\"/>\n");
        } else {
            for (ViolationReport v : violations) {
                String className = "com.engine.querylens." + v.type().name().toLowerCase();
                String testName = escapeXml(v.title() + " - " + v.offendingQuery());

                sb.append("    <testcase classname=\"").append(className).append("\" name=\"").append(testName).append("\" time=\"0.0\">\n");
                sb.append("        <failure message=\"").append(escapeXml(v.description())).append("\">\n");
                sb.append("Pattern: ").append(v.type()).append("\n");
                sb.append("Offending Query: ").append(v.offendingQuery()).append("\n");
                if (v.parentQuery() != null && !v.parentQuery().isBlank()) {
                    sb.append("Parent Driving Query: ").append(v.parentQuery()).append("\n");
                }
                if (v.sourceLocation() != null && !v.sourceLocation().isBlank()) {
                    sb.append("Source Location: ").append(v.sourceLocation()).append("\n");
                }
                sb.append("Repetitions: ").append(v.repetitionCount()).append("x\n");
                sb.append("Latency: ").append(v.durationMs()).append(" ms\n");
                sb.append("Recommendation: ").append(v.recommendation()).append("\n");
                sb.append("        </failure>\n");
                sb.append("    </testcase>\n");
            }
        }

        sb.append("</testsuite>\n");

        try (FileWriter writer = new FileWriter(targetFile, StandardCharsets.UTF_8)) {
            writer.write(sb.toString());
        }
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
