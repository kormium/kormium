# Kormium Documentation

This directory is the long-form documentation for Kormium. The root [README](../readme.md)
is intentionally short; the deeper material lives here.

## Reading Path

1. [Installation](installation.md) - artifacts, BOM, native requirements and supported
   targets.
2. [Quick start](quick-start.md) - a complete first table, connection and CRUD flow.
3. [Tables and entities](tables-and-entities.md) - catalogs, table names, columns and
   entities.
4. [Queries](queries.md) - predicates, ordering, pagination, joins, projections and
   aggregations.
5. [Transactions and migrations](transactions-and-migrations.md) - scopes, savepoints,
   suspend API, async backends and migrations.
6. [Backends](backends.md) - PostgreSQL, SQLite, r2dbc and platform support.
7. [Observing changes](observe.md) - reactive `Flow` queries that re-emit on writes.
8. [Kormium for AI developers](ai.md) - semantic search with pgvector, storing model output,
   and working alongside a coding agent.
9. [Ktor integration](ktor.md) - DI-agnostic, Ktor DI and Koin helpers.
10. [API cookbook](api-cookbook.md) - copy-pasteable recipes for common tasks.
11. [API ergonomics](api-ergonomics.md) - current canonical API style and escape hatches.
12. [Observability](observability.md) - target logging, metrics and failure visibility.
13. [Production guide](production-guide.md) - conservative guidance for real services.
14. [Compatibility policy](compatibility.md) - supported versions, targets and API policy.
15. [Design](design.md) - internal architecture and extension points.
16. [Roadmap](roadmap.md) - current baseline, pre-1.0 hardening and future work.
17. [Project guide](project.md) - samples, benchmarks, testing and contribution notes.

## API Surface

Kormium is split into small artifacts:

| Module | Purpose |
| --- | --- |
| `kormium-bom` | Bill of Materials for version alignment |
| `kormium-core` | Backend-agnostic DSL, scopes and transactions |
| `kormium-postgres` | PostgreSQL dialect and drivers: JDBC/HikariCP on JVM, libpq on Native; pgvector vector search |
| `kormium-sqlite` | SQLite dialect and drivers: sqlite-jdbc, sqlite3 and AndroidX SQLite |
| `kormium-r2dbc` | True async PostgreSQL on JVM, suspend API only |
| `kormium-observe` | Reactive `Flow` queries that re-emit when watched tables change |
| `kormium-migrate` | Ordered, idempotent raw-SQL migration runner with a recorded journal |
| `kormium-jdbc` | Internal/shared JVM JDBC plumbing used by JVM drivers |
| `kormium-ktor` | Ktor helpers without a DI dependency |
| `kormium-ktor-di` | Ktor built-in DI integration |
| `kormium-ktor-koin` | Koin integration for Ktor |

## Current Status

Kormium is pre-1.0. The API is already useful and tested, but not frozen. Documentation should
prefer honest precision over marketing claims: describe what is shipped, what is tested in
CI and what is planned separately.

## Maintenance Notes

- Add user-facing examples to [API cookbook](api-cookbook.md).
- Keep canonical API style in [API ergonomics](api-ergonomics.md).
- Keep production-facing guidance in [Production guide](production-guide.md) and
  [Observability](observability.md).
- Update [Compatibility policy](compatibility.md) when versions or targets change.
- Add architecture and contributor context to [Design](design.md).
- Add planned work to [Roadmap](roadmap.md), keeping shipped and planned behavior separate.
