# kormium-observe

Reactive `Flow` queries for [Kormium](../readme.md), in the spirit of Room: turn a query into a
`Flow` that emits now and re-emits after every committed write that touches the tables it reads.
Ideal for Compose Multiplatform and Android UIs.

Pure common code, built on core's `WriteListener` commit-hook seam. No driver of its own — it
works with any backend whose driver enables write notification (all shipped drivers do).

## Install

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:<version>"))
    implementation("io.github.kormium:kormium-observe")
}
```

`kormium-core` is pulled in transitively. Supports the same targets as `kormium-core` (JVM, Native,
Android, iOS).

## Example

```kotlin
// Typed table overload: emits the matching rows now, then again whenever `users` changes.
val adults: Flow<List<User>> = Users.observe(db) {
    where { Users.age gtEq 18 }
}

// Generic overload: observe an arbitrary read, naming the tables it depends on.
val report: Flow<Report> = db.observe(tables = setOf("users", "orders")) {
    buildReport()
}
```

`db` is a `SuspendDatabase`. Reads run in `suspendAutocommit`, and bursts of writes are
conflated into a single re-fetch.

## Notification boundary

Like Room, notifications cover writes made **through this same database handle**. Writes by
another process, another `Database` instance, or raw SQL that does not name its tables are not
seen — pass the affected table names explicitly for raw SQL.

## Documentation

- [Observability](../docs/observability.md)
- [Observe](../docs/observe.md)
