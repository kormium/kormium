# Transactions and Migrations

Kormium table operations run inside a scope. A scope pins one connection and provides the
executor used by table, query and migration operations.

## Blocking Scopes

```kotlin
db.transaction {
    Users.insert(user)
}

val users = db.autocommit {
    Users.all()
}
```

- `transaction { }` opens one transaction and commits on success.
- If the block throws, Kormium rolls the transaction back and rethrows.
- `autocommit { }` pins a connection but does not wrap the block in an explicit
  transaction.

## Isolation and read-only

`transaction { }` and `suspendTransaction { }` take two optional, portable parameters:

```kotlin
import io.github.kormium.TransactionIsolation

db.transaction(isolation = TransactionIsolation.Serializable, readOnly = true) {
    Reports.all()
}

db.suspendTransaction(isolation = TransactionIsolation.RepeatableRead) {
    Accounts.update(account) { where { Accounts.id eq account.id } }
}
```

- **`isolation`** is one of the four SQL-standard levels — `ReadUncommitted`, `ReadCommitted`,
  `RepeatableRead`, `Serializable`. The default (`null`) leaves the connection's configured level
  untouched (no `SET TRANSACTION` is emitted).
- **`readOnly`** opens a read-only transaction where the backend supports it.

Both apply only to `transaction` / `suspendTransaction`; `autocommit` ignores them. The Ktor
helpers (`call.transaction(db, isolation = …, readOnly = …) { }` and
`call.kormium<G>().transaction(isolation = …, readOnly = …) { }`) forward them too.

### Backend differences

These are intentionally not hidden — the behavior maps to what each database actually offers:

| Backend | Isolation | Read-only |
| --- | --- | --- |
| **PostgreSQL** (JDBC, native libpq, r2dbc) | All four levels; `READ UNCOMMITTED` behaves as `READ COMMITTED` (Postgres has no dirty reads) | Honored — writes raise `25006 read_only_sql_transaction` |
| **MySQL/MariaDB** (JDBC, native, r2dbc) | All four levels | Honored (`START TRANSACTION READ ONLY`) |
| **SQLite** (JDBC, native, Android) | **Ignored** — SQLite has a single level (≈ `SERIALIZABLE`); a non-null value is silently dropped, never emulated or rejected | Honored via `PRAGMA query_only` |

How it is applied per backend: the JDBC backend uses the driver API
(`Connection.setTransactionIsolation` / `setReadOnly`), except SQLite-over-JDBC — whose driver
rejects `setReadOnly` — which uses `PRAGMA query_only`. The native libpq backend emits
`BEGIN ISOLATION LEVEL … READ ONLY`; native MySQL emits `SET TRANSACTION ISOLATION LEVEL …` then
`START TRANSACTION [READ ONLY]`; r2dbc carries both through a `TransactionDefinition`. Any
per-connection state is restored when the connection returns to the pool.

> Because SQLite ignores isolation, code that relies on a specific level for correctness is not
> portable to SQLite. Reach for `readOnly` (which SQLite does honor) or test against your target
> database.

## Savepoints

Use `savepoint { }` when one nested unit may fail without rolling back the whole outer
transaction.

```kotlin
db.transaction {
    Users.insert(user)

    savepoint {
        Audit.insert(entry)
    }
}
```

If the savepoint block throws, Kormium rolls back to that savepoint. The enclosing transaction
can continue if you catch the exception.

## Transactional Helpers

Prefer helpers that extend `Scope<G>` or `SuspendScope<G>`. They join the caller's existing
transaction instead of opening a second connection.

```kotlin
fun Scope<App>.createUser(user: User) {
    Users.insert(user)
}

db.transaction {
    createUser(user)
}
```

The suspend variant:

```kotlin
suspend fun SuspendScope<App>.createUser(user: User) {
    Users.insert(user)
}
```

Calling another database handle's `transaction { }` inside a transaction creates an
independent transaction on another connection.

## Suspend API

Blocking backends also expose `SuspendDatabase<G>`. The DSL is the same, but the block can
suspend:

```kotlin
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.database.createDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction

val db: SuspendDatabase<App> = createDatabase(
    host = "localhost",
    database = "postgres",
    user = "postgres",
    password = "password",
)

suspend fun handler() {
    db.suspendTransaction {
        Users.insert(user)
    }

    val users = db.suspendAutocommit {
        Users.all()
    }
}
```

For `kormium-postgres` and `kormium-sqlite`, suspend work is offloaded from the caller:

- on JVM, to a virtual-thread dispatcher;
- on Native, to `Dispatchers.Default`.

This keeps coroutine workers free, but the underlying driver is still blocking.

## True Async PostgreSQL

`kormium-r2dbc` implements `SuspendDatabase<G>` only. There is no blocking `Database<G>` API
because r2dbc is non-blocking.

```kotlin
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.r2dbc.createR2dbcDatabase

val db: SuspendDatabase<App> = createR2dbcDatabase(
    host = "localhost",
    database = "postgres",
    user = "postgres",
    password = "password",
)

db.suspendTransaction {
    Users.insert(user, returning = true)
}
```

Use r2dbc when you specifically need non-blocking PostgreSQL I/O on JVM. For many server
applications, a normal connection pool plus virtual-thread offload is simpler and adequate.

## Cancellation

The suspend scopes are cancellation-safe. When the coroutine running a `suspendTransaction` /
`suspendAutocommit` is cancelled (a timeout, a cancelled parent job, structured-concurrency
teardown), the scope unwinds cleanly:

