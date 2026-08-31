# kormium-ktor

DI-agnostic [Ktor](https://ktor.io) integration for [Kormium](../readme.md). You pass the
database explicitly, so it works with any way of obtaining it — a plain reference, Ktor's
built-in DI, or Koin.

It provides:

- `ApplicationCall.transaction(db) { }` / `autocommit(db) { }` — suspend transaction helpers
  that never block the worker (work with any `SuspendDatabase`, including offloaded JDBC/libpq
  and async r2dbc).
- `KormException.httpStatusCode()` — map database exceptions to a Ktor `HttpStatusCode`.
- The optional `Korm` plugin — closes databases registered with `manage(db)` on application
  stop.

For automatic database resolution by catalog type, add
[`kormium-ktor-di`](../kormium-ktor-di/README.md) (Ktor's built-in DI) or
[`kormium-ktor-koin`](../kormium-ktor-koin/README.md) (Koin).

## Install

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:<version>"))
    implementation("io.github.kormium:kormium-ktor")
}
```

## Example

```kotlin
fun Application.module() {
    install(Korm) { manage(database) } // optional: close db on stop

    install(StatusPages) {
        exception<KormException> { call, e ->
            call.respond(e.httpStatusCode(), e.message ?: "database error")
        }
    }

    routing {
        get("/users") {
            val users = call.autocommit(database) { Users.all() }
            call.respond(users.map { it.name })
        }
        post("/users") {
            val saved = call.transaction(database) { Users.insert(user, returning = true) }
            call.respond(HttpStatusCode.Created, saved)
        }
    }
}
```

The `Korm` plugin is optional — skip it if your DI container owns the database lifecycle.

## Platforms

JVM, Native, Android and iOS (the same targets Ktor server supports).

## Documentation

- [Ktor integration](../docs/ktor.md)
