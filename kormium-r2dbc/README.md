# kormium-r2dbc

A truly non-blocking PostgreSQL backend for [Kormium](../readme.md), built on
[r2dbc-postgresql](https://github.com/pgjdbc/r2dbc-postgresql) with an r2dbc connection pool.
**JVM only.**

Unlike `kormium-postgres` on JVM (which offloads blocking JDBC calls onto virtual threads), this
driver is reactive end to end — there is no blocking call to offload. It reuses
`PostgresDialect` from `kormium-postgres` for SQL rendering, so the query DSL is identical.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:<version>"))
    implementation("io.github.kormium:kormium-r2dbc")
}
```

`kormium-core` and `kormium-postgres` are pulled in transitively.

## Example

```kotlin
val db: SuspendDatabase<App> = createR2dbcDatabase(
    host = "localhost",
    port = 5432,
    database = "postgres",
    user = "postgres",
    password = "password",
    poolSize = 10,
)

val adults = db.suspendAutocommit {
    Users.find { where { Users.age gtEq 18 } }
}
```

`createR2dbcDatabase` returns a `SuspendDatabase`, so use the suspend API
(`suspendTransaction { }` / `suspendAutocommit { }`). The Ktor helpers
([`kormium-ktor`](../kormium-ktor/README.md) and friends) work with it unchanged.

## Platforms

JVM only. For PostgreSQL on Native use [`kormium-postgres`](../kormium-postgres/README.md).

## Documentation

- [Backends and platform support](../docs/backends.md)
- [Transactions and the suspend API](../docs/transactions-and-migrations.md)
- See also [`samples/r2dbc`](../samples/r2dbc).
