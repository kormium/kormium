# Sample: crud-sqlite

A standalone console app — **no server, no external database**. It does migrations + CRUD over
**SQLite**, and the whole sample lives in `commonMain`, so the exact same code runs on the JVM and
on Kotlin/Native.

Shows: `createSqliteDatabase()`, `Database.migrate(...)`, `transaction { }` / `autocommit { }`,
`insert` / `findOne { ... }` / `find { ... }` / `update` / `deleteWhere`.

## Run

Run from the repository root. No Docker needed.

```sh
# JVM ...
./gradlew :samples:crud-sqlite:runJvm
# ... or native
./gradlew :samples:crud-sqlite:runDebugExecutableNative
```

It uses an in-memory database by default; pass a path to `createSqliteDatabase("app.db")` in
`Main.kt` to persist to a file instead. The run prints a single-row `findOne` lookup, a filtered
query, and the rows remaining after an update + delete.
