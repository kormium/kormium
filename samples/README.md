# Kormium samples

Each subdirectory is a focused, runnable sample. Run gradle commands from the repository root.

| Sample | Backend | Needs Docker | What it shows |
|---|---|---|---|
| [ktor-di](ktor-di) | Postgres | yes (compose) | Ktor CRUD, database from Ktor's built-in DI |
| [ktor-koin](ktor-koin) | Postgres | yes (compose) | Ktor CRUD, database from Koin |
| [r2dbc](r2dbc) | Postgres (async) | yes (compose) | Same Ktor CRUD on the non-blocking r2dbc driver (JVM only) |
| [sqlite-cache](sqlite-cache) | Postgres + SQLite | yes (compose) | SQLite as a read-through cache in front of Postgres |
| [cross-instance-cache](cross-instance-cache) | Postgres + Redis | yes (compose) | Cross-instance cache invalidation via a Redis `NotificationTransport` (rethis) |
| [crud-sqlite](crud-sqlite) | SQLite | no | Standalone CRUD + migrations (JVM + native) |
| [repository](repository) | SQLite | no | A copyable Repository pattern: CRUD, custom queries, observe, cross-repo transactions |
| [sharding](sharding) | SQLite | no | Multiple catalogs (compile-time safety) + sharding |
| [sqlite-vec](sqlite-vec) | SQLite | no | A SQLite extension packaged for Kormium ([sqlite-vec](https://github.com/asg017/sqlite-vec)): vector search via `sqlite { extension(SqliteVec) }` (Kotlin/Native) |
| [sqlite-uuid](sqlite-uuid) | SQLite | no | A second extension package (SQLite's `ext/misc/uuid.c`), showing that extensions from different packages compose |

The Postgres samples ship a `docker-compose.yml` matching their connection settings:

```sh
docker compose -f samples/<name>/docker-compose.yml up -d   # start Postgres
./gradlew :samples:<name>:runJvm                            # run the sample
docker compose -f samples/<name>/docker-compose.yml down    # stop Postgres
```

The SQLite samples need nothing external — just `./gradlew :samples:<name>:runJvm` (or
`:runDebugExecutableNative`). Each sample's own README has the details, endpoints and tests.
