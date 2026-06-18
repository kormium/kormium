# API Ergonomics

This page captures the current public API direction. It is not a historical TODO list; the
examples here should match the codebase.

## Tables and Entities

Declare table columns with delegated properties. Columns register automatically.

```kotlin
object Users : Table<App, User>("users", ::User) {
    val id by Column.UUID().primaryKey()
    val email by Column.Text()
    val name by Column.Text()
    val deletedAt by Column.Instant(name = "deleted_at").nullable()
}

class User : Entity() {
    var id by Users.id
    var email by Users.email
    var name by Users.name
    var deletedAt by Users.deletedAt
}
```

Rules:

- `Column.Text()` is non-null by default.
- `.nullable()` changes the Kotlin property type to nullable.
- `.primaryKey()` marks a non-null primary-key column.
- SQL names use `name = "deleted_at"`.
- Entity state is internal. Use assigned properties, `isSet(column)` and `unset(column)`
  instead of raw maps.

## Reads

Use block queries for everyday reads:

```kotlin
Users.find {
    where { Users.deletedAt eq null }
    orderBy ASC Users.email
    limit = 50
    offset = 100
}
```

`Query(...)` remains available for reusable/prebuilt query values and raw-expression escape
hatches.

## Mutations

Use SQL-explicit operation names:

```kotlin
Users.insert(user)
Users.insertAll(users)
```

Partial update uses a patch entity:

```kotlin
Users.update(User().apply { name = "Ada Lovelace" }) {
    where { Users.id eq id }
}
```

Delete uses the same block condition style:

```kotlin
Users.deleteWhere {
    where { Users.deletedAt neq null }
}
```

An empty query means no `WHERE`, consistently across reads and mutations:

```kotlin
Users.find { }
Users.update(patch) { }
Users.deleteWhere { }
```

## Insert Semantics

Assigned properties are written. Unassigned properties are omitted.

```kotlin
val user = User().apply {
    email = "ada@example.com"
    name = "Ada"
    // deletedAt absent: omitted from INSERT
}
```

Explicit `null` is different from absent:

```kotlin
user.deletedAt = null // present; written as SQL NULL
user.unset(Users.deletedAt) // absent; omitted again
```

This lets database defaults and generated columns work without Kormium modeling schema
defaults.

## Upsert

Use column-based conflict targets for PostgreSQL and SQLite portability:

```kotlin
Users.upsert(
    entity = insert,
    onConflict = Users.email,
    update = patch,
    returning = true,
)
```

Composite conflict targets use a list:

```kotlin
Users.upsert(
    entity = insert,
    onConflict = listOf(Users.tenantId, Users.externalId),
    update = patch,
)
```

Use `insertOrIgnore` for `DO NOTHING`:

```kotlin
val inserted: Long = Users.insertOrIgnore(
    entity = insert,
    onConflict = Users.email,
)
```

The conflict target is validated on two levels:

- **Compile time:** `onConflict` is typed `Column<*, *, T>` (and `List<Column<*, *, T>>`), so a
  column from another table does not compile — `Users.upsert(onConflict = Orders.id)` is a type
  error, caught in the IDE. Same-table targets (including `onConflict = Users.primaryKey`) work
  unchanged.
- **Runtime backstop:** before any SQL is built, the target is also checked to be non-empty and to
  belong to this table, failing fast with an `IllegalArgumentException` that names the offending
  column and tables. This covers what types cannot express — an empty list, or the rare case of a
  column from a different table that happens to share this entity type — instead of emitting
  invalid `ON CONFLICT ()` or the wrong column name.

## Raw SQL

Raw SQL is the escape hatch for schema, backend-specific features and complex conflict
targets. Prefer parameter maps for values:

```kotlin
executeUpdate(
    """CREATE UNIQUE INDEX IF NOT EXISTS users_email_idx ON "users" ("email")""",
)
```

Use `RawExpression` only for controlled SQL fragments, never for concatenated user input.
