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
// Each ":memory:" call is its OWN database (private to that driver, gone on close()). To share
// one between drivers: createSqliteDatabase("file:shared?mode=memory&cache=shared") — JVM/native.
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

val ada: User? = db.autocommit { Users.findOne { where { Users.id eq uuid } } }   // one row or null
val all: List<User> = db.autocommit { Users.all() }
val n: Long       = db.autocommit { Users.count { where { Users.age gtEq 18 } } }
```

Predicates: `eq`, `neq`, `gt`, `gtEq`, `lt`, `ltEq`, `between (lo..hi)`, `like`,
`inList(...)`, `eq null` / `neq null` (render as `IS [NOT] NULL`), combined with
`and` / `or` / `not(...)`. Values are typed (`Users.age eq 18`, not `"18"`) and always bound
as parameters. `between` takes an inclusive Kotlin range (`Users.age between 18..65`); an empty
range and an empty `inList` both render to `FALSE` (match no rows), never invalid SQL.

`orderBy` takes a column or a computed expression: `orderBy ASC Users.name.lower()` (case-insensitive),
`orderBy DESC (Users.qty * Users.price)`.

String functions: a `String` column has `lower()`, `upper()`, `trim()`, `ltrim()`, `rtrim()`,
each returning a `StringExpr` that chains (`name.trim().lower()`), composes with the predicates
above (`name.lower() eq "ada"`, `a.lower() eq b.lower()`), and is readable in `select(...)`. Also
`length()` → character count as a `NumericExpr<Int>` (`name.length() gtEq 3`; portable — `CHAR_LENGTH`
on MySQL).
Plain string comparison (`eq` / `like` / `<`) follows the **engine collation** — case- and
accent-sensitivity differ across PostgreSQL/MySQL/SQLite. Kormium does not inject a collation;
for deterministic case-insensitive matching, lower **both sides**: `name.lower() eq "ada"`.

Null fallback: `column.coalesce(default)` → `COALESCE("column", default)` — the value or the
fallback when NULL. Readable in `select(...)` and comparable to a literal (`Users.rank.coalesce(0)
gt 5`). N-ary: `a.coalesce(b, c)` (more columns), `a.coalesce(b).coalesce("default")` (trailing
literal). A non-null fallback makes the result non-null.

Conditional value: `case { whenever(cond) then v; …; otherwise(d) }` → searched `CASE`. A `Selectable`
(readable in `select(...)`) keyed structurally, so a freshly built identical `case { }` reads back —
a `val` is handy for reuse, not required. The result type is inferred for built-in types; for an
enum/custom type pass the ColumnType: `case(MyColumnType) { … }`.

Arithmetic: numeric columns support `+ - * / %` (`Posts.likes - Posts.dislikes`, `Posts.views + 1`)
— in `where { }`, in an `update { }` `set`, and as a `select(...)` projection that reads back.

Existence (correlated EXISTS): `Table.any { predicate }` → `EXISTS (SELECT 1 FROM table WHERE …)`,
`Table.none { }` → `NOT EXISTS`. The predicate references the outer column to correlate:
`Users.find { where { Orders.any { Orders.userId eq Users.id } } }`. This also covers `IN (SELECT)`.
A scalar subquery isn't modeled — compare against a `RawExpression("(SELECT …)")`.

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

    Users.upsert(user, onConflict = listOf(Users.id), update = patch)      // INSERT ... ON CONFLICT
    Users.insertOrIgnore(user, onConflict = listOf(Users.id))
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
        .having(total gt Decimal.of(100))   // aggregate vs a typed literal, no Value(...) wrapper
        .select(Users.name, orders, total)
}.forEach { row ->
    println("${row[Users.name]}: ${row[orders]} orders, ${row[total]}")
}
```

