# kormium-migrate

A small, raw-SQL schema migration runner for [Kormium](../readme.md). Kormium does not own
schema, so a `Migration` is whatever SQL your backend needs — intentionally **not** portable
across databases.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:<version>"))
    implementation("io.github.kormium:kormium-migrate")
}
```

`kormium-core` is pulled in transitively. Works on every target `kormium-core` supports.

## Example

```kotlin
val appMigrations = listOf(
    Migration("001-create-users", """
        CREATE TABLE "users" ("id" integer PRIMARY KEY, "name" text NOT NULL);
        CREATE INDEX users_name_idx ON "users" ("name");
    """),
    Migration("002-add-age", """ALTER TABLE "users" ADD COLUMN "age" integer NOT NULL DEFAULT 0"""),
)

// Typically from the createX { } builder, so the schema is ready before the first connection:
val db = createSqliteDatabase("app.db") {
    beforeStart { migrate(appMigrations) }
}
```

## How it works

- **Idempotent.** Each `Migration.id` is recorded once in the `kormium_migrations` journal;
  re-running the same list is a safe no-op, so calling `migrate(...)` on every startup is fine.
- **One transaction, all-or-nothing.** If any migration fails the whole batch rolls back and
  nothing is recorded. (So a statement that cannot run inside a transaction — e.g.
  `CREATE INDEX CONCURRENTLY` — cannot be part of a batch.)
- **Checksum validation.** Editing an already-applied migration throws
  `MigrationChecksumException` (Flyway-style fail-fast) — add a new migration instead.
- **Concurrency-safe on PostgreSQL.** A transaction-scoped advisory lock serializes
  concurrently-starting instances. SQLite has no advisory lock, so prefer migrating it from a
  single process (it stays safe, but is not fully serialized cross-process).

The single SQL-string constructor splits on top-level `;` (honouring quotes, `--` and `/* */`
comments, and Postgres `$tag$…$tag$` dollar-quoting). For statements the splitter can't handle,
use the `Migration(id, List<String>)` constructor.

## Documentation

- [Transactions, suspend API and migrations](../docs/transactions-and-migrations.md)
