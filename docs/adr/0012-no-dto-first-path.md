# ADR 0012 — No DTO-first path; projections stay an escape hatch

- Status: Accepted
- Date: 2026-08-28

## Context

[ADR 0011](0011-table-and-entity-stay-separate.md) settles that `Table` and `Entity` stay separate
types. It does not settle the request behind the question, which is about **layer count**: an
application with a domain type and a REST DTO writes the same field list four or five times —
columns in the table, delegates in the entity, fields in the DTO, and a mapping in each direction.

The entity is the layer Kormium owns, so the natural proposal is to make it optional: let the user
keep their own type and read and write through it directly. Most of the machinery already exists.
Reads have `select(...) { row -> … }` projections; `update { }` and `deleteWhere` already work
without an entity. The gaps were narrow enough to look cheap:

- reads had no `ORDER BY` / `LIMIT` / `OFFSET` on projections;
- the write path required an entity in **all four** of `insert`, `insertAll`, `upsert` and
  `insertOrIgnore` — and every one of them funnels through the same `generatePresentFields` seam, so
  builder forms taking assignments would have been a mechanical addition, not new SQL;
- `Table<G, T : Entity>` demands an entity type even when none is used;
- the mapping lambda is repeated at every call site, which a reusable projection object would fix.

Taken together that is a coherent feature. It was costed before being built.

## Decision

Kormium does not build a DTO-first path. Specifically: no reusable projection object, no entity-free
builder family for the insert/upsert/insertOrIgnore/insertAll group, and no table declaration
without an entity type.

`select(...)` projections remain available and are documented as an escape hatch for reading into
your own type — useful for read-only endpoints, explicitly not a second first-class path.

The reason is arithmetic. Counting how many times the field list is written:

| | entity path | DTO-first path |
| --- | --- | --- |
| columns in the table | 1 | 1 |
| entity delegates | 1 | — |
| the user's own type | 1 | 1 |
| read mapping | 1 | 1 |
| write mapping | 1 | 1 |
| **total** | **5** | **4** |

The path saves **one block out of five**. And the block it removes is the compiler-checked binding
(`var name by Users.name`), while the two it keeps are hand-written mappings that nothing checks:

```kotlin
select(Users.name, Users.email) { UserDto(row[Users.email], row[Users.name]) }  // compiles; wrong
```

Both fields are `String`, so the swap is silent. The entity path has one such site per field; the
DTO-first path has two, read and write. Paying a second API path to trade one checked block for two
unchecked ones, at a 20% saving in declarations, is a bad trade for a project whose central claim is
that the compiler catches this class of mistake.

Two secondary costs reinforce it. Every operation is written three times — `Scope`, `SuspendScope`
and `RenderScope` all mirror the same surface — so the write-builder family alone is a dozen
signatures plus a test matrix across four backends. And a second path has to be explained: the
"guessable, consistent API" the project offers to coding agents becomes a fork where a model must
choose, which is exactly the situation `AGENTS.md` exists to avoid.

## Consequences

- An application whose DTO differs from its row shape keeps its mappings. That is a real cost, and
  it is the user's architecture, not something the ORM should decide for them.
- An application whose DTO is identical to its row shape has one redundant layer. The layer to
  reconsider is one of its own — only Kormium's entity is checked against the schema.
- Projections stay documented for reads into a user type, with their limits stated.
- Reopening this should start from the table above. If the count changes — for instance because the
  mapping stops being hand-written — the conclusion may change with it.

## Alternatives considered

**A reusable projection object**, binding columns and a mapper once
(`Users.projection(Users.id, Users.name) { … }`) and reusing it across queries. This was the
strongest version of the idea, because repeated call-site mapping is the DTO path's real cliff.
Rejected as part of the whole: without the write builders it does not make the path usable, and with
them it is the second path this ADR declines. Worth noting it does not beat what a user can already
write themselves — `fun User.toDto() = UserDto(id = id, name = name)` is one line, needs nothing
from the library, and with named arguments is safer than a positional column list.

**A typed mapper helper** (`Users.mapper(::UserDto, Users.id, Users.name)`, checked for arity and
types against the constructor). Rejected: it costs N arity overloads and still loses to the
hand-written extension function above on both brevity and safety.

**Entity as a transport type** — a `KSerializer` built from table metadata (`Table.columns` plus
`ColumnType`), with no reflection, so it would work on every target. This is the only option that
actually *deletes* a layer rather than shortening a mapping: for simple CRUD the DTO disappears
instead of the entity. **Deferred, not rejected.** Against it: it couples the wire contract to the
schema, so the API changes shape whenever a column does — which is the reason most applications keep
a DTO in the first place; and it contradicts what `Entity`'s own documentation currently says
("an entity is not a DTO … map entities to your own DTOs for transport"). It would revisit on a
concrete signal: the same request arriving from several independent users, with cases where the row
shape genuinely is the contract. It would ship as a separate opt-in artifact, never as core
behaviour.

## Notes

Two features that surfaced during this analysis were shipped, both justified on their own terms
rather than as DTO support, and both would have been worth doing had this ADR never been written:

- `ORDER BY` / `LIMIT` / `OFFSET` on joins and grouped queries — before it, top-N by aggregate
  (`GROUP BY … ORDER BY SUM(…) DESC LIMIT 10`) had no expression in the DSL at all;
- the expression form of `upsert`'s `DO UPDATE` half — before it, the atomic counter
  (`SET hits = hits + 1`) required a read followed by a write.

A third idea from the same discussion, an `insert { }` builder with expression values, was **not**
taken: in a plain `INSERT` an expression has nothing to reference (there is no row yet), and the
functions that would justify it — `now()`, `gen_random_uuid()` — do not exist in the DSL. If
zero-argument SQL function expressions are ever added, `insert { }` acquires a real use case and
should be reconsidered then, in that order.
