# Queries

Kormium queries are built from typed expressions and rendered into parameterized SQL. Values are
bound through the backend driver; they are not inlined into the SQL string.

## Basic Selects

```kotlin
val all: List<User> = db.autocommit {
    Users.all()
}

val one: User? = db.autocommit {
    Users.findOne { where { Users.id eq id } }
}

val adults: List<User> = db.autocommit {
    Users.find {
        where { Users.age gtEq 18 }
    }
}
```

## Predicates

```kotlin
Users.find { where { Users.id eq id } }
Users.find { where { Users.age gt 18 } }
Users.find { where { Users.age lessEq 65 } }
Users.find { where { Users.age between 18..65 } }     // inclusive both ends
Users.find { where { Users.name like "A%" } }
Users.find { where { Users.id inList listOf(id1, id2) } }
Users.find { where { Users.note eq null } }
Users.find { where { Users.note neq null } }
```

Predicates can be combined:

```kotlin
Users.find {
    where { (Users.age gtEq 18) and (Users.name like "A%") }
}

Users.find {
    where { (Users.name eq "Ada") or (Users.name eq "Grace") }
}

Users.find {
    where { not(Users.note eq null) }
}
```

An empty `inList` renders to `FALSE`, so it matches no rows instead of generating invalid
SQL.

## String Functions

A `String` column exposes the scalar functions `lower()`, `upper()`, `trim()`, `ltrim()` and
`rtrim()`. Each returns a `StringExpr` that chains and composes with the string predicates, and
can also be read back from a `select(...)` projection:

```kotlin
Users.find { where { Users.name.lower() eq "ada" } }              // LOWER("name") = 'ada'
Users.find { where { Users.name.trim().lower() eq "ada" } }       // chained
Users.find { where { Users.name.lower() eq Users.handle.lower() } } // column-to-column
Users.find { where { Users.sku.upper() gtEq "M000" } }            // lexicographic range

val lowered: List<String> = db.autocommit {
    val name = Users.name.lower()
    Users.query().select(name).map { it[name] }
}
```

> **Collation note.** A plain string comparison (`eq`, `like`, `<`, …) follows the **engine's
> collation**, so its case- and accent-sensitivity differ across PostgreSQL, MySQL and SQLite —
> the same `Users.name eq "Ada"` can match `"ada"` on one engine and not another. Kormium does
> not inject a collation for you (that would be hidden, and would change which index the query
> can use). For deterministic, case-insensitive matching, lower **both sides** explicitly:
> `Users.name.lower() eq "ada"`. `lower()` settles the case dimension; locale ordering and
> accents still follow the collation. For why Kormium has no `ilike` operator, see
> [ADR 0002](adr/0002-no-ilike-explicit-lower.md).

`LOWER(col)` cannot use a plain index on `col` — add a functional index on `LOWER(col)` if you
match on it often.

`length()` returns the **character** count as a number (`NumericExpr<Int>`, so it compares and does
arithmetic): `Users.find { where { Users.name.length() gtEq 3 } }`. It renders the dialect's
character-length function (`LENGTH` on PostgreSQL/SQLite, `CHAR_LENGTH` on MySQL, whose `LENGTH`
counts bytes), so a multibyte string counts the same on every backend.

## Null Fallback (`COALESCE`)

`column.coalesce(default)` renders `COALESCE("column", default)` — the column's value, or the
fallback when it is `NULL`. It reads back from a `select(...)` projection and composes with the
predicates, comparing a literal through the column's converter:

```kotlin
// Read a nullable column with a fallback (non-null, so row[...] is safe):
val names: List<String> = db.autocommit {
    val name = Users.nickname.coalesce("anonymous")
    Users.query().select(name).map { it[name] }
}

// Treat a NULL as the fallback in a predicate:
Users.find { where { Users.rank.coalesce(0) gt 5 } }   // COALESCE("rank", 0) > 5

// First non-null of several columns, then a literal fallback:
Users.find { where { Users.nickname.coalesce(Users.handle, Users.name) eq "Ada" } }
select(Users.nickname.coalesce(Users.handle, Users.name).coalesce("anonymous")) // COALESCE(nick, handle, name, 'anonymous')
```

The default binds through the column's converter, so an enum/`Instant`/`BigDecimal` fallback maps
the same way a comparison literal does. When the fallback is non-null the result is non-null —
read it with `row[...]`; for a `coalesce` of two nullable columns, use `getOrNull`.

## Conditional Values (`CASE`)

