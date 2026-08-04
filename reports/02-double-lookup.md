# 02 — Fix 2: collapse the double map lookup in `NotNullColumn.getValue`

**Change.** `kormium-core/src/commonMain/kotlin/Column.kt`, one accessor.

**Result.** Entity field reads **−29.7%** on Kotlin/Native (44.0 → 31.0 ns per read).
Cumulative with fix 1: **−56.7%** from the original 71.5 ns.

## What was wrong

Reading a non-null field hashed the key twice:

```kotlin
if (!n.fields.containsKey(fieldKey)) error(...)   // lookup 1
return (n.fields[fieldKey] ?: error(...)) as Z    // lookup 2
```

`containsKey` existed only to tell two failure modes apart — a field that was never assigned
versus one holding an explicit null — because a bare `get` returning null cannot distinguish
them. But that distinction is only ever needed while *building an exception*, and both
lookups were being paid on every successful read.

Report 01 measured a `HashMap.get` at 14.8 ns on Native, so this was ~15 ns of the 44 ns
per-read budget.

## What changed

Look up once; pay the second lookup only on the error path, where an exception is about to
be thrown anyway.

```kotlin
val value = n.fields[fieldKey]
if (value == null) {
    if (!n.fields.containsKey(fieldKey)) {
        error("Field '$fieldKey' is not present on ${tableRef.tableName}")
    }
    error("Field '$fieldKey' is null but column '$name' is non-null")
}
return value as Z
```

Both error messages are byte-identical to before, and which one fires in which situation is
unchanged. `EntityFieldStorageTest` asserts the absent-field message names both the field
and the table, and `ResultMappingTest` covers the non-null-column-returned-NULL path.

## Measurement

Paired A/B: the fix-1 binary against the fix-2 binary, run alternately in one quiet window,
5 rounds each, minimum per metric. Kotlin/Native, `mingwX64`, optimized binary. ns/op.

| Workload | fix 1 | fix 2 | change |
| --- | ---: | ---: | ---: |
| `readFields100` | 22 006 | 15 477 | **−29.7%** |
| `renderSelect` | 8 809 | 8 378 | −4.9% |
| `renderInsert` | 8 317 | 8 267 | −0.6% |
| `hydrate100` | 129 256 | 126 498 | −2.1% |
| *probe:* `HashMap.get` ×500 | 7 416 | 7 637 | +3.0% |
| *probe:* build `HashMap` ×100 | 23 705 | 23 252 | −1.9% |
| *probe:* `Uuid.parse` ×100 | 6 478 | 6 518 | +0.6% |
| *probe:* `Array` index ×500 | 208 | 210 | +1.0% |

Probes agree to within 3%, so the run is valid. Only `readFields100` moves beyond that
band; the `renderSelect`, `renderInsert` and `hydrate100` deltas are inside it and should be
read as "no change" — none of those paths goes through the property accessors.

The result matches the model exactly: 44.0 ns minus one 14.8 ns lookup predicts 29.2 ns,
measured 31.0 ns.

## Cumulative progress on field reads

| Stage | ns per read | vs baseline |
| --- | ---: | ---: |
| Baseline (report 00) | 71.5 | — |
| After fix 1 (logger closure) | 44.0 | −38.5% |
| After fix 2 (single lookup) | 31.0 | **−56.7%** |

Remaining budget at 31.0 ns: one `HashMap.get` at ~14.8 ns (48%), and ~16 ns of delegate
dispatch and boxing. Fix 3 (ordinal-indexed array storage) targets the first component —
report 00 measured an `Array` index read at 0.4 ns against 14.8 ns for a map get.

## Verification

- `:kormium-core:jvmTest` — 101 tests, 0 failures
- `:kormium-core:mingwX64Test` — 99 tests, 0 failures
- `:kormium-core:apiCheck` — passes; `git diff kormium-core/api/` is empty
