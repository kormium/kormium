# Observability

Production users need to answer simple questions when something goes wrong:

- What SQL was executed?
- How long did it take?
- Which backend and pool was involved?
- Was the failure a constraint violation, timeout, connection failure or cancellation?
- Can logs be enabled without leaking secrets or personally identifiable data?

Kormium does not have a complete observability API yet. This page defines the target behavior
and the minimum contract needed before recommending Kormium for production.

## Current State

Today Kormium has:

- typed exceptions for common constraint failures;
- backend-specific exception translation for JDBC, r2dbc, SQLite and libpq paths;
- a public per-statement query observer (`KormiumConfig.queryObserver`) — see
  [Query Observer](#query-observer) below;
- some internal trace logging through kotlin-logging;
- HikariCP underneath JVM JDBC backends, which has its own metrics integrations if the
  application configures them.

Current gaps:

- no stable slow query logging helper (the observer makes it a one-liner, but there is no
  built-in threshold/logger);
- no built-in parameter redaction policy beyond "values are never exposed";
- no turn-key metrics bridge (the observer is the seam; no shipped Micrometer/OTel adapter);
- no documented pool metrics story;
- no request/correlation context story.

## Query Observer

`KormiumConfig.queryObserver` is a single, backend-neutral hook called once per executed
statement — DSL operations, raw `execute`/`executeUpdate`, and migrations — across every backend
(JDBC, r2dbc, SQLite, libpq). It is installed only when set, so the default path wraps nothing
and pays nothing.

```kotlin
val db = createDatabase(
    host = "localhost", database = "postgres", user = "postgres", password = "password",
    config = KormiumConfig(
        queryObserver = { e ->
            meterRegistry.timer("kormium.query.duration", "backend", e.backend, "kind", e.kind.name,
                "outcome", if (e.succeeded) "ok" else "error")
                .record(e.durationNanos, TimeUnit.NANOSECONDS)
            if (!e.succeeded) errorCounter.increment(e.sqlState ?: "unknown")
            if (e.durationNanos > 250_000_000) slowLog.warn("slow ${e.kind} ${e.durationNanos / 1_000_000}ms: ${e.sql}")
        },
    ),
)
```

Each `QueryEvent` carries:

| Field | Meaning |
| --- | --- |
| `backend` | dialect tag (e.g. `SqliteDialect`, `PostgresDialect`) — a low-cardinality metric label |
| `sql` | the parameterized SQL template — **placeholders only, never bound values** |
| `kind` | `Select` / `Insert` / `Update` / `Delete` / `Other`, derived from the leading keyword |
| `durationNanos` | monotonic wall-clock duration of the statement |
| `rowCount` | rows returned (queries) or affected (writes) when the backend reports one, else `null` |
| `error` / `succeeded` | the failure that ended the statement, or `null` on success |
| `sqlState` | SQLSTATE / backend error code when the failure carried one |

Rules the hook guarantees:

- **Parameter values are never present on the event** — redaction is by construction, not policy.
- **A throwing observer never affects the query** — exceptions from the callback are swallowed.
- The callback runs **synchronously** on the executing thread; keep it cheap (record/enqueue/log).
- `sql` is high-cardinality — use `backend` + `kind` as metric labels, not the SQL text.

## Principles

### Logs Are for Humans

Logs should help debug incidents. They should not be the only way to collect latency or pool
health.

### Metrics Are for Operations

Metrics should be structured and low-cardinality:

- query duration;
- rows read or affected when known;
- success/failure count;
- pool acquisition timing where the backend exposes it;
- transaction duration;
- rollback count.

Raw SQL text is high-cardinality and should not be used as a metric label.

### Parameters Are Sensitive by Default

Kormium should never log bound parameter values by default.

Target redaction levels:

| Level | Behavior |
| --- | --- |
| `none` | Do not log parameters |
| `types` | Log parameter names and Kotlin/backend types |
| `values` | Log values, intended only for local debugging |
| custom | User-provided redactor |

The default should be `none` or `types`, not `values`.

## Target API Shape

A future observability API should be backend-neutral:

```kotlin
interface KormiumObserver {
    fun onQueryStart(event: QueryStart)
    fun onQuerySuccess(event: QuerySuccess)
    fun onQueryFailure(event: QueryFailure)
    fun onTransactionStart(event: TransactionStart)
    fun onTransactionEnd(event: TransactionEnd)
}
```

Events should carry:

- backend name;
- operation kind: select, insert, update, delete, raw, migration, transaction;
- SQL string or normalized SQL depending on configuration;
- parameter metadata after redaction;
- elapsed time;
- affected row count when known;
- exception type and SQLSTATE/error code on failure.

This does not need to be the final API, but it defines the contract that docs and tests
should eventually cover. The shipped [Query Observer](#query-observer) is the minimal first
cut of this contract: one `onQuery(QueryEvent)` callback covering success and failure, without
the separate start/transaction events or pluggable parameter redaction yet.

## Slow Query Logging

Target behavior:

```kotlin
createDatabase(
    host = "localhost",
    database = "postgres",
    user = "postgres",
    password = "password",
    observability = KormiumObservability {
        slowQueryThreshold = 250.milliseconds
        parameterLogging = ParameterLogging.Types
    },
)
```

Slow query logs should include:

- elapsed time;
- backend;
- operation kind;
- SQL;
- redacted parameter metadata;
- SQLSTATE/error code if failed.

They should not include raw parameter values unless explicitly configured.

## Metrics

Recommended metric names if Kormium ships a metrics bridge:

| Metric | Type | Labels |
| --- | --- | --- |
| `kormium.query.duration` | timer/histogram | backend, operation, outcome |
| `kormium.transaction.duration` | timer/histogram | backend, outcome |
| `kormium.query.rows` | distribution/counter | backend, operation |
| `kormium.pool.acquire.duration` | timer/histogram | backend |
| `kormium.pool.active` | gauge | backend, pool |
| `kormium.pool.idle` | gauge | backend, pool |
| `kormium.pool.pending` | gauge | backend, pool |

For JVM JDBC, HikariCP already exposes pool metrics when configured by the application. Kormium
should document how to wire that before adding its own duplicate pool gauges.

## Failure Classification

Failures should be easy to classify:

| Failure | Current/target signal |
| --- | --- |
| unique violation | `UniqueViolationException` |
| foreign key violation | `ForeignKeyViolationException` |
| not-null violation | `NotNullViolationException` |
| check violation | `CheckViolationException` |
| other SQL failure | `QueryException` with SQLSTATE/error code when available |
| pool closed | `QueryException` or backend-specific closed connection error |
| cancellation | should preserve coroutine cancellation semantics |
| timeout | should be distinguishable where backend reports it |

## Production Checklist

Before recommending Kormium for production, observability should have:

- documented exception taxonomy;
- no parameter values in logs by default — **done**: `queryObserver` never receives values;
- slow query logging story — **partial**: trivial to build on `queryObserver`, no built-in helper;
- query timing hook or metrics bridge — **done** (hook): `KormiumConfig.queryObserver`; no shipped
  metrics adapter yet;
- pool metrics guide for JDBC/HikariCP;
- examples for Ktor `StatusPages`;
- tests proving observers run on success and failure — **done**: covered for the query observer
  (core + SQLite) and the `WriteListener` commit hook (see below).

## Write Notification

`Database`/`SuspendDatabase` expose a `writeListeners: WriteListeners` registry. After a
`transaction { }` / `autocommit { }` (or suspend counterpart) commits, every registered
`WriteListener` is called with the set of table names written during it (rolled-back work
notifies nothing). This is a generic, synchronous commit hook — it backs `kormium-observe`
([Observing changes](observe.md)) but is equally usable for cache invalidation, audit or
metrics:

```kotlin
db.writeListeners.add { tables -> log.info("committed writes to {}", tables) }
```

Raw SQL declares its tables via the `invalidates` argument on `execute`/`executeUpdate`;
see [Observing changes](observe.md#raw-sql).

## Current Recommendation

For query timing and failure metrics:

- set `KormiumConfig.queryObserver` and forward events to your metrics/logging stack — this
  replaces wrapping every repository method (see [Query Observer](#query-observer));
- rely on typed exceptions for application-level handling;
- use the observer's `durationNanos` for slow-query logging (pick your own threshold);
- configure HikariCP metrics directly for JVM JDBC **pool** visibility (the observer covers
  statements, not pool acquisition);
- avoid enabling trace logs in environments where parameter values may contain sensitive data.
