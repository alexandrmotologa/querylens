# Dashboard and TUI

QueryLens provides real-time visibility into intercepted traffic through two interfaces: the Terminal TUI and the embedded web dashboard.

## Terminal TUI

When enabled, the terminal interface prints a live status view:
- Global queries per second (QPS).
- Active open connections and transaction states.
- Running count of detected N+1 patterns and slow queries.
- Instant alert banners rendered directly in ANSI colors when anti-patterns occur.

## Embedded Web Dashboard

The web interface is served directly by QueryLens without external dependencies or Node.js runtimes.

- Port: 8080 (configurable via `--dashboard-port`)
- URL: `http://localhost:8080/dashboard`

### Available Endpoints

- `GET /dashboard`: Serves the single-page application (HTML, CSS, JavaScript).
- `GET /api/events`: Server-Sent Events (SSE) stream delivering real-time queries, anti-pattern detections, and connection status events.
- `GET /api/stats`: JSON snapshot of global cluster statistics, total queries, and P50/P90/P99 latency metrics.
- `GET /api/violations`: JSON list of all detected anti-pattern violations with causal details and timestamps.
- `GET /api/queries`: JSON list of top queries ordered by frequency or latency.

### Visual Components

1. Overview cards: Shows total queries, active connections, and violation counts.
2. Heatmap and latency chart: Visualizes execution times across query fingerprints.
3. Active violations table: Lists N+1 cascades with the parent query, repeating child query, repetition count, and suggested join query.
4. Top slow queries table: Highlights queries with high P99 latency and total cumulative execution time.
