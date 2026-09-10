package com.engine.querylens.infrastructure;

import com.engine.querylens.domain.model.AntiPatternType;
import com.engine.querylens.domain.model.ViolationReport;
import com.engine.querylens.infrastructure.report.JunitReportExporter;
import com.engine.querylens.infrastructure.report.SarifReportExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportersTest {

    @Test
    @DisplayName("Should export valid JUnit XML report with failure nodes")
    void shouldExportJunitReport(@TempDir Path tempDir) throws Exception {
        File junitFile = tempDir.resolve("junit.xml").toFile();

        ViolationReport report = ViolationReport.builder(AntiPatternType.N_PLUS_ONE)
                .title("N+1 Cascade Detected")
                .description("5 queries inside transaction")
                .offendingQuery("SELECT * FROM customer WHERE id = ?")
                .parentQuery("SELECT * FROM orders WHERE status = ?")
                .sourceLocation("OrderService.java:42")
                .repetitionCount(5)
                .build();

        JunitReportExporter.export(List.of(report), junitFile);

        assertThat(junitFile).exists();
        String xml = Files.readString(junitFile.toPath());
        assertThat(xml).contains("<testsuite name=\"QueryLens Database Anti-Pattern Audit\" tests=\"1\" failures=\"1\"");
        assertThat(xml).contains("<failure message=\"5 queries inside transaction\">");
        assertThat(xml).contains("OrderService.java:42");
    }

    @Test
    @DisplayName("Should export valid SARIF v2.1.0 report with physical locations")
    void shouldExportSarifReport(@TempDir Path tempDir) throws Exception {
        File sarifFile = tempDir.resolve("results.sarif").toFile();

        ViolationReport report = ViolationReport.builder(AntiPatternType.SLOW_QUERY)
                .title("Slow Query Execution")
                .description("Query took 450 ms")
                .offendingQuery("SELECT * FROM large_table")
                .sourceLocation("UserRepository.java:108")
                .durationMs(450)
                .build();

        SarifReportExporter.export(List.of(report), sarifFile);

        assertThat(sarifFile).exists();
        String json = Files.readString(sarifFile.toPath());
        assertThat(json).contains("\"version\" : \"2.1.0\"");
        assertThat(json).contains("\"name\" : \"QueryLens\"");
        assertThat(json).contains("\"uri\" : \"UserRepository.java\"");
        assertThat(json).contains("\"startLine\" : 108");
    }
}
