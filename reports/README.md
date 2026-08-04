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

## Cumulative result (Kotlin/Native, `mingwX64`, fixes 1–4)

| Workload | baseline | now | change |
| --- | ---: | ---: | ---: |
| Entity field read | 69.9 ns | 16.1 ns | **−76.9%** (4.3x) |
| Render a SELECT | 8 148 ns | 3 064 ns | **−62.4%** (2.7x) |
| Hydrate 100 rows × 6 columns | 118 860 ns | 79 283 ns | **−33.3%** (1.5x) |
| Render an INSERT | 7 871 ns | 6 877 ns | **−12.6%** |

Public API unchanged throughout; `apiCheck` green at every step. Tests: 104 JVM / 102 Native,
zero failures.

Not addressed: boxing of `Int`/`Long`/`Boolean` into `Any?`, plus `ResultSet` and `ColumnType`
virtual dispatch. Report 00 attributed ~63% of hydration cost to that group, and it is
untouched — removing it needs typed per-column storage, a much larger change.

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
