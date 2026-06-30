# Kormium for AI agents

Type-safe Kotlin Multiplatform ORM / SQL DSL. This file is the canonical, copy-ready
reference: prefer the forms shown here over any you might infer. Package is
`io.github.kormium`. Full docs in [`docs/`](docs/README.md); deeper examples in
[`samples/`](samples/README.md).

## Mental model (read this first)

- A `Catalog` is a compile-time database identity; a `Database<G>` is an instance of it.
- A `Table<G, T>` is a pure schema descriptor; `T : Entity` holds the row's values.
- **All queries run inside a scope**: `db.transaction { ... }` (BEGIN/COMMIT/ROLLBACK) or
  `db.autocommit { ... }` (one pinned connection, no explicit tx — use for reads). Table
  operations (`find`, `insert`, `update`, joins, ...) **only exist inside that block** — they
  are scope extensions, not global methods on the table.
- Suspend mirror: `db.suspendTransaction { ... }` / `db.suspendAutocommit { ... }` with the
  same API. (`kormium-r2dbc` is true async; JDBC / SQLite / native libpq+libmariadb are
  offloaded blocking on virtual threads.)
- **No session, no dirty checking, no lazy loading.** Behavior is local: the SQL is exactly
  what the DSL renders, and an `update` writes only the fields you assigned.

## Define a schema

```kotlin
import io.github.kormium.*

object App : Catalog

object Users : Table<App, User>("users", ::User) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
    val note by Column.Text().nullable()   // nullable -> entity property is String?
}

class User : Entity() {
    var id by Users.id        // non-null column -> non-null property (no `!!` needed)
    var name by Users.name
    var age by Users.age
    var note by Users.note    // String?
}
```

Kormium does not own schema management. Create/evolve schema with `kormium-migrate` (below) or
any migration tool; a `Table` only describes the row↔entity mapping.

## Connect

```kotlin
import io.github.kormium.database.createDatabase

// PostgreSQL (kormium-postgres), JVM + Native:
val db: Database<App> = createDatabase(
    host = "localhost", port = 5432, database = "postgres", user = "postgres", password = "password",
)
// MySQL / MariaDB (kormium-mysql): same createDatabase(...), port = 3306.
// SQLite (kormium-sqlite):   createSqliteDatabase(path = ":memory:")
// Async PostgreSQL (kormium-r2dbc):  createR2dbcDatabase(...)
// Async MySQL (kormium-r2dbc):       createMySqlR2dbcDatabase(...)
```

## Inspect the SQL without running it

`renderSql { }` returns the SQL a query would run (string + bound params) with no connection — the
block reads like a `transaction { }` body but each operation returns a `RenderedSql`. Use it to
self-check a query before executing.

```kotlin
val r = renderSql(App, PostgresDialect) { Users.find { where { Users.age gtEq 18 } } }
r.sql     // SELECT "id", ... FROM "users" WHERE "age" >= :p0 ...
r.params  // {p0=18}
db.renderSql { Users.count { where { Users.age gtEq 18 } } }   // uses the db's own dialect
```

## Read — the canonical single-table form

```kotlin
val adults: List<User> = db.autocommit {
    Users.find {
        where { Users.age gtEq 18 }
        where { Users.name like "A%" }   // multiple where { } blocks AND together
        orderBy DESC Users.age
        limit = 50
        offset = 0
    }
}

val ada: User? = db.autocommit { Users.findById(uuid) }   // targets the primary key
val all: List<User> = db.autocommit { Users.all() }
val n: Long       = db.autocommit { Users.count { where { Users.age gtEq 18 } } }
```

Predicates: `eq`, `neq`, `gt`, `gtEq`, `less`, `lessEq`, `between (lo..hi)`, `like`,
`inList(...)`, `eq null` / `neq null` (render as `IS [NOT] NULL`), combined with
`and` / `or` / `not(...)`. Values are typed (`Users.age eq 18`, not `"18"`) and always bound
as parameters. `between` takes an inclusive Kotlin range (`Users.age between 18..65`); an empty
range and an empty `inList` both render to `FALSE` (match no rows), never invalid SQL.

String functions: a `String` column has `lower()`, `upper()`, `trim()`, `ltrim()`, `rtrim()`,
each returning a `StringExpr` that chains (`name.trim().lower()`), composes with the predicates
above (`name.lower() eq "ada"`, `a.lower() eq b.lower()`), and is readable in `select(...)`.
Plain string comparison (`eq` / `like` / `<`) follows the **engine collation** — case- and
accent-sensitivity differ across PostgreSQL/MySQL/SQLite. Kormium does not inject a collation;
for deterministic case-insensitive matching, lower **both sides**: `name.lower() eq "ada"`.

Null fallback: `column.coalesce(default)` → `COALESCE("column", default)` — the value or the
fallback when NULL. Readable in `select(...)` and comparable to a literal (`Users.rank.coalesce(0)
gt 5`), or to another column (`a.coalesce(b)`). A non-null fallback makes the result non-null.

Conditional value: `case { whenever(cond) then v; …; otherwise(d) }` → searched `CASE`. A `Selectable`
(readable in `select(...)`) — **hold it in a `val`** to read it back. The result type is inferred for
built-in types; for an enum/custom type pass the ColumnType: `case(MyColumnType) { … }`.

For a reusable query, build a `Query` value instead of a block:
`Users.find(Query(Users.age gtEq 18))`. Every `find` / `count` / `update` / `deleteWhere`
accepts either form.