Aggregates: `count()` → `Long`, `col.count()` → `Long`, `col.min()` / `col.max()` → the
column's type, `col.sum()` (integer columns widen to `Long`), `col.avg()` → `Double`.
Exact decimal columns (`Column.decimal()`, values of type `Decimal`) come from the
`kormium-decimal` artifact — `import io.github.kormium.decimal.Decimal` and
`import io.github.kormium.decimal.decimal`.
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
| One row by id / unique column | `Table.findOne { where { col eq v } }` (→ `T?`) |
| Reusable / prebuilt query | `Table.find(Query(...))` |
| Two-table join as entities | `(A innerJoin B on ...).find()` |
| Columns / aggregates from a join | `.select(...)` + `row[col]` / `row[agg]` |
| 3+ table join as entities | `.select()` + `row.entity(table)` |
| Single-table grouping | `Table.query().groupBy(...).select(...)` |
| Upsert / ignore-on-conflict | `Table.upsert(...)` / `Table.insertOrIgnore(...)` |

## Recipes

Copy-ready patterns for common tasks. They use only the forms above — no raw SQL.

**Dynamic / optional filters.** `where { }` blocks AND together, so add a filter only when its
argument is present — no string building:

```kotlin
fun search(name: String?, minAge: Int?) = db.autocommit {
    Users.find {
        if (name != null)   where { Users.name like "$name%" }
        if (minAge != null) where { Users.age gtEq minAge }
        orderBy ASC Users.name
        limit = 50
    }
}
```

**Keyset (seek) pagination.** Stabler than `offset` on deep pages — the cursor is the last value
of an ordered, unique key:

```kotlin
fun page(after: Instant?, size: Int = 50) = db.autocommit {
    Users.find {
        if (after != null) where { Users.createdAt lt after }
        orderBy DESC Users.createdAt
        limit = size
    }
}
```

`limit`/`offset` works too (`offset = page * size`), but on deep pages it is slower and can skip or
repeat rows under concurrent writes. If the key is not unique, add a tiebreaker
(`… or ((Users.createdAt eq c) and (Users.id gt lastId))`).

**Soft-delete.** Kormium has no implicit filters — a "delete" is an `UPDATE` of a marker column, and
every read **explicitly** excludes the marked rows (no hidden state to forget):

```kotlin
val deletedAt by Column.Instant().nullable()   // null = live row

db.transaction {
    Users.update(User().apply { deletedAt = Clock.System.now() }) { where { Users.id eq id } }
}
db.autocommit { Users.find { where { Users.deletedAt eq null } } }   // add the predicate on each read
```

**Optimistic locking.** `update { }` returns the affected-row count, so check the version matched;
bump it atomically with `set (col + 1)`:

```kotlin
val applied = db.transaction {
    Docs.update {
        Docs.body    set newBody
        Docs.version set (Docs.version + 1)
        where { (Docs.id eq id) and (Docs.version eq expected) }
    } == 1L
}
if (!applied) error("stale write — reload and retry")
```

**Batch insert, input order preserved.** `insertAll` batches by row shape; with `returning = true` it
backfills DB-generated values and keeps the input order:

```kotlin
val saved = db.transaction { Users.insertAll(newUsers, returning = true) }
```

**Find-or-create.** Insert if absent, then read the row back — new or pre-existing:

```kotlin
val user = db.transaction {
    Users.insertOrIgnore(newUser, onConflict = Users.id)        // 1 = inserted, 0 = already there
    Users.findOne { where { Users.id eq newUser.id } }!!        // the row either way
}
```

**Retry a transaction on a transient conflict.** Under `SERIALIZABLE` / `REPEATABLE READ`, or on a
deadlock, the database aborts one transaction with a `ConcurrencyConflictException` (SQLSTATE
`40001` / `40P01`) — it is **safe to retry the whole transaction**. Kormium ships the typed
exception, not a retry loop: the policy (attempts, backoff) is yours, and the block must be
idempotent outside the DB since it re-runs.

```kotlin
fun <R> retrying(max: Int = 3, block: () -> R): R {
    repeat(max - 1) {
        try { return block() } catch (_: ConcurrencyConflictException) { /* transient: retry */ }
    }
    return block()   // last attempt: let it throw
}

retrying {
    db.transaction(isolation = TransactionIsolation.SERIALIZABLE) {
        val item = Items.findOne { where { Items.id eq id } } ?: error("no item")
        require(item.stock > 0) { "out of stock" }
        Items.update(Item().apply { stock = item.stock - 1 }) { where { Items.id eq id } }
    }
}
```

