# Backends

The core DSL is backend-agnostic. Backends provide a `Dialect`, a `TypeMapper` and a driver
that executes parameterized SQL.

## Engines: database × driver × target

A backend is **one database × one driver × one target**, not a fork of the others. Every engine
implements the *same* core SPI — `Dialect`, `SqlExecutor` / `SuspendSqlExecutor`, `ResultSet` —
and reuses the one pure dialect per database (`kormium-<db>-dialect`, see
[ADR 0001](adr/0001-standalone-dialect-modules.md)). So your `Table`/`Entity`/query code is
identical regardless of which engine you open. Adding a driver is a new row below, not a new API.

| Database | Driver | Target | API | Module |
| --- | --- | --- | --- | --- |
| PostgreSQL | JDBC + HikariCP | JVM | blocking + suspend | `kormium-postgres` |
| PostgreSQL | libpq (cinterop) | Native | blocking + suspend | `kormium-postgres` |
| PostgreSQL | r2dbc | JVM | suspend | `kormium-r2dbc` |
| PostgreSQL | node-postgres (`pg`) | Wasm/Node | suspend | `kormium-postgres-node` |
| PostgreSQL | PGlite (Postgres in WASM) | Wasm/browser + Node | suspend | separate repo: [kormium/pglite](https://github.com/kormium/pglite) |
| MySQL / MariaDB | JDBC + HikariCP | JVM | blocking + suspend | `kormium-mysql` |
| MySQL / MariaDB | libmariadb (cinterop) | Native (Linux/macOS) | blocking + suspend | `kormium-mysql` |
| MySQL / MariaDB | r2dbc-mysql | JVM | suspend | `kormium-r2dbc` |
| MySQL / MariaDB | mysql2 | Wasm/Node | suspend | `kormium-mysql-node` |
| SQLite | sqlite-jdbc | JVM | blocking + suspend | `kormium-sqlite` |
| SQLite | sqlite3 (cinterop) | Native / iOS | blocking + suspend | `kormium-sqlite` |
| SQLite | AndroidX SQLite | Android | blocking + suspend | `kormium-sqlite` |
| SQLite | better-sqlite3 | Wasm/Node | suspend | `kormium-sqlite-node` |
| SQLite | wa-sqlite (SQLite in WASM) | Wasm/browser | suspend | `kormium-sqlite-wasm` |

**Blocking + suspend** engines implement both `Database` and `SuspendDatabase`. The **suspend**-only
engines (r2dbc and every Wasm/Node one) implement only `SuspendDatabase` — a JS event loop can't be
blocked, and `SuspendDatabase` is a *sibling* of `Database`, not a subtype, exactly for this case.
The Wasm/Node engines bind values as text and bridge the driver's `Promise` to suspend with
`await()`; the JS-interop ones (PGlite, wa-sqlite) run a database compiled to WASM in the page. They
share the named-parameter parser, the text `ResultSet` and the binding helper from
`kormium-wasm-driver`, so only the driver bindings differ per engine.

> **Supply chain:** `kormium-sqlite-node` uses a native npm package (better-sqlite3), so the build
> re-enables npm install scripts for the Wasm/Node toolchain (see the root `build.gradle.kts`). npm
> versions are pinned and the resolved tree is committed in `kotlin-js-store/*yarn.lock`; review
> lockfile changes when bumping these engines' dependencies.

## PostgreSQL

Artifact:

```kotlin
implementation("io.github.kormium:kormium-postgres")
```

Factory:

```kotlin
val db: Database<App> = createDatabase(
    host = "localhost",
    port = 5432,
    database = "postgres",
    user = "postgres",
    password = "password",
    poolSize = 10,
)
```

Implementations:

- JVM: PostgreSQL JDBC driver plus HikariCP through `kormium-jdbc`.
- Kotlin/Native: libpq cinterop.

Native builds need `libpq` headers/libraries on the build machine. See
[Installation](installation.md#postgresql-native).

PostgreSQL notes:

- On JVM, parameters are bound as properly-typed JDBC objects (uuid, numeric,
  timestamptz, jsonb, ...), so server-prepared statements execute in a single protocol
  round-trip. **Raw SQL** that binds a `String` to a non-text column must cast it
  explicitly — `WHERE id = :id::uuid` — or pass a typed value; DSL queries need nothing.
- On Kotlin/Native, libpq sends parameters as text and the dialect adds the needed
  `::type` casts.

## MySQL / MariaDB

Artifact:

```kotlin
implementation("io.github.kormium:kormium-mysql")
```

Factory:

```kotlin
val db: Database<App> = createDatabase(
    host = "localhost",
    port = 3306,
    database = "app",
    user = "root",
    password = "password",
    poolSize = 10,
)
```

Implementations:

- JVM: `mysql-connector-j` plus HikariCP through `kormium-jdbc`.
- Kotlin/Native (Linux/macOS): MariaDB Connector/C (`libmariadb`) cinterop using prepared
  statements. Windows is served by the JVM driver.
- Async on JVM: `createMySqlR2dbcDatabase(...)` in `kormium-r2dbc` over `io.asyncer:r2dbc-mysql`.

Native builds need `libmariadb` headers/libraries on the build machine
(`brew install mariadb-connector-c` / `apt-get install libmariadb-dev`).

MySQL notes:

- UUID is stored as `CHAR(36)`; JSON uses the native `JSON` type. The session is pinned to UTC so
  `Instant`/`TIMESTAMP` round-trips unchanged.
- Integrity violations are mapped to typed exceptions by vendor code (1062 unique, 1452 foreign
  key, 1048 NOT NULL, 3819 check) since MySQL reports them all under SQLSTATE 23000.
- No transaction-scoped advisory lock (MySQL `GET_LOCK` is session-scoped), so `kormium-migrate`
  runs without one — prefer migrating from a single instance.

## SQLite

Artifact:

```kotlin
implementation("io.github.kormium:kormium-sqlite")
```

Factory:

```kotlin
val memory: Database<App> = createSqliteDatabase()
val file: Database<App> = createSqliteDatabase("app.db")
```

Implementations:

- JVM: sqlite-jdbc.
- Kotlin/Native: sqlite3 cinterop.
- Android: AndroidX SQLite with bundled SQLite.

SQLite notes:

- `poolSize` defaults to `1` because SQLite allows one writer.
- File databases are opened in WAL mode.
- Foreign keys are enabled with `PRAGMA foreign_keys=ON`.
- `UUID`, `BigDecimal`, `Json` and temporal values are stored as text and parsed back.

## r2dbc PostgreSQL

Artifact:

```kotlin
implementation("io.github.kormium:kormium-r2dbc")
```

Factory:

```kotlin
val db: SuspendDatabase<App> = createR2dbcDatabase(
    host = "localhost",
    port = 5432,
    database = "postgres",
    user = "postgres",
    password = "password",
    poolSize = 10,
)
```

`kormium-r2dbc` is JVM-only and implements the suspend API only. It is the backend to choose
when you need true non-blocking PostgreSQL I/O.

## Backend behavior matrix

The DSL is uniform, but a few operations resolve to backend-specific SQL or surface
backend-specific errors. This table documents the differences so they are visible rather than
hidden behind a generic failure. Each row is covered by edge-case tests
(`EdgeCaseTest` / `QueryCoverageTest` and the per-backend `*EdgeCaseTest` / `*QueryCoverageTest`).

| Behavior | PostgreSQL (JVM/Native) | r2dbc PostgreSQL | SQLite | MySQL / MariaDB |
| --- | --- | --- | --- | --- |
| `insert(returning = true)` | Native `INSERT ... RETURNING` | Native `INSERT ... RETURNING` | Native `INSERT ... RETURNING` | No `RETURNING`; the insert runs, then the row is re-selected (`supportsReturning = false`) |
| `upsert` (single & composite conflict) | `ON CONFLICT (...) DO UPDATE` | `ON CONFLICT (...) DO UPDATE` | `ON CONFLICT (...) DO UPDATE` | `ON DUPLICATE KEY UPDATE` (the conflict columns must back a key) |
| `insertOrIgnore` | `ON CONFLICT (...) DO NOTHING` | `ON CONFLICT (...) DO NOTHING` | `ON CONFLICT (...) DO NOTHING` | `INSERT IGNORE` |
| `savepoint { }` nesting/rollback | `SAVEPOINT` / `ROLLBACK TO SAVEPOINT` | same, over the reactive connection | `SAVEPOINT` / `ROLLBACK TO SAVEPOINT` | `SAVEPOINT` / `ROLLBACK TO SAVEPOINT` |
| `savepoint` outside a transaction | throws `IllegalStateException` (checked before any SQL) | same | same | same |
| Nullable left-join projection | unmatched right side is `null` (`Pair<A, B?>`); `row[col]` throws, `row.getOrNull(col)` is `null` | same | same | same |
| Colliding join column names | qualified `"table"."col"` keeps each side distinct | same | same | same |
| Empty `IN ()` list | matches nothing (no row) | same | same | same |

### Constraint-violation mapping

All backends map integrity violations to the same typed exceptions
(`UniqueViolationException`, `ForeignKeyViolationException`, `NotNullViolationException`,
`CheckViolationException`, all extending `QueryException`). What differs is the **source code**
carried in `QueryException.sqlState`:

| Violation | Exception | PostgreSQL / r2dbc (SQLSTATE) | SQLite (extended result code) | MySQL (vendor code) |
| --- | --- | --- | --- | --- |
| Unique / primary key | `UniqueViolationException` | `23505` | `2067` / `1555` | `1062` |
| Foreign key | `ForeignKeyViolationException` | `23503` | `787` | `1452` |
| Not null | `NotNullViolationException` | `23502` | `1299` | `1048` |
| Check | `CheckViolationException` | `23514` | `275` | `3819` |

PostgreSQL and r2dbc report a real 5-character SQLSTATE. SQLite has no SQLSTATE, so the mapper
keys off the extended result code (carried in `sqlState` as a decimal string) and falls back to
the stable message text. MySQL reports every constraint violation under SQLSTATE `23000`, so the
mapper keys off the vendor error number instead. Foreign-key enforcement on SQLite requires
`PRAGMA foreign_keys=ON`, which Kormium sets on every connection.

## Database lifecycle

Every `Database` / `SuspendDatabase` follows one lifecycle contract, the same across JDBC, libpq,
native SQLite, native MySQL and r2dbc:

| Aspect | Contract |
| --- | --- |
| `close()` | **Idempotent** — calling it again is a safe no-op; only the first call tears down the pool. |
| `isClosed` | `false` until `close()` is called, `true` afterwards. Cheap to read (a single atomic flag); use it for health checks. |
| Use after close | `usePinned` / `useConnection` — and therefore any `transaction` / `autocommit` / `suspendTransaction` / `suspendAutocommit` — throw `DatabaseClosedException` (a `KormiumException`), **not** a backend-specific closed-connection error. |
| In-flight statement during close | A statement that already borrowed a connection is allowed to finish: the native pools *drain* on close (they wait for every borrowed connection to come back before tearing down). The JVM pools (HikariCP, r2dbc) follow their own pool-shutdown semantics. |
| Statement that races close | May either run to completion or throw `DatabaseClosedException`, depending on the exact interleaving. |

```kotlin
val db = createDatabase(/* ... */)
db.use {
    // ... queries ...
}                      // close() called here by use { }
check(db.isClosed)
db.close()             // idempotent: safe
db.autocommit { }      // throws DatabaseClosedException
```

`DatabaseClosedException` extends `KormiumException`, so it is caught by the same boundary that
handles other Kormium errors; in Ktor it is not a constraint violation, so it does not map to a
4xx by default — treat it as a programming/lifecycle error.

## Type Mapping

Kormium's common column types map through backend-specific SQL types:

| Kormium type | PostgreSQL | SQLite |
| --- | --- | --- |
| `UUID` | `UUID` | `TEXT` |
| `Text` | `TEXT` | `TEXT` |
| `Boolean` | `BOOLEAN` | `INTEGER` |
| `Short`, `Int`, `Long` | integer types | `INTEGER` |
| `Float`, `Double` | floating types | `REAL` |
| `BigDecimal` | numeric | `TEXT` |
| `Instant` and local date/time types | temporal/text depending on backend mapper | `TEXT` |
| `Json` | JSON/JSONB-compatible binding | `TEXT` |
| `Bytes` | `bytea` | `BLOB` |

`Bytes` (`ByteArray`) is bound and read as native binary, not text: JDBC `setObject`/`getBytes`,
libpq's `bytea` (OID 17), and a `Buffer`/`Uint8Array` on the Wasm/Node engines (verified there).

The public API presents Kotlin values consistently even when storage differs.

## Platform Support

| Platform | PostgreSQL | SQLite |
| --- | --- | --- |
| JVM | JDBC/HikariCP and r2dbc | sqlite-jdbc |
| Linux Native | libpq | sqlite3 |
| macOS Native | libpq | sqlite3 |
| Android | Not shipped | AndroidX SQLite |
| iOS | Not shipped | sqlite3 |
| Windows Native | libpq (experimental) | sqlite3 (experimental) |
| Wasm/Node | node-postgres (`kormium-postgres-node`) | better-sqlite3 (`kormium-sqlite-node`) |
| Wasm/Browser | PGlite ([separate repo](https://github.com/kormium/pglite)) | wa-sqlite (`kormium-sqlite-wasm`) |

MySQL/MariaDB on Wasm/Node uses mysql2 (`kormium-mysql-node`). See the
[engine matrix](#engines-database--driver--target) above and
[Installation](installation.md#platform-matrix) for module-level details.
