# Benchmarks

The full comparison matrix: **Kormium JVM**, **Kormium Native** (libpq, no JVM),
**Exposed** and **Hibernate**, all against the same PostgreSQL workload.

## TL;DR

```bash
# from the repo root; the only prerequisite is a running Docker daemon
./benchmarks/run.sh
```

The script starts one tuned PostgreSQL container, runs the Kotlin/Native harness, then the
JVM JMH benchmarks, and prints a merged summary (~20 minutes):

```
Benchmark summary — ops/s, higher is better
═══════════════════════════════════════════
Operation    Kormium JVM  Kormium Native  Exposed  Hibernate
------------------------------------------------------------
findById          16,253          22,988    7,688     15,535
selectWhere       16,022          24,096    7,637     15,873
...
```

Useful flags:

| Flag | Effect |
| --- | --- |
| `--quick` | fast indicative run, ~3 minutes — for checking the setup, not for quoting |
| `--skip-native` | JVM ORMs only (works on hosts without a native toolchain) |
| `--skip-jvm` | native harness only, then re-render the merged summary |

Results are written to:

- `benchmarks/build/results/jmh/summary.md` — the merged table as Markdown;
- `benchmarks/build/results/jmh/results.json` — full JMH output with per-iteration data;
- `benchmarks/build/results/jmh/native.json` — the native harness numbers.

To re-print the table from the last run without re-running anything:

```bash
./gradlew :benchmarks:benchmarkSummary
```

## Windows

Use the batch counterpart (requires Docker Desktop):

```bat
benchmarks\run.bat [--quick] [--skip-native] [--skip-jvm]
```

The native column is built for the experimental mingwX64 target and needs a Windows
libpq. The easiest route is MSYS2 (`winget install MSYS2.MSYS2`, then
`C:\msys64\usr\bin\pacman -S mingw-w64-x86_64-postgresql`); an EDB PostgreSQL install
or anything exposing `pg_config` on PATH also works — the script auto-detects all three
and passes the paths to Gradle (`-Plibpq.include` / `-Plibpq.lib`, settable manually for
unusual layouts). Without a libpq the script explains what to install and runs the JVM
matrix only.

## JVM-only runs via Gradle

`./gradlew :benchmarks:jmh` runs the JVM matrix on its own throwaway Testcontainers
PostgreSQL. `-Pbench.quick` applies the quick profile, and `-Pbench.filter` is a regex
over benchmark names:

```bash
./gradlew :benchmarks:jmh "-Pbench.filter=FindById"          # one operation, all ORMs
./gradlew :benchmarks:jmh "-Pbench.filter=kormium(Insert|Update)"
```

## What is measured

Six operations per ORM, all against the same table (uuid primary key, `text` + `numeric`
columns, index on `name`), 8 benchmark threads, connection pool of 8 for every ORM:

| Operation | Shape |
| --- | --- |
| `findById` | `SELECT` by primary key, autocommit |
| `selectWhere` | `SELECT ... WHERE name = ?`, index lookup returning 1 row |
| `selectMany` | same, returning 100 rows — measures row materialization |
| `insert` | single-row `INSERT` in a transaction |
| `batchInsert` | 50 rows per transaction, each ORM's idiomatic batch API* |
| `updateById` | single-row `UPDATE` by primary key (random row out of 1024) |

\* kormium `insertAll` (multi-row `VALUES`), Exposed `batchInsert`, Hibernate `persist`
loop with `hibernate.jdbc.batch_size=50`. The score unit is transactions/s, so one
`batchInsert` op writes 50 rows.

Competitor versions: Exposed 1.0.0-beta-4 and Hibernate ORM 7.0.2.Final, both via
HikariCP (see `build.gradle.kts`).

The native harness (`NativeBenchmark.kt` in `kormium-postgres/src/nativeTest`) runs the
same six operations with the same seeding on 8 workers, compiled as an optimized release
binary (`linkBenchReleaseTest`) — the default debug test binary understates CPU-bound
operations (row materialization, batch binding) by 2-3x. It has no JMH, so its numbers
come from a single timed run without an error estimate — treat them as coarser.

## Methodology and stability

- JMH 1.37; per benchmark: 2 forks, 5×2s warmup + 5×2s measurement, fixed 2 GiB heap.
- PostgreSQL (`postgres:18-alpine`) keeps its data directory on tmpfs and runs with
  `fsync`, `synchronous_commit` and `full_page_writes` off. The benchmarks measure
  ORM/driver overhead, not disk latency — disk is by far the noisiest component,
  especially under Docker on macOS. All ORMs get the same database.
- The table is truncated and reseeded between iterations (seed row, 100 bulk rows,
  1024 update targets), so write benchmarks do not grow it and drag later iterations.

## Using an external PostgreSQL

For Gradle runs, pass coordinates as properties (environment variables do not reliably
reach forked JMH JVMs through the Gradle daemon):

```bash
./gradlew :benchmarks:jmh -Pbench.db.host=db.example.com -Pbench.db.port=5432 \
    -Pbench.db.name=postgres -Pbench.db.user=postgres -Pbench.db.password=secret
```

The `KORMIUM_DB_HOST/PORT/NAME/USER/PASSWORD` environment variables are also honored
(by both the JVM and native harnesses) when set in the process that actually runs them —
`run.sh` uses this to point everything at its own container.

Note: the durability tweaks above only apply to the container started by `run.sh` /
Testcontainers. An external server is used as-is, so its numbers are not comparable
with container runs.

## Honesty notes

These are the project's own benchmarks. Treat every number as relative: compare columns
within one run on one machine, not against tables published elsewhere. The native column
comes from a simpler harness than JMH. Run everything on your own hardware and database
before making architecture decisions.
