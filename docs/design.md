# Design

This page explains the internal shape of Kormium so contributors can reason about changes
without first reading every module.

## Design Goals

Kormium optimizes for:

- a type-safe Kotlin API that still maps clearly to SQL;
- shared table/query definitions across JVM and Kotlin/Native;
- backend implementations that can differ in I/O model without changing user DSL;
- explicit transaction scopes;
- small public abstractions that are easy to test.

It does not try to hide the database. Users should still understand tables, SQL predicates,
transactions and indexes.

## Module Boundaries

| Module | Responsibility |
| --- | --- |
| `kormium-core` | DSL, table/entity model, query rendering contracts and scopes |
| `kormium-<db>-dialect` | The pure SQL dialect per database (`postgres`/`sqlite`/`mysql`), compiled to every target — see [ADR 0001](adr/0001-standalone-dialect-modules.md) |
| `kormium-postgres` | PostgreSQL backend factories for JDBC/libpq |
| `kormium-mysql` | MySQL / MariaDB backend factories for JDBC/libmariadb |
| `kormium-sqlite` | SQLite backend factories for sqlite-jdbc/sqlite3/AndroidX SQLite |
| `kormium-r2dbc` | Async PostgreSQL and MySQL `SuspendDatabase` implementations |
| `kormium-{sqlite-wasm,sqlite-node,postgres-node,mysql-node}` | Browser / Node engines (suspend-only), sharing `kormium-wasm-driver` |
| `kormium-migrate` | Ordered, checksummed migration runner over raw SQL execution |
| `kormium-observe` | Reactive `Flow` queries over core's `WriteListener` commit hook |
| `kormium-jdbc` | Shared JVM JDBC execution, pooling and named parameter binding |
| `kormium-ktor*` | Server integration over the suspend API |

The core module cannot depend on a concrete backend. Backends depend on core and provide
execution.

## Catalog Tags

`Catalog` is a phantom type used for compile-time database/table safety:

```kotlin
object Main : Catalog
object Users : Table<Main, User>("users", ::User)

val db: Database<Main> = createDatabase(/* ... */)
```

`Database<out G>` and `SuspendDatabase<out G>` are covariant. Backend factories can return
a catalog-agnostic driver, and user code pins the catalog by assignment.

This is what lets the compiler reject a `Table<Cache, *>` inside a `Database<Main>` scope.

## Table and Entity Model

`Table<G, T>` owns:

- the SQL table name;
- registered columns;
- pure SQL builders for select/insert/update/delete;
- thin blocking and suspend runners.

`Entity` owns internal row state. Column property delegates read and write that state; the
backing storage is not public API.

This design keeps entity construction cheap and makes partial updates explicit:

- absent field: omit from `UPDATE`;
- present field with `null`: set the column to SQL `NULL`.

## SQL Rendering

Core SQL generation is split from execution.

`Dialect` handles SQL syntax differences:

- identifier quoting;
- bind placeholder rendering;
- limit/offset rendering.

`ParamBuilder` collects bind parameters while expressions render themselves. A predicate
like `Users.age gtEq 18` renders a column identifier on the left and a generated bind
placeholder on the right.

`TypeMapper` converts values:

- `toParameter` maps Kotlin values to the driver's parameter representation;
- `fromResult` maps a result-set column back into the Kotlin type for a Kormium column.

The rule is simple: identifiers are rendered by the dialect, values are bound as parameters.

## Execution Surfaces

Kormium has two sibling database interfaces:

```kotlin
interface Database<out G : Catalog>
interface SuspendDatabase<out G : Catalog>
```

They are siblings, not parent/child types. A true async backend such as r2dbc cannot expose
a meaningful blocking `Database`, so it implements only `SuspendDatabase`.

Blocking backends can implement both:

- blocking calls use `transaction { }` and `autocommit { }`;
- suspend calls use `suspendTransaction { }` and `suspendAutocommit { }`.

On JVM, blocking suspend work is offloaded to virtual threads. On Native, it is offloaded to
`Dispatchers.Default`.

## Scopes

User-facing table operations are available inside scopes:

```kotlin
db.transaction {
    Users.insert(user)
    Users.find {
        where { Users.age gtEq 18 }
    }
}
```

A scope pins one connection. This matters for:

- transaction correctness;
- savepoints;
- consistent reads where the backend requires a single connection;
- helper composition.

Reusable transactional functions should extend `Scope<G>` or `SuspendScope<G>`, so they
join the caller's connection instead of opening their own.

## Joins and Result Rows

Join SQL qualifies columns by table to avoid ambiguous identifiers. `ResultRow` maps
selected fields by a **structural** key (`Selectable.resultKey()`), because delegated column
access and the expression operators create fresh instances per use:

- a column keys by table name and column name;
- a computed expression (aggregate, `COALESCE`, `CASE`, arithmetic, string function) keys by its
  structure — the function/shape over its operands' keys, including literals.

So a row reads back with a freshly built, identical expression — a `val` is handy for reuse (and
in `having(...)`), not required:

```kotlin
row[Users.name]
row[Orders.total.sum()]   // fresh instance; no val needed
```

## Migrations

Migrations live in their own module, `kormium-migrate` (not core): they build on scopes and raw
execution, so they need no backend-specific code. Backends only need to provide normal SQL execution.

Each migration:

- has a stable ID;
- runs only once;
- is recorded in `kormium_migrations`;
- runs inside its own transaction.

## Adding a Backend

A backend needs:

1. A `Dialect`.
2. A `TypeMapper` or reuse of `StandardTypeMapper`.
3. A `ResultSet` adapter.
4. A `SqlExecutor` for blocking, a `SuspendSqlExecutor` for suspend, or both.
5. A `Database` and/or `SuspendDatabase` implementation. The handle is not itself a
   `SqlExecutor`; it hands a pinned executor to `usePinned` / `useConnection`. To support
   reactive observation, override `writeListeners` with a real `WriteListeners()` (the
   default `WriteListeners.Disabled` opts out at no cost).
6. A factory function that returns the driver.
7. Tests for SQL rendering, type round trips, transactions and error mapping.

Keep the first version small. Backend-specific features can be added later once the shared
contract is solid.
