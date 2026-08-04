# 00 — Baseline: JVM vs Kotlin/Native on the CPU path

**Question.** Kormium is suspected of being slow on Kotlin/Native, with custom collections
proposed as the remedy. Is the suspicion real, and if so, where does the time actually go?

**Answer in one line.** Real, but unevenly: SQL rendering is ~3x slower on Native, row
hydration ~5x, and **entity field access ~12x** — and most of the field-access cost is not
the collection at all, it is a logger closure allocated on every accessor call.

## What was measured

`kormium-core/src/commonTest/kotlin/CorePerfBench.kt` — CPU only. A fake `ResultSet` feeds
canned rows through the real `Table.select` / `selectSql` / `insertSql` code paths, so the
measurement covers exactly the work Kormium does per query and per row, with no database,
driver, socket or JDBC in the picture.

Table shape: 6 columns (`Uuid`, `Text`, `Int`, `Text`, `Boolean`, `Long`), one primary key.

| Workload | What it does |
| --- | --- |
| `renderSelect` | `selectSql` for a two-predicate query with `LIMIT` |
| `renderInsert` | `insertSql` for a fully populated entity |
| `hydrate100` | `select` returning 100 rows — result mapping into entities |
| `readFields100` | 500 entity property reads (100 entities × 5 fields) |
| `probeMapGet500` | 500 bare `HashMap<String, Any?>` gets — the storage floor |
| `probeArrayGet500` | 500 bare `Array<Any?>` index reads — the alternative floor |
| `probeMapBuild100` | 100 × (presized `HashMap` + 6 puts) — mirrors `mapToDao` |
| `probeUuidParse100` | 100 × `Uuid.parse` — mirrors the per-row UUID column |

The last four are attribution probes, not code paths: they establish what the underlying
operations cost on each platform so the workload numbers can be decomposed.

## Results

ns/op, lower is better. Kotlin/Native is the **optimized** (`benchRelease`) binary.

| Workload | JVM | K/N | K/N slower by |
| --- | ---: | ---: | ---: |
| `renderSelect` | 3 189 | 9 929 | 3.1x |
| `renderInsert` | 3 011 | 10 472 | 3.5x |
| `hydrate100` | 28 087 | 149 994 | 5.3x |
| `readFields100` | 3 702 | 43 079 | **11.6x** |
| *probe:* `HashMap.get` ×500 | 1 648 | 8 642 | 5.2x |
| *probe:* `Array` index ×500 | 188 | 199 | 1.1x |
| *probe:* build `HashMap` ×100 | 8 108 | 27 344 | 3.4x |
| *probe:* `Uuid.parse` ×100 | 4 231 | 7 162 | 1.7x |

Normalized to a single operation:

| Operation | JVM | K/N |
| --- | ---: | ---: |
| One entity field read | 7.4 ns | 86.2 ns |
| One `HashMap` get | 3.3 ns | 17.3 ns |
| One `Array` index read | 0.38 ns | 0.40 ns |
| One hydrated row (6 columns) | 281 ns | 1 500 ns |

## Methodology caveat that changes the conclusion

The default `mingwX64Test` task links a **debug** Kotlin/Native binary, and debug K/N is
unoptimized. The same benchmark on the debug binary reports:

| Workload | K/N debug | K/N release | debug inflation |
| --- | ---: | ---: | ---: |
| `renderSelect` | 62 957 | 9 929 | 6.3x |
| `hydrate100` | 1 476 786 | 149 994 | 9.8x |
| `readFields100` | 373 989 | 43 079 | 8.7x |

Reading the debug numbers would have produced "Native is 50x slower than JVM", which is
false. `benchmarks/README.md` already warns about this for the Postgres harness; the same
applies here. **Every Native number in these reports comes from the release binary.**

## Where the time goes

### Field access — 86 ns per read on Native

`Column.NotNullColumn.getValue` (`kormium-core/.../Column.kt:74-79`) does three things:
a `logger.trace { }` call, a `containsKey`, and a `get`.

- Two hash lookups at 17.3 ns ≈ **34.6 ns**
- Everything else ≈ **51.6 ns (60%)**

The only other thing in the body is `logger.trace { "Get value $fieldKey" }`.
`KormiumLogger.trace(msg: () -> String)` is a plain interface method, not `inline`
(`KormiumLogger.kt:13`), and the lambda captures `fieldKey` — so a closure is heap-allocated
on **every field read and every field write**, whether or not trace is enabled.

On the JVM the same closure is eliminated by escape analysis: total accessor overhead above
the map lookup is 4.1 ns. Kotlin/Native has no equivalent, so it pays allocation plus GC.

The native Postgres driver already avoids exactly this trap deliberately — see the comment
at `kormium-postgres/.../PostgresResultSet.kt:58-61`. The same reasoning never reached core.

### Storage choice — 43x headroom on Native

`Array` index reads cost the same on both platforms (0.4 ns). `HashMap` gets cost 3.3 ns on
JVM but 17.3 ns on Native. So moving `Entity` storage from a `String`-keyed map to an
ordinal-indexed array is worth **~43x on that operation on Native**, versus ~9x on the JVM —
the change pays off disproportionately on the platform that needs it.

### Row hydration — diffuse, no single hotspot

Decomposing the 149 994 ns Native cost of `hydrate100`:

| Component | ns | Share |
| --- | ---: | ---: |
| Per-row `HashMap` construction | 27 344 | 18.2% |
| `hydrate()` second pass (600 gets) | ~10 370 | 6.9% |
| `selectSql` (once per query) | ~9 929 | 6.6% |
| `Uuid.parse` ×100 | 7 162 | 4.8% |
| Unattributed | ~95 189 | 63.5% |

The unattributed remainder is `ResultSet` / `ColumnType` virtual dispatch, boxing of
`Int` / `Long` / `Boolean` into `Any?`, the `try`/`catch` in `readColumn`, and `factory()`.

This matters for expectations: replacing the collection removes roughly a quarter of
hydration cost, not the 5.3x gap. Boxing survives an `Array<Any?>` rewrite — only typed
storage would remove it, which is a much larger change.

## Revised priorities

The originally proposed order was wrong; the cheapest item turns out to be the most
valuable.

1. **Remove the logger closure from the accessors.** ~5 lines, removes ~60% of field-read
   cost on Native. Best effect-to-risk ratio in the list.
2. **Collapse `containsKey` + `get` into one lookup.** Independent of storage choice.
3. **Move `Entity` storage to an ordinal-indexed array.** 43x on the access operation,
   ~25% off hydration. Confined to `commonMain`; does not touch driver ABI.
4. **Reduce per-query rendering work** (cache the rendered column list per dialect, drop
   `trimIndent`, single-pass `generatePresentFields`). Attacks the 3x zone, so a smaller
   share — but it benefits every target, including JS and Wasm.

## Scope and limits

- One run, one machine, no error bars. Target is `mingwX64`, which the project treats as
  experimental; `linuxX64` and `iosArm64` may differ.
- **CPU only.** Against a real database a single-row read is dominated by the round trip —
  which is why `benchmarks/README.md` shows Kormium Native *ahead* of Kormium JVM on
  `findById`. These costs become visible on multi-hundred-row reads and on code that reads
  entity properties heavily.
- The JVM column varies ~15% run to run; the Native column is stable to a few percent.
