<p align="center">
  <img src="docs/images/logo.png?raw=true" alt="QueryLens Logo" width="140" style="border-radius: 28px;" />
</p>

<h1 align="center">QueryLens</h1>

<p align="center">
  <strong>Real-Time PostgreSQL Wire Protocol Proxy & N+1 Anti-Pattern Hunter</strong>
</p>

<p align="center">
  <a href="https://github.com/alexandrmotologa/querylens/actions"><img src="https://img.shields.io/badge/build-passing-brightgreen.svg" alt="Build Status" /></a>
  <a href="https://www.oracle.com/java/technologies/downloads/#java21"><img src="https://img.shields.io/badge/java-21%20LTS-orange.svg" alt="Java 21" /></a>
  <a href="https://netty.io/"><img src="https://img.shields.io/badge/network-Netty%204.1-blue.svg" alt="Netty 4.1" /></a>
  <a href="https://www.postgresql.org/"><img src="https://img.shields.io/badge/protocol-PostgreSQL%20v3.0-336791.svg" alt="PostgreSQL v3.0" /></a>
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/license-MIT-green.svg" alt="License: MIT" /></a>
</p>

QueryLens is a transparent PostgreSQL v3.0 wire-protocol proxy and query analysis engine written in Java 21 LTS with Netty. It sits between client applications and a PostgreSQL server, inspecting traffic in flight to catch N+1 query patterns, slow queries, unindexed table scans, and transaction hoarding.

```
+-------------------+      Port 5433       +-------------------+      Port 5432       +-------------------+
|  App Microservice |  =================>  | QueryLens Proxy   |  =================>  | PostgreSQL Server |
| (Hibernate, etc.) |  <=================  | & Analysis Engine |  <=================  | (Local or Remote) |
+-------------------+                      +---------+---------+                      +-------------------+
                                                     |
                                            +--------+--------+
                                            |                 |
                                            v                 v
                                    Terminal TUI       Web Dashboard
                                    Live Stream        (Port 8080)
                                    & Webhooks         & /metrics
```

## Live Dashboard & Visual Telemetry

QueryLens serves an interactive real-time telemetry dashboard backed by Java 21 Virtual Threads and Server-Sent Events (SSE) at `http://localhost:8080/dashboard`.

<p align="center">
  <img src="docs/images/dashboard-overview.png?raw=true" alt="QueryLens Real-Time Overview Dashboard" width="100%" style="border-radius: 12px; box-shadow: 0 16px 32px rgba(0,0,0,0.25);" />
</p>

The overview displays live QPS with sparkline velocity, total queries, tail latencies (P50, P90, P99), and active anti-pattern detections with direct source code attribution (`controller`, `action`, `file`, `line`).

### Live Query Stream & P99 Latency Profiler

<p align="center">
  <img src="docs/images/dashboard-stream.png?raw=true" alt="QueryLens Live Query Stream and Profiler" width="100%" style="border-radius: 12px; box-shadow: 0 16px 32px rgba(0,0,0,0.25);" />
</p>

- Stream Freeze / Resume: Pause the incoming stream to inspect specific database operations without losing background events.
- Instant Search & Filter: Filter queries by table name, statement type (SELECT, UPDATE, INSERT), or minimum duration in milliseconds.
- 1-Click Clipboard Actions: Copy parameterized SQL templates or formatted `EXPLAIN (ANALYZE, BUFFERS)` statements with a single click.

## Why QueryLens exists

Database performance regressions often slip into production through Object-Relational Mappers like Hibernate, Spring Data JPA, Prisma, and SQLAlchemy. 

Common issues include:
- N+1 query cascades: An application executes one query to load parent records, then issues separate queries in a loop for each child relation. Local development datasets usually contain too few rows to expose the latency cost.
- Table scans on large tables: Queries that execute in 2 ms on 50 development rows degrade to multi-second delays when datasets grow.
- Transaction hoarding: Connections held open during slow application logic or external network calls exhaust connection pools.

QueryLens identifies these regressions before they reach production. Because it functions as a standard network proxy, it works with any programming language and framework without requiring bytecode instrumentation or application code modifications.

## Core capabilities

