# Tables and Entities

Kormium models database shape with three pieces:

- `Catalog` marks a logical database.
- `Table<G, T>` describes a table belonging to catalog `G`.
- `Entity` exposes typed delegated properties backed by Kormium's internal row state.

## Catalogs

```kotlin
object Main : Catalog
object CacheCatalog : Catalog
```

Catalogs are phantom type tags. A `Database<Main>` accepts `Table<Main, *>` operations and
rejects `Table<CacheCatalog, *>` operations at compile time.

```kotlin
db.transaction {           // db: Database<Main>
    Users.insert(user)        // OK: Users is Table<Main, User>
    CacheRows.insert(row)     // Compile error if CacheRows is Table<CacheCatalog, CacheRow>
}
```

You can still have many database instances for one catalog, which is useful for sharding:

```kotlin
val shard0: Database<Main> = createDatabase(host = "shard0", database = "app", user = "postgres", password = "password")
val shard1: Database<Main> = createDatabase(host = "shard1", database = "app", user = "postgres", password = "password")

fun shardFor(tenantId: String): Database<Main> = if (tenantId.hashCode() % 2 == 0) shard0 else shard1
```

## Table Metadata

```kotlin
object Users : Table<Main, User>("users", ::User)
```

The first argument is the SQL table name. Kormium renders it through the backend dialect's
identifier quoting. Schema-qualified table names are intentionally not part of the table
metadata API; use the database connection's default schema/search path or raw SQL migrations
for schema setup.

## Columns

Columns are declared with delegated properties:

```kotlin
object Users : Table<Main, User>("users", ::User) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
    val note by Column.Text().nullable()
}
```

Supported column types:

| Column | Kotlin value |
| --- | --- |
| `Column.UUID` | `kotlin.uuid.Uuid` |
| `Column.Text` | `String` |
| `Column.Boolean` | `Boolean` |
| `Column.Short` | `Short` |
| `Column.Int` | `Int` |
| `Column.Long` | `Long` |
| `Column.Float` | `Float` |
| `Column.Double` | `Double` |
| `Column.BigDecimal` | `com.ionspin.kotlin.bignum.decimal.BigDecimal` |
| `Column.Instant` | `kotlinx.datetime.Instant` |
| `Column.LocalDate` | `kotlinx.datetime.LocalDate` |
| `Column.LocalTime` | `kotlinx.datetime.LocalTime` |
| `Column.LocalDateTime` | `kotlinx.datetime.LocalDateTime` |
| `Column.Json` | `kotlinx.serialization.json.JsonElement` |
| `Column.Bytes` | `ByteArray` (bound/read as native binary — `bytea`/`BLOB`) |

These are the built-ins; the type list is open — see [Custom column types](#custom-column-types)
for enums, JSON-mapped values and your own types.

Every column accepts:

```kotlin
Column.Text().nullable()
Column.UUID().primaryKey()
```

`findById` uses a single explicit primary key. If none is marked, it falls back to a column
named `id`. For composite primary keys, use `find { ... }` or `find(Query(...))` instead
of `findById`.

A non-null column (one declared without `.nullable()`) is enforced on read as well as on write:
if the database returns SQL `NULL` for it, hydration fails fast with a `ResultMappingException`
naming the table and column, rather than letting an invalid `null` reach the entity. This
surfaces a schema/entity mismatch (or a bad row) at the read boundary. Nullable columns hydrate
`NULL` normally.

## Custom Column Types

The type list is open. Two ready-made helpers cover the common cases:

```kotlin
val role  by Column.enum<Role>()    // enum stored by name (text)
val prefs by Column.json<Prefs>()   // @Serializable value stored as JSON (jsonb on Postgres)
```

For any other type, derive one from an existing column type with `convert` — you only map the
value both ways (the storage, reading and any dialect casts are inherited). This replaces
Room's `@TypeConverter`, type-safe and without annotations:

```kotlin
val color by Column.of(
    TextColumnType.convert<Color, String>(
        toStored   = { it.hex },
        fromStored = { Color(it) },
    ),
)
```

A column type is just three pieces — `read` and an optional `toParam` (the entity property
type and any conversion). Implement `ColumnType<T>` directly only when you need a genuinely
new storage type; most custom types are a `convert` over `TextColumnType` / `JsonColumnType` /
`IntColumnType`. Conversion applies on insert, update and in predicates, so
`where { Users.role eq Role.ADMIN }` binds the stored form automatically.

## Entities

```kotlin
class User : Entity() {
    var id by Users.id
    var name by Users.name
    var age by Users.age
    var note by Users.note
}
```

Entities are intentionally small. They inherit Kormium's internal field storage and expose
typed property delegates. This gives Kormium two useful update semantics:

- A property never assigned on the entity is omitted from `UPDATE`.
- A property assigned to `null` is included and written as SQL `NULL`.

```kotlin
Users.update(User().apply { note = null }) {
    where { Users.id eq id }
}
```

This updates only `note`. Kormium tracks assigned fields internally; entities should not expose
or mutate that storage directly.

## Schema Management

Kormium does not own schema management. A `Table` describes how rows map to entities for
queries, inserts and updates — not the full database schema. Create and evolve schema with
a migration tool (Flyway, Liquibase) or raw SQL, which also gives you indexes, foreign
keys, checks, defaults and generated columns that the mapping layer intentionally does not
model:

```kotlin
db.transaction {
    executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS "users" (
            "id" uuid NOT NULL,
            "name" text NOT NULL,
            "age" integer NOT NULL,
            PRIMARY KEY ("id")
        )
        """,
    )
    executeUpdate("""CREATE INDEX IF NOT EXISTS users_name_idx ON "users" ("name")""")
}
```

For repeatable, ordered setup use `Database.migrate(...)` from the `kormium-migrate` module (see
[Transactions and migrations](transactions-and-migrations.md)).
