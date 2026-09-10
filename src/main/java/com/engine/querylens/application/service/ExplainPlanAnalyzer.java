package com.engine.querylens.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Analyzes PostgreSQL execution plans in JSON format (EXPLAIN FORMAT JSON),
 * recursively inspecting plan nodes to flag sequential table scans, disk-spilling sorts,
 * and high-cost operators.
 */
public class ExplainPlanAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record PlanAuditResult(
            double totalCost,
            List<String> bottlenecks,
            List<String> recommendations,
            boolean hasSequentialScans,
            boolean hasDiskSpill
    ) {
        public static final PlanAuditResult EMPTY = new PlanAuditResult(
                0.0, Collections.emptyList(), Collections.emptyList(), false, false
        );
    }

    public PlanAuditResult analyze(String jsonPlan) {
        if (jsonPlan == null || jsonPlan.isBlank()) {
            return PlanAuditResult.EMPTY;
        }

        try {
            JsonNode root = MAPPER.readTree(jsonPlan);
            JsonNode planNode;

            if (root.isArray() && !root.isEmpty()) {
                planNode = root.get(0).get("Plan");
            } else if (root.has("Plan")) {
                planNode = root.get("Plan");
            } else {
                return PlanAuditResult.EMPTY;
            }

            if (planNode == null) {
                return PlanAuditResult.EMPTY;
            }

            double totalCost = planNode.path("Total Cost").asDouble(0.0);
            List<String> bottlenecks = new ArrayList<>();
            List<String> recommendations = new ArrayList<>();
            boolean[] flags = new boolean[2]; // [0] = seqScan, [1] = diskSpill

            traverseNode(planNode, bottlenecks, recommendations, flags);

            return new PlanAuditResult(
                    totalCost,
                    Collections.unmodifiableList(bottlenecks),
                    Collections.unmodifiableList(recommendations),
                    flags[0],
                    flags[1]
            );
        } catch (Exception e) {
            return PlanAuditResult.EMPTY;
        }
    }

    private void traverseNode(JsonNode node, List<String> bottlenecks, List<String> recommendations, boolean[] flags) {
        String nodeType = node.path("Node Type").asText("");
        String relation = node.path("Relation Name").asText("");
        double cost = node.path("Total Cost").asDouble(0.0);
        long rows = node.path("Plan Rows").asLong(0);

        if ("Seq Scan".equalsIgnoreCase(nodeType)) {
            flags[0] = true;
            String filter = node.path("Filter").asText("");
            String issue = "Sequential Scan on table '" + relation + "' (Estimated rows: " + rows + ", Cost: " + cost + ")";
            bottlenecks.add(issue);

            if (!filter.isBlank()) {
                recommendations.add("Add an index on table '" + relation + "' covering filter condition: " + filter);
            } else {
                recommendations.add("Evaluate creating an index on table '" + relation + "' to avoid full table scanning.");
            }
        }

        String sortMethod = node.path("Sort Method").asText("");
        if (sortMethod.toLowerCase().contains("disk") || sortMethod.toLowerCase().contains("external merge")) {
            flags[1] = true;
            bottlenecks.add("Sort spilled to disk (" + sortMethod + ") on operator with cost " + cost);
            recommendations.add("Increase PostgreSQL 'work_mem' setting for this query or session to keep sorts in memory.");
        }

        JsonNode plans = node.get("Plans");
        if (plans != null && plans.isArray()) {
            for (JsonNode child : plans) {
                traverseNode(child, bottlenecks, recommendations, flags);
            }
        }
    }
}