`case { }` builds a searched `CASE WHEN ... THEN ... ELSE ... END`: `whenever(condition) then value`
adds a branch, `otherwise(value)` sets the fallback. It is a `Selectable`, so it reads back from a
`select(...)` projection, and it composes with the predicates:

```kotlin
val tier = case {
    whenever(Users.age gtEq 65) then "senior"
    whenever(Users.age gtEq 18) then "adult"
    otherwise("minor")
}

val labels = db.autocommit {
    Users.query().select(tier).map { it[tier] }     // "senior" / "adult" / "minor"
}

Users.find { where { case { whenever(Users.age gtEq 18) then true; otherwise(false) } eq true } }
```

The result type is inferred from the branch values for the built-in types (String, the integer and
floating types, Boolean, `BigDecimal`, `Instant`, the date/time types, `Uuid`). For an enum or other
custom-mapped result, pass the `ColumnType` so the value can be read and bound:

```kotlin
val status = case(StatusColumnType) {
    whenever(Users.active eq true) then Status.ACTIVE
    otherwise(Status.INACTIVE)
}
```

A `CASE` is keyed structurally (by its conditions and branch values), like the other computed
expressions — a freshly built, identical `case { }` reads back from a row, so a `val` is for reuse,
not required. Only searched `CASE` is modeled (no `CASE expr WHEN value`).

## Arithmetic

