# Roadmap

Kormium is pre-1.0. The roadmap is intentionally explicit about what is shipped, what is
being hardened and what is still exploratory. Items here are not promises for a specific
release date; they are the direction of the project.

## Current Baseline

Shipped today:

- backend-agnostic Kotlin Multiplatform core;
- PostgreSQL on JVM through JDBC/HikariCP;
- PostgreSQL on Kotlin/Native through libpq;
- MySQL / MariaDB on JVM (JDBC) and Kotlin/Native (libmariadb);
- SQLite on JVM, Kotlin/Native and Android;
- async PostgreSQL and MySQL on JVM through r2dbc;
- browser & Node engines (JS / Wasm): SQLite (wa-sqlite, better-sqlite3) and Node
  Postgres/MySQL — see [Backends](backends.md) and [Web targets](web-targets.md);
- typed table/entity DSL;
- an open `ColumnType` system (built-ins + `enum`/`json` + custom converters);
- typed predicates, joins and aggregations;
- transactions, savepoints, suspend scopes and migrations;
- reactive `Flow` queries (`kormium-observe`) over a `WriteListener` commit hook;
- cross-process change notification via a pluggable `NotificationTransport` (the Postgres
  `LISTEN/NOTIFY` transport ships with no external dependency), making observe — and any cache —
  work across instances;
- Ktor integration for explicit database passing, Ktor DI and Koin;
- Maven Central publishing with a BOM.

## Before 1.0

The main goal before 1.0 is boring reliability: fewer surprising edge cases, stronger docs
and a smaller chance that public API has to move later.

### API Hardening

- Decide which APIs are stable enough to keep source-compatible after 1.0.
- Make raw SQL extension points clearer and safer.
- Keep the entity model small unless a new abstraction removes real complexity.

(Done: blocking/suspend naming reviewed and made symmetric — `lt`/`ltEq` paired with `gt`/`gtEq`;
comparison/membership operators unified onto one `Operand<Z>` abstraction; untyped `findById`
replaced by typed `findOne`; `insert`/`upsert` return a non-null `T`; a compile-error-quality audit
tightened predicate mismatch messages; the impl-only expression nodes are `internal`. See the
[API ergonomics review](api-ergonomics.md).)

### Query Coverage

(Done: tests for joins with colliding column names; aggregation/`HAVING` coverage; pagination,
projection and nullable-left-join recipes; an explicit unsupported-SQL section in
[queries](queries.md). `RETURNING` on `UPDATE`/`DELETE` was considered and **declined** — it isn't
portable (MySQL has none) without hidden multi-statement emulation; see
[ADR 0008](adr/0008-no-returning-on-update-delete.md). Get the rows with an explicit two-step in one
transaction.)

### Schema and Migrations

- Consider first-class index/foreign-key metadata only after the raw SQL workflow is stable.
- Harden migration tests across PostgreSQL and SQLite.
- Add guidance for production migration review and rollback strategy.

(Done: documented recipes for foreign keys, indexes and check constraints through raw SQL.)

### Backend Reliability

- Keep PostgreSQL JVM, PostgreSQL Native and SQLite behavior aligned where practical.
- Improve Native driver observability and failure messages.
- Build on the JVM and Native parse caches: investigate server-side prepare/execute reuse where benchmarks show real wins.

(Done: SQLSTATE/error-mapping tests for unique, not-null and foreign-key violations.)

### Documentation

- Keep README short and honest.
- Keep `docs/` deep enough that users can build a real application without reading source.
- Add more copy-pasteable recipes to [API cookbook](api-cookbook.md).
- Maintain [Production guide](production-guide.md), [Observability](observability.md) and
  [Compatibility policy](compatibility.md) as the production-readiness contract.
- Add architecture diagrams once the public shape settles.

(Done: a documentation link/anchor verification task in CI.)

## After 1.0

After 1.0 the project should optimize for compatibility and ecosystem fit:

- stronger source/binary compatibility guarantees;
- a clearer deprecation policy;
- more backend-specific tuning guides;
- richer integrations for application frameworks where there is real demand;
- production migration patterns;
- more benchmark scenarios and published methodology.

## Exploratory Areas

These are useful but should not distract from core reliability:

- Windows Native hardening — drop the experimental label after a stable release cycle;
- Kotlin web stack (JS / Wasm-JS / Wasm-WASI) — the typed DSL compiles and tests green on all
  three, and the browser/Node engines have shipped (wa-sqlite in the browser, better-sqlite3 /
  node-postgres / mysql2 on Node); still new, so treated as experimental. A PGlite engine
  (Postgres in the browser) lives in a separate repo. Remaining: wasmWasi stays DSL-only until
  WASI sockets mature. See [Web targets](web-targets.md);
- more SQL dialects;
- richer schema DSL;
- generated entities or compiler plugin support;
- advanced query planner hints and backend-specific SQL features.

## Non-Goals for Now

- Hiding SQL completely. Kormium is an ORM/SQL DSL, not an attempt to make relational storage
  invisible.
- Reimplementing a full SQL parser.
- Adding every backend before the existing ones are boring.
- Building a heavy runtime metadata system if Kotlin types already express the constraint.
- A built-in second-level (L2) entity cache. Caching policy — what to cache, for how long, and the
  consistency model — belongs to the application, not the ORM; a transparent cache under the DSL
  would hide round-trips (against Kormium's explicitness) and invite silent staleness. Kormium
  instead ships the *mechanism* a cache needs — the `WriteListener` commit hook plus cross-process
  `NotificationTransport` — and leaves the cache itself to the app. See the production guide's
  caching section.

## How to Use This Roadmap

For issues and PRs, tie proposed work to one of these buckets:

- reliability bug;
- API hardening;
- backend compatibility;
- docs gap;
- benchmark-backed performance work;
- exploratory prototype.

That keeps the project from accumulating unrelated features while the public API is still
pre-1.0.
