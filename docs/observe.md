# Observing Changes

`kormium-observe` turns a query into a `Flow` that re-emits whenever the data it reads changes.
It is the building block for reactive UIs (Compose Multiplatform, Android) the way Room's
observable queries are — without Kormium adopting an annotation processor or SQL strings.

```kotlin
dependencies {
    implementation("io.github.kormium:kormium-observe")
}
```

## Observing a Table

```kotlin
import io.github.kormium.observe.observe

val adults: Flow<List<User>> = Users.observe(db) {
    where { Users.age gtEq 18 }
}
```

`Users.observe(db) { … }` emits the query result once immediately, then re-runs the query and
emits again after every committed write that touches the `users` table. With no block it
observes every row. `db` is the suspend database handle (`SuspendDatabase<G>`); every shipped
driver provides one.

The query block is the same one used by [`Table.find`](queries.md): predicates, ordering,
`limit`/`offset`. Reads run in `suspendAutocommit`.

## How Invalidation Works

Kormium tracks which tables each `transaction { }` / `autocommit { }` (and their suspend
counterparts) writes, and notifies observers **after the block commits**. A rolled-back
transaction notifies nothing. Bursts of writes are conflated — a flood of commits collapses
into a single re-fetch rather than one per commit.

```kotlin
// This commit re-fires every Flow observing "users":
db.suspendTransaction { Users.insert(user) }
```

## The Generic Form

For multi-table queries (joins) or custom fetch logic, use the lower-level overload and pass
the tables to watch:

```kotlin
val dashboard: Flow<Dashboard> = db.observe(setOf("users", "orders")) {
    // any SuspendScope read(s)
    Dashboard(
        userCount = Users.count(),
        orderCount = Orders.count(),
    )
}
```

## Raw SQL

Kormium cannot see which tables raw SQL touches, so declare them with `invalidates` so observers
(and any future cache) are notified on commit — the analog of Room's
`@RawQuery(observedEntities = …)`:

```kotlin
db.transaction {
    executeUpdate("UPDATE products SET price = price * 2", invalidates = listOf(Products))
}
```

Without `invalidates`, a raw write commits normally but does not fire observers.

## Boundaries

By default, observation sees writes made **through this database handle's API**. It does not see:

- writes by another process or another `Database` instance over the same database (unless you
  connect a notification transport — see [Cross-process](#cross-process));
- raw SQL whose tables you did not declare via `invalidates`;
- cascading changes from triggers or `ON DELETE CASCADE` (only the table you wrote is marked).

This is the same default boundary Room has for a single in-process database.

## Cross-process

To make `observe` (and any cache built on the same commit hook) re-fire when **another instance**
commits — the multi-instance / clustered case — connect a `NotificationTransport`:

```kotlin
import io.github.kormium.connectNotifications
import io.github.kormium.postgresListenNotifyTransport

val registration = db.connectNotifications(
    postgresListenNotifyTransport(host, port, database, user, password),
)
// ... later, on shutdown:
registration.remove()
```

Once connected, every committed write is published to the transport, and signals from other
instances are delivered into this handle's listeners exactly as a local commit would be — so the
`Flow`s above re-fire cluster-wide with no change to the query code.

Transports are pluggable. Kormium ships the Postgres `LISTEN/NOTIFY` transport with **no external
dependency** (`postgresListenNotifyTransport` for JDBC/libpq, `r2dbcListenNotifyTransport` for
r2dbc — both interoperate on the same channel). Backends without native pub/sub (MySQL, SQLite) use
a broker-backed transport instead; the `cross-instance-cache` sample implements one over Redis with
the multiplatform `rethis` client. Writing your own is two methods — see [NotificationTransport] in
`kormium-core`.

Delivery is best-effort: a transport that drops a signal (a reconnect, a network blip) won't
re-fire observers for that commit, so anything correctness-sensitive (a cache) should also carry a
TTL. Notifications are table-granular.

## Lifecycle

Each collector registers its own listener and removes it automatically when collection stops
(the `Flow` is cold). Nothing to close manually. A backend whose driver does not enable
notification emits the initial value and nothing further.
