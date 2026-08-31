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
| SQLite | wa-sqlite (main-thread, `:memory:` / IndexedDB) | Wasm/browser | suspend | `kormium-sqlite-wasm` |
| SQLite | `@sqlite.org/sqlite-wasm` (Worker, `:memory:`) | Wasm/browser | suspend | `kormium-sqlite-wasm` |
| SQLite | `@sqlite.org/sqlite-wasm` (Worker pool, OPFS) — *experimental* | Wasm/browser | suspend | `kormium-sqlite-wasm` |

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
- Kotlin/Native: sqlite3 cinterop, built from the amalgamation vendored in `kormium-sqlite`
  (deliberately *not* the system libsqlite3 — see the note in `kormium-sqlite/build.gradle.kts`).
- Android: AndroidX SQLite with bundled SQLite.

SQLite notes:

- `poolSize` defaults to `1` because SQLite allows one writer.
- File databases are opened in WAL mode.
- Foreign keys are enabled with `PRAGMA foreign_keys=ON`.
- Every `createSqliteDatabase(":memory:")` call opens its **own** in-memory database, private to
  that driver and shared only across its own pooled connections. It lives as long as the driver:
  `close()` frees it.
- To put several drivers on one in-memory database, name it with a SQLite URI (JVM and native —
  Android rejects `file:` paths, androidx.sqlite opens without `SQLITE_OPEN_URI`):

  ```kotlin
  val a = createSqliteDatabase("file:shared?mode=memory&cache=shared")
  val b = createSqliteDatabase("file:shared?mode=memory&cache=shared") // same database as `a`
  ```

- `journal_mode`, `foreign_keys` and `busy_timeout` spelled out in a path are honoured; Kormium
  appends only the defaults you left out.
- `UUID`, `Decimal`, `Json` and temporal values are stored as text and parsed back.

### Which SQLite each engine carries

No engine is a wrapper over one shared SQLite: each brings its own, so the version differs by
target. Where Kormium picks the version itself it keeps them equal; the rest follow whatever their
platform runtime ships. Versions move with the dependency bumps recorded in the
[changelog](../CHANGELOG.md).

| Engine | SQLite comes from | Version | Chosen by |
| --- | --- | --- | --- |
| `kormium-sqlite` (JVM) | sqlite-jdbc 3.53.4.0 | 3.53.4 | Kormium |
| `kormium-sqlite` (Native / iOS) | vendored amalgamation | 3.53.4 | Kormium |
| `kormium-sqlite-node` | better-sqlite3 13.0.3 | 3.53.4 | Kormium |
| `kormium-sqlite-js`, `kormium-sqlite-wasm` (main thread) | wa-sqlite v1.1.2 | 3.53.0 | upstream build |
| `kormium-sqlite-wasm` (Worker engines) | `@sqlite.org/sqlite-wasm` via sqlite-wasm-kt | 3.53.0 | upstream build |
| `kormium-sqlite` (Android) | androidx.sqlite-bundled 2.7.0 | 3.50.1 | AndroidX |

In practice this matters only for SQL that a recent SQLite added: the Android engine trails the
others, so a feature newer than its version will work everywhere else and fail there.

### SQLite extensions

The `sqlite { }` block of `createSqliteDatabase` installs extensions (`sqlite-vec` and friends) and
applies pragmas on every connection a driver opens. Kormium ships no extensions and curates no set
of them: an extension package is an ordinary dependency implementing `SqliteExtension` from
`kormium-sqlite-spi`, and anyone can publish one. See
[ADR 0013](adr/0013-sqlite-extensions.md) and the reference package in `samples/sqlite-vec`.

Every engine applies `pragma(...)`. Loading an extension differs:

| Engine | Loads extensions | How |
| --- | --- | --- |
| JVM (sqlite-jdbc) | yes | `load_extension()` per connection, with loading armed only for that call |
| Native / iOS | yes | the package links its own static library and registers it before the pool opens |
| Node (better-sqlite3) | yes | `loadExtension()` when the connection is opened |
| Android (androidx) | yes | registered process-wide via `sqlite3_auto_extension`, through Kormium's JNI shim (`kormium-sqlite-android-ext`); the package ships only its `.so` per ABI |
| Browser (wa-sqlite, sqlite-wasm) | with the loadable build | an extension is a *different* WASM build; pass it via the `engine` parameter (`SqliteWasmEngine` / `SqliteJsEngine`). The default is upstream's, compiled with `SQLITE_OMIT_LOAD_EXTENSION`; [sqlite-wasm-engines](https://github.com/kormium/sqlite-wasm-engines) publishes an extension-capable one |

An extension declares the engines it supports, and a driver rejects one it was not built for while
opening the database — by name, rather than leaving it to surface as `no such module` later. The
browser engines install through the **suspend** half of the SPI (`suspendInstall`), because their
SQLite sits behind an async VFS or in a Worker; everything else uses the blocking `install`.

Factories that take options directly (Node and the browser) build them with `sqliteOptions { }`;
`createSqliteDatabase` has the `sqlite { }` block instead.

Kormium publishes the SQLite headers it links as a `sqlite-headers` artifact next to
`kormium-sqlite`, so an extension author compiles against exactly the SQLite the driver uses.

