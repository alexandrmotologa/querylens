# PostgreSQL v3.0 Wire Protocol Handling

PostgreSQL uses a message-oriented protocol layered on TCP. Each message begins with a 1-byte type identifier followed by a 4-byte big-endian integer denoting the total message length (including the 4-byte length integer itself, but excluding the 1-byte identifier).

The exception is the initial connection startup packet, which begins directly with the 4-byte length and a 4-byte protocol version integer.

## Intercepted Frontend Messages

| Type Byte | Message Name | Description |
|-----------|--------------|-------------|
| None (Initial) | `StartupMessage` / `SSLRequest` | Initiates the connection, passes user and database parameters. |
| `'Q'` | `Query` (Simple Query) | Plain SQL string terminated with a null byte. |
| `'P'` | `Parse` (Extended Query) | Prepares a named or unnamed statement with a parameterized SQL string. |
| `'B'` | `Bind` (Extended Query) | Binds concrete parameters to a previously parsed statement into a portal. |
| `'E'` | `Execute` (Extended Query) | Runs a bound portal up to a maximum row limit (0 for unlimited). |
| `'S'` | `Sync` (Extended Query) | Tells the backend to end extended query processing and issue `ReadyForQuery`. |
| `'X'` | `Terminate` | Closes the connection gracefully. |

## Intercepted Backend Messages

| Type Byte | Message Name | Description |
|-----------|--------------|-------------|
| `'R'` | `Authentication` | Requests authentication steps (Ok, MD5, SASL/SCRAM). |
| `'C'` | `CommandComplete` | Signals SQL statement completion and affected row counts (e.g. `SELECT 42`). |
| `'T'` | `RowDescription` | Describes columns in a returned row set. |
| `'D'` | `DataRow` | Contains column values for a single returned row. |
| `'Z'` | `ReadyForQuery` | Signals readiness for the next command. Contains a 1-byte transaction indicator: `'I'` (Idle), `'T'` (In transaction block), or `'E'` (Failed transaction block). |
| `'E'` | `ErrorResponse` | Indicates database-side error code, message, and detail fields. |

## Extended Query Protocol Mapping

Real-world ORMs use prepared statements via the Extended Query Protocol:
1. The client sends `Parse` (`P`) with the SQL text:
   `SELECT id, name FROM users WHERE tenant_id = $1`
2. QueryLens caches this statement against the channel context keyed by statement name.
3. The client issues `Bind` (`B`) and `Execute` (`E`).
4. QueryLens looks up the original SQL text, starts the execution clock, and links the subsequent `CommandComplete` or `DataRow` frames to the statement.