- Transparent proxying: Decodes PostgreSQL v3.0 frontend and backend frames with sub-millisecond overhead. Supports both Simple Query (`Query`) and Extended Query (`Parse`, `Bind`, `Execute`, `Sync`) protocol flows.
- SQL normalization and fingerprinting: Strips literals and constants to produce parameterized query templates. Groups executions under 64-bit cryptographic hashes.
- Causal N+1 detection: Tracks query counts within transaction scopes and sliding windows. When a query repeats past a configured threshold, QueryLens links the repeated queries back to the preceding parent query.
- SQLCommenter source attribution: Extracts controller, service method, source file, line number, and distributed trace IDs from SQL comments.
- Execution plan analysis: Parses PostgreSQL `EXPLAIN (FORMAT JSON)` structures to flag sequential table scans and disk-spilling sort operations.
- Latency and percentile tracking: Calculates running P50, P90, and P99 latencies for each query fingerprint.
- Connection and transaction monitoring: Flags transactions that remain uncommitted past configured duration limits.
- Cartesian product detection: Warns when queries return large result sets without an explicit `LIMIT` clause.
- Prometheus metrics exporter: Serves standard OpenMetrics / Prometheus metrics at `/metrics` for Grafana dashboards.
- Webhook notifications: Asynchronously dispatches violation alert cards to Slack, Discord, or generic HTTP endpoints.
- Interactive web dashboard: Provides a browser interface served over Server-Sent Events (SSE) with live query stream, pause/resume toggles, instant search filtering, 1-click clipboard actions, and sparkline graphs.
- CI/CD quality gates: Exports violation reports in JSON, standard JUnit XML, or OASIS SARIF v2.1.0 formats to fail automated builds and produce inline code annotations in GitHub Actions pull request diffs.

## Architecture

QueryLens uses a hexagonal structure:

- `domain`: Pure Java 21 business logic with zero framework dependencies. Contains models (`QueryFingerprint`, `QueryExecution`, `TransactionContext`, `SqlMetadata`), anti-pattern rules (`NPlusOneRule`, `SlowQueryRule`, `LongTransactionRule`), and domain events.
- `application`: Services that coordinate analysis, maintain sliding time windows, compute percentiles, parse SQLCommenter tags, inspect EXPLAIN plans, and generate recommendations.
- `infrastructure`: Netty wire codecs for PostgreSQL v3.0, upstream/downstream socket handlers, Picocli command runner, embedded HTTP/SSE dashboard, Prometheus exporter, and JUnit/SARIF report writers.

Detailed design documents are located in the `docs/` directory:
- [Architecture details](docs/architecture.md)
- [Wire protocol handling](docs/wire-protocol.md)
- [Detection algorithms](docs/detection-algorithms.md)
- [Web dashboard and TUI](docs/dashboard.md)

## Getting started

### Prerequisites

- Java 21 LTS or newer
- Maven 3.9+

### Build

Compile the project and assemble the executable JAR:

```bash
mvn clean package
```

The resulting executable JAR will be located at:
`target/querylens-1.0.0-SNAPSHOT.jar`

### Running the proxy

Start QueryLens listening on port 5433 and forwarding to PostgreSQL on port 5432:

```bash
java -jar target/querylens-1.0.0-SNAPSHOT.jar proxy \
  --listen-port 5433 \
  --target-host 127.0.0.1 \
  --target-port 5432 \
  --dashboard-port 8080
```

Point your application database connection to `localhost:5433` instead of `5432`.

### CLI Options

```text
Usage: querylens proxy [-fhqV] [-d=<dashboardPort>] [-H=<targetHost>]
                       [-l=<listenPort>] [-m=<maxTxDurationMs>]
                       [-n=<nPlusOneThreshold>] [-p=<targetPort>]
                       [-r=<reportJsonPath>] [--report-junit=<reportJunitPath>]
                       [--report-sarif=<reportSarifPath>]
                       [-s=<slowThresholdMs>] [-w=<webhookUrl>]

Options:
  -l, --listen-port=<listenPort>       Port QueryLens listens on (default: 5433)
  -H, --target-host=<targetHost>       Target PostgreSQL host (default: 127.0.0.1)
  -p, --target-port=<targetPort>       Target PostgreSQL port (default: 5432)
  -d, --dashboard-port=<dashboardPort> Port for web dashboard and SSE (default: 8080)
  -n, --nplusone-threshold=<count>     Repetition count to flag N+1 (default: 5)
  -s, --slow-threshold-ms=<ms>         Duration threshold for slow queries (default: 100)
  -m, --max-tx-duration-ms=<ms>        Maximum idle duration before flagging transaction (default: 5000)
  -w, --webhook-url=<url>              Webhook URL for Slack, Discord, or Teams alerts
  -f, --fail-on-violation              Exit with non-zero status code on violations (CI/CD gate)
  -r, --report-json=<path>             Path to write final violation report in JSON format
      --report-junit=<path>            Path to write final violation report in JUnit XML format
      --report-sarif=<path>            Path to write final violation report in OASIS SARIF v2.1.0 format
  -q, --quiet                          Suppress live query console ticker output
  -h, --help                           Show this help message and exit
  -V, --version                        Print version information and exit
```

## Running tests

Execute the test suite:

```bash
mvn test
```

## License

QueryLens is licensed under the MIT License. See [LICENSE](LICENSE) for details.