### Browser SQLite (`kormium-sqlite-wasm`)

The browser has no one right SQLite engine, so `kormium-sqlite-wasm` ships **three**, each a
distinct point on the storage × host × concurrency trade-off (see
[ADR 0010](adr/0010-browser-sqlite-three-engines.md)). All three implement the same
`SuspendDatabase`, so your `Table`/query code is identical across them.

| Factory | SQLite runs on | Storage | Concurrency | COOP/COEP | Use when |
| --- | --- | --- | --- | --- | --- |
| `createSqliteWasmDatabase(dataDir?)` | main thread | `:memory:` (default) or IndexedDB (`dataDir`) | one connection (`Mutex`) | not needed | you need IndexedDB persistence without cross-origin isolation |
| `createWorkerSqliteWasmDatabase()` | a dedicated Worker | `:memory:` | one connection (`Mutex`) | not needed | **default choice** — data fits in memory and need not survive a reload |
| `createPooledSqliteWasmDatabase(opfsPath, readerPoolSize = 4)` | a Worker pool | OPFS (`opfs-wl`) | 1 writer + N readers | **required** | *experimental* — persistent data + infrequent heavy queries |

**Prefer `createWorkerSqliteWasmDatabase` unless you have a specific reason not to.** It runs SQLite
off the main thread (a long query no longer freezes UI rendering) and measured ~35% faster per query
than the main-thread engine, at memory speed with no lock traffic. Its read-only blocks
(`suspendTransaction(readOnly = true) { }`) skip `BEGIN`/`COMMIT` — the single connection plus a
block-scoped `Mutex` make it trivially serializable, so no round trips are spent on a transaction
that cannot interleave.

**The pooled OPFS engine is experimental and deliberately not the default.** Reader pooling helps
*only* when individual queries are slow (hundreds of ms to seconds) and infrequent — there the win
of overlapping them beats `opfs-wl`'s per-statement lock handoff between connections. For bursts of
fast indexed queries the handoff dominates and a single connection is both faster and more reliable
(a 4-query dashboard burst at 1M rows measured ~410 ms on one reader vs ~1030 ms on four; rapid
bursts can even fail with `xLock GetSyncHandleError`, since OPFS sync access handles are exclusive
by spec). It also requires `Cross-Origin-Opener-Policy: same-origin` +
`Cross-Origin-Embedder-Policy: require-corp` response headers (`opfs-wl` needs cross-origin
isolation; without them the database silently fails to open) and a small consumer-side webpack
config addition. Reach for it for persistent OPFS data with the writes-don't-block-reads property,
not as a general speedup. See [web-targets.md](web-targets.md) Phase 4 for the full measurements.

> The pooled and Worker engines are built on the official `@sqlite.org/sqlite-wasm` (maintained in
> lockstep with SQLite releases) via the standalone
> [`kormium/sqlite-wasm-kt`](https://github.com/kormium/sqlite-wasm-kt) library and a
> `@kormium/sqlite-wasm-worker` bundle. The original `createSqliteWasmDatabase` stays on wa-sqlite.

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

### Pool exhaustion (`acquireTimeout`)

Every fixed-size pool answers the same question the same way: **what happens when all `poolSize`
connections are busy?** The caller waits up to `acquireTimeout` (a `createDatabase` /
`createSqliteDatabase` parameter, default **30 s**) and then fails with
**`PoolExhaustedException`** — a `KormiumException` distinct from `QueryException`, so load
shedding / retry / capacity alerting can catch exhaustion without string-matching SQL errors. The
wait is never unbounded; before 0.10.0 a saturated native pool blocked forever.

- **JVM (PostgreSQL / MySQL / SQLite)** — HikariCP's `connectionTimeout` (which has a 250 ms
  floor); its checkout timeout is translated to `PoolExhaustedException`.
- **Native (PostgreSQL / MySQL / SQLite) and Android SQLite** — the Channel-based pool bounds both
  the blocking (`usePinned`) and suspend (`useConnection`) borrow, including the async libpq
  reactor path. Cancellation still propagates as cancellation; only an elapsed timeout raises
  `PoolExhaustedException`.
- The classic trap is `poolSize = 1` (SQLite's default) plus a **nested** borrow — a
  `transaction { }` that calls `autocommit { }` on the same database can never succeed and now
  fails in `acquireTimeout` with a message naming the pool size instead of deadlocking.
- **r2dbc and the Node/browser engines** keep their drivers' own pooling semantics (r2dbc-pool,
  node-postgres/mysql2, single-connection wasm engines); `acquireTimeout` applies to the
  JVM/native/Android pools above.

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
| `Decimal` (kormium-decimal) | numeric | `TEXT` |
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
| Wasm/Browser | PGlite ([separate repo](https://github.com/kormium/pglite)) | `kormium-sqlite-wasm` — three engines (main-thread wa-sqlite, Worker in-memory, experimental OPFS pool); see [Browser SQLite](#browser-sqlite-kormium-sqlite-wasm) |

MySQL/MariaDB on Wasm/Node uses mysql2 (`kormium-mysql-node`). See the
[engine matrix](#engines-database--driver--target) above and
[Installation](installation.md#platform-matrix) for module-level details.
