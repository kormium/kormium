# Queries

Korm queries are built from typed expressions and rendered into parameterized SQL. Values are
bound through the backend driver; they are not inlined into the SQL string.

## Basic Selects

```kotlin
val all: List<User> = db.autocommit {
    Users.all()
}

val one: User? = db.autocommit {
    Users.findById(id)
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

## Ordering, Limit and Offset

```kotlin
Users.find {
    where { Users.age gtEq 18 }
    orderBy DESC Users.age
    limit = 50
    offset = 100
}
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

`returning = false` is the fast path: Korm runs an `INSERT` and returns the entity or list you
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

`leftJoin` is available. Read nullable right-side fields with `getOrNull`:

```kotlin
val rows = db.autocommit {
    (Users leftJoin Orders on (Users.id eq Orders.userId))
        .select(Users.name, Orders.total)
}

rows.forEach { row ->
    val total = row.getOrNull(Orders.total)
}
```

Joining a third table keeps the `select(...)` projection forms. The two-entity `Pair`
mapping is intentionally limited to two-table joins.

## Aggregations

Keep aggregate expressions in `val`s and reuse those values when reading rows. Aggregates
are keyed by expression identity.

```kotlin
val orders = count()
val total = Orders.total.sum()

val result = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId))
        .groupBy(Users.id)
        .having(total gt Value(BigDecimal.fromInt(100)))
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
