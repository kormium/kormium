# ADR 0001 — Concrete dialects live in standalone modules

- Status: Accepted
- Date: 2026-06-23

## Context

`Dialect` is the SQL-rendering SPI (quote identifiers, render binds, LIMIT/OFFSET, upsert tail,
read-only toggle, …). The interface and the neutral `StandardDialect` live in `kormium-core`.

The concrete implementations — `PostgresDialect`, `SqliteDialect`, `MySqlDialect` — were bundled
inside the respective **driver** modules (`kormium-postgres`, `kormium-sqlite`, `kormium-mysql`),
in `commonMain`, alongside an `expect fun createDatabase(...)` whose `actual`s are the
JDBC / libpq / libmariadb connections.

Bringing Kormium to Kotlin/JS and Kotlin/Wasm surfaced a problem: the new web engines (a browser
SQLite engine, Node engines for sqlite/pg/mysql, the out-of-repo PGlite engine) need the concrete
dialect on `js`/`wasmJs`, but the driver modules **cannot** add those targets. A Kotlin
Multiplatform module must supply an `actual` for every target it compiles to, and there is no
implementable `actual createDatabase(): PostgresDriver` on wasm (no TCP in the browser; the return
type is a live server connection). So the pure dialect is held hostage by the platform driver it
ships next to.

A hard constraint from the project: **`kormium-core` must not know about concrete dialects.** Core
owns the `Dialect` *abstraction* only; specific dialects must stay pluggable and must not leak in.

## Decision

Each backend's concrete dialect lives in its own **pure** module:

```
kormium-core                interface Dialect (SPI only)
   ▲
kormium-<db>-dialect        object <Db>Dialect — rendering only, NO expect, all needed targets
   ▲ (api)              ▲ (implementation)
kormium-<db>            web engines (browser/Node)
   createDatabase + JDBC/native driver (jvm/native/android)
```

- `kormium-sqlite-dialect`, `kormium-postgres-dialect`, `kormium-mysql-dialect` contain only the
  dialect object. They depend on `kormium-core` (for the `Dialect` interface) and nothing
  backend-specific. Having no `expect`, they compile to every target — including `js`/`wasmJs`/
  `wasmWasi`. Their target set mirrors their backend's reach plus the web stack.
- The driver module depends on its dialect module with `api(project(":kormium-<db>-dialect"))`, so
  the public symbol (`io.github.kormium.<Db>Dialect`, package unchanged) stays visible to existing
  consumers exactly as before, transitively.
- Web engines depend on the `*-dialect` module and reuse the one canonical dialect.

## Consequences

Positive:

- Dialects compile everywhere; web engines reuse them with zero duplication.
- `kormium-core` stays dialect-agnostic — only the SPI lives there.
- A dialect is now a first-class, self-contained unit. Writing a new dialect (in-tree or by a third
  party) means a small module depending on `kormium-core`, touching nothing else.
- Non-breaking for existing users: same package/FQN, re-exported via `api` from the driver module.

Negative / costs:

- Three extra published modules.
- The concrete dialect symbol now ships in a different artifact (`kormium-<db>-dialect` instead of
  `kormium-<db>`). Not a source break (transitively visible), but a coordinate change worth a
  changelog note for anyone depending on the dialect artifact directly.

## Alternatives considered

- **Move concrete dialects into `kormium-core`.** Rejected: it would make core know about specific
  databases — the exact leak the project forbids. (This was the first proposal and was turned down.)
- **Source-set reorg inside the driver module:** keep the dialect in `commonMain`, move
  `expect createDatabase` into a driver-only intermediate source set, and add web targets to the
  driver module. Viable and moves no public symbols, but gives the "postgres *driver*" module a
  wasm artifact that contains only a dialect — a muddier module identity. Kept as the lighter
  fallback; not chosen.
- **Duplicate the dialect inside each web engine** (as the PGlite engine first did with a local
  `PGliteDialect`). Rejected for the in-tree engines: N copies of the same rendering that drift.

## Notes

The out-of-repo PGlite engine (`github.com/kormium/pglite`) keeps a local `PGliteDialect` copy for
now, because pulling a wasmJs `kormium-postgres-dialect` across the repo boundary needs it published
first; it can switch to `kormium-postgres-dialect` once that ships.