## Write

```kotlin
db.transaction {
    Users.insert(user)                       // INSERT, returns the entity you passed (fast path)
    Users.insert(user, returning = true)     // INSERT ... RETURNING for DB-generated values
    Users.insertAll(listOf(a, b, c))

    Users.update(User().apply { age = 37 }) { where { Users.id eq id } }   // only assigned fields
    Users.deleteWhere { where { Users.name eq "Ada" } }

    Users.upsert(user, conflict = listOf(Users.id), update = patch)        // INSERT ... ON CONFLICT
    Users.insertOrIgnore(user, conflict = listOf(Users.id))
}
```

`update` writes only the properties you assigned on the patch entity; untouched ones are left
alone. Assigning `null` *does* write SQL `NULL`. (This is why concurrent partial updates do
not clobber each other — Kormium never reloads-and-rewrites the whole row.)

## Joins

```kotlin
// Columns from a join: select(), then read by the column you selected.
db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId))
        .where(Users.age gtEq 18)            // .where(expr) on a join, NOT a where { } block
        .select()
        .map { row -> row[Users.name] to row[Orders.total] }
}

// Project straight into your own type:
db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId))
        .select(Users.name, Orders.total) { row -> UserSpend(row[Users.name], row[Orders.total]) }
}

// Two-table join -> entity pairs:
val pairs: List<Pair<User, Order>> = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId)).find()
}

// leftJoin keeps the right side nullable; find() returns Pair<User, Order?>:
val left: List<Pair<User, Order?>> = db.autocommit {
    (Users leftJoin Orders on (Users.id eq Orders.userId)).find()
}

// 3+ tables -> select() + row.entity(table), each side a whole entity:
val triples: List<Triple<User, Order, Item>> = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId)
           innerJoin Items  on (Orders.id eq Items.orderId))
        .select()
        .map { Triple(it.entity(Users), it.entity(Orders), it.entity(Items)) }
}
```

Read nullable right-side fields in `select(...)` with `row.getOrNull(col)`; `row[col]` throws
on NULL/absent. `find()`'s entity-`Pair` is the two-table convenience over `entity()`; for a
LEFT join, `entity()` still hydrates the right side (NULL columns), so detect unmatched rows
with `row.getOrNull(Right.id) == null`.

## Aggregates

Aggregates are keyed structurally, so you read a row with the same expression you selected —
a freshly built one works (`row[Orders.total.sum()]`). A `val` is handy for reuse (in
`having(...)` and when reading), not required.

```kotlin
val orders = count()
val total  = Orders.total.sum()

db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId))
        .groupBy(Users.id)
        .having(total gt Value(BigDecimal.fromInt(100)))
        .select(Users.name, orders, total)
}.forEach { row ->
    println("${row[Users.name]}: ${row[orders]} orders, ${row[total]}")
}
```

Aggregates: `count()` → `Long`, `col.count()` → `Long`, `col.min()` / `col.max()` → the
column's type, `col.sum()` (integer columns widen to `Long`), `col.avg()` → `Double`.
Single-table grouping starts from `Table.query()`:
`Users.query().groupBy(Users.age).distinct().select(Users.age)`.

## Migrations (kormium-migrate)

```kotlin
import io.github.kormium.migrate.Migration
import io.github.kormium.migrate.migrate

db.migrate(listOf(
    Migration("001-create-users", """
        CREATE TABLE "users" ("id" uuid PRIMARY KEY, "name" text NOT NULL, "age" integer NOT NULL);
    """),
))
```

Ordered, idempotent, checksum-verified; applied ids recorded in `kormium_migrations`. For SQL
the `;` splitter can't handle, pass statements explicitly: `Migration("002", listOf(a, b))`.

## Which form for what

| Task | Use |
|------|-----|
| Filtered read, one table | `Table.find { where { } orderBy limit }` |
| By primary key | `Table.findById(id)` |
| Reusable / prebuilt query | `Table.find(Query(...))` |
| Two-table join as entities | `(A innerJoin B on ...).find()` |
| Columns / aggregates from a join | `.select(...)` + `row[col]` / `row[agg]` |
| 3+ table join as entities | `.select()` + `row.entity(table)` |
| Single-table grouping | `Table.query().groupBy(...).select(...)` |
| Upsert / ignore-on-conflict | `Table.upsert(...)` / `Table.insertOrIgnore(...)` |

## Gotchas

- Operations are scope extensions — they don't compile outside `db.transaction { }` /
  `db.autocommit { }` (or the `suspend*` variants).
- A `Table<G, _>` can only be used in a `Database<G>` scope; mixing catalogs is a compile error.
- `findById` targets the primary key and throws on a composite key — use `find(Query(col eq v))`.
- Predicate names are `less` / `lessEq` (not `lt` / `ltEq`) and `neq` (not `ne`).
- Read nullable join columns with `getOrNull`; `row[col]` throws on NULL/absent.
- Not modeled by the typed DSL: subqueries, `UNION`, CTEs, window functions, `RIGHT`/`FULL`/
  `CROSS`/self-joins, `ILIKE` operator (use `lower()`), regex, simple `CASE expr WHEN` (searched `case { }` is supported), scalar functions
  beyond `lower`/`upper`/`trim`/`ltrim`/`rtrim`, arithmetic in `SELECT` projections, `RETURNING` on
  `UPDATE`/`DELETE`, `FOR UPDATE`. Drop to `RawExpression` or `execute(...)` for those.
