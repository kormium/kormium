# Architecture Decision Records

Short records of significant, hard-to-reverse architectural decisions: the context, the choice,
and its consequences. One file per decision, numbered and append-only — supersede rather than
rewrite.

- [0001 — Concrete dialects live in standalone modules](0001-standalone-dialect-modules.md)
- [0002 — No `ilike`; case-insensitive matching is explicit via `lower()`](0002-no-ilike-explicit-lower.md)
- [0003 — `dialect` is a public member of `Database` / `SuspendDatabase`](0003-public-dialect-on-database.md)
- [0004 — Subqueries: correlated `EXISTS` via `any` / `none`, not a subquery-as-value](0004-correlated-exists-any-none.md)
- [0005 — No untyped `findById`; single-row reads via typed `findOne`](0005-no-untyped-findbyid.md)
- [0006 — No idiomatic-path nudge (no `@RequiresOptIn` marker, no detekt rule)](0006-no-idiomatic-path-nudge.md)
- [0007 — `ConcurrencyConflictException` as a typed signal, not a retry helper](0007-concurrency-conflict-exception.md)
- [0008 — No `RETURNING` on `UPDATE` / `DELETE`](0008-no-returning-on-update-delete.md)
- [0009 — Raw SQL requires `@OptIn(DelicateKormiumApi::class)` (supersedes 0006 in part)](0009-delicate-raw-sql-optin.md)
- [0010 — Browser SQLite ships as three engines; OPFS reader pool is experimental](0010-browser-sqlite-three-engines.md)
- [0011 — `Table` and `Entity` stay separate types](0011-table-and-entity-stay-separate.md)
- [0012 — No DTO-first path; projections stay an escape hatch](0012-no-dto-first-path.md)
