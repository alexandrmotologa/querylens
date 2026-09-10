package com.engine.querylens.application;

import com.engine.querylens.application.service.ExplainPlanAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainPlanAnalyzerTest {

    @Test
    @DisplayName("Should detect Sequential Scan and recommend indexes from JSON plan")
    void shouldDetectSequentialScan() {
        String jsonPlan = """
        [
          {
            "Plan": {
              "Node Type": "Seq Scan",
              "Relation Name": "orders",
              "Alias": "orders",
              "Startup Cost": 0.00,
              "Total Cost": 4250.00,
              "Plan Rows": 150000,
              "Plan Width": 32,
              "Filter": "(status = 'PENDING')"
            }
          }
        ]
        """;

        ExplainPlanAnalyzer analyzer = new ExplainPlanAnalyzer();
        ExplainPlanAnalyzer.PlanAuditResult result = analyzer.analyze(jsonPlan);

        assertThat(result.hasSequentialScans()).isTrue();
        assertThat(result.totalCost()).isEqualTo(4250.00);
        assertThat(result.bottlenecks()).anyMatch(b -> b.contains("Sequential Scan on table 'orders'"));
        assertThat(result.recommendations()).anyMatch(r -> r.contains("Add an index on table 'orders'"));
    }

    @Test
    @DisplayName("Should detect disk spill in sort operations")
    void shouldDetectDiskSpill() {
        String jsonPlan = """
        [
          {
            "Plan": {
              "Node Type": "Sort",
              "Startup Cost": 120.00,
              "Total Cost": 890.00,
              "Plan Rows": 5000,
              "Sort Method": "external merge Disk: 1540kB"
            }
          }
        ]
        """;

        ExplainPlanAnalyzer analyzer = new ExplainPlanAnalyzer();
        ExplainPlanAnalyzer.PlanAuditResult result = analyzer.analyze(jsonPlan);

        assertThat(result.hasDiskSpill()).isTrue();
        assertThat(result.bottlenecks()).anyMatch(b -> b.contains("Sort spilled to disk"));
        assertThat(result.recommendations()).anyMatch(r -> r.contains("work_mem"));
    }
}
