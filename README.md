# QueryLens

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
```

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
- Latency and percentile tracking: Calculates running P50, P90, and P99 latencies for each query fingerprint.
- Connection and transaction monitoring: Flags transactions that remain uncommitted past configured duration limits.
- Cartesian product detection: Warns when queries return large result sets without an explicit `LIMIT` clause.
- Terminal TUI: Displays real-time query throughput, active alerts, and top slow queries in the console.
- Embedded web dashboard: Provides a browser interface served over Server-Sent Events (SSE) with live query flame charts and anti-pattern reports.
- CI/CD quality gate: Supports headless execution modes with exit codes or JSON reports to fail integration test runs on detected anti-patterns.

## Architecture

QueryLens uses a hexagonal structure:

- `domain`: Pure Java 21 business logic with zero framework dependencies. Contains models (`QueryFingerprint`, `QueryExecution`, `TransactionContext`), anti-pattern rules (`NPlusOneRule`, `SlowQueryRule`, `LongTransactionRule`), and domain events.
- `application`: Services that coordinate analysis, maintain sliding time windows, compute percentiles, and generate recommendations.
- `infrastructure`: Netty wire codecs for PostgreSQL v3.0, upstream/downstream socket handlers, Picocli command runner, and the embedded HTTP/SSE dashboard.

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
Usage: querylens proxy [-hV] [--fail-on-violation] [--listen-port=<listenPort>]
                       [--target-host=<targetHost>] [--target-port=<targetPort>]
                       [--dashboard-port=<dashboardPort>]
                       [--nplusone-threshold=<nPlusOneThreshold>]
                       [--slow-threshold-ms=<slowThresholdMs>]
                       [--report-json=<reportJsonPath>]

Options:
  -l, --listen-port=<listenPort>       Port QueryLens listens on (default: 5433)
  -H, --target-host=<targetHost>       Target PostgreSQL host (default: 127.0.0.1)
  -p, --target-port=<targetPort>       Target PostgreSQL port (default: 5432)
  -d, --dashboard-port=<dashboardPort> Port for web dashboard and SSE (default: 8080)
  -n, --nplusone-threshold=<count>     Repetition count to flag N+1 (default: 5)
  -s, --slow-threshold-ms=<ms>         Duration threshold for slow queries (default: 100)
  -f, --fail-on-violation              Exit with non-zero status code on violations
  -r, --report-json=<path>             Path to write final violation report in JSON format
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
