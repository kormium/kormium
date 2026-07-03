# API Cookbook

This page collects practical recipes. It is intentionally example-heavy and should grow as
real usage patterns appear.

## Define a Table

```kotlin
object App : Catalog

object Users : Table<App, User>("users", ::User) {
    val id by Column.UUID().primaryKey()
    val email by Column.Text()
    val name by Column.Text()
    val deletedAt by Column.Instant().nullable()
}

class User : Entity() {
    var id by Users.id
    var email by Users.email
    var name by Users.name
    var deletedAt by Users.deletedAt
}
```

## Create Tables and Indexes

Kormium does not manage the database schema automatically, but provides a built-in migration tool. It is recommended to use the kormium-migrate module for schema management.

```kotlin
db.migrate(
    listOf(
        Migration(
            "001-init",
            """
            CREATE TABLE "users" (
                "id" uuid PRIMARY KEY,
                "name" text NOT NULL
            );
            """
        )
    )
)
```

## Foreign Keys and Check Constraints

Kormium does not model foreign keys, composite/partial indexes or check constraints in the
table DSL — declare them in the same raw SQL that creates the table (or in a migration). Raw SQL
needs `@OptIn(DelicateKormiumApi::class)` in scope, and `executeUpdate` always takes `params` and
`invalidates` explicitly:

```kotlin
db.transaction {
    executeUpdate(
        """CREATE TABLE IF NOT EXISTS "orders" (
             "id" uuid NOT NULL,
             "userId" uuid NOT NULL REFERENCES "users" ("id"),
             "total" integer NOT NULL CHECK ("total" >= 0),
             PRIMARY KEY ("id")
           )""",
        params = emptyMap(),
        invalidates = emptyList(),
    )
    executeUpdate(
        """CREATE INDEX IF NOT EXISTS orders_user_idx ON "orders" ("userId")""",
        params = emptyMap(),
        invalidates = emptyList(),
    )
}
```

`CREATE INDEX CONCURRENTLY` cannot run inside a transaction, so it does not belong in a
migration batch (migrations run in one transaction) — issue it on its own connection.

## Insert a Row

```kotlin
val user = User().apply {
    id = Uuid.random()
    email = "ada@example.com"
    name = "Ada"
    deletedAt = null
}

db.transaction {
    Users.insert(user)
}
```

## Fetch Database-Generated Values

```kotlin
val saved: User? = db.transaction {
    Users.insert(user, returning = true)
}
```

Use `returning = true` when the database may fill values you want to read back.

## Batch Insert

```kotlin
val saved: List<User> = db.transaction {
    Users.insertAll(listOf(user1, user2, user3), returning = true)
}
```

## Partial Update

```kotlin
db.transaction {
    Users.update(User().apply { name = "Ada Lovelace" }) {
        where { Users.id eq id }
    }
}
```

Only assigned fields are written.

## Set a Nullable Column to NULL

```kotlin
db.transaction {
    Users.update(User().apply { deletedAt = null }) {
        where { Users.id eq id }
    }
}
```

An assigned `null` is different from an untouched property. Kormium writes assigned `null`
values as SQL `NULL`.

## Paginate

```kotlin
val page = db.autocommit {
    Users.find {
        where { Users.deletedAt eq null }
        orderBy ASC Users.email
        limit = 50
        offset = 100
    }
}
```

Offset pagination is simple and portable. For high-volume feeds, prefer keyset (seek)
pagination: order by a key, then page forward by passing the last row of the previous page as
the cursor. The first page passes `null`. Add a unique tie-breaker (here `id`) so rows that
share the ordering key are neither skipped nor repeated.

```kotlin
fun usersAfter(cursor: User?): List<User> = db.autocommit {
    Users.find {
        where { Users.deletedAt eq null }
        if (cursor != null) {
            where {
                (Users.email gt cursor.email) or
                    ((Users.email eq cursor.email) and (Users.id gt cursor.id))
            }
        }
        orderBy ASC Users.email
        orderBy ASC Users.id
        limit = 50
    }
}
```

Each `where { }` is ANDed with the others; multiple `orderBy` calls keep their order.

## Case-Insensitive Match (instead of `ILIKE`)

Kormium has no `ilike` operator. Plain `like` / `eq` follow the engine's **collation**, so their
case behavior differs across PostgreSQL, MySQL and SQLite. For a result that is identical on every
backend, lower **both sides** explicitly with the `lower()` scalar function:

```kotlin
// Case-insensitive equality — matches "Ada", "ADA", "ada" on every engine.
val ada = db.autocommit {
    Users.find { where { Users.name.lower() eq "ada" } }            // LOWER("name") = 'ada'
}

// Case-insensitive LIKE — the pattern is already lowercase, since the left side is lowered.
val examples = db.autocommit {
    Users.find { where { Users.email.lower() like "%@example.com" } }
}
```

