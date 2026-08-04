# 04 — Fix 4: per-query SQL rendering cost

**Change.** `kormium-core` — `Table.kt`, `Column.kt`.

**Result.** SELECT rendering **−60.9%** (2.6x faster). Hydration −5.2% (it renders one
statement per query). INSERT rendering and field reads unchanged.

## What was wrong

Four things, all recomputing constants:

1. **The select list was re-rendered per statement.** `getColumnNames(dialect)` built a
   `List<String>`, calling `dialect.quoteIdentifier` once per column, and every caller then
   ran `joinToString(", ")` over it — on every `SELECT`, every `INSERT ... RETURNING`, every
   upsert. The result is fixed for a given (table, dialect).
2. **`trimIndent()` on every built statement.** It splits the string into lines, computes the
   common indent and rebuilds — on strings that were already single-line.
3. **`primaryKey` was a computed property**, running two `filter` passes and allocating on
   every access.
4. **`Column.resultKey()` rebuilt `"table.column"` on every call**, though both halves are
   fixed at construction.

## What changed

**Cached select list.** One immutable holder, published through a single `@Volatile` field:

```kotlin
private class ColumnList(val dialect: Dialect, val rendered: String)

@Volatile private var columnListCache: ColumnList? = null

private fun columnList(dialect: Dialect): String {
    val cached = columnListCache
    if (cached != null && cached.dialect === dialect) return cached.rendered
    val rendered = fieldDisplayName.values.joinToString(", ") { dialect.quoteIdentifier(it.name) }
    columnListCache = ColumnList(dialect, rendered)
    return rendered
}
```

A `Table` is a singleton shared across threads, so the pair must be published atomically —
holding dialect and rendered text in one object means a racing reader sees either the
previous entry or a complete new one, never a mismatched pair. A lost race costs one
recompute. Keying on the dialect (rather than caching a single value) keeps a table usable
from two databases of different dialects, which the type system allows.

**`trimIndent()` removed.** For the single-line builders this is provably a no-op on content:
`trimIndent` on a string with no newline computes a common indent of 0 and returns it
unchanged. The two `UPDATE` builders did use a multi-line raw string; they are now built as
one line, which changes only whitespace. The SQL is semantically identical, and the assertion
helper the tests use (`remoteNewLinesAndSpaces`) normalizes whitespace anyway.

**`primaryKey` is `by lazy`**, and **`Column.resultKey` is a `val`** computed in the
constructor.

## Measurement

Paired A/B, alternating runs, minimum of 5. Kotlin/Native, `mingwX64`, optimized binary.

| Workload | fix 3 | fix 4 | change |
| --- | ---: | ---: | ---: |
| `renderSelect` | 7 983 | 3 122 | **−60.9%** |
| `hydrate100` | 82 847 | 78 503 | −5.2% |
| `readFields100` | 8 141 | 8 076 | −0.8% |
| `renderInsert` | 6 669 | 6 650 | −0.3% |
| *probes* | — | — | within 2.6% |

Larger than the ~10% this fix was predicted to yield. For a six-column table, one `SELECT`
was doing six `quoteIdentifier` calls, a list allocation, a `joinToString`, and a
`trimIndent` — all of it now either cached or gone.

`renderInsert` is unchanged because `insertSql` without `RETURNING` never touches the select
list, its `trimIndent` was already a no-op, and `generatePresentFields` was made single-pass
back in fix 3. `hydrate100` picks up the SELECT saving once per query, spread over 100 rows.

## Cumulative — baseline vs fixes 1–4

| Workload | baseline | after fix 4 | change |
| --- | ---: | ---: | ---: |
| `readFields100` | 34 932 | 8 068 | **−76.9%** (4.3x) |
| `renderSelect` | 8 148 | 3 064 | **−62.4%** (2.7x) |
| `hydrate100` | 118 860 | 79 283 | **−33.3%** (1.5x) |
| `renderInsert` | 7 871 | 6 877 | **−12.6%** |

Per unit: one entity field read **69.9 → 16.1 ns**; one hydrated row (6 columns)
**1 189 → 793 ns**.

### Cross-check

The cumulative figures were measured directly, but they can also be predicted by chaining the
four independent per-fix A/B measurements. The two agree closely, which is the strongest
evidence available here that no step's number is an artefact:

| Workload | chained per-fix deltas | measured cumulative |
| --- | ---: | ---: |
| `readFields100` | −76.0% | −76.9% |
| `hydrate100` | −33.2% | −33.3% |
| `renderSelect` | −64.4% | −62.4% |
| `renderInsert` | −14.5% | −12.6% |

## A measurement caveat worth recording

In the cumulative run, three of the four probes agreed between binaries to within 2%, but
`probeUuidParse100` was **2.3x slower on the baseline binary** — consistently, across all
five rounds, not as noise.

The probes contain no Kormium code, so this is not a code difference. The likely cause is
that probes run *after* the workloads in the same process and therefore share heap state: the
baseline binary's workloads leave a much larger live set (100 entities each holding a 6-entry
`HashMap`, versus a 6-element array), which makes every subsequent GC cycle more expensive for
the allocation-heavy UUID probe.

This does not invalidate the workload deltas — the chained cross-check above confirms them
independently — but it does mean the probes are not perfectly isolated controls when two
binaries differ greatly in allocation behaviour. A future harness should run the probes before
the workloads, or in a separate process.

## Verification

- `:kormium-core:jvmTest` — 104 tests, 0 failures
- `:kormium-core:mingwX64Test` — 102 tests, 0 failures
- `:kormium-core:apiCheck` — passes; `git diff kormium-core/api/` is empty
- `compileKotlinJvm` across every module — passes