**Vector / semantic search (pgvector, Postgres).** Store an embedding in a `Column.Vector` and
rank by a distance operator — nearest-neighbour search is a plain ascending `orderBy` (for every
metric, smaller = more similar). Kormium does not own DDL, so enable the extension and declare the
column in a migration (`CREATE EXTENSION vector; ... embedding vector(1536)`):

```kotlin
object Docs : Table<App, Doc>("docs", ::Doc) {
    val id        by Column.UUID().primaryKey()
    val embedding by Column.Vector(dimensions = 1536)   // entity property: Vector
}
class Doc : Entity() { var id by Docs.id; var embedding by Docs.embedding }

// embed(...) is YOUR embedding model (OpenAI/Cohere/local ...), not a Kormium function; it returns a
// FloatArray / List<Float>. Kormium stores and searches vectors, it does not generate them.
db.transaction { Docs.insert(Doc().apply { id = docId; embedding = Vector(embed(text)) }) }

val query = Vector(embed(question))
val hits = db.autocommit {
    Docs.find {
        orderBy ASC Docs.embedding.distance(query, VectorMetric.COSINE)   // <=>
        limit = 5
    }
}
```

`distance(query, metric)` (metric defaults to `COSINE`) has aliases `euclideanDistance` (`<->`),
`cosineDistance` (`<=>`), `innerProduct` (`<#>`). The query vector binds as a parameter with a
`::vector` cast (never interpolated). `Vector` wraps a `FloatArray` (or `List<Float>`); `dimensions`
is validated on write. See [docs/queries.md](docs/queries.md#vector-search-pgvector).

## Gotchas

- Operations are scope extensions — they don't compile outside `db.transaction { }` /
  `db.autocommit { }` (or the `suspend*` variants). Symptom of being outside one: `find` / `insert`
  resolve to a Kotlin stdlib function (`kotlin.collections.find`) or `where` is "unresolved".
- A `Table<G, _>` can only be used in a `Database<G>` scope; mixing catalogs is a compile error
  ("receiver type mismatch" naming `Table<ThatCatalog, _>`).
- One row by primary key (or any unique column): `findOne { where { col eq v } }` → `T?` (`LIMIT 1`).
  There is no `findById` — naming the column keeps the id **type-checked**.
- Value comparisons are typed: `Users.age eq "18"` won't compile (pass `18`; the error names the
  expected type). Column-to-column comparisons are NOT cross-checked — `intCol eq uuidCol` compiles,
  so match the types yourself.
- `eq null` / `neq null` (→ `IS [NOT] NULL`) work only on **nullable** columns; on a non-null column
  they don't compile (it can never be NULL). For a computed expression that can be null (a `COALESCE`
  of nullable columns, `rank + 1`, …), use `expr.isNull()` / `expr.isNotNull()` — available on any operand.
- `orderBy` needs a direction — `orderBy ASC col` / `orderBy DESC col`; bare `orderBy col` is a syntax error.
- Predicate names are `lt` / `ltEq` (not `less` / `lessEq` or `<`) and `neq` (not `ne`).
- Read nullable join columns with `getOrNull`; `row[col]` throws on NULL/absent.
- Not modeled by the typed DSL: subqueries other than correlated `EXISTS` (use `any`/`none`; scalar →
  compare against a `RawExpression`), `UNION`, CTEs, window functions, `RIGHT`/`FULL`/
  `CROSS`/self-joins, `ILIKE` operator (use `lower()`), regex, simple `CASE expr WHEN` (searched
  `case { }` is supported), scalar functions beyond `lower`/`upper`/`trim`/`ltrim`/`rtrim`/`length`,
  `RETURNING` on `UPDATE`/`DELETE`, `FOR UPDATE`. Drop to `RawExpression` or `execute(...)` for
  those — both require `@OptIn(DelicateKormiumApi::class)`, and `execute`/`executeUpdate` require
  `params`/`invalidates` explicitly (`emptyMap()`/`emptyList()` when there's nothing to pass).

## Changing kormium's own public API (contributors)

Every published module compiles with Kotlin `explicitApi()` — new public declarations need
explicit visibility and return types (the compiler errors until they do; prefer `internal`
unless the symbol is meant for consumers). The public ABI is dumped per module in
`<module>/api/` and checked by CI: after any deliberate API change run `./gradlew apiDump`
and commit the `.api` diffs — they are the review artifact for the change.
