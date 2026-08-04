# 03 — Fix 3: ordinal-indexed entity field storage

**Change.** `kormium-core` — `Entity.kt` (rewritten), `Column.kt`, `Table.kt`, `Join.kt`.

**Result.** Row hydration **−28.3%**, entity field reads **−45.4%** (two steps, below),
INSERT rendering **−13.7%**. Cumulative over fixes 1–3: field reads **−77.2%**, hydration
**−31.0%**.

## What was wrong

`Entity` stored field values in a `MutableMap<String, Any?>` keyed by the Kotlin property
name. Every read, every write, and every row hydrated paid a string hash. Report 00 measured
a `HashMap.get` at 14.8 ns on Native against 0.4 ns for an array index, and attributed 18% of
hydration cost to constructing one such map per row.

## The constraint that shaped the design

The obvious fix — index values by the column's position in its table — is **wrong**, and the
test suite did not catch it because the case was untested. The type system permits one entity
type to back columns of two different tables:

```kotlin
class TwoTableRow : Entity() {
    var left  by LeftT.left      // column 0 of left_t
    var right by RightT.right    // column 0 of right_t
}
```

Both properties are `Column<*, *, TwoTableRow>`, so this compiles and works today — string
keys `"left"` and `"right"` never collide. Indexing by per-table position would alias both
onto slot 0, so writing `right` would silently overwrite `left`. Not a crash: corrupted data.

This was verified before designing around it. `SharedEntityTypeTest` was written first and
passed against the old map-based storage, pinning the behaviour that had to survive.

## What changed

Storage is now a hybrid: an array for the common case, a map for the case that needs one.

```kotlin
public abstract class Entity protected constructor() {
    internal var values: Array<Any?> = NO_VALUES      // indexed by Column.ordinal
    internal var owner: Table<*, *>? = null           // whose ordinal space `values` uses
    internal var overflow: MutableMap<String, Any?>? = null   // columns of any other table
}
```

- `Column.ordinal` is assigned in `Table.addColumn`, following declaration order.
- The **first write** claims ownership: `owner` is fixed to that column's table and `values`
  is sized to its column count. Hydration does the same, directly.
- A column whose `tableRef === owner` reads and writes by array index.
- Any other column falls back to `overflow`, which stays `null` unless such a column is
  actually touched — so a normal single-table entity never allocates it.
- An `ABSENT` sentinel distinguishes "never assigned" from a stored `null`, the role
  previously played by key absence.

Access goes through three `internal inline` functions — `slotGet`, `slotSet`, `slotClear` —
so all eight call sites share one implementation of the branch.

### Two simplifications fell out

`hydrate` no longer takes an `absentFields: Set<String>` parameter: an `ABSENT` slot already
*means* "not selected", which is exactly what the error message needed. `Join.hydrateFrom`
consequently stops building a side set of missing field names.

`NotNullColumn.getValue` is now a single read even on the error path — `ABSENT` and `null`
are distinct values, so the two failure modes are told apart without the second lookup that
fix 2 still needed.

## Measurement

Paired A/B, alternating runs, minimum per metric. Kotlin/Native, `mingwX64`, optimized
binary. ns/op.

### Step 1 — hybrid storage (vs fix 2)

| Workload | fix 2 | fix 3 | change |
| --- | ---: | ---: | ---: |
| `hydrate100` | 126 813 | 90 963 | **−28.3%** |
| `renderInsert` | 8 437 | 7 283 | **−13.7%** |
| `readFields100` | 15 837 | 13 845 | −12.6% |
| `renderSelect` | 8 601 | 8 501 | −1.2% |
| *probes* | — | — | within 2.9% |

Hydration landed almost exactly on the −25% predicted from report 00's attribution.
`renderInsert` improved as a side effect: `generatePresentFields` became a single pass
instead of `filter {}` followed by `map {}`.

Field reads, however, moved only −12.6% against a predicted −48%.

### Step 2 — inlining the slot accessors

The shortfall was the accessor call itself: `slotGet` was a real function call wrapping what
should be an array index. Marking the three slot functions `internal inline`:

| Workload | fix 3 | fix 3 + inline | change |
| --- | ---: | ---: | ---: |
| `readFields100` | 13 594 | 8 621 | **−36.6%** |
| `hydrate100` | 89 955 | 90 233 | +0.3% |
| `renderInsert` | 7 301 | 7 405 | +1.4% |
| *probes* | — | — | within 2.3% |

Field reads only. Hydration writes the array directly and never goes through the accessors.

### Cumulative — original baseline vs fixes 1+2+3

| Workload | baseline | after fix 3 | change |
| --- | ---: | ---: | ---: |
| `readFields100` | 38 127 | 8 702 | **−77.2%** (4.4x) |
| `hydrate100` | 129 961 | 89 639 | **−31.0%** (1.45x) |
| `renderInsert` | 8 256 | 7 082 | **−14.2%** |
| `renderSelect` | 8 659 | 8 697 | +0.4% |
| *probe:* `HashMap.get` ×500 | 7 559 | 7 634 | +1.0% |
| *probe:* build `HashMap` ×100 | 23 730 | 23 537 | −0.8% |
| *probe:* `Uuid.parse` ×100 | 6 429 | 6 534 | +1.6% |
| *probe:* `Array` index ×500 | 207 | 207 | 0.0% |

One entity field read: **76.3 → 17.4 ns**.

## What this did not fix

Boxing. `Array<Any?>` boxes `Int`, `Long` and `Boolean` exactly as the map did, so the ~63%
of hydration cost that report 00 could not attribute to collections is still there —
`ResultSet` and `ColumnType` virtual dispatch, boxing, and the `try`/`catch` in `readColumn`.
Removing it would need typed per-column storage, a much larger change that is not proposed.

The shared-entity-type case is now *slower* than before: a failed reference comparison plus
two map lookups, against one map lookup previously. That is the deliberate trade — the rare
path pays so the common one does not.

## Verification

- `:kormium-core:jvmTest` — 104 tests, 0 failures
- `:kormium-core:mingwX64Test` — 102 tests, 0 failures
- `:kormium-core:apiCheck` — passes; `git diff kormium-core/api/` is empty
- `compileKotlinJvm` across every module — passes (the changed `hydrate` signature is
  `internal`, so no driver module was affected)

`SharedEntityTypeTest` passes unchanged against the new storage — the aliasing hazard that
motivated the hybrid design is covered.
