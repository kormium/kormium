# 08 — Kormium on JVM vs Kotlin/Native: SQLite and PostgreSQL

**Question.** After the optimization work, how does Kormium on Kotlin/Native actually compare
to Kormium on the JVM — on each backend, through the real drivers?

**Answer.** It depends entirely on how much of the operation is I/O.

- **SQLite (in-memory, no socket):** Native is **6–8% faster** on a 100-row read, level on the
  other two.
- **PostgreSQL (over a socket):** JVM is **10–46% faster**, the gap widest on writes.

Neither result is a platform verdict. Both are explained below.

## Method

One benchmark source per backend, compiled for **both** targets, so the two columns run
identical Kormium code against the same database:

- `kormium-sqlite/src/commonTest/kotlin/SqliteE2EBench.kt`
- `kormium-postgres/src/commonTest/kotlin/PgE2EBench.kt` (with an `expect`/`actual` for reading
  environment variables)

Same six-column table shape in both, so the backends are comparable too. Each measurement warms
up (at least 1 000–3 000 iterations, enough for the JIT to settle) and reports the **best of
three measured rounds** in one process. Every configuration was run **three times**; the tables
below give the best, and the spread across runs is reported alongside.

Native is the optimized `benchRelease` binary — the debug binary understates Kotlin/Native by
~10x (report 00).

Drivers are what each platform actually ships: **JVM = pgjdbc + HikariCP** or the JDBC SQLite
driver; **Native = libpq** or the sqlite3 cinterop. That difference is the substance of the
comparison, not a confound.

Machine: Windows 11, `mingwX64`, JDK 21, PostgreSQL 16 in Docker on loopback with `fsync` and
`synchronous_commit` off. Pool size 1 on both sides.

## SQLite — in memory, pure CPU

ns/op, lower is better. Best of three runs; spread in parentheses.

| Operation | JVM | Native | Winner |
| --- | ---: | ---: | --- |
| `SELECT` 100 rows | 112 278 (±0.7%) | 99 089 (±5.4%) | **Native, 1.13x** |
| `SELECT` 1 row by primary key | 8 840 (±10%) | 8 971 (±4.5%) | tie |
| `INSERT` one row | 16 249 (±6.7%) | 15 112 (±6.3%) | tie (Native +7%, within spread) |

Only the 100-row read is a real difference: 13% faster on Native, comfortably outside the
run-to-run spread. That is the row-materialization path — exactly what fixes 1–5 targeted, and
with no socket in the way, the improvement shows through.

The single-row and insert numbers differ by less than their own variance. Call them level.

## PostgreSQL — over a socket

| Operation | JVM | Native | Winner |
| --- | ---: | ---: | --- |
| `SELECT` 100 rows | 529 525 (±10%) | 615 527 (±9%) | **JVM, 1.16x** |
| `SELECT` 1 row by primary key | 427 462 (±19%) | 434 366 (±21%) | tie |
| `INSERT` one row in a transaction | 722 212 (±11%) | 1 053 420 (±13%) | **JVM, 1.46x** |

Note the scale change: every PostgreSQL operation costs **40–100x** its SQLite counterpart.
That is the round trip, and it dominates everything measured in reports 00–07.

Two real gaps, both with a concrete cause:

**Writes, 1.46x.** An `INSERT` in a transaction is three round trips — `BEGIN`, `INSERT`,
`COMMIT`. The native driver issues each as a separate synchronous exchange. pgjdbc does the
same in principle but has years of protocol-level tuning behind it. This is the largest gap in
the whole comparison and it is not CPU.

**100-row read, 1.16x.** pgjdbc switches to **server-side prepared statements** after five
executions of the same SQL; the native driver always sends `PQexecParams` with
`paramTypes = null`, so the server re-parses and re-plans every single time. In a benchmark that
runs one statement thousands of times, that advantage compounds. This was flagged in report 06
as the one structural item CPU work cannot address — here it is, measured.

Single-row reads are a tie, with ~20% spread: at that size the round trip is nearly the whole
cost and neither driver's overhead is visible.

## What this means

**The CPU work was worth doing, and its visibility depends on the workload.** Against
in-memory SQLite, reading 100 rows is now measurably faster on Native than on the JVM — a
platform with a mature JIT. Against a networked PostgreSQL, the same work is buried under
latency: the read is ~5x more expensive than the entire SQLite operation, before Kormium does
anything.

**"Native is faster than JVM" is not a claim this data supports**, and neither is the reverse.
Native wins where CPU is the cost. The JVM wins where a decade of driver engineering is the
cost. Choose the target for deployment reasons — no JVM, startup time, binary size — not for
these numbers.

**The remaining gap is in the native PostgreSQL driver, not in core.** `PQprepare` support is
the concrete next item, and it is now measurable on this machine.

## Honesty notes

- Single machine, loopback network, pool size 1, one table shape. Concurrency is not exercised
  at all; a pooled multi-threaded workload could rank these differently.
- PostgreSQL runs with durability off, so writes measure driver and protocol overhead, not disk.
  Real write latency would compress the 1.46x write gap.
- The JVM numbers include no JMH machinery — just warmup and best-of-rounds. They are good
  enough to separate a 46% gap from noise, not to resolve a 5% one, which is why several rows
  above are reported as ties rather than small wins.
- The project's own `benchmarks/` suite (JMH, 8 threads, four ORMs) is the more rigorous
  instrument for JVM-side numbers. This report exists because that suite cannot compile the
  Native column on this host and does not isolate CPU from I/O.