`lower()` chains, and the right side can be another lowered column:

```kotlin
Users.find { where { Users.name.trim().lower() eq "ada" } }
Users.find { where { Users.name.lower() eq Users.email.lower() } }
```

`LOWER("name")` cannot use a plain index on `name`. If you match on it often, add a functional
index so the lookup stays fast (raw SQL, so `@OptIn(DelicateKormiumApi::class)` and explicit
`params`/`invalidates` apply here too):

```kotlin
db.transaction {
    executeUpdate(
        """CREATE INDEX IF NOT EXISTS users_name_lower_idx ON "users" (LOWER("name"))""",
        params = emptyMap(),
        invalidates = emptyList(),
    )
}
```

`lower()` settles only the case dimension; locale ordering and accents still follow the collation.
See [ADR 0002](adr/0002-no-ilike-explicit-lower.md) for the full rationale.

## Render a Query's SQL Without Running It

`renderSql { }` produces the SQL a query *would* run — as a string plus its bound parameters —
without a connection. The block reads exactly like a `transaction { }` / `autocommit { }` body,
but each operation returns its `RenderedSql` instead of executing. Useful to inspect, log, or
have a coding agent self-check a query before it runs.

```kotlin
import io.github.kormium.renderSql

// Offline: pass the Catalog (for the type tag) and a dialect.
val r = renderSql(App, PostgresDialect) {
    Users.find { where { Users.age gtEq 18 } }
}
println(r.sql)     //  SELECT "id", "name", "age" FROM "users" WHERE "age" >= :p0 ...
println(r.params)  //  {p0=18}

// Against a live database, using its own dialect:
val r2 = db.renderSql { Users.deleteWhere { where { Users.deletedAt neq null } } }
```

Reads, writes and joins all render. A batch `insertAll` may split into several statements, so it
returns a `List<RenderedSql>`. For a suspend-only backend (r2dbc), pass its dialect to the offline
form: `renderSql(App, db.dialect) { ... }`.

## Count Rows

```kotlin
val total = db.autocommit {
    Users.count()
}

val active = db.autocommit {
    Users.count {
        where { Users.deletedAt eq null }
    }
}
```

## Left Join with Nullable Right Side

The join examples assume an `Orders` table declared in the same style as `Users`.

```kotlin
val rows = db.autocommit {
    (Users leftJoin Orders on (Users.id eq Orders.userId))
        .select(Users.email, Orders.total)
}

rows.forEach { row ->
    val email = row[Users.email]
    val total = row.getOrNull(Orders.total)
}
```

Use `getOrNull` when a selected field can be absent or SQL `NULL`.

For entity pairs, `find()` on a `leftJoin` returns a nullable right side:

```kotlin
val pairs: List<Pair<User, Order?>> = db.autocommit {
    (Users leftJoin Orders on (Users.id eq Orders.userId)).find()
}
```

## Project Into a Data Class

To read a subset of columns instead of full entities, start from `Table.query()`, `select`
the columns you need, and map each `ResultRow` to your own type. Read columns with `row[field]`
(throws on SQL `NULL`) or `row.getOrNull(field)`.

```kotlin
data class UserCard(val email: String, val name: String)

val cards: List<UserCard> = db.autocommit {
    Users.query()
        .select(Users.email, Users.name)
        .map { row -> UserCard(row[Users.email], row[Users.name]) }
}
```

The same `select(...)` works on a join, so a projection can span tables.

## Aggregate and Read the Result

```kotlin
val orderCount = Orders.id.count()
val totalSpent = Orders.total.sum()

val rows = db.autocommit {
    (Users innerJoin Orders on (Users.id eq Orders.userId))
        .groupBy(Users.id)
        .select(Users.email, orderCount, totalSpent)
}

rows.forEach { row ->
    println("${row[Users.email]}: ${row[orderCount]} orders, ${row[totalSpent]}")
}
```

Keep aggregates in variables and read rows with the same instances.

## Compose Transactional Helpers

This example assumes an `AuditEvents` table and `AuditEvent` entity in the same catalog.

```kotlin
fun Scope<App>.registerUser(user: User) {
    Users.insert(user)
    AuditEvents.insert(AuditEvent.forUser(user.id))
}

db.transaction {
    registerUser(user)
}
```

This helper joins the caller's transaction. It does not open a second connection.

## Use Suspend API in a Handler

```kotlin
suspend fun listUsers(db: SuspendDatabase<App>): List<User> =
    db.suspendAutocommit {
        Users.find {
            where { Users.deletedAt eq null }
        }
    }
```

