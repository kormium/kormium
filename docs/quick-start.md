# Quick Start

This guide creates a catalog, declares a table, connects to PostgreSQL and runs basic CRUD.

## 1. Define a Catalog

A `Catalog` is a type tag for one logical database. It has no runtime data; it exists so the
compiler can reject using tables with the wrong database handle.

```kotlin
import io.github.kormium.Catalog

object App : Catalog
```

## 2. Define a Table and Entity

```kotlin
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import kotlin.uuid.Uuid

object Users : Table<App, User>("users", ::User) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
    val note by Column.Text().nullable()
}

class User : Entity() {
    var id by Users.id
    var name by Users.name
    var age by Users.age
    var note by Users.note
}
```

Columns register themselves when the delegated properties are declared. Read operations
select columns in declaration order and map rows back into entity fields.

## 3. Connect

```kotlin
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase

val db: Database<App> = createDatabase(
    host = "localhost",
    port = 5432,
    database = "postgres",
    user = "postgres",
    password = "password",
    poolSize = 10,
)
```

Assigning the factory result to `Database<App>` pins the catalog type. The driver itself is
catalog-agnostic.

## 4. Create the Table

Kormium maps queries and rows; it does not own schema management. Create tables with raw
SQL (or a migration tool / `Database.migrate`):

```kotlin
import io.github.kormium.transaction

db.transaction {
    executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS "users" (
            "id" uuid NOT NULL,
            "name" text NOT NULL,
            "age" integer NOT NULL,
            "note" text,
            PRIMARY KEY ("id")
        )
        """,
    )
}
```

## 5. Insert and Read

```kotlin
import io.github.kormium.autocommit
import io.github.kormium.gtEq
import kotlin.uuid.Uuid

val user = User().apply {
    id = Uuid.random()
    name = "Ada"
    age = 36
    note = null
}

db.transaction {
    Users.insert(user)
}

val ada: User? = db.autocommit {
    Users.findOne { where { Users.id eq user.id } }
}

val adults: List<User> = db.autocommit {
    Users.find {
        where { Users.age gtEq 18 }
        orderBy DESC Users.age
        limit = 50
    }
}
```

`transaction { }` wraps the block in `BEGIN` / `COMMIT` / `ROLLBACK`. `autocommit { }`
pins one connection without an explicit transaction, which is useful for simple reads.

## 6. Update and Delete

```kotlin
import io.github.kormium.eq

db.transaction {
    Users.update(User().apply { age = 37 }) {
        where { Users.id eq user.id }
    }

    Users.deleteWhere {
        where { Users.name eq "Ada" }
    }
}
```

`update` only writes properties assigned on the patch entity. An untouched property is
omitted, while a property explicitly set to `null` is written as SQL `NULL`.

## SQLite Variant

The table and query code stays the same. Swap only the factory:

```kotlin
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database

val db: Database<App> = createSqliteDatabase()      // in-memory
val fileDb: Database<App> = createSqliteDatabase("app.db")
```

Next: [Tables and entities](tables-and-entities.md) or [Queries](queries.md).
