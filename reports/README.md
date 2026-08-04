# Performance reports

Working notes from the Kotlin/Native CPU-path optimization effort on `kormium-core`.

Each report records what was measured, on what, and what changed. Numbers are from one
machine and one run unless stated otherwise — they are for comparing **columns within a
report**, not for quoting as absolute throughput.

| # | Report | Subject | Headline |
| --- | --- | --- | --- |
| 00 | [baseline.md](00-baseline.md) | JVM vs Kotlin/Native CPU baseline, cost attribution | Native 3x–12x slower on CPU |
| 01 | [logger-closure.md](01-logger-closure.md) | Fix 1 — logger closure in column accessors | field reads −37.8% on Native |
| 02 | [double-lookup.md](02-double-lookup.md) | Fix 2 — double map lookup in `getValue` | field reads −29.7% (−56.7% cumulative) |
| 03 | [ordinal-storage.md](03-ordinal-storage.md) | Fix 3 — ordinal-indexed entity storage | hydration −28.3%, field reads −45.4% |
| 04 | [sql-rendering.md](04-sql-rendering.md) | Fix 4 — per-query SQL rendering | SELECT rendering −60.9% |
| 05 | [column-iteration.md](05-column-iteration.md) | Fix 5 — per-row `LinkedHashMap` walk | hydration −62.1%; boxing measured and dismissed |
| 06 | [end-to-end.md](06-end-to-end.md) | Through a real driver (native SQLite) | 100-row SELECT 1.96x; ~70% of what remains is driver-side |
| 07 | [uuid-parsing.md](07-uuid-parsing.md) | Hand-rolled UUID parser — **rejected** | 27% slower than stdlib once correct; nothing shipped |

## Cumulative result (Kotlin/Native, `mingwX64`, fixes 1–5)

| Workload | baseline | now | change |
| --- | ---: | ---: | ---: |
| Hydrate 100 rows × 6 columns | 127 526 ns | 31 920 ns | **−75.0%** (4.0x) |
| Entity field read | 75.2 ns | 18.2 ns | **−75.8%** (4.1x) |
| Render a SELECT | 8 724 ns | 3 189 ns | **−63.4%** (2.7x) |
| Render an INSERT | 8 390 ns | 7 005 ns | **−16.5%** |

Public API unchanged throughout; `apiCheck` green at every step. Tests: 104 JVM / 102 Native,
zero failures.

### Costs that were measured and left alone

- **Boxing** of `Int`/`Long`/`Boolean` into `Any?` — measured at ~5.1 ns per value, ~1.8% of
  hydration. Eliminating it needs typed per-column storage across two public interfaces and
  every driver. Not worth it (report 05).
- **`try`/`catch` per cell** in `readColumn` — measured free.

## Through a real driver

The table above is core in isolation (fake `ResultSet`). Report 06 measures the same fixes
end-to-end on native SQLite in memory:

| Operation | baseline | now | change |
| --- | ---: | ---: | ---: |
| `SELECT` 100 rows | 211 022 ns | 107 578 ns | **1.96x** |
| `SELECT` 1 row by primary key | 15 691 ns | 9 804 ns | **1.6x** |
| `INSERT` one row | 18 158 ns | 16 214 ns | 1.12x |

After the fixes, roughly **70% of a 100-row read is driver-side** — cinterop calls and
`toKString()` UTF-8 decoding per text cell — so further core CPU work has little left to give.

### Not yet investigated

Per-cell C-string conversion and bind/step machinery in the drivers, and server-side statement
planning in the native PostgreSQL driver (it uses `PQexecParams` with `paramTypes = null`, so
the server re-plans every execution, while pgjdbc switches to server-side prepared statements
after five). The latter needs a Windows `libpq` and a running PostgreSQL.

### Second harness

`kormium-sqlite/src/commonTest/kotlin/SqliteE2EBench.kt` — end-to-end, real driver.

```bash
./gradlew :kormium-sqlite:linkBenchReleaseTestMingwX64
./kormium-sqlite/build/bin/mingwX64/benchReleaseTest/bench.exe --ktest_filter=SqliteE2EBench.benchmark
```

## Measuring a change

Absolute numbers are **not** comparable across reports — machine noise between sessions
reaches 2x and will invent or hide any effect you are looking for. Report 01 has a worked
example of a measurement invalidated exactly this way.

Use paired A/B instead:

1. Build the bench binary with the change, save it aside.
2. `git stash push -- <only the changed source files>`, rebuild, save the baseline binary.
3. `git stash pop`, then run the two binaries **alternately** for several rounds and take
   the minimum per metric.
4. Check the `probe*` results last. They contain no Kormium code, so they must agree between
   the two binaries. If they do not, the run is contaminated — discard it.

## Reproducing

The harness is `kormium-core/src/commonTest/kotlin/CorePerfBench.kt` — a CPU-only
benchmark over a fake `ResultSet`. No database, no driver, no I/O.

```bash
# JVM
./gradlew :kormium-core:jvmTest --tests "CorePerfBench" --rerun -i

# Kotlin/Native — MUST be the optimized binary, see the caveat in report 00
./gradlew :kormium-core:linkBenchReleaseTestMingwX64
./kormium-core/build/bin/mingwX64/benchReleaseTest/bench.exe --ktest_filter=CorePerfBench.benchmark
```

The `benchRelease` test binary is registered in `kormium-core/build.gradle.kts`, mirroring
the one `kormium-postgres` already uses for `benchmarks/run.sh`. It is linked only on
explicit request, so ordinary test and CI builds do not pay for it.
