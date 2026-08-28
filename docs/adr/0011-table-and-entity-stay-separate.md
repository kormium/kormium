# ADR 0011 — `Table` and `Entity` stay separate types

- Status: Accepted
- Date: 2026-08-28

## Context

Users ask, recurrently, whether `Table` and `Entity` can be collapsed into one declaration. The
duplication they point at is real and easy to see:

```kotlin
object Users : Table<Main, User>("users", ::User) {
    val id by Column.Long().primaryKey()
    val name by Column.Text()
    val email by Column.Text()
}

class User : Entity() {
    var id by Users.id
    var name by Users.name
    var email by Users.email
}
```

The column list is written twice, and in an application that also has a domain type and a REST DTO
the same field names appear twice more. The question is fair, and it is asked often enough that the
answer should be a document rather than a maintainer's preference.

Two things frame the answer. First, what the entity is *for*: it is not a data holder but a
tri-state field store, distinguishing **absent** (never assigned — omitted from `INSERT`/`UPDATE`),
**explicit null** (assigned `null` — written as SQL `NULL`) and a concrete value. Partial writes
rest on that distinction: `update` writes exactly the fields you assigned, which is why concurrent
partial updates do not clobber each other. A plain `data class` cannot express "this field was not
set".

Second, the entity block is not copy-paste but a **binding the compiler checks**. `var name by
Users.name` ties the property to a column of a known type; rename the column or change its type and
the entity stops compiling, pointing at the line. Removing that block removes a check, not just
characters.

## Decision

`Table` and `Entity` remain separate types. Kormium does not ship a merged form, a generated
mirror, or a blessed shared entity type.

The direct blocker is a **type collision on a single property**. `Users.age` must be
`Column<Int, …>`, because that is what the DSL is built from (`where { Users.age gtEq 18 }`).
`user.age` must be `Int`, because that is what an entity is for. In a merged declaration these are
one property with one type, so one of the two has to go — and each is load-bearing.

Everything else compounds it:

- **Table state is per instance.** The column registry (`fieldDisplayName`), the ordinal assignment
  in `addColumn`, and the derived caches live on the table object. If a row were a `Table` instance,
  that registry would be rebuilt for every row read. Today a table is one singleton and a row is one
  `Array<Any?>` indexed by ordinal; the hot-path costs the benchmarks measure depend on that split.
- **The generics become self-referential.** `Column<Z, T : Table<*, N>, N : Entity>` would turn into
  an F-bounded `Table<G, Self : Table<G, Self>>`, and the recursion would propagate into `find`,
  joins and aggregates — precisely where inference is already doing the most work.
- **It breaks a pinned invariant.** One entity type may back columns of several tables; that is not
  incidental, it is fixed by `SharedEntityTypeTest`, whose comment constrains how field storage may
  be represented. A merged type makes the invariant meaningless.

## Consequences

- Declaring a table means declaring its entity: two blocks per table, the second checked against the
  first at compile time.
- The reasons above are structural, not stylistic. Anyone reopening this should engage with the
  property-type collision first — the rest follows from it.
- Users who do not need tri-state semantics can read straight into their own type with a
  `select(...)` projection. That is documented as an escape hatch, not as a second path; see
  [ADR 0012](0012-no-dto-first-path.md), which records that decision and its cost analysis.

## Alternatives considered

**Merge via a self type** (`Table<G, Self>`). Rejected: the property-type collision above is not
resolvable, and the generics/state/invariant costs land on top of it.

**Interface plus runtime proxy** (Ktorm's `interface Entity<E>`). The user declares one interface and
the library synthesizes the implementation. Rejected: it needs `java.lang.reflect.Proxy`, which does
not exist on Kotlin/Native or Wasm — half of Kormium's stated targets.

**DAO with a companion object** (Exposed's `Entity` + `EntityClass`). Rejected on two counts: it is
still two declarations, so it does not answer the request; and it presupposes a persistence context
with dirty checking, which Kormium deliberately does not have ("no session, no lazy loading" —
behaviour is local, the SQL is what the DSL renders).

**Generate the mirror with KSP.** Technically the cheapest option: the user writes the table, an
annotation processor emits the entity, core is untouched and there is no runtime cost. Rejected on
project principles rather than on mechanics — explicitness over implicitness, and no annotation
processor in the build. It is worth recording that this rejection is a values choice: if the
project's stance on codegen ever changes, this is the option that becomes available, and nothing in
the core would need to move for it.

**Ship a shared `Row : Entity()` in core**, so a table could be declared as
`Table<App, Row>("users", ::Row)` with no user-declared entity at all. Rejected. It saves N−1 empty
lines across a project, and costs the per-table entity typing: with every table sharing one entity
type, `Column<*, *, Row>` matches any table's column, so the compile-time check on
`upsert(onConflict = …)`, `insertOrIgnore(onConflict = …)`, `isSet` and `unset` stops distinguishing
tables. Gating it behind a `@RequiresOptIn` marker (as raw SQL is gated per [ADR 0009](0009-delicate-raw-sql-optin.md))
was considered and does not change the trade: the marker fires where the table is declared, while
the loss shows up much later, as a *missing* error at an `upsert` call site. A user who genuinely
wants no entity properties can already declare an empty `class UserRow : Entity()` — one line per
table, and every check stays intact. That is strictly better than the shared version, and it needs
nothing from the library.

## Notes

This ADR covers the type-level question only. The related question — whether Kormium should support
reading and writing through the user's own types as a first-class path, so the entity layer can be
skipped in an application that already has a domain type and a DTO — is decided separately in
[ADR 0012](0012-no-dto-first-path.md).
