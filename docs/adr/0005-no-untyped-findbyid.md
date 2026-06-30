# ADR 0005 — No untyped `findById`; single-row reads via typed `findOne`

- Status: Accepted
- Date: 2026-06-30

## Context

`findById(id: Any)` was the single read in the API that took an untyped argument. Kormium's whole
pitch is a typed DSL where a wrong column or wrong value is a **compile error** — but `findById`
quietly accepted anything: `Users.findById("not-a-uuid")` for a `Uuid` key compiled and only failed
at runtime (or, on a lax engine, silently matched nothing). It also bound the raw `id` directly,
bypassing the primary-key column's converter (unlike `Users.id eq id`, which binds through it), and
threw at runtime on a composite key.

A typed alternative already existed and is strictly safer: `find(Query(Users.id eq id))`. The id is
checked against the column's type at compile time and bound through its converter. Its only gap vs
`findById` was the return shape — `find` returns `List<T>`, while `findById` returned `T?`.

This surfaced during an audit of the compiler messages agents hit (the most common mistake — a
wrong-typed predicate value — plus this untyped-id hole). `findById` is exactly the kind of
convenience that reads well but defeats the type-safety that makes the DSL worth using, especially
for AI agents that reach for the obvious `findById(x)` and get no feedback on a wrong id.

## Decision

Remove `findById` entirely (no deprecation — the project is pre-1.0). Add a typed single-row read:

```kotlin
fun <T : Entity> Table<G, T>.findOne(query: Query): T?                       // LIMIT 1, or null
fun <T : Entity> Table<G, T>.findOne(block: QueryBuilder.() -> Unit): T?     // block form
```

By-id becomes `Users.findOne { where { Users.id eq id } }` (or `findOne(Query(Users.id eq id))`):

- the id is **type-checked** against the column and **bound through its converter**;
- it reads by **any unique column**, not only the primary key (`findOne { where { Users.email eq e } }`);
- composite keys are just an `and` of `eq`s — no special-cased runtime throw;
- `LIMIT 1` is applied, so it is at least as efficient as the old direct-by-pk SQL.

A generic repository that needs a by-id method carries the typed primary-key column instead of
relying on `Any` (see `samples/repository`):

```kotlin
abstract class Repository<G : Catalog, T : Entity, ID>(
    db: SuspendDatabase<G>, table: Table<G, T>, private val idColumn: Column<ID, *, T>,
) {
    suspend fun findById(id: ID): T? = read { table.findOne { where { idColumn eq id } } }
}
```

## Consequences

Positive:

- The read API is fully typed — no `Any` escape; a wrong-typed id is a compile error.
- `findOne` subsumes `findById` (by-pk is just one predicate) and generalizes to any unique column.
- The id binds through the column converter, like every other comparison — one consistent path.

Negative / costs:

- Breaking change: every `findById(id)` call site migrates to `findOne { where { col eq id } }`.
  Mechanical, and the explicit column is more readable (and what an agent already knows from the
  schema). Done across all tests, samples and docs in the same change.
- A generic, schema-agnostic "by id" helper now needs the PK column passed in (typed `ID`), rather
  than a bare `Any`. This is the typed repository pattern — a feature, not a workaround.

## Alternatives considered

- **Keep `findById`, document the gap.** Rejected: it leaves an untyped hole in a "typed DSL" and is
  precisely the footgun the audit set out to remove.
- **Type `findById` via a third `Table<G, T, ID>` type parameter.** Rejected as too invasive — it
  changes every `object Users : Table<App, User>(…)` declaration. `findOne` gets the type-safety
  with no new generic on `Table`, by naming the column at the call site.
- **Deprecate rather than remove.** Unnecessary pre-1.0; a clean removal avoids carrying a known
  footgun into the API surface.

## Notes

Removing `findById` came out of the same audit that narrowed `eq null` / `neq null` to nullable
columns (so a wrong-typed value comparison reports against the real `eq(value)` candidate, not the
`Nothing?` null overload). Both are small, deliberate moves toward "the obvious wrong thing does not
compile" — the property the whole DSL is built to provide. Related: [[korm-ai-oriented-direction]].
