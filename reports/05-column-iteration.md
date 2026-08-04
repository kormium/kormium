# 05 — Fix 5: stop walking a LinkedHashMap once per row

**Change.** `kormium-core` — `Table.kt`, `Join.kt`.

**Result.** Row hydration **−62.1%** (2.6x faster). The largest single win of the effort, from
the smallest change in it.

This report also records the attribution work that found it — including the finding that
killed a much larger change that had looked attractive.

## The question that started it

After fix 4, hydration was 83 321 ns per 100 rows and ~88% of that was unattributed. The
leading theory was boxing: `ResultSet.getInt()` returns `Int?`, which cannot be an unboxed
primitive, and `Entity.values` is an `Array<Any?>`. Removing that would mean typed
per-column storage — rewriting `Entity`, `Column`, `ColumnType`, `ResultSet` and every
driver's result set. Before committing to that, the suspects were measured.

## Attribution results

Probes added to `CorePerfBench`. Each pair differs in exactly one property, so the gap is
the cost of that property. Kotlin/Native, `mingwX64`, optimized binary.

| Probe | ns/op |
| --- | ---: |
| Store `Long` into `Array<Any?>` (boxes) | 5.66 |
| Store into `LongArray` (no box) | 0.50 |
| Read through a `Int?` return (boxes) | 5.54 |
| Read through an `Int` return (no box) | 0.51 |
| Virtual read wrapped in `try`/`catch` | 0.48 |
| Iterate a 6-entry `LinkedHashMap` (destructured) | **47.2** |
| Iterate a 6-element `Array` | **0.54** |

Three conclusions, in order of how much work they saved:

**Boxing costs ~5.1 ns per value — and is irrelevant here.** Two independent probes (store
side and read side) agree. It is one box per primitive cell, not two: the box created by
`getInt(): Int?` is the same object stored in the array. At most 3 of the 6 benchmark columns
box, so 100 rows × 3 × 5.1 ns ≈ 1 530 ns out of 83 321 — **1.8%**. The typed-storage rewrite
would have touched two public interfaces and every driver to win under two percent. It was
dropped.

**`try`/`catch` per cell is free.** 0.48 ns wrapped versus 0.51 ns bare — inside noise, and if
anything faster. `readColumn`'s per-cell wrapper costs nothing and stays.

**Iterating a `LinkedHashMap` costs 47.2 ns per entry on Kotlin/Native — 88x an array walk.**
That is the finding.

## What was wrong

Both per-row paths walked the column registry, which is a `LinkedHashMap`:

```kotlin
// mapToDao, once per row
for ((fieldName, column) in fieldDisplayName) { ... }
// hydrate, once per row
for ((fieldName, column) in fieldDisplayName) { ... }
```

A 6-column row therefore paid 12 map-entry iterations. For 100 rows: 1 200 × 47.2 ns
≈ 56 600 ns, against a measured total of 83 321 — an estimated **68% of hydration cost**.

This also explains why fix 3 under-delivered on hydration (−28%, not more): it removed the
per-row map *construction* but left two per-row map *walks* untouched.

## What changed

The columns are kept as a flat array alongside the name-keyed map:

```kotlin
internal val columns: Array<Column<*, *, T>>
    get() = columnsCache ?: fieldDisplayName.values.toTypedArray().also { columnsCache = it }
```

Hot paths — `hydrate`, `mapToDao`, `generatePresentFields`, `presentColumns`, `batchGroups`,
`columnList`, and `Join.hydrateFrom` — iterate the array. `fieldDisplayName` remains the
lookup-by-name structure behind the public `getFieldDisplayNames()`.

`hydrate` and `mapToDao` needed the map key only for error messages, and that key is always
`column.fieldKey` (`Column.init` registers itself under exactly that name), so nothing is
lost by dropping the entry destructuring.

The cache is invalidated in `addColumn` rather than computed with `by lazy`, because
`Column.init()` is public and open — a column could in principle register after the first
read. `@Volatile` publishes the built array safely; a lost race only recomputes. `primaryKey`
was moved from `by lazy` to the same pattern for the same reason.

## Measurement

Paired A/B, alternating, minimum of 5.

| Workload | fix 4 | fix 5 | change |
| --- | ---: | ---: | ---: |
| `hydrate100` | 84 624 | 32 102 | **−62.1%** |
| `renderInsert` | 7 263 | 6 993 | −3.7% |
| `renderSelect` | 3 340 | 3 229 | −3.3% |
| `readFields100` | 8 769 | 8 955 | +2.1% |
| *probes* | — | — | within 5.5% |

Predicted from the attribution: ~27 000 ns remaining. Measured: 32 102. Field reads are
unaffected, as expected — they never touch the registry.

## Cumulative — baseline vs fixes 1–5

| Workload | baseline | now | change |
| --- | ---: | ---: | ---: |
| `hydrate100` | 127 526 | 31 920 | **−75.0%** (4.0x) |
| `readFields100` | 37 614 | 9 108 | **−75.8%** (4.1x) |
| `renderSelect` | 8 724 | 3 189 | **−63.4%** (2.7x) |
| `renderInsert` | 8 390 | 7 005 | **−16.5%** |
| *probe:* `HashMap.get` ×500 | 7 599 | 8 164 | +7.4% |
| *probe:* build `HashMap` ×100 | 23 530 | 23 872 | +1.5% |
| *probe:* `Uuid.parse` ×100 | 6 588 | 6 604 | +0.2% |
| *probe:* `Array` index ×500 | 206 | 209 | +1.5% |

Per unit: one hydrated row (6 columns) **1 275 → 319 ns**; one entity field read
**75.2 → 18.2 ns**.

## What is left in hydration

31 920 ns per 100 rows = 319 ns per row, 53 ns per cell. Of that, `Uuid.parse` alone is
~66 ns per row (one UUID column), and `selectSql` ~3 200 ns per query. The rest is
`ResultSet`/`ColumnType` virtual dispatch, entity construction, and the array allocations —
all small and none obviously reducible without changing public interfaces.

Boxing remains, at a measured ~1.8%. It is not worth pursuing.

## Verification

- `:kormium-core:jvmTest` — 104 tests, 0 failures
- `:kormium-core:mingwX64Test` — 102 tests, 0 failures
- `:kormium-core:apiCheck` — passes; `git diff kormium-core/api/` is empty
- `compileKotlinJvm` across every module — passes
