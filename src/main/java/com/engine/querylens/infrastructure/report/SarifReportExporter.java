package com.engine.querylens.infrastructure.report;

import com.engine.querylens.domain.model.ViolationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Exports detected anti-patterns in OASIS SARIF v2.1.0 format
 * for direct integration into GitHub Actions code scanning annotations and pull request diffs.
 */
public class SarifReportExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void export(List<ViolationReport> violations, File targetFile) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json");
        root.put("version", "2.1.0");

        ArrayNode runs = root.putArray("runs");
        ObjectNode run = runs.addObject();

        // Tool Driver
        ObjectNode tool = run.putObject("tool");
        ObjectNode driver = tool.putObject("driver");
        driver.put("name", "QueryLens");
        driver.put("version", "1.0.0");
        driver.put("informationUri", "https://github.com/alexandrmotologa/querylens");

        ArrayNode results = run.putArray("results");

        for (ViolationReport v : violations) {
            ObjectNode result = results.addObject();
            result.put("ruleId", "QL-" + v.type().name());
            result.put("level", "error");

            ObjectNode message = result.putObject("message");
            String msg = v.title() + ": " + v.description() + ". Recommendation: " + v.recommendation();
            message.put("text", msg);

            // Locations (if source file was extracted via SQLCommenter)
            ArrayNode locations = result.putArray("locations");
            ObjectNode location = locations.addObject();
            ObjectNode physLoc = location.putObject("physicalLocation");
            ObjectNode artifactLoc = physLoc.putObject("artifactLocation");

            String sourceLoc = v.sourceLocation();
            if (sourceLoc != null && !sourceLoc.isBlank()) {
                String file = sourceLoc;
                int line = 1;
                if (sourceLoc.contains(":")) {
                    String[] parts = sourceLoc.split(":", 2);
                    file = parts[0];
                    try {
                        line = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
                artifactLoc.put("uri", file);
                ObjectNode region = physLoc.putObject("region");
                region.put("startLine", line);
            } else {
                artifactLoc.put("uri", "src/main/resources/application.properties");
                ObjectNode region = physLoc.putObject("region");
                region.put("startLine", 1);
            }
        }

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(targetFile, root);
    }
}
