# Changelog

All notable changes to Kormium are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **`createSqliteDatabase()` no longer shares `:memory:` across unrelated instances at the
  default `poolSize = 1`.** JVM and Native always opened `":memory:"` with `cache=shared`, so
  two independent `createSqliteDatabase()` calls in the same process — e.g. two test fixtures —
  silently read and wrote the same physical database. The shared cache is now only used when
  `poolSize > 1` (where a pool's connections genuinely need to see one database); a single
  connection gets SQLite's own default, a private in-memory database.

## [0.11.1] — Native bytea fix and Kotlin/Native hot-path speedups

### Fixed
- **`Column.Bytes` round-trips correctly on the native PostgreSQL driver.** Both directions were
  broken, silently. Writing sent the `ByteArray` through `toString()`, so the stored value was an
  object identity (`kotlin.ByteArray@7e0f165e…`) rather than the bytes; reading did
  `getString()?.encodeToByteArray()`, which re-encodes PostgreSQL's *text encoding* of the value
  (`\x48656c6c6f`) instead of decoding it. A five-byte array came back as fifty-two bytes of
  unrelated text, with no error. Values now go out as `bytea`'s hex text format and are decoded
  straight from libpq's C string; reading accepts both the `hex` and the legacy `escape` output
  formats. JVM/JDBC was unaffected. The gap survived because `Column.Bytes` was the one column
  type with no test anywhere — it is now covered by a codec unit test, a native round-trip
  integration test, and an `aBytes` column added to the JVM all-types round-trip.

### Changed
- **Kotlin/Native CPU path: row hydration 4.0x faster, entity field reads 4.1x, SELECT
  rendering 2.7x.** No API change — every improvement is internal to `kormium-core`, and all
  targets benefit (the gap was widest on Native, which has no JIT escape analysis).
  Four changes: the internal logging facade takes a formatted message behind an `inline`
  guard, so `trace { }` no longer allocates a closure on every field read and write, and the
  column accessors stop tracing entirely; `NotNullColumn.getValue` does one storage lookup
  instead of two; entity field values move from a `String`-keyed `HashMap` to an array indexed
  by column position, keeping a fallback map so one entity type may still back columns of
  several tables; the rendered select list is cached per dialect, with `trimIndent()` dropped
  from the builders where it was already a no-op and `Column.resultKey` precomputed; and the
  per-row paths walk a flat array of columns instead of the name-keyed `LinkedHashMap`, which
  on Kotlin/Native costs ~47 ns per entry against ~0.5 ns for an array element and was being
  walked twice per row.
  Measurements and method are in [`reports/`](reports/README.md).

## [0.11.0] — SQLite on the Kotlin/JS target (`kormium-sqlite-js`)

### Added
- **Browser SQLite on the Kotlin/JS target — `kormium-sqlite-js`.** A new module that runs the
  same wa-sqlite engine as `kormium-sqlite-wasm`, but bound with Kotlin/JS interop so it links
  against **js-only** consumers such as the `kotlin-react` wrappers (which ship no `wasmJs`
  artifact). `createSqliteJsDatabase(dataDir = "name")` opens an IndexedDB-persisted database
  (`IDBBatchAtomicVFS`), `dataDir = null` an in-memory one; the returned `SqliteJsDatabase` is the
  same `SuspendDatabase` and typed DSL every other backend exposes. Single-connection, main-thread
  (a `Mutex` serialises transactions), no COOP/COEP requirement. The Worker/pooled engines stay
  Kotlin/Wasm-only (they depend on wasmJs companion executables). Consumers must emit ES modules
  (`js { useEsModules() }`) since wa-sqlite is ESM-only. Verified end-to-end under Node (in-memory
  `:memory:`): CRUD, blob round-trip and transaction rollback.

## [0.10.0] — pgvector, API lock, three browser SQLite engines, bounded pools

### Changed (breaking, pre-1.0)
- **Accidentally-public driver internals are now `internal`.** None of these appeared in
  any documentation or were usable without reaching into implementation packages:
  the native PostgreSQL plumbing inherited from pgkn (`AbstractSqlParameterSource`,
  `MapSqlParameterSource`, `Oid`, `AnonymousClassException`, `GetColumnValueException`,
  `InvalidDataAccessApiUsageException`), the per-backend JDBC `ResultSet` wrappers
  (`PgResultSetWrapper`, `MySqlResultSetWrapper`, `SqliteResultSetWrapper`),
  `JdbcExecutor`, `MySqlNativeTypeMapper` and the `sqliteException` helper. The exact
  removals are the `.api` dump diffs in this change (−186 ABI lines). Everything the
  docs, samples or sibling modules use is untouched.

### Added
- **API surface is now locked.** Every published module compiles in Kotlin's
  `explicitApi()` strict mode (all public declarations carry explicit visibility and
  return types), and [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator)
  dumps the JVM and klib ABI of each module into `<module>/api/`; CI fails on any surface
  change that isn't an explicitly reviewed `./gradlew apiDump`. This change is purely
  mechanical — the dumped ABI is byte-identical before and after. (Deliberate narrowing of
  accidentally-public internals comes separately, as reviewable `.api` diffs.)

### Changed
- **BREAKING: `Instant` is now `kotlin.time.Instant`.** `Column.Instant`, `InstantColumnType`
  and `ResultSet.getInstant` use the stdlib `kotlin.time.Instant` instead of
  `kotlinx.datetime.Instant`, which no longer exists in kotlinx-datetime 0.7+. Kormium now
  depends on kotlinx-datetime **0.8.0**, exposed as an `api` dependency of `kormium-core`
  (the `LocalDate`/`LocalTime`/`LocalDateTime` column types are part of the public API).
  Migration: replace `kotlinx.datetime.Instant`/`Clock` imports with `kotlin.time.Instant`/
  `kotlin.time.Clock`; `Local*` imports are unchanged.
- **BREAKING: decimal support moved out of core into `kormium-decimal`.** `Column.BigDecimal`,
  `BigDecimalColumnType` and `ResultSet.getBigDecimal` are removed from `kormium-core`, and the
  `com.ionspin.kotlin:bignum` dependency is gone from every module — core now ships no type
  implementations, and 1.0 exposes no third-party 0.x types in its API. Decimal columns come
  from the new `kormium-decimal` artifact, which bridges
  [`io.github.kormium:decimal`](https://github.com/kormium/decimal) through the open
  `ColumnType` seam. Migration: add `io.github.kormium:kormium-decimal`, replace
  `Column.BigDecimal()` with `Column.decimal()` (import `io.github.kormium.decimal.decimal`)
  and ionspin's `BigDecimal` values with `io.github.kormium.decimal.Decimal`
  (`Decimal.parse("10.50")`, `Decimal.of(100)`). On the JVM parameters now bind as
  `java.math.BigDecimal` (typed `numeric` bind); on all other targets values travel as
  decimal text, as before. `case { }` no longer infers a decimal result type — pass
  `DecimalColumnType` explicitly: `case(DecimalColumnType) { ... }`.

### Added
- **`kormium-decimal` module** — `Column.decimal()` / `DecimalColumnType` for exact decimal
  columns, published for the full target matrix and pinned by the BOM.
- **Browser SQLite now ships three engines** (see
  [ADR 0010](docs/adr/0010-browser-sqlite-three-engines.md) and
  [Backends → Browser SQLite](docs/backends.md#browser-sqlite-kormium-sqlite-wasm)); the original
  `createSqliteWasmDatabase` (main thread, `:memory:`/IndexedDB) is untouched:
  - `createWorkerSqliteWasmDatabase()` — **the recommended default**: one in-memory connection in
    a dedicated Worker, so SQLite runs off the main thread (UI keeps rendering during a query);
    measured ~35% faster per query than the main-thread engine at 1M rows. No COOP/COEP needed.
  - `createPooledSqliteWasmDatabase(opfsPath, readerPoolSize)` — **experimental**: persistent
    OPFS storage with one writer + N concurrent readers (`opfs-wl` VFS); reads route via
    `suspendTransaction(readOnly = true) { }`, and `closeAndAwait()` releases OPFS handles before
    a reopen. Requires COOP/COEP response headers and suits *infrequent, heavy* queries —
    for bursts of fast indexed queries a single connection measured both faster and more reliable.
  - Both new engines are built on the published
    [`kormium/sqlite-wasm-kt`](https://github.com/kormium/sqlite-wasm-kt) bindings
    (`io.github.kormium:sqlite-wasm-kt` + npm `@kormium/sqlite-wasm-worker`) over the official
    `@sqlite.org/sqlite-wasm`.
- **Bounded pool checkout: `acquireTimeout` + `PoolExhaustedException`** (#36). When all `poolSize`
  connections are busy, an acquire now waits at most `acquireTimeout` (new
  `createDatabase`/`createSqliteDatabase` parameter, default 30 s) and fails with the new
  `PoolExhaustedException` naming the pool size and what to do — instead of blocking forever, which
  is what the native/Android Channel pools did (the classic deadlock: SQLite's `poolSize = 1`
  default plus a nested `transaction { autocommit { } }`). On the JVM the parameter maps to
  HikariCP's `connectionTimeout` and its checkout timeout is translated to the same exception, so
  exhaustion is one catchable type on every JVM/native/Android backend — see
  [Backends → Pool exhaustion](docs/backends.md#pool-exhaustion-acquiretimeout).

### Fixed
- **The BOM now pins every published artifact** (#8). Ten modules had drifted out of
  `kormium-bom` — `kormium-r2dbc`, `kormium-observe`, the three dialect modules, `kormium-wasm-driver`
  and the four web/Node engines — so the documented BOM setup still required explicit versions for
  them. The BOM's constraints are now derived from the same `publishableModules` set that decides
  what gets published (the exact drift class 0.9.1 fixed for publishing), so they cannot diverge
  again. The per-artifact POM `name`/`description` were also refreshed (they still described
  Kormium as "Postgres + SQLite, JVM + Native").

## [0.9.1] — Publish the 0.9.0 web/Node modules

### Fixed
- **Web/Node engine modules weren't published.** The root `publishableModules` allowlist wasn't
  updated when the web stack merged into 0.9.0, so `kormium-postgres-dialect`,
  `kormium-mysql-dialect`, `kormium-sqlite-dialect`, `kormium-wasm-driver`, `kormium-sqlite-wasm`,
  `kormium-sqlite-node`, `kormium-postgres-node` and `kormium-mysql-node` were built and tested in
  CI but never uploaded to Maven Central. This release adds them to the allowlist; no source
  changes.

## [0.9.0] — Web stack (JS / Wasm / Node), and a pre-1.0 API consolidation

### Added
- **Kotlin/JS + Kotlin/Wasm support.** The typed DSL now compiles and is tested on `js`, `wasmJs`
  and `wasmWasi`; `kormium-core`, `kormium-migrate` and `kormium-observe` ship web artifacts.
  Logging goes through an internal `KormiumLogger` facade so core no longer hard-depends on
  kotlin-logging (which has no wasmWasi artifact).
- **Standalone dialect modules** (`kormium-postgres-dialect` / `kormium-sqlite-dialect` /
  `kormium-mysql-dialect`): the pure SQL dialect is split out of each driver so it can compile to
  every target (incl. js/wasm). The driver modules re-export it via `api`, so existing imports are
  unchanged. See [ADR 0001](docs/adr/0001-standalone-dialect-modules.md).
- **Browser & Node database engines** (suspend-only, sharing `kormium-wasm-driver`):
  `kormium-sqlite-wasm` (wa-sqlite, IndexedDB-persisted, with a Compose Multiplatform todo sample),
  `kormium-sqlite-node` (better-sqlite3), `kormium-postgres-node` (node-postgres, pooled) and
  `kormium-mysql-node` (mysql2, pooled). A separate
  [kormium/pglite](https://github.com/kormium/pglite) repo runs full Postgres (PGlite) in the browser.
- **`Column.Bytes()`** — a `ByteArray` column type, bound and read as native binary (`bytea`/`BLOB`,
  JDBC `setObject`/`getBytes`, libpq bytea, and `Buffer`/`Uint8Array` on the Wasm/Node engines).
- **Compile-time validation of `upsert` / `insertOrIgnore` conflict columns.** `onConflict` is now
  typed `Column<*, *, T>` (and `List<Column<*, *, T>>`), so a conflict column from another table is
  a compile error instead of rendering the wrong column into `ON CONFLICT`. Same-table targets,
  including `onConflict = Table.primaryKey`, are unchanged. A runtime backstop still rejects an
  empty target — and the rare same-entity-other-table case — with a clear `IllegalArgumentException`
  naming the column and tables (#32).
- **`ConcurrencyConflictException`** — a typed signal for a transient serialization failure / deadlock
  (SQLSTATE `40001` / `40P01`), so a caller can catch one portable type instead of matching SQLSTATE
  strings to know a transaction is safe to retry. Kormium ships the exception, not a retry loop (the
  policy is the application's) — see the retry recipe in `AGENTS.md` and
  [ADR 0007](docs/adr/0007-concurrency-conflict-exception.md).

### Changed
- `Table.primaryKey` is now `List<Column<*, *, T>>` (was `List<Column<*, *, *>>`) and the table's
  columns carry their entity type. Source-compatible for normal use; code that passed columns held
  as a bare `Column<*, *, *>` to `onConflict` must use the table's own columns.
- **`update(query, entity)` argument order flipped to `update(entity, query)`.** The `Query` form now
  takes the patch entity first, matching the block form `update(entity) { where { … } }` — the entity
  is in the same position in both. Migrate `update(Query(...), patch)` to `update(patch, Query(...))`.
- **`insert` / `upsert` now return a non-null `T`** (was `T?`). They always yield a row — the passed
  entity on the fast path, or the written row on `returning = true` — so the result no longer needs a
  `!!`. The `returning = true` path now throws (instead of returning null) if the row can't be read
  back, matching `insertAll`. `insertOrIgnore` still returns the affected-row count (`Long`).
- **`findById(id: Any)` removed in favour of typed `findOne`.** The single untyped read in the API
  silently accepted a wrong-typed id (e.g. a `String` for a `Uuid` key) and bypassed the column's
  converter. Replaced by `findOne { where { Users.id eq id } }` / `findOne(Query(...))` → `T?`
  (`LIMIT 1`): the id is type-checked against the column, binds through its converter, and the same
  form reads by any unique column, not only the primary key. See
  [ADR 0005](docs/adr/0005-no-untyped-findbyid.md).
- **`eq null` / `neq null` are restricted to nullable columns.** They render `IS [NOT] NULL`; on a
  non-null column the comparison is meaningless (it can never be NULL) and now fails to compile.
  This also makes a wrong-typed value comparison (`age eq "x"`) report against the real
  `eq(value)` candidate ("Int expected") instead of the null overload ("Nothing? expected").
- **Comparison / membership operators unified onto `Operand<Z>`.** The typed-literal forms of `eq`,
  `neq`, `lt`, `ltEq`, `gt`, `gtEq`, `inList`, `between` and `like` were declared separately on
  `Column` / `NumericExpr` / `StringExpr` / `CoalesceOp` / `CaseOp` (≈30 overloads, with gaps), and
  aggregates had none. They are now defined once over a new `Operand<Z>` interface (a `Selectable`
  that carries its `ColumnType`) that every typed expression implements. Existing code compiles
  unchanged and renders identical SQL; the change is additive — the gaps close, so e.g. `case { … }
  inList listOf(…)`, `col.coalesce(0) between 1..10` and `total.sum() gt 100` (no `Value(…)` wrapper)
  now work uniformly. A new expression type composes for free.
- **Predicates renamed `less` → `lt`, `lessEq` → `ltEq`.** The comparison operators are now symmetric
  (`lt` / `ltEq` paired with `gt` / `gtEq`, instead of the abbreviated `gt` against the spelled-out
  `less`), matching the common `lt`/`gt` convention. `eq` / `neq` / `gt` / `gtEq` are unchanged.
- **`isNull()` / `isNotNull()` moved from `Column` to any `Operand`.** A computed expression that can be
  null (a `COALESCE` of nullable columns, `rank + 1`, a `CASE`, …) can now be tested for NULL —
  previously only a `Column` could. Additive; `eq null` / `neq null` stay as the nullable-column sugar.

## [0.8.0] — Cross-instance notifications, Windows async, expression UPDATE

### Added
- **Cross-instance change notifications (`NotificationTransport`).** Commit notifications — the
  seam behind `kormium-observe` and app-level caches — now cross process boundaries. A
  `NotificationTransport` carries "these tables were written" signals between separate database
  instances, and `SuspendDatabase.connectNotifications(transport)` wires it both ways: remote
  signals fire the local `WriteListeners` (so `kormium-observe` queries re-fire cluster-wide with
  no change on their side), and every local commit's dirty tables are published fire-and-forget
  (remote-delivered signals are not re-published, so instances don't echo). Ships the Postgres
  `LISTEN/NOTIFY` transport (JVM + Native, no external dependency) and an R2DBC transport; a
  broker-backed transport (Redis, Kafka, …) is a few lines on the interface — see the new
  `samples/cross-instance-cache`. Delivery is best-effort (rely on a cache TTL as the safety net).
  The shared `encodeTablePayload`/`decodeTablePayload` wire format lets JDBC and r2dbc instances
  interoperate on one channel.
- **Expression-form `UPDATE`.** `Table.update { }` assigns a SQL [Expression] to each column, so a
  column can be updated from its own value without raw SQL: `Posts.update { Posts.views set
  (Posts.views + 1); where { Posts.id eq id } }` renders `SET "views" = "views" + $1`. Literals
  (`col set false`) bind as parameters; arithmetic composes (`(col + 2) * 3`, `col * col`) and works
  in `where` too. Complements the existing entity-patch `update(entity)` form.
- **PostgreSQL Native: true-async on Windows too.** `WindowsSocketReactor` (WSAPoll over the
  in-flight sockets + a loopback-UDP wake socket) gives mingwX64 the same non-blocking suspend path
  Linux/macOS got in 0.6.0, instead of the blocking offload. Kotlin/Native doesn't expose WSAPoll
  and a direct cinterop on `<winsock2.h>` yields empty bindings, so a project-owned `winsock_shim.h`
  wraps the calls behind a small static-inline `ksock_*` C API that cinterop compiles into the stub
  klib (no separate link step beyond `-lws2_32`). Verified on a native Windows host.

### Changed
- **Core: DSL scope safety (`@DslMarker`) and `EXACTLY_ONCE` contracts.** `Scope`, `SuspendScope`
  and `QueryBuilder` are marked `@KormiumDsl`, so an outer-scope member can no longer be called
  implicitly from inside a `find`/`count`/`update` builder block (an accidental mutation inside a
  read block is now a compile error; reach the outer receiver deliberately with `this@transaction`).
  `transaction`/`autocommit`/`savepoint` (sync + suspend) gained `callsInPlace(EXACTLY_ONCE)`
  contracts, so a `val` initialised inside the block smart-casts afterwards. Both are additive.

## [0.7.0] — MySQL / MariaDB engine

### Added
- **New engine: MySQL / MariaDB (`kormium-mysql`).** A second full database engine, mirroring
  `kormium-postgres`, with `MySqlDialect` (backtick identifiers, MySQL `LIMIT`/`OFFSET`), the
  `MySqlDriver` interface and a `createDatabase(...)` factory across three backends:
  - **JVM (JDBC)** over `mysql-connector-j` — typed parameter binding, vendor-code exception
    mapping (1062 → `UniqueViolationException`, 1452 → `ForeignKeyViolationException`, …), and a
    UTC-pinned session so `Instant` round-trips through `TIMESTAMP`.
  - **Native (Linux/macOS)** over the MariaDB Connector/C (`libmariadb`, API-compatible with
    `libmysqlclient`) using prepared statements (`mysql_stmt_*`). The suspend path offloads to the
    IO dispatcher (libmysql has no portable non-blocking API), as PostgreSQL Native does on Windows.
    Windows native is not built (served by the JVM driver).
  - **R2DBC** via `createMySqlR2dbcDatabase(...)` in `kormium-r2dbc` (now multi-backend), over
    `io.asyncer:r2dbc-mysql`.

  UUIDs are stored as `CHAR(36)` text and JSON as MySQL's native `JSON`. There is no
  transaction-scoped advisory lock (MySQL `GET_LOCK` is session-scoped), so `kormium-migrate` runs
  without one on MySQL (as on SQLite).

### Changed
- **Core: `Dialect` gained write-path rendering hooks** so the INSERT family is no longer
  hardcoded to Postgres/SQLite syntax: `renderLimitOffset(limit, offset)` (a bare `OFFSET` without
  `LIMIT` is a MySQL syntax error), `supportsReturning`, `renderInsertDefaultValues`,
  `renderUpsertSuffix` and `renderInsertOrIgnoreSuffix`. All have defaults equal to the previous
  behaviour, so Standard / Postgres / SQLite output is unchanged. `MySqlDialect` overrides them
  (`() VALUES ()`, `ON DUPLICATE KEY UPDATE`, and — since MySQL has no `RETURNING` —
  `insert/upsert(returning = true)` re-selects the row by primary key).
- **`kormium-migrate`: journal `id` column is now `varchar(255)`** (was `text`). MySQL forbids a
  `TEXT` primary key without a prefix length; `varchar(255)` is equally valid on Postgres and SQLite.

## [0.6.0] — Native driver hardening and true-async reads

### Fixed
- **PostgreSQL Native: `timestamptz`/`timestamp` reads no longer throw on UTC values.**
  `getInstant` parsed Postgres' text output with a fixed `replaceRange(10, 11, "T")` and
  `Instant.parse`, which rejects the common hours-only offset form `2024-01-15 13:45:30+00`
  (kotlinx-datetime wants a full `±HH:MM`). Reads now normalise the date/time separator and
  every offset form Postgres emits (`+00`, `+05:30`, `-08`, with seconds and fractional
  seconds) before parsing. Covers `getInstant` and `getLocalDateTime`.

### Changed
- **PostgreSQL Native: production connection defaults.** Connections now open via
  `PQconnectdbParams` with `connect_timeout=10` (a dead host fails fast instead of hanging),
  TCP `keepalives` (detect a silently dropped connection) and `application_name=kormium`
  (labels the backend in `pg_stat_activity`). No public API change. Parameter-less statements
  (transaction `BEGIN`/`COMMIT`, DDL, `SET`) now use the simple query protocol (`PQexec`),
  which also accepts multiple `;`-separated statements in a single raw-SQL call.

### Added
- **PostgreSQL Native: true-async reads (Linux/macOS).** `useConnection` (and the
  `suspendTransaction` / `suspendAutocommit` built on it) now drive libpq asynchronously
  through a single-threaded socket reactor (`PQsendQuery*`/`PQflush`/`PQconsumeInput` + `poll`),
  so a suspended statement no longer holds a coroutine thread — many concurrent queries
  multiplex over a few threads instead of one-thread-per-in-flight-query (verified: 8 sleeping
  transactions on one thread overlap instead of serialising). Previously the suspend path
  offloaded blocking calls to `Dispatchers.Default`. The blocking `usePinned` path is unchanged.
  Windows keeps the blocking offload (Kotlin/Native exposes no `WSAPoll`); its behaviour is
  unchanged — true-async there is a follow-up.
- **Experimental Windows Native (mingwX64) target** for all multiplatform modules:
  `kormium-core`, `kormium-postgres` (libpq), `kormium-sqlite` (vendored amalgamation),
  `kormium-observe`, `kormium-migrate` and the Ktor integrations. Artifacts cross-compile
  and publish from any host, and CI runs the JVM and native (libpq + SQLite) test suites
  on a Windows runner; the target still ships without compatibility guarantees until it
  bakes for a release cycle. `benchmarks/run.bat` runs the benchmark matrix (including
  the native column) on Windows.

### Performance
- **PostgreSQL Native: repeated statements skip re-parsing.** The libpq driver now keeps a
  per-connection LRU cache (128 entries) of the `:name` → `$n` parse/substitution result,
  mirroring the JVM driver's parse cache. Each parameter is also converted to its text
  form once instead of twice, and the redundant per-call length/format/type arrays are no
  longer allocated (libpq ignores lengths for text-format parameters; null types means
  "infer", exactly as the previous all-zero array did). Statements with positional `?`
  parameters or Iterable/Array values keep the previous per-call path. Batch inserts gain
  ~14% on the comparison workload.
- **`BigDecimal` columns read and bind without bignum string arithmetic.** Plain decimal
  text of up to 18 digits — everything PostgreSQL emits for typical `numeric` columns —
  is parsed via a single `Long` accumulation instead of `BigDecimal.parseString`'s
  per-digit big-integer multiplication (the hottest frames in row-materialization
  profiles), producing a bit-identical ionspin representation. Binding renders from the
  (significand, exponent) pair directly. Note: the bound text is now canonical — e.g.
  `BigDecimal.fromInt(100)` binds as `100` instead of ionspin's `1.0E+2`; the database
  value is unchanged.
- **Benchmarks run an optimized Kotlin/Native binary.** `kormium-postgres` now links a
  release-mode test binary (`linkBenchReleaseTest<Target>`) for the native benchmark
  harness; the default debug test binary understated CPU-bound throughput (row
  materialization, batch binding) by 2-3x against the JIT-optimized JVM ORMs. Linked only
  on demand, so regular test and CI builds are unaffected.
- **PostgreSQL Native: fewer per-row allocations on reads.** `getString` no longer allocates
  a logging closure for every cell (the logger's `trace` is not inlined, so the lambda was
  built even when tracing is off), integer columns parse straight from libpq's C string
  instead of materialising an intermediate Kotlin `String`, and the result list is presized
  to the row count instead of growing and recopying. ~10% on the multi-row read benchmark
  (`selectMany`, 30.2k → 33.3k ops/s); single-row reads are unchanged, as the savings scale
  with row count.

## [0.5.0] — Review fixes and typed PostgreSQL binding

### Performance
- **PostgreSQL JVM reads are ~1.7-2x faster: parameters now bind as properly-typed JDBC
  objects** (uuid, numeric, timestamptz, jsonb, date/time, float4/int2) via the new
  `PostgresJvmTypeMapper`, replacing text binding under `stringtype=unspecified`. An
  untyped text parameter forced the server to re-infer its type on every execution of a
  server-prepared statement — an extra protocol round-trip per query (wire-traced; this
  was the entire read gap to Hibernate). **Raw SQL note:** binding a `String` to a
  non-text column (uuid, timestamptz, ...) previously worked via server inference and now
  needs an explicit cast (`WHERE id = :id::uuid`) or a typed value; DSL queries are
  unaffected. Native (libpq, text + dialect casts) and r2dbc (already typed) are unchanged.

### Fixed
- **Mixed `and` / `or` predicates now render with correct precedence.** Kotlin infix calls
  are all same-precedence and left-associative, so `a or b and c` builds `(a OR b) AND c` —
  but it previously rendered as `a OR b AND c`, which SQL parses as `a OR (b AND c)`,
  silently changing the query. Nested compound operands with a different operator are now
  parenthesized.
- **`WriteListeners` registration is now lossless under concurrency.** `add` / `remove`
  swap the copy-on-write listener list with a CAS loop instead of a plain volatile write,
  so concurrent Flow collections through `kormium-observe` can no longer drop each other's
  listeners (a dropped listener meant a Flow that silently stopped updating).
- **The JDBC `:name` parse cache is now bounded.** It is an LRU capped at 1024 entries, and
  SQL longer than 4096 chars (batch INSERTs, large IN-lists — a distinct string per call)
  bypasses it, so long-lived servers no longer accumulate parse entries without limit.
- **`ResultSet` / `ColumnType` docs now state the real 0-based column indexing.** The KDoc
  (inherited from JDBC) claimed indexes are 1-based, which would mislead custom
  `ColumnType` implementations; the convention is and was 0-based.
- **JDBC `rollback()` now translates `SQLException`** into typed Kormium exceptions, like
  `commit()` already did.
- **SQLite (Native/Android): a connection released after `close()` is closed, not leaked.**

### Changed
- **`leftJoin` entity pairs are now properly nullable** (breaking). `Table.leftJoin` returns
  a dedicated `LeftJoinPair` whose `find()` is `List<Pair<A, B?>>`: the right side is `null`
  for left rows with no match (detected by a `NULL` right-side primary key). Previously
  `find()` claimed `Pair<A, B>` and the unmatched right entity threw on first property
  access. The `select(...)` forms, `where`, `groupBy`, `distinct` and three-table chaining
  are unchanged; `innerJoin` is unaffected.
- **Table metadata is read-only** (breaking): `Column.name` and `Column.nullable` are now
  `val` (were public mutable `var`), and `getFieldDisplayNames()` returns a read-only
  `Map` (was the internal `MutableMap`), so table metadata can no longer be corrupted at
  runtime.
- **`savepoint { }` outside a transaction fails fast** (breaking): calling it inside
  `autocommit { }` / `suspendAutocommit { }` now throws `IllegalStateException` with a
  clear message on every backend. Previously it surfaced as a confusing server error on
  PostgreSQL while silently opening an implicit transaction on SQLite.

## [0.4.0] — Rebrand to kormium

### Changed
- **All published artifacts renamed `korm-*` → `kormium-*`** (breaking — Maven coordinates change).
  Update dependencies from `io.github.kormium:korm-core` to `io.github.kormium:kormium-core` (likewise
  `-postgres`, `-sqlite`, `-r2dbc`, `-jdbc`, `-migrate`, `-observe`, `-ktor`, `-ktor-di`, `-ktor-koin`,
  `-bom`). Gradle project paths become `:kormium-*`. The Maven **group** stays `io.github.kormium`.
- **Public API identifiers renamed `Korm*` → `Kormium*`** (breaking): `KormConfig` → `KormiumConfig`,
  `KormConfigBuilder` → `KormiumConfigBuilder`, `KormBuilder` → `KormiumBuilder`, `KormException` →
  `KormiumException`, `KormHandle` → `KormiumHandle`, and the `KormUuid` / `KormId` / `KormBigDecimal`
  column types → `Kormium*`. In `kormium-ktor` the lifecycle plugin `Korm` → `Kormium`
  (`install(Kormium) { ... }`) and the `ApplicationCall.korm()` helper → `kormium()`.
- **Migration journal table renamed `korm_migrations` → `kormium_migrations`** (breaking for existing
  databases — the new table starts empty, so prior migrations re-apply; since 0.x is unpublished, drop
  any throwaway dev database).
- **License changed from MIT to Apache License 2.0** — adds an explicit patent grant and aligns with
  the Kotlin / Exposed ecosystem. See `LICENSE` and the new `NOTICE`.
- **Migrations moved out of `kormium-core` into a new `kormium-migrate` module** (breaking). Core does
  not own schema, so the migration runner is now opt-in. `io.github.kormium.Migration` / `migrate`
  become `io.github.kormium.migrate.{Migration, migrate}`; add the `kormium-migrate` dependency and
  update imports. They still run the same way via `beforeStart { migrate(appMigrations) }`.

### Added
- **Per-module `README.md`** for every published artifact (purpose, Maven coordinates, usage example,
  platforms), linked from `docs/installation.md`.
- **`kormium-migrate`** module: an ordered, idempotent **raw-SQL** migration runner.
  - `Migration(id, sql)` takes raw SQL split into statements on top-level `;` (quoted
    strings/identifiers, `--` / `/* */` comments and Postgres `$tag$…$tag$` bodies respected), or
    `Migration(id, statements)` for explicit statements.
  - **Checksum validation**: editing an already-applied migration fails fast with
    `MigrationChecksumException`.
  - **Concurrency-safe**: the whole batch runs in one transaction; on PostgreSQL it first takes a
    transaction-scoped advisory lock so concurrently-starting instances block and don't
    double-apply (all-or-nothing — a failed batch records nothing). SQLite has no advisory lock, so
    concurrent cross-process migration is not fully serialized, but the journal primary key plus the
    all-or-nothing rule out double-application (prefer migrating SQLite from one process).
  - The `kormium_migrations` journal now also records the SQL `checksum`, an `applied_at` timestamp
    and the apply-order index.
- **`Dialect.advisoryLockSql(key)`** (defaults to `null`): a backend exposes advisory-lock SQL for
  the migration runner; `PostgresDialect` returns `pg_advisory_xact_lock`.

### Migration notes
- The previous `up: Scope.() -> Unit` lambda form is removed — migrations are raw SQL now. Move
  any seed/data logic that used Kormium operations into application startup or an explicit-statement
  migration.
- The `kormium_migrations` journal gained columns; since 0.x is unpublished, drop any throwaway dev
  journal so it is recreated with the new schema.

## [0.3.0] — Reactive queries

### Added
- **`kormium-observe`** module: reactive `Flow` queries. `Table.observe(db) { where { … } }`
  emits the result now and re-emits after every committed write to the table; a generic
  `SuspendDatabase.observe(tables) { … }` covers joins and custom fetches. Writes are
  conflated. See [Observing changes](docs/observe.md).
- **`WriteListener` commit hook** on `Database` / `SuspendDatabase`
  (`db.writeListeners.add { tables -> … }`): notified, after commit, with the set of tables a
  scope wrote. A generic seam (also usable for cache invalidation, audit, metrics) that backs
  `kormium-observe`.
- **`invalidates` argument** on the raw `Scope.execute` / `executeUpdate` (and suspend
  counterparts): declare the tables a raw statement writes so observers are notified — the
  analog of Room's `@RawQuery(observedEntities = …)`.
- **`createX { }` configuration builders.** `createSqliteDatabase("app.db") { config { … };
  beforeStart { … } }` and the same for `createDatabase(...)` (PostgreSQL). `config { }` is a
  mutable view of `KormiumConfig`; `beforeStart { }` runs once before the database is returned —
  the place to run migrations (your own package, or Flyway/Liquibase), which the builder does
  not own. The existing `config: KormiumConfig` factory overloads are unchanged.
- **Open column-type system.** Column types are now an extensible `ColumnType<T>` interface
  instead of a fixed list. New built-ins `Column.enum<E>()` (enum by name) and
  `Column.json<T>()` (`@Serializable` value as JSON), a `ColumnType<S>.convert(to, from)`
  helper for custom types over an existing one (replacing Room's `@TypeConverter`), and
  `Column.of(type)` to declare a column of any `ColumnType`. The 14 built-in types are
  unchanged in behaviour. Conversion applies on insert, update and in predicates.

### Changed
- **Breaking (internal extension point): the column-type representation changed.**
  `Column.ColumnNameEnum` is removed and `TypeMapper.fromResult(...)` is gone (reading is now
  `ColumnType.read`); `Column.columnType` is a `ColumnType<Z>`. Ordinary table/query/insert
  code is unaffected and behaves identically — only custom `TypeMapper`s or code referencing
  `ColumnNameEnum` need migration (nothing in Kormium itself did).
- **Breaking: `Database` no longer extends `SqlExecutor`.** The pooled, scope-less
  `db.execute(...)` / `db.executeUpdate(...)` is removed — run one-off statements through a
  scope instead: `db.autocommit { execute(...) }`. This makes every write transactional and
  observable. `SuspendDatabase` was never an executor, so it is unchanged. The
  `SqlParameterSource` overloads now live only on the pinned `SqlExecutor` inside a scope.

## [0.2.0] — API redesign

A breaking redesign of the core API. Kormium now models runtime query/insert/update mapping
only — schema ownership moves out to migrations / raw SQL. (Pre-1.0, so breaking changes
bump the minor version.)

### Added
- **Read query block DSL** over `Query(...)`: `Table.find { where { … }; orderBy DESC …;
  limit = …; offset = … }` and `Table.count { where { … } }`. Multiple `where { }` blocks
  AND together (each parenthesized); `Query(...)` value API is unchanged.
- **Null predicates** `column eq null` / `column neq null`, rendering `IS NULL` /
  `IS NOT NULL` while keeping the comparison vocabulary uniform.
- **Mutation block DSL**: `Table.update(patch) { where { … } }` and
  `Table.deleteWhere { where { … } }`, mirroring the read DSL.
- **`upsert(entity, onConflict, update, returning)`** and **`insertOrIgnore(entity,
  onConflict)`** for single- and composite-column conflict targets, rendered cross-dialect
  as `ON CONFLICT … DO UPDATE` / `DO NOTHING`.
- **Per-database `KormiumConfig`** (with `batchInsertMode`), threaded through
  `createDatabase` / `createSqliteDatabase` / `createR2dbcDatabase` and carried on the
  `Database` / `SuspendDatabase` handle.
- **Batch insert modes** for `insertAll`: `Strict`, `GroupByAssignedFields` (default,
  preserves input order on `returning`) and `UnionNulls`.

### Changed
- **Type-safe column nullability + fluent column API**. Nullability is now encoded in the
  type: `val note by Column.Text().nullable()` (entity property `String?`),
  `val id by Column.UUID().primaryKey()` (non-null PK). `Column` splits into
  `NotNullColumn` / `NullableColumn`; `.nullable()` and `.primaryKey()` are mutually
  exclusive in the type system. Custom SQL name via constructor `name = …`. Removed the
  `nullable=` / `primaryKey=` constructor params and the per-type `*Type` classes.
- **`Table` takes the SQL table name directly** (`Table("users", ::User)`); the `schema`
  concept and `Table.Meta` are removed (rely on the connection's search_path / migrations).
  `Entity.fields` is now internal (with `replaceFields` / `isSet` / `unset` escape hatches);
  user entities are just `class User : Entity()`, and `@Serializable` is no longer required
  on them.
- **`Scope`/`SuspendScope` `new()` / `new(List)` renamed to `insert()` / `insertAll()`.**
- **`executeUpdate` returns the affected-row count** (`Long`) across JDBC, native libpq,
  native/Android SQLite and r2dbc; `update()` and `deleteWhere()` propagate it (0 = no row
  matched, for not-found / optimistic locking).
- A single `insert` now omits absent fields (so DB defaults / generated values apply) and
  emits `INSERT … DEFAULT VALUES` when nothing is set; an explicit `null` is still bound as
  `NULL`.

### Removed
- **`createTable()` / `dropTable()`** (from `Scope` / `SuspendScope`), the
  `createTableSql` / `dropTableSql` builders on `Table`, and `Dialect.sqlType` (with its
  `PostgresDialect` / `SqliteDialect` overrides). Kormium no longer owns schema DDL — create
  schema via raw `CREATE TABLE` (`execSql` / `executeUpdate`) or a migration tool
  (Flyway, Liquibase, …).

### Fixed
- **JDBC**: `JdbcExecutor` now closes the `PreparedStatement` after each execute (previously
  every prepared statement leaked until the pooled connection was returned).

## [0.1.0] — first public release

First release published to Maven Central (group `io.github.kormium`), with artifacts
for **JVM** and **Kotlin/Native** (`linuxX64`, `macosX64`, `macosArm64`).

### Added
- **Kotlin Multiplatform ORM** with a backend-agnostic core (`kormium-core`) and pluggable
  backends: **PostgreSQL** (`kormium-postgres`, JVM via JDBC/HikariCP + Native via libpq) and
  **SQLite** (`kormium-sqlite`, JVM via sqlite-jdbc + Native via the sqlite3 cinterop).
- Compile-time database↔table safety via `Catalog` tags and `Database<G>`; sharding by
  holding many `Database<G>` instances.
- Transactions and scopes: `transaction { }` / `autocommit { }` (+ `suspend` variants),
  savepoints, typed errors (`UniqueViolationException`, `ForeignKey`, `NotNull`, `Check`).
- Query power: typed predicates (`eq`, `inList`, `like`, `isNull`, …), `innerJoin`/`leftJoin`,
  aggregations (`count`/`min`/`max`/`sum`/`avg`), `groupBy`/`having`/`distinct`.
- Schema + idempotent migrations: `createTable()`/`dropTable()`, `Database.migrate(...)`.
- 14 column types (incl. `Long`, `Float`, `Short`, `LocalDate`/`LocalTime`/`LocalDateTime`),
  primary-key abstraction, `INSERT … RETURNING` (opt-in), batch insert, `count()`.
- Ktor server integration split per DI framework: `kormium-ktor` (DI-agnostic),
  `kormium-ktor-di` (Ktor built-in DI), `kormium-ktor-koin` (Koin).
- `kormium-bom` Bill of Materials pinning all artifact versions.

### Changed
- Module layout unified under the `kormium-` prefix: `core` → `kormium-core`; the former `pg`
  (Postgres dialect/driver interface) and `pgkn` (native libpq driver) modules were folded
  into `kormium-postgres` (commonMain + nativeMain respectively).
- Publishing moved from the retired OSSRH endpoint to the **Maven Central Portal**, driven
  by the `com.vanniktech.maven.publish` plugin.

[0.2.0]: https://github.com/kormium/kormium/releases/tag/v0.2.0
[0.1.0]: https://github.com/kormium/kormium/releases/tag/v0.1.0
