# 07 — UUID parsing: a hand-rolled parser, measured and rejected

**Outcome.** No change shipped. A hand-rolled UUID parser turned out **27% slower** than
`kotlin.uuid.Uuid.parse` once it was made correct. The work is recorded here so nobody
repeats it.

**Kept:** `GetUuidTest` — six tests covering `ResultSet.getUUID`, which had none.

## Why it looked promising

`getUUID` is `getString(index)?.let { Uuid.parse(it) }`, and every row of a UUID-keyed table
pays it — the shape used throughout the project's own documentation. Report 05 left hydration
at ~319 ns per row, so a 64 ns parse is a fifth of that.

An early probe compared `Uuid.parse` against a hand-rolled hyphenated-hex parser:

| | ns/op |
| --- | ---: |
| `Uuid.parse` | 64.1 |
| Hand-rolled, **no validation** | 39.8 |
| `Uuid.fromLongs` (construction floor) | 7.1 |

A 37% saving, apparently.

## Why it evaporated

The 39.8 ns parser accepted anything. It mapped every non-hex character through
`code - 'A'.code + 10`, so `"not-a-uuid-but-36-characters-long!!!"` would have produced a
plausible-looking UUID instead of an error. Turning a malformed value into a believable one is
precisely the class of defect fixed in the previous commit (`bytea`), and not something to
trade for nanoseconds.

The shippable version therefore validates: exact length, hyphens at 8/13/18/23, and every
remaining character a hex digit, falling back to `Uuid.parse` for anything else (which also
accepts the bare 32-digit form). Two implementations were measured, both against the *old*
path through the same stub result set, so `getString` overhead is held constant:

| Implementation | ns/op |
| --- | ---: |
| Old path — `getString` + `Uuid.parse` | 64.6 |
| Hand-rolled, hyphen positions tested per index | 78.9 |
| Hand-rolled, five explicit hex-group loops | 82.2 |

Both lose. The first version spent 144 comparisons per UUID just deciding which indices were
hyphens; removing that did not help, so the cost is the per-character `String` indexing and
validation itself. `kotlin.uuid.Uuid.parse` is evidently already well optimized on
Kotlin/Native, and a naive character loop does not beat it.

The change was reverted.

## A defect caught before it shipped

An intermediate version factored the hex groups into a helper returning `Long`, signalling
"not a hex digit" with a `-1L` sentinel, and asserted in a comment that a real accumulator
could never hold that value.

That was false. The most-significant half accumulates sixteen hex digits — a full 64 bits — so
`ffffffff-ffff-ffff-ffff-ffffffffffff` produces exactly `-1L`. The result would still have been
*correct*, because the sentinel only triggers the fallback to `Uuid.parse`, but a legitimate
value would have silently taken the slow path while a comment claimed it could not happen.

Worth noting how nearly it slipped through: `canonicalFormMatchesStdlib` already tests the
all-`f` UUID and would have passed, since the fallback produces the right answer. Only a
performance probe distinguishes the two paths. The final version spells the groups out and has
no sentinel.

## What is actually expensive about reading a UUID

The parse is not the problem:

| Step | ns |
| --- | ---: |
| `toKString()` — UTF-8 decode of the 36-byte value | **130.8** |
| `Uuid.parse` | 64.1 |
| `getUUID` end to end (`getString` + parse) | 76.3 |
| `Uuid.fromLongs` | 7.1 |

Two thirds of the cost is building a Kotlin `String` that is immediately discarded. Skipping it
means parsing straight from the driver's bytes — the trick the native driver already uses for
integers (`parsePgLong`) and now for `bytea`. That needs `ResultSet` to expose a UUID getter so
a driver can override it, which is an addition to a public interface, and applies to `Instant`,
`LocalDate`, `LocalTime`, `LocalDateTime` and `Decimal` for the same reason.

Note the native SQLite driver cannot benefit from that shape: it materializes every cell
eagerly (`rows.add(Array(colCount) { readColumn(stmt, it) })`), so the `String` already exists
before core asks for a value. That driver would need its read model changed instead.

## Verification

- `:kormium-core:jvmTest --tests GetUuidTest` — 6 tests, 0 failures (against the unchanged
  stdlib implementation, so the coverage stands on its own)
- No production code changed; `kormium-core/api/` untouched.
