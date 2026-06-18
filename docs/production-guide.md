# Production Guide

This guide describes what a team should think through before using Kormium in a real service.
It is intentionally conservative because Kormium is still pre-1.0.

## Production Readiness Position

Kormium is suitable today for:

- prototypes;
- internal tools;
- experiments;
- benchmarks;
- services where the team can tolerate API changes and help debug backend issues.

Kormium should not yet be the only persistence layer for a critical system unless the team is
ready to own the risk and test its exact backend/platform combination.

## Application Checklist

Before deploying:

- pin exact Kormium versions;
- choose one backend path deliberately: JDBC, libpq, SQLite or r2dbc;
- run integration tests against the same database version used in production;
- run migration tests on a copy of realistic schema/data;
- define connection pool size and timeouts;
- define transaction boundaries in code;
- add error mapping at service boundaries;
- collect query timing/failure metrics via `KormiumConfig.queryObserver`, and pool metrics through backend tools;
- document how to roll back a failed release.

## Connection Pools

### PostgreSQL JVM

JVM PostgreSQL uses HikariCP through `kormium-jdbc`.

Production considerations:

- set pool size based on database capacity, not application thread count;
- configure connection timeout and max lifetime through backend support when exposed;
- monitor active, idle and pending connections through HikariCP;
- avoid creating one pool per request or per route.

### PostgreSQL Native

Native PostgreSQL uses libpq with Kormium's pool implementation.

Production considerations:

- test pool exhaustion and database restart behavior;
- verify libpq version in deployment images;
- keep Native target and host requirements documented.

### SQLite

SQLite allows one writer. Kormium defaults `poolSize` to 1 for SQLite.

Production considerations:

- use WAL for file-backed databases;
- keep writes short;
- expect write contention if many request handlers write concurrently;
- use SQLite for local storage, caches, edge services or small apps, not high-write
  multi-node workloads.

## Transactions

Keep transaction blocks small:

```kotlin
db.transaction {
    Users.insert(user)
    Profiles.insert(profile)
}
```

Avoid:

- network calls inside a transaction;
- long CPU work inside a transaction;
- opening independent nested database transactions by accident;
- swallowing rollback-causing exceptions without understanding the state.

Reusable helpers should extend `Scope<G>` or `SuspendScope<G>`.

## Migrations

Kormium migrations are idempotent by ID. Production still needs process discipline.

Recommendations:

- never edit an already-applied migration in a released app;
- add a new migration for every schema change;
- test migrations from the previous production version to the new version;
- use idempotent DDL where practical;
- review backend DDL transaction behavior;
- decide whether startup migrations are acceptable for your deployment model.

Open production hardening item:

- add a migration lock strategy for multi-instance startup, such as PostgreSQL advisory
  locks or a backend-neutral locking table.

## Error Handling

At application boundaries, catch Kormium exceptions and map them to domain errors:

```kotlin
try {
    db.transaction {
        Users.insert(user)
    }
} catch (e: UniqueViolationException) {
    throw DuplicateUserEmail()
}
```

For Ktor, `KormiumException.httpStatusCode()` gives a baseline HTTP mapping. Most production
services should still translate database errors into application-specific response bodies.

## Observability

For per-query timing and failures, set `KormiumConfig.queryObserver`: a single backend-neutral
hook called once per statement (DSL, raw SQL, migrations) with backend, SQL template (no
parameter values), kind, duration, row count and SQLSTATE on failure. It replaces wrapping every
repository method. A `WriteListener` commit hook (`db.writeListeners`) is also available for
write notification — cache invalidation, audit, metrics on commit — and backs reactive
`kormium-observe` queries. For the rest:

- forward `queryObserver` events to your metrics stack, and threshold `durationNanos` for slow-query logs;
- configure HikariCP metrics directly on JVM JDBC deployments for pool visibility;
- use database logs and slow query tooling;
- do not log parameter values in production;
- include SQLSTATE/error codes in internal error logs where safe.

See [Observability](observability.md).

## Caching

Kormium ships **no built-in second-level (L2) cache**, and that is deliberate. A transparent entity
cache under the DSL would hide round-trips (Kormium optimizes for explicitness) and invite silent
staleness — the worst kind of bug. Caching policy (what to cache, the TTL, the consistency model)
depends on your domain, so it belongs in the application, where an in-process cache (Caffeine, a
`Map`) or a shared store (Redis) is simpler and more correct than a one-size-fits-all L2.

What Kormium gives you is the *mechanism* a correct cache needs:

- the `WriteListener` commit hook (`db.writeListeners`) — fired after a commit with the tables it
  wrote, so you can evict the affected entries;
- cross-process invalidation via `db.connectNotifications(transport)` — so a write on one instance
  evicts caches on the others (the part most home-grown caches get wrong).

```kotlin
val reg = db.connectNotifications(
    postgresListenNotifyTransport(host, port, database, user, password),
)
db.writeListeners.add { tables ->
    if ("products" in tables) productCache.invalidateAll()
}
```

Guidance for a cache built this way:

- always add a **TTL** — notification delivery is best-effort, so a dropped signal must not pin a
  stale entry forever;
- invalidation is **table-granular** (evict the table's region), not per-row;
- for a single instance you can skip the transport (local `writeListeners` is enough); add one only
  when you run more than one instance;
- the `cross-instance-cache` sample shows the full pattern with a Redis transport.

## Testing Strategy

Recommended test layers:

- unit tests for SQL rendering and small repository helpers;
- integration tests against the real backend;
- migration tests from old schema to new schema;
- transaction rollback tests;
- concurrency tests for hot paths;
- failure tests for duplicate keys, foreign keys, not-null and connection loss.

SQLite in-memory tests are useful, but they do not replace PostgreSQL tests for PostgreSQL
applications.

## Release Discipline

For applications using Kormium pre-1.0:

- read the Kormium changelog before upgrades;
- upgrade in small steps;
- run migrations in staging;
- run focused integration tests for every backend you use;
- avoid unreviewed dependency bumps in persistence code.

## When Kormium Becomes Recommendable

For broad production recommendation, Kormium should have:

- documented API stability policy;
- compatibility matrix by version;
- stronger integration/concurrency test matrix;
- production-grade observability story;
- migration locking guidance;
- more backend-specific troubleshooting docs.
