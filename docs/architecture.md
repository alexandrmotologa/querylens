# Architecture

QueryLens uses hexagonal architecture (ports and adapters) to decouple database wire networking from anti-pattern detection rules.

```
com.engine.querylens/
├── domain/                          # Pure Java 21, no external framework imports
│   ├── model/                       # Core abstractions: QueryFingerprint, QueryExecution, TransactionContext
│   ├── rules/                       # Detection logic: NPlusOneRule, SlowQueryRule, LongTransactionRule
│   ├── event/                       # Sealed domain events: NPlusOneDetectedEvent, SlowQueryAlertEvent
│   └── port/                        # Ports: AlertPublisherPort, FingerprintPort, QueryStoragePort
├── application/                     # Application services and coordinators
│   ├── service/                     # QueryAnalysisEngine, SlidingWindowTracker, SqlNormalizer, AdvisorService
│   └── dto/                         # Data transfer objects and summaries
└── infrastructure/                  # Network, protocols, and interfaces
    ├── wire/                        # Netty decoders and transparent proxy handlers
    ├── dashboard/                   # Embedded HTTP server and SSE broadcaster
    ├── tui/                         # Terminal table and ANSI alert renderer
    ├── cli/                         # Picocli command runner
    └── config/                      # Configuration records and options
```

## Data flow

1. Client connection: A client application (e.g. Hibernate over JDBC) connects to QueryLens on port 5433.
2. Handshake: QueryLens opens a corresponding outbound connection to the target PostgreSQL instance on port 5432 and forwards the initial handshake and authentication frames transparently.
3. Query interception:
   - When a frontend query packet arrives (either Simple Query `Q` or Extended Query `P`/`B`/`E`), QueryLens records the query text, decodes statement names, and starts an execution timer.
   - The packet forwards immediately to the PostgreSQL backend to minimize proxy overhead.
4. Response interception:
   - As the backend returns response frames (`CommandComplete`, `DataRow`, `ReadyForQuery`), QueryLens counts returned rows, calculates elapsed time, and extracts transaction status.
5. Analysis engine:
   - The intercepted query passes to `QueryAnalysisEngine`.
   - `SqlNormalizer` generates a canonical parameterized string and 64-bit fingerprint hash.
   - Active rules evaluate the execution against transaction history and sliding time windows.
6. Event emission:
   - If an anti-pattern triggers, the engine publishes a domain event to registered ports (terminal TUI, SSE event bus, and memory store).
