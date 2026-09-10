package com.engine.querylens.infrastructure.config;

/**
 * Immutable runtime configuration options for the QueryLens proxy and detection engine.
 */
public record QueryLensConfig(
        int listenPort,
        String targetHost,
        int targetPort,
        int dashboardPort,
        int nPlusOneThreshold,
        long slowThresholdMs,
        long maxTxDurationMs,
        boolean failOnViolation,
        String reportJsonPath
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int listenPort = 5433;
        private String targetHost = "127.0.0.1";
        private int targetPort = 5432;
        private int dashboardPort = 8080;
        private int nPlusOneThreshold = 5;
        private long slowThresholdMs = 100;
        private long maxTxDurationMs = 5000;
        private boolean failOnViolation = false;
        private String reportJsonPath;

        public Builder listenPort(int listenPort) { this.listenPort = listenPort; return this; }
        public Builder targetHost(String targetHost) { this.targetHost = targetHost; return this; }
        public Builder targetPort(int targetPort) { this.targetPort = targetPort; return this; }
        public Builder dashboardPort(int dashboardPort) { this.dashboardPort = dashboardPort; return this; }
        public Builder nPlusOneThreshold(int nPlusOneThreshold) { this.nPlusOneThreshold = nPlusOneThreshold; return this; }
        public Builder slowThresholdMs(long slowThresholdMs) { this.slowThresholdMs = slowThresholdMs; return this; }
        public Builder maxTxDurationMs(long maxTxDurationMs) { this.maxTxDurationMs = maxTxDurationMs; return this; }
        public Builder failOnViolation(boolean failOnViolation) { this.failOnViolation = failOnViolation; return this; }
        public Builder reportJsonPath(String reportJsonPath) { this.reportJsonPath = reportJsonPath; return this; }

        public QueryLensConfig build() {
            return new QueryLensConfig(
                    listenPort, targetHost, targetPort, dashboardPort,
                    nPlusOneThreshold, slowThresholdMs, maxTxDurationMs,
                    failOnViolation, reportJsonPath
            );
        }
    }
}
