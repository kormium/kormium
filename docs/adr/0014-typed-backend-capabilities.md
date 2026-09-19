# ADR 0014 — Backend capabilities are typed with a phantom tag on the scope

- Status: Accepted
- Date: 2026-09-20

## Context

Kormium's DSL renders the **intersection** of what PostgreSQL, MySQL/MariaDB and SQLite can do.
That keeps `Users.find { … }` portable, and it is why the "Unsupported / Out-of-Scope SQL" list in
[queries.md](../queries.md) is as long as it is. The only way out has been
`@OptIn(DelicateKormiumApi::class)` plus raw SQL — a cliff, not a step: you lose typed columns and
entity mapping all at once.

[Issue #162](https://github.com/kormium/kormium/issues/162) asked for `FOR UPDATE` / `NOWAIT` /
`SKIP LOCKED`, which forced the question. Row locking exists on Postgres and MySQL and does **not**
exist on SQLite, so whatever we built had to answer: what happens when a query asks for a clause
the backend cannot run?

Rendering nothing is the dangerous answer. A `SKIP LOCKED` that quietly disappears on SQLite turns
a work queue into two workers processing the same job — the exact bug the clause was added to
prevent. Throwing at render time is correct but late. The prize, if the capability can be carried
in a type, is bigger than this one feature: it is the path to `DISTINCT ON`, `ILIKE`, `jsonb`
operators, MySQL hints and everything else currently priced at "drop to raw SQL".

Note that the trend had already started without a framework: pgvector (`Column.Vector`,
`distance(...)`) is Postgres-only, lives in `kormium-postgres-dialect`, and is unguarded — declare
such a column against SQLite and it fails at runtime.

## Decision

Carry the capability as a **phantom type parameter on the scope and the query builder**, and put
the tag on the **driver**, not on the catalog.

```kotlin
// core: the tags, and the gated DSL declared once
public interface Backend
public interface AnyBackend : Backend           // the portable baseline
public interface RowLockingBackend : Backend    // a capability

public class ScopeOf<G : Catalog, out B : Backend>
public class SelectQueryBuilderOf<out B : Backend> : QueryBuilderOf<B>()

public fun SelectQueryBuilderOf<RowLockingBackend>.forUpdate(wait: LockWait = LockWait.Wait)

public typealias Scope<G> = ScopeOf<G, AnyBackend>          // every existing reference keeps working
public typealias QueryBuilder = QueryBuilderOf<AnyBackend>
```

```kotlin
// a dialect module: the tag, plus entry points that hand out a scope carrying it
public interface PostgresBackend : AnyBackend, RowLockingBackend

public interface PostgresDatabase<out G : Catalog> : Database<G> {
    public fun <R> transaction(
        isolation: TransactionIsolation? = null,
        readOnly: Boolean = false,
        block: ScopeOf<@UnsafeVariance G, PostgresBackend>.() -> R,
    ): R = runTransaction(isolation, readOnly, block)
}
```

Four properties follow, and each one was a requirement:

1. **The catalog is untouched.** `Catalog` stays a logical identity — many databases may share one
   (sharding), and one schema may run on Postgres in production and SQLite in tests. Tagging the
   *catalog* with a backend would have made that inexpressible, and `Table<G, T>` — the largest
   surface in user code — would have had to change. It does not.
2. **The tag is proof, not a promise.** A user can write `object App : Catalog` and claim anything;
   nobody can make `PostgresDriver` be SQLite.
3. **Opt-in by declaration.** `Database<App>` behaves exactly as before. Writing
   `PostgresDatabase<App>` is what opens the extra syntax, and widening back to `Database<App>` is
   how a portable helper says "I must keep working on every backend".
4. **Capabilities, not engines, gate the DSL.** `forUpdate` hangs off `RowLockingBackend`, which
   both `PostgresBackend` and `MySqlBackend` extend — so the DSL is declared once in core, and a
   dialect module contributes a tag plus `Dialect.renderRowLock`, not a copy of the DSL.

The entry points are **members** of `PostgresDatabase` rather than extensions. A member wins
overload resolution against the core `Database<G>.transaction` extension, so the same call site
gets the richer scope when the static type is known and the portable one otherwise. Their parameter
lists must mirror the core extensions' exactly, or calls using named arguments break.

Three seams in core are opened for dialect modules, all behind `@RequiresOptIn`
(`KormiumDialectApi`): `runTransaction` / `runAutocommit` (so the transaction machinery — pinning,
dirty-table collection, write notification — is not duplicated per backend), `renderSqlWith` (the
offline renderer, which is how backend DSL is tested without a server), and
`SelectQueryBuilderOf.rowLock`. The opt-in also stops application code from writing a lock directly
and bypassing the compile-time check.

`Dialect.renderRowLock` **throws by default**. The type system covers what it can; the throwing
default covers what it cannot — a MySQL server older than 8.0.1, or someone using the seams.

## Two things the compiler taught us

Both were found by building the thing, not by reasoning about it, and both killed a design that
looked correct on paper.

**`@DslMarker` forbids the obvious design.** The first shape put the gated DSL on the scope, as a
member extension (`fun QueryBuilder.forUpdate()` inside `PgScope`) — the same idiom Kormium already
uses for `Table<G, T>.find`. It cannot work here: a member extension needs *two* implicit receivers,
and `KormiumDsl` exists precisely to stop an inner block from reaching the outer one. Every call
site failed with *"cannot be called in this context with an implicit receiver"*. Tagging the
builder instead makes the receiver the nearest one, which is what the marker wants.

**A more specific receiver does not win if the lambda is contravariant.** Declaring the Postgres
entry point as an *extension* (`PostgresDatabase<G>.transaction`) is ambiguous against the core
extension: `PostgresDatabase<G>` is more specific as a receiver, but `Scope<G>.() -> R` is more
specific as a parameter (function receivers are contravariant), so neither candidate wins. Hence
members.

## Consequences

- **Source-compatible, binary-incompatible.** The typealiases keep every existing `Scope<G>` /
  `QueryBuilder` reference compiling — verified across `kormium-observe`, the Ktor modules and the
  samples — but `Scope`, `SuspendScope`, `QueryBuilder` and `RenderScope` no longer exist as JVM
  classes under those names. Consumers must recompile; a stale binary gets `NoClassDefFoundError`.
  Acceptable pre-1.0, and it is the reason to do this now rather than later.
- **A raw driver handle loses catalog inference.** `PostgresDriver` is `PostgresDatabase<Nothing>`,
  and the member fixes the catalog to the interface's own parameter instead of inferring it at the
  call site the way the core extension did. So `db.transaction { Users.find { … } }` needs the
  handle pinned — `val db: PostgresDatabase<App> = createDatabase(…)`. Pinning is already the
  documented idiom, so the cost is one word, but it is a behaviour change.
- **Each dialect mirrors four entry points** (`transaction`, `autocommit`, and the two suspend
  ones), plus an offline-render helper. Small and mechanical, but it grows with the number of
  backends, and `RenderScope` needs the same tag as the executing scopes.
- **The portable core stays portable.** Nothing about the default path changed: `Database<G>`
  renders exactly the SQL it rendered before.
- **The capability list is now the extension point.** Adding `DISTINCT ON`, `ILIKE` or MySQL hints
  means adding a tag and an extension on `QueryBuilderOf<ThatCapability>` — no core surgery, and no
  new decision to make.

## Alternatives considered

- **Capability markers on `Catalog`** (`object App : Catalog, RowLocking`). Rejected: the catalog
  is a logical identity, not an engine — sharding and "Postgres in prod, SQLite in tests" both stop
  working. It is also only a user's assertion, and `Database<Nothing>` satisfies *any* constraint
  vacuously, so the check evaporates exactly where typing is weakest.
- **The whole DSL inside dialect modules** (à la pgvector). Rejected: it gives no compile-time
  safety at all — the dialect is a runtime property of `Database`, so a Postgres-only import
  compiles fine in code that runs on SQLite — and it duplicates identical DSL across every backend
  that shares the feature.
- **A raw SQL tail on `Query`** (`Query(rawTail = "FOR UPDATE")`). Rejected: no types, nowhere to
  validate, and it is a public string-injection channel into a statement builder whose entire point
  is that values go through `ParamBuilder`.
- **Runtime-only rejection**, with no tag at all. This remains the fallback for what types cannot
  see (server versions) and is still implemented, but as the *only* mechanism it reports the
  problem at the moment the query runs — typically in production, on the backend that was not
  developed against.

## Notes

`Dialect.renderRowLock` defaulting to a throw is deliberate and differs from
`supportsTransactionIsolation`, which silently ignores an unsupported level. That is safe because
SQLite's single level is effectively `SERIALIZABLE` — degrading is conservative. A dropped lock
degrades in the dangerous direction, so it must fail.
