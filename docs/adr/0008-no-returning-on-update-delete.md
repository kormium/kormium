# ADR 0008 — No `RETURNING` on `UPDATE` / `DELETE`

- Status: Accepted
- Date: 2026-07-01

## Context

`INSERT ... RETURNING` is supported (via `insert(returning = true)` / `upsert(returning = true)`),
so a write can hand back DB-generated values. A natural extension is `RETURNING` on `UPDATE` and
`DELETE` — "update and return the new row", "delete and return what was removed" (audit / outbox).

PostgreSQL and SQLite (3.35+) support `UPDATE … RETURNING` / `DELETE … RETURNING`. **MySQL does not
have `RETURNING` at all** (`Dialect.supportsReturning = false`). For `INSERT` that gap is bridged by
re-selecting the written row by primary key. The same trick does **not** carry over:

- `DELETE … RETURNING` cannot be emulated by a re-select — the rows are gone after the delete.
- `UPDATE … RETURNING` would need a three-step dance in one transaction: `SELECT` the matching PKs →
  `UPDATE` → `SELECT … WHERE pk IN (…)`. Multiple statements, a single-column-PK requirement, and
  subtle semantics if the predicate touches an updated column.

This was implemented once early on and then dropped (no ADR was written — this records it).

## Decision

Do **not** model `RETURNING` on `UPDATE` / `DELETE`. `update { }` / `update(entity)` and
`deleteWhere { }` return the **affected-row count** (`Long`), nothing more.

To get the rows, be explicit, inside one transaction:

```kotlin
db.transaction {
    Users.update(patch) { where { Users.id eq id } }
    Users.findOne { where { Users.id eq id } }          // the updated row
}
db.transaction {
    val doomed = Users.find { where { Users.status eq "expired" } }   // capture first
    Users.deleteWhere { where { Users.status eq "expired" } }
    doomed                                                // what was removed
}
```

On PostgreSQL / SQLite, a one-statement `… RETURNING` is available through `RawExpression` /
`execute(...)` for callers who specifically want it (both require
`@OptIn(DelicateKormiumApi::class)`; `execute` also requires `params`/`invalidates` explicitly).

## Consequences

Positive:

- **No hidden multi-statement magic.** Kormium's contract is "the SQL is exactly what the DSL
  renders"; emulating `UPDATE … RETURNING` as select→update→select (or `DELETE` as select→delete)
  would render *several* statements behind one call — the opposite of that contract.
- **Uniform across engines.** No dialect-gated method that works on PostgreSQL/SQLite and throws on
  MySQL — every write operation behaves the same on all three backends.
- The explicit two-step form is clear, portable, and (in a transaction) race-safe.

Negative / costs:

- A one-statement atomic "modify and return" isn't first-class; the two-step form is two statements
  (still one transaction). For the rare case where the single-statement form matters on
  PostgreSQL/SQLite, drop to `RawExpression` / `execute(...)`.

## Alternatives considered

- **A — Emulate to stay uniform** (native `RETURNING` on PG/SQLite; MySQL via select-before-delete /
  capture-PK→update→re-select, mirroring how `insert(returning)` re-selects). Rejected: it bakes
  hidden multi-statement behaviour into one call, which contradicts "the SQL is what the DSL renders".
  The `insert(returning)` precedent is *milder* — a single re-select of a row you just created by a
  PK you supplied — and was accepted only because `INSERT … RETURNING` is so common; it does not
  justify the heavier `UPDATE`/`DELETE` emulation.
- **B — Dialect-gated, throwing on MySQL.** Rejected: it would be the first operation whose behaviour
  is not uniform across engines (works on PG/SQLite, throws on MySQL), breaking a core property.

## Notes

`INSERT … RETURNING` stays (already shipped): its emulation is a single by-PK re-select, mild enough
that the convenience won. The asymmetry is deliberate, not an oversight. Same reasoning family as
[ADR 0004](0004-correlated-exists-any-none.md) (don't model what doesn't fit cleanly) and
[ADR 0007](0007-concurrency-conflict-exception.md) (no hidden control flow in the library). Related:
[[korm-ai-oriented-direction]].
