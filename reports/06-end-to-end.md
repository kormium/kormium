# 06 — End-to-end: what the fixes are worth through a real driver

**Question.** Reports 00–05 all used a fake `ResultSet`, so they measured core and nothing
else. Through a real driver — statement preparation, cinterop, `toKString()` UTF-8 decoding,
value conversion — how much of the 4x is left?

**Answer.** A 100-row SELECT is **1.96x faster**; a single-row SELECT **1.6x**; an INSERT
**1.12x**. And after the fixes, roughly **70% of a 100-row read is driver-side work**, not
core — so core CPU optimization has little left to give.

## Setup

`kormium-sqlite/src/commonTest/kotlin/SqliteE2EBench.kt`, SQLite **in memory** on
Kotlin/Native (`mingwX64`), optimized `benchRelease` binary. Same six-column table shape as
`CorePerfBench`, so the two harnesses line up.

SQLite rather than PostgreSQL for one practical and one methodological reason. Practical:
this machine has Docker but no Windows `libpq`, which the native PostgreSQL driver must link
against; SQLite is built from a vendored amalgamation already in the repo, so it needs no
installation. Methodological: an in-memory SQLite has no socket and no disk, so what remains
after the query is pure CPU — exactly the thing under test. A networked PostgreSQL would bury
these differences under round-trip latency, which is the point made in report 00 and is
unchanged.

Baseline binary was built by checking out `kormium-core`'s main sources from `main`
(`git checkout main -- kormium-core/src/commonMain …`) and relinking; the benchmark and the
driver are byte-identical between the two. Paired A/B, alternating, minimum of 5.

## Results

ns/op, lower is better.

| Operation | baseline | after fixes 1–5 | change |
| --- | ---: | ---: | ---: |
| `SELECT` returning 100 rows | 211 022 | 107 578 | **−49.0%** (1.96x) |
| `SELECT` returning 1 row by primary key | 15 691 | 9 804 | **−37.5%** (1.6x) |
| `INSERT` one row in a transaction | 18 158 | 16 214 | **−10.7%** |

## What this says

**The core-only numbers were real but not the whole story.** Hydration got 4.0x faster in
isolation (report 05); through the driver the same read is 1.96x faster. Nothing is wrong
with either figure — they measure different denominators. The core work was simply never
100% of the cost.

**Most of what is left is the driver.** Taking the 100-row SELECT: core hydration accounts for
roughly 32 000 ns of the 107 578 ns that remain, so about **70% is now driver-side** —
sqlite3 cinterop calls, statement stepping, and `toKString()` decoding UTF-8 into a Kotlin
`String` for every text cell. Before the fixes that share was roughly 40%. Optimizing core
further would be chasing the smaller half.

(The two harnesses ran in different sessions, so this split is indicative, not a precise
subtraction.)

**Writes barely moved, as expected.** An `INSERT` renders one statement and hydrates nothing,
so it only picks up the rendering improvements — and even those are a small part of a
statement that has to cross into SQLite and commit.

## Where the next win is, if one is wanted

Per row, the remaining 100-row read costs ~1 076 ns, of which ~757 ns is driver work — about
126 ns per cell. The obvious suspects there are the per-cell C-string conversions and the
per-statement bind/step machinery, neither of which this effort touched. That is a different
piece of work in a different module, and it would need its own attribution pass before
anything is promised.

For the native PostgreSQL driver there is also a structural item that no amount of CPU work
addresses: it executes every statement with `PQexecParams` and `paramTypes = null`, so the
server re-parses and re-plans each time, while pgjdbc switches to server-side prepared
statements after five executions. Evaluating that needs a Windows `libpq` and a running
PostgreSQL.

## Verification

- `:kormium-sqlite:jvmTest` — 74 tests, 0 failures
- `:kormium-sqlite:mingwX64Test` — 74 tests, 0 failures