- **A cancelled `suspendTransaction` rolls back.** The block sees the `CancellationException`,
  the work is rolled back, and the connection is returned to the pool. Nothing is committed.
- **A cancelled `suspendAutocommit` releases the connection.** There is no transaction to roll
  back; the pinned connection is returned to the pool.
- **Rollback and release run under `NonCancellable`**, so they complete even though the
  surrounding coroutine is already cancelled — a cancelled scope never leaks its connection.
  This holds for both paths: the offload runner (JDBC / native SQLite / native MySQL) and the
  native-libpq and r2dbc async runners.

```kotlin
// The insert is rolled back and the connection released when the timeout cancels the scope.
withTimeout(200) {
    db.suspendTransaction {
        Users.insert(user)
        callSomeSlowService()   // suspends; if it overruns, the timeout cancels here
    }
}
```

### Best-effort during commit

The **commit** itself is a normal (cancellable) suspension point — it is *not* wrapped in
`NonCancellable`. So a cancellation that arrives before the commit completes results in a
**rollback**, not a partial commit: you either get a fully committed transaction or a fully
rolled-back one. Once the commit has completed, the data is durable regardless of a later
cancellation. Rollback and connection release, by contrast, are always run to completion.

### Don't outlive the scope

Do not launch child coroutines inside a scope that keep touching the database after the block
returns — the pinned connection is returned to the pool when the block exits, so a coroutine
that outlives the scope would use a connection it no longer owns. Keep all database work inside
the `suspendTransaction` / `suspendAutocommit` block (or pass results out and start new scopes):

```kotlin
// WRONG: the launched coroutine outlives the scope and the borrowed connection.
db.suspendTransaction {
    scope.launch { Users.insert(user) }   // don't — runs after the block (and its connection) is gone
}

// RIGHT: all database work completes within the block.
db.suspendTransaction {
    Users.insert(user)
}
```

### Backend differences

- **Offload backends** (`kormium-postgres` JVM/Native libpq fallback, `kormium-sqlite`,
  `kormium-mysql` native) drive a blocking driver on a dispatcher; cancellation interrupts at
  the suspension points between statements, and cleanup is the `NonCancellable` rollback/release
  above. A statement already executing on the driver is not interrupted mid-call.
- **Native libpq async** and **r2dbc** are genuinely non-blocking: an in-flight network wait is
  itself cancellable, then the same `NonCancellable` rollback/release runs.

## Migrations

Migrations live in the separate **`kormium-migrate`** module (Kormium core does not own schema). Add
the dependency and import from `io.github.kormium.migrate`:

```kotlin
// build.gradle.kts
implementation("io.github.kormium:kormium-migrate")
```

A migration is **raw SQL** with a stable `id` and is bound to a catalog. Kormium does not generate
DDL, so the SQL is intentionally backend-specific — write it for the database you target. A
single SQL string is split into statements on top-level `;` (quoted strings/identifiers,
`--` / `/* */` comments and Postgres `$tag$…$tag$` bodies are respected):

```kotlin
import io.github.kormium.migrate.Migration
import io.github.kormium.migrate.migrate

db.migrate(
    listOf(
        Migration("001-create-users", """
            CREATE TABLE "users" ("id" uuid PRIMARY KEY, "name" text NOT NULL, "age" integer NOT NULL);
            CREATE INDEX users_name_idx ON "users" ("name");
        """),
    ),
)
```

For SQL the splitter can't handle (e.g. a Postgres function body containing `;`), pass the
statements explicitly: `Migration("002-fn", listOf(stmtA, stmtB))`.

What the runner guarantees:

- **Ordered & idempotent.** Applied ids are recorded in `kormium_migrations` (with the SQL
  checksum, an `applied_at` timestamp and the apply order); only missing migrations run, so
  calling `migrate(...)` on every startup is safe.
- **Checksum validation.** If an already-applied migration's SQL is later edited, `migrate`
  fails fast with `MigrationChecksumException`. Migrations are immutable once applied — add a
  new one instead.
- **Concurrency-safe.** The whole run executes in one transaction; on PostgreSQL it takes a
  transaction-scoped advisory lock first, so several instances starting at once block and don't
  apply the same migration twice. SQLite has no advisory lock, so concurrent cross-process
  migration is not fully serialized — it stays safe (the journal primary key plus the
  all-or-nothing transaction rule out double-application; at worst one instance rolls back and
  no-ops on restart), but prefer migrating SQLite from a single process.
- **All-or-nothing.** Because the batch is one transaction, a failure rolls the whole batch back
  and records nothing. (A statement that cannot run inside a transaction — e.g.
  `CREATE INDEX CONCURRENTLY` — therefore cannot be part of a batch.)

The idiomatic place to run migrations is the `createX { }` builder's `beforeStart { }` hook,
which runs once after the pool is up and before the database is returned:

```kotlin
val db: Database<App> = createDatabase(host = "…", database = "…", user = "…", password = "…") {
    beforeStart { migrate(appMigrations) }
}
```

## Error Handling

Backend errors are normalized to Kormium exceptions where possible.

```kotlin
try {
    db.transaction { Users.insert(user) }
} catch (e: UniqueViolationException) {
    // Duplicate key, SQLSTATE 23505 on PostgreSQL.
}
```

Common typed exceptions:

| Exception | Typical meaning |
| --- | --- |
| `UniqueViolationException` | Duplicate primary key or unique index |
| `ForeignKeyViolationException` | Foreign key constraint failed |
| `NotNullViolationException` | Required column was written as NULL |
| `CheckViolationException` | Check constraint failed |
| `QueryException` | Other statement/database failure, with SQLSTATE when available |