Numeric columns support `+`, `-`, `*`, `/`, `%`. The operands are a same-typed column, another
arithmetic expression, or a literal (bound through the column's converter); the result is the same
type and nests, so it chains. It works in a `where { }`, in an `update { }` `set`, and as a
`select(...)` projection that reads back:

```kotlin
// In a predicate:
Posts.find { where { (Posts.likes - Posts.dislikes) gtEq 100 } }

// Atomic self-referential update:
Posts.update { Posts.views set (Posts.views + 1); where { Posts.id eq id } }

// As a projection, read back (a literal is part of the key, so no `val` is needed):
val lineTotals: List<Int> = db.autocommit {
    Orders.query().select(Orders.qty * Orders.unitPrice).map { it[Orders.qty * Orders.unitPrice] }
}
```

The result reads through the column's type and is `NULL` when any operand is — use `getOrNull`
for an expression that can be null.

## Existence (`any` / `none`)

`Table.any { predicate }` renders a correlated `EXISTS (SELECT 1 FROM table WHERE predicate)`;
`Table.none { }` renders `NOT EXISTS`. Read like Kotlin's `any`/`none`. The predicate is an ordinary
boolean expression and references the outer query's columns to correlate — columns on both sides
render qualified (`orders.userId = users.id`) so they don't collide:

```kotlin
// Users who have at least one order over 100:
Users.find { where { Orders.any { (Orders.userId eq Users.id) and (Orders.total gt 100) } } }

// Users with no orders at all:
Users.find { where { Orders.none { Orders.userId eq Users.id } } }
```

This is also how to write an `IN (SELECT ...)`: `id IN (SELECT userId FROM orders WHERE …)` is the
same as `Orders.any { (Orders.userId eq Users.id) and … }`. A **scalar** subquery (comparing to a
single value) is not modeled — write it as a typed comparison against a `RawExpression`:
`Products.find { where { Products.price gt RawExpression("""(SELECT AVG("price") FROM "products")""") } }`.
For why subqueries are modeled this way (and not as an embeddable value), see
[ADR 0004](adr/0004-correlated-exists-any-none.md).

## Ordering, Limit and Offset

```kotlin
Users.find {
    where { Users.age gtEq 18 }
    orderBy DESC Users.age
    limit = 50
    offset = 100
}
```

`orderBy` takes a column or a **computed expression** — `orderBy ASC Users.name.lower()` for a
case-insensitive sort, or `orderBy DESC (Users.qty * Users.price)`. Multiple `orderBy` calls keep
their order. (Ordering by an aggregate belongs to the grouped `Table.query()` path, and
`NULLS FIRST` / `LAST` is not modeled.)

## Reusable Queries with `Query`

The `find { ... }` / `count { ... }` block is a thin ergonomic layer over `Query`, the value
type. `QueryBuilder.build()` simply returns a `Query`, so anything you can express in the block
has an equivalent `Query` — use `Query` when you want to **build a query once and reuse it**, pass
it around, store it, or compose it:

```kotlin
import io.github.kormium.Query
import io.github.kormium.AscDescOrder

// Prebuilt, reusable value — construct it anywhere, run it against any matching table.
val adultsByAge = Query(
    whereExpression = Users.age gtEq 18,
    orderBy = mapOf(Users.age to AscDescOrder.DESC),
    limit = 50u,
)

val page: List<User> = db.autocommit { Users.find(adultsByAge) }
val howMany: Long = db.autocommit { Users.count(adultsByAge) }
```

Every `find` / `count` / `update` / `deleteWhere` has both forms: pass a `Query` value, or open a
`{ ... }` block. They render to the same SQL and bind parameters. Reach for the block at a call
site for readability; reach for `Query` when the query is reusable, parameterized in your own code,
or assembled ahead of time. The block does not replace `Query` — it is built on it.

```kotlin
// These two are equivalent:
Users.find(Query(Users.age gtEq 18))
Users.find { where { Users.age gtEq 18 } }
```

## Insert, Returning and Count

```kotlin
db.transaction { Users.insert(user) }

val saved: User? = db.transaction {
    Users.insert(user, returning = true)
}

val savedAll: List<User> = db.transaction {
    Users.insertAll(listOf(user1, user2), returning = true)
}

val total: Long = db.autocommit {
    Users.count()
}

val adults: Long = db.autocommit {
    Users.count {
        where { Users.age gtEq 18 }
    }
}
```

`returning = false` is the fast path: Kormium runs an `INSERT` and returns the entity or list you
passed in. `returning = true` adds SQL `RETURNING` and maps the stored row back into an
entity, which is useful for database-generated values.

## Joins

Join expressions qualify columns automatically so same-name columns do not collide.

```kotlin
val rows = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId))
        .where(Users.age gtEq 18)
        .select()
}

rows.forEach { row ->
    println("${row[Users.name]} spent ${row[Orders.total]}")
}
```

You can project rows into your own type:

```kotlin
data class UserSpend(val name: String, val total: BigDecimal)

val spend: List<UserSpend> = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId))
        .select(Users.name, Orders.total) { row ->
            UserSpend(row[Users.name], row[Orders.total])
        }
}
```

For a two-table join, `find()` can map rows into entity pairs:

```kotlin
val pairs: List<Pair<User, Order>> = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId)).find()
}
```

`leftJoin` keeps the right side nullable. Its `find()` returns `Pair<A, B?>` — the right
entity is `null` for left rows with no match (detected by a `NULL` right-side primary key):

```kotlin
val pairs: List<Pair<User, Order?>> = db.autocommit {
    (Users leftJoin Orders on (Users.id eq Orders.userId)).find()
}
```

In the `select(...)` forms, read nullable right-side fields with `getOrNull`:

```kotlin
val rows = db.autocommit {
    (Users leftJoin Orders on (Users.id eq Orders.userId))
        .select(Users.name, Orders.total)
}

rows.forEach { row ->
    val total = row.getOrNull(Orders.total)
}
```

For three or more tables, read each side as a whole entity with `row.entity(table)` over a
plain `select()`:

```kotlin
val triples: List<Triple<User, Order, Item>> = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId)
           innerJoin Items  on (Orders.id eq Items.orderId))
        .select()
        .map { Triple(it.entity(Users), it.entity(Orders), it.entity(Items)) }
}
```

`entity(table)` rebuilds any joined table's entity from the row, so it scales to any number of
tables. The two-entity `Pair` mapping of `find()` is the two-table convenience over it; for a
`LEFT` join, `entity()` still hydrates the right side (its columns are NULL), so detect an
unmatched row yourself with `row.getOrNull(Right.id) == null`.

## Aggregations

Aggregates are keyed structurally (by the function over their target), so you read a row back
with the same expression you selected — a freshly built one works, e.g. `row[Orders.total.sum()]`.
A `val` is handy when you reuse the expression (in `having(...)` and again when reading), but it
is no longer required.

```kotlin
val orders = count()
val total = Orders.total.sum()

val result = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId))
        .groupBy(Users.id)
        .having(total gt BigDecimal.fromInt(100))
        .select(Users.name, orders, total)
}

result.forEach { row ->
    println("${row[Users.name]}: ${row[orders]} orders, ${row[total]}")
}
```

Available aggregates:

- `count()` → `Long`
- `column.count()` → `Long`
- `column.min()` / `column.max()` → the column's own type
- `column.sum()` → `Long` for integer columns (`Int`/`Short`/`Long`), since `SUM` widens to
  `bigint` server-side and could otherwise overflow; the column's own type otherwise (e.g.
  `BigDecimal`, `Double`)
