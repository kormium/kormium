# Sample: sharding

A standalone console app (over **SQLite**, no external database) showing two things at once:

- **Multiple catalogs** — `AccountsCatalog` and `AuditCatalog`, each with its own table and
  database. Using a table against the wrong catalog's database is a **compile error** (see the
  commented line in `Main.kt`).
- **Sharding** — one catalog (`AccountsCatalog`) spread across several `Database` instances,
  routed by key (`id % shards`).

Each shard is its own in-memory SQLite database: every `createSqliteDatabase()` call opens a
fresh one, so no two shards share storage. Pass a file path instead to make a shard outlive the
process.

## Run

Run from the repository root. No Docker needed.

```sh
# JVM ...
./gradlew :samples:sharding:runJvm
# ... or native
./gradlew :samples:sharding:runDebugExecutableNative
```

It prints rows-per-shard (`[3, 3]`), a shard lookup, and the audit log from the second catalog.
