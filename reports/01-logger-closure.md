# 01 — Fix 1: remove the logger closure from column accessors

**Change.** `kormium-core` — `KormiumLogger.kt`, `KormiumLogger.logging.kt`,
`KormiumLogger.wasmWasi.kt`, `Column.kt`.

**Result.** Entity field reads **−37.8%** on Kotlin/Native (71.5 → 44.4 ns per read).
SQL rendering −2 to −3%. Hydration and the JVM unchanged.

## What was wrong

`KormiumLogger.trace(msg: () -> String)` was a plain interface method. An interface method
cannot be inlined, so `logger.trace { "Get value $fieldKey" }` in `Column.getValue` /
`setValue` built and heap-allocated a capturing closure on **every entity field read and
write**, whether or not trace output was enabled.

Report 00 measured the damage: of 86 ns per field read on Native, only ~35 ns was the two
map lookups. The rest was this.

The JVM never paid it — escape analysis removes the closure. Kotlin/Native has no
equivalent, so it paid allocation plus the resulting GC pressure.

## What changed

Two parts.

**1. The facade takes a formatted message; laziness moves to an inline extension.**

```kotlin
internal interface KormiumLogger {
    val isTraceEnabled: Boolean
    fun traceMessage(msg: String)
}

internal inline fun KormiumLogger.trace(msg: () -> String) {
    if (isTraceEnabled) traceMessage(msg())
}
```

Because the extension is `inline`, the message is only constructed inside the enabled
branch. Every existing `logger.trace { ... }` call site keeps compiling unchanged and
becomes allocation-free when tracing is off.

**2. The four accessors in `Column.kt` no longer trace at all.**

An intermediate version kept the trace calls and relied on the new guard. It was measured
and only recovered ~20% instead of the expected ~60%: at per-field frequency, the guard
itself (a virtual call into kotlin-logging's `isLoggingEnabledFor`) is not free. Since a log
line per property read is noise rather than diagnostics, the calls were dropped outright —
the same call the project already made for the per-cell path in
`PostgresResultSet` (`kormium-postgres/.../PostgresResultSet.kt:58-61`).

Part 1 is kept regardless: it makes the remaining call sites (`Table.getColumnNames`, run
once per query) allocation-free, which is where the −2 to −3% on rendering comes from.

## Measurement

Both binaries were built from the same tree with only the fix differing, then run
**alternately** in one quiet window, 5 rounds each, taking the minimum per metric.

Kotlin/Native, `mingwX64`, optimized (`benchRelease`) binary. ns/op, lower is better.

| Workload | before | after | change |
| --- | ---: | ---: | ---: |
| `readFields100` | 35 730 | 22 212 | **−37.8%** |
| `renderSelect` | 8 627 | 8 339 | −3.3% |
| `renderInsert` | 8 151 | 7 992 | −2.0% |
| `hydrate100` | 125 005 | 125 095 | +0.1% |
| *probe:* `HashMap.get` ×500 | 7 481 | 7 496 | +0.2% |
| *probe:* build `HashMap` ×100 | 23 242 | 23 546 | +1.3% |
| *probe:* `Uuid.parse` ×100 | 6 445 | 6 335 | −1.7% |
| *probe:* `Array` index ×500 | 198 | 214 | +8.1% |

The probes contain no Kormium code, so they must not move. They agree to within 0.2–1.7%
(the `Array` probe is 198 ns total and too small to resolve), which is what makes the
−37.8% trustworthy: it is more than twenty times the noise floor.

Hydration is unchanged as expected — `mapToDao` writes into the field map directly and never
goes through the property accessors.

### JVM: no change, as predicted

| Workload | report 00 | now | normalized to the `HashMap.get` probe |
| --- | ---: | ---: | --- |
| `readFields100` | 3 702 | 2 272 | 2.25 → 2.22 (−1.3%, noise) |

The raw JVM numbers dropped ~38%, but so did the probes — the machine was simply quieter
than during report 00. Normalizing against the probe shows the fix is neutral on the JVM,
which is the expected outcome when escape analysis was already eliminating the closure.

## Where the remaining field-read cost sits

44.4 ns per read on Native now decomposes as:

| Component | ns | Share |
| --- | ---: | ---: |
| Two `HashMap` gets (`containsKey` then `get`) | 29.9 | 67% |
| Delegate dispatch and the rest | ~14.5 | 33% |

That sets up the next two fixes directly: collapsing the double lookup targets half of the
29.9 ns, and moving to ordinal-indexed array storage targets nearly all of it.

## Verification

- `:kormium-core:jvmTest` — 101 tests, 0 failures
- `:kormium-core:mingwX64Test` — 99 tests, 0 failures
- `:kormium-core:apiCheck` — passes; `git diff kormium-core/api/` is empty

Public API unchanged. `KormiumLogger` is `internal` and used only by `Column.kt` and
`Table.kt`.

## Methodology note

Report 00's absolute numbers were taken in a noisier window than everything from here on.
The first attempt at measuring this fix was invalid and discarded: the probes had moved 2x,
proving the machine — not the code — had changed. Cross-report absolute comparison is
therefore unsafe; the paired A/B method used here (same tree, alternating runs, probes as a
validity check) is the one to trust, and reports from 01 onward use it.