- `column.avg()` → `Double`

For single-table grouping, start from `Table.query()`:

```kotlin
val byAge = db.autocommit {
    Users.query()
        .groupBy(Users.age)
        .distinct()
        .select(Users.age)
}
```

## Raw Expressions

`RawExpression` embeds SQL verbatim. It is useful for controlled SQL fragments, but unsafe
with untrusted input.

Prefer:

```kotlin
Users.find {
    where { Users.name eq input }
}
```

Use raw SQL only when the SQL text is fully controlled by your application:

```kotlin
Users.find(Query(RawExpression("""lower("name") = 'ada'""")))
```

## Unsupported / Out-of-Scope SQL

Kormium covers a deliberate slice of SQL: typed `SELECT`/`WHERE`/`ORDER BY`/`LIMIT`/`OFFSET`,
`INNER`/`LEFT` joins, `GROUP BY` / `HAVING` / `DISTINCT`, the aggregates listed above, and the
predicate vocabulary below. The DSL does not try to model all of SQL. Anything outside that
slice runs through raw SQL — either a `RawExpression` inside the DSL or `execute(...)` /
`executeUpdate(...)` on a scope (see [Raw Expressions](#raw-expressions) and the
[API cookbook](api-cookbook.md)).

Not modeled by the typed DSL today:

- **Subqueries** other than `EXISTS`. Correlated `EXISTS` / `NOT EXISTS` is modeled by `any` / `none`
  (see [Existence](#existence-any--none)); subqueries in `SELECT` / `FROM`, `IN (SELECT ...)` and
  scalar subqueries are not — express a scalar one as a typed comparison against a `RawExpression`
  (`Products.price gt RawExpression("(SELECT AVG(\"price\") FROM \"products\")")`). `inList` takes an
  in-memory `List`, not a query.
- **`UNION` / `INTERSECT` / `EXCEPT`.** No set-operation combinators.
- **CTEs and recursive queries.** No `WITH` / `WITH RECURSIVE`.
- **Window functions.** No `OVER (...)`, `PARTITION BY`, or ranking functions. Aggregates are
  `GROUP BY`-only.
- **`RIGHT` / `FULL OUTER` / `CROSS` joins and self-joins.** Only `innerJoin` and `leftJoin`
  are available, and a table cannot be aliased to join it to itself.
- **`DISTINCT ON`.** Only plain `DISTINCT` is supported.
- **Pattern-match variants.** `like` only; no `ILIKE` operator (lower both sides for a
  case-insensitive match — see [String Functions](#string-functions)), `SIMILAR TO`, or regex.
- **Computed expressions.** No string concatenation, casts, or scalar functions other than
  the string functions `lower` / `upper` / `trim` / `ltrim` / `rtrim` / `length`
  (see [String Functions](#string-functions)) in `SELECT`/`WHERE`/`HAVING`. Arithmetic
  (`+` `-` `*` `/` `%`) over numeric columns *is* supported — see [Arithmetic](#arithmetic);
  conditional values via searched `case { }` — see [Conditional Values](#conditional-values-case).
- **Aggregate ordering and null placement.** `ORDER BY` takes a column or a computed expression
  (`lower(name)`, arithmetic) with `ASC` / `DESC`, but not an aggregate (that is the grouped
  `Table.query()` path), and no `NULLS FIRST` / `NULLS LAST`.
- **Grouping in the `find { }` block.** `groupBy` / `having` / `distinct` live on the join /
  `Table.query()` path, not the entity-returning `find { }` builder.
- **Statement-level extras.** No `ORDER BY` / `LIMIT` on `UPDATE` / `DELETE`, no `RETURNING`
  on `UPDATE` / `DELETE`, no `LOCK` / `FOR UPDATE` clauses, and no DDL through the query DSL.
  (`INSERT ... ON CONFLICT` *is* available — see `upsert` and `insertOrIgnore`.)

The supported `WHERE` / `HAVING` predicates are exactly: `eq`, `neq`, `less`, `lessEq`, `gt`,
`gtEq`, `between` (an inclusive `lo..hi` range; an empty range matches nothing), `like`,
`inList`, `eq null` / `neq null` (rendered as `IS [NOT] NULL`), and the `and` / `or` / `not(...)`
combinators.

## Observing Changes

To re-run a query automatically whenever its data changes, use `kormium-observe`:
`Users.observe(db) { where { Users.age gtEq 18 } }` returns a `Flow<List<User>>` that
re-emits after every committed write to the table. See [Observing changes](observe.md).
