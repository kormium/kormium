# Architecture Decision Records

Short records of significant, hard-to-reverse architectural decisions: the context, the choice,
and its consequences. One file per decision, numbered and append-only — supersede rather than
rewrite.

- [0001 — Concrete dialects live in standalone modules](0001-standalone-dialect-modules.md)
- [0002 — No `ilike`; case-insensitive matching is explicit via `lower()`](0002-no-ilike-explicit-lower.md)
- [0003 — `dialect` is a public member of `Database` / `SuspendDatabase`](0003-public-dialect-on-database.md)
- [0004 — Subqueries: correlated `EXISTS` via `any` / `none`, not a subquery-as-value](0004-correlated-exists-any-none.md)
- [0006 — No idiomatic-path nudge (no `@RequiresOptIn` marker, no detekt rule)](0006-no-idiomatic-path-nudge.md)