The same table DSL works with blocking and suspend scopes.

## Handle Constraint Errors

```kotlin
try {
    db.transaction {
        Users.insert(user)
    }
} catch (e: UniqueViolationException) {
    // Return 409 Conflict or equivalent application error.
}
```

For Ktor, `kormium-ktor` includes `KormiumException.httpStatusCode()`.

## Run Migrations on Startup

Migrations live in the `kormium-migrate` module (`implementation("io.github.kormium:kormium-migrate")`).
A migration is raw SQL; one string is split into statements on top-level `;`.

```kotlin
import io.github.kormium.migrate.Migration
import io.github.kormium.migrate.migrate

db.migrate(
    listOf(
        Migration("001-create-users", """
            CREATE TABLE "users" ("id" uuid PRIMARY KEY, "email" text NOT NULL, "name" text NOT NULL);
            CREATE UNIQUE INDEX users_email_idx ON "users" ("email");
        """),
    ),
)
```

Migration IDs are permanent, and the SQL is checksummed once applied — editing an already-applied
migration fails fast with `MigrationChecksumException`. Add a new migration instead.

## Configure a Database with a Builder

`createSqliteDatabase { }` / `createDatabase { }` take an optional configuration block. `config { }`
sets `KormiumConfig`; `beforeStart { }` runs once before the database is returned — the place to run
migrations (the `kormium-migrate` module, or Flyway/Liquibase). The receiver is the database, so a
migration list resolves its own catalog:

```kotlin
val db: Database<App> = createSqliteDatabase("app.db") {
    config { batchInsertMode = BatchInsertMode.UnionNulls }
    beforeStart { migrate(appMigrations) }
}
```

Migrations are not a built-in concern of the builder — `beforeStart` is a generic startup hook, so
you can run any tool there (e.g. `Flyway.configure().dataSource(url, user, pw).load().migrate()`).
Seed data belongs in a migration, not in `beforeStart`.

## Use SQLite in Tests

```kotlin
val db: Database<App> = createSqliteDatabase()

db.transaction {
    executeUpdate(
        """CREATE TABLE IF NOT EXISTS "users" ("id" INTEGER NOT NULL, "name" TEXT NOT NULL, PRIMARY KEY ("id"))""",
        params = emptyMap(),
        invalidates = emptyList(),
    )
}
```

The default SQLite database is in-memory and lives while the database handle is open.

## Resolve a Database in Ktor DI

```kotlin
dependencies {
    provide<SuspendDatabase<App>> {
        createDatabase(
            host = "localhost",
            database = "postgres",
            user = "postgres",
            password = "password",
        )
    }
}

routing {
    get("/users") {
        call.respond(
            call.autocommit<App, _> {
                Users.all()
            }
        )
    }
}
```

Use `SuspendDatabase` in server routes so the route body can suspend naturally.

## A Repository

Kormium does not ship a `Repository` type — like Exposed, you call table operations inside
`suspendTransaction { }` / `suspendAutocommit { }`. When you want a Room-style home for a table's
queries, this small base is the recommended pattern; copy it and adapt it (it is yours to change):

```kotlin
abstract class Repository<G : Catalog, T : Entity, ID>(
    protected val db: SuspendDatabase<G>,
    protected val table: Table<G, T>,
    private val idColumn: Column<ID, *, T>,   // typed primary key, so findById is checked
) {
    suspend fun findById(id: ID) = db.suspendAutocommit { table.findOne { where { idColumn eq id } } }
    suspend fun all() = db.suspendAutocommit { table.all() }
    suspend fun insert(entity: T) = db.suspendTransaction { table.insert(entity) }
    fun observeAll(): Flow<List<T>> = table.observe(db)                 // needs kormium-observe
    protected suspend fun <R> read(block: suspend SuspendScope<G>.() -> R) = db.suspendAutocommit(block)
    protected suspend fun <R> write(block: suspend SuspendScope<G>.() -> R) = db.suspendTransaction(block)
}

class UserRepository(db: SuspendDatabase<App>) : Repository<App, User>(db, Users) {
    suspend fun adults() = read { Users.find { where { Users.age gtEq 18 } } }
    fun observeAdults() = Users.observe(db) { where { Users.age gtEq 18 } }
}
```

Each method is its own transaction. To make several repository operations atomic, wrap their
table operations in one outer `suspendTransaction { }` (the Unit of Work lives in your service):

```kotlin
db.suspendTransaction {
    Users.insert(user)
    Orders.insert(order)
}
```

For unit-testing services without a database, depend on a domain interface and implement it via
this base, then pass a fake in tests. See the runnable [repository sample](../samples/repository).
