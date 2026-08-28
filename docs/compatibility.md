# Compatibility Policy

This page defines the compatibility policy Kormium should follow. Until 1.0, it is a target
policy rather than a strict guarantee.

## Current Version Status

Kormium is pre-1.0. Public APIs may change between minor versions. The project should still
avoid unnecessary churn and document breaking changes in [CHANGELOG.md](../CHANGELOG.md).

## Supported Runtime Baseline

| Area | Current baseline |
| --- | --- |
| JDK | 21+ |
| Kotlin | 2.4.x project line |
| Gradle | Gradle wrapper in the repository |
| PostgreSQL | Tested with PostgreSQL 18 in CI/sample infrastructure |
| MySQL / MariaDB | JDBC (`mysql-connector-j`), native (`libmariadb`) and r2dbc; driver versions pinned in Gradle files |
| SQLite | Driver versions pinned in Gradle files |
| Ktor | Version pinned in Ktor module Gradle files |
| Android | minSdk 24, compileSdk 36 |

When these baselines change, the change should be called out in the changelog.

## Platform Support

| Platform | Policy |
| --- | --- |
| JVM | Primary target |
| Linux Native | Supported for core/PostgreSQL/SQLite where CI runs tests |
| macOS Native | Published for x64 and arm64; compile and smoke coverage should be kept |
| Android | Supported for core and SQLite compilation/runtime path |
| iOS | Supported for core/SQLite compilation; production backend story is SQLite only |
| Windows Native | Experimental (mingwX64): CI runs JVM and native (libpq + SQLite) tests on a Windows runner; no compatibility guarantee until it bakes for a release cycle |
| Wasm | Research, no compatibility guarantee yet |

## API Stability Levels

Kormium should classify APIs before 1.0:

| Level | Meaning |
| --- | --- |
| Stable candidate | Expected to survive 1.0 with minimal changes |
| Experimental | Useful but may change before 1.0 |
| Internal | Not intended for application code |

Suggested stable candidates:

- `Catalog`;
- `Table`;
- `Column` declarations;
- `Entity`;
- `Database` and `SuspendDatabase`;
- `transaction` / `autocommit`;
- `suspendTransaction` / `suspendAutocommit`;
- typed predicates;
- basic `Query`;
- typed constraint exceptions.

Suggested experimental areas:

- joins and aggregations;
- migrations;
- raw SQL expression escape hatches;
- Ktor DI convenience call styles;
- backend factory optional parameters;
- future observability APIs.

## Breaking Change Policy Before 1.0

Before 1.0, breaking changes are allowed when they improve long-term API quality, but they
should follow rules:

- document the change in the changelog;
- provide a migration note when the replacement is not obvious;
- avoid breaking multiple unrelated APIs in one release;
- prefer deprecation first when practical;
- update docs and samples in the same change.

## Breaking Change Policy After 1.0

After 1.0, the target policy should be:

- no source-breaking changes in patch releases;
- no intentional binary-breaking changes in patch releases;
- deprecate before removal;
- removals happen only in the next major version;
- backend support changes require a changelog entry and migration note.

## Dependency Compatibility

Kormium should document and test dependency ranges for:

- Kotlin Gradle plugin;
- kotlinx.coroutines;
- kotlinx.datetime;
- Ktor;
- HikariCP;
- PostgreSQL JDBC driver;
- sqlite-jdbc;
- AndroidX SQLite;
- r2dbc-postgresql.

Until ranges are tested, docs should state pinned versions instead of claiming broad
compatibility.

## Database Compatibility

Minimum docs needed:

- PostgreSQL versions tested in CI;
- the SQLite version each engine carries — they differ per engine and are listed in
  [Backends](backends.md#which-sqlite-each-engine-carries);
- known backend differences in DDL and type mapping;
- SQLSTATE/error mapping support by backend.

## Documentation Compatibility

Every public example in `README.md` and `docs/` should be treated as part of the user
contract:

- examples should compile against the current API or clearly say they are schematic;
- changed examples should be updated with code changes;
- docs should not promise a platform/backend combination that CI does not build or test.

## Release Checklist

Before tagging a release:

- run the CI matrix;
- update changelog;
- update compatibility table if dependencies or targets changed;
- verify Maven coordinates and BOM;
- verify docs links;
- run focused sample tests;
- publish release notes with breaking changes first.
