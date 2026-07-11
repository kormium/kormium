# ADR 0009 — Raw SQL requires `@OptIn(DelicateKormiumApi::class)` (supersedes ADR 0006 in part)

- Status: Accepted
- Date: 2026-07-03
- Supersedes: [ADR 0006](0006-no-idiomatic-path-nudge.md), for `execute` / `executeUpdate` / `execSql` /
  `RawExpression` only. ADR 0006's rejection of a detekt rule and of gating `.select()` still stands.

## Context

While closing the roadmap's "make raw SQL extension points clearer and safer" item, two real gaps
turned up in `Scope`/`SuspendScope`'s raw-SQL surface:

- `invalidates: List<Table<G, *>> = emptyList()` defaulted to empty, so a raw write could silently
  skip notifying `kormium-observe` — nothing failed, `Flow` queries just went stale.
- `params: Map<String, Any?> = emptyMap()` also defaulted to empty, so the parameterized path was
  never *shorter* than string-concatenating a value into the SQL text — the opposite of what should
  be true for the safer option.
- `execute(sql, params, invalidates): Long` and `executeUpdate(sql, params, invalidates)` were the
  same underlying call under two names, one of them silently discarding the driver's row count.

Fixing this made both `params` and `invalidates` required arguments (no defaults) on the merged
`execute`/`executeUpdate`, which raised the question ADR 0006 already answered once: should raw SQL
also carry a compile-time marker?

[ADR 0006](0006-no-idiomatic-path-nudge.md) (2026-06-30) considered exactly this and said no,
reasoning that (a) `execute`/`executeUpdate`/`execSql` "bind parameters" and are the legitimate path
for DDL, so gating them is pure friction on every migration, and (b) even for `RawExpression` — the
one form it called genuinely unsafe — a marker adds only marginal value over its existing name and
docs, and (c) the right lever for steering an AI agent is documentation read *before* code is
written (`AGENTS.md`), not compile friction discovered *after*.

## Decision

Add `DelicateKormiumApi`, a `@RequiresOptIn(level = ERROR)` marker, and apply it to `RawExpression`,
`Scope.execute` / `Scope.executeUpdate` / `Table.execSql`, and their `SuspendScope` counterparts.
Callers add `@OptIn(DelicateKormiumApi::class)` (typically `@file:OptIn(...)`) once per file that
uses raw SQL. `.select()` / the join API is untouched — it stays first-class per ADR 0006.

This reverses ADR 0006's conclusion for the escape-hatch functions specifically, for three reasons
not in play when 0006 was written:

- **The marginal-friction argument weakens once `params`/`invalidates` are mandatory.** ADR 0006's
  strongest point was "gating `execSql`/`execute` nags on every legitimate DDL call." That call site
  now already stops to supply `params`/`invalidates` explicitly — the opt-in is one more line next to
  arguments the caller must write anyway, not a fresh source of friction on an otherwise-terse call.
- **Docs-first steering and a compile-time gate solve different failures.** ADR 0006 is right that
  `AGENTS.md` is what stops an agent from *reaching for* raw SQL when the typed DSL would do — that
  reasoning is unchanged and still governs the recipes. But it does not catch code copied from an
  older example, a maintainer reaching for `execute(...)` out of habit, or an agent that never loaded
  `AGENTS.md` this session. A `@RequiresOptIn` marker is not a substitute steering mechanism; it is a
  backstop for the moment steering fails, which docs alone cannot provide.
- **The risk was underestimated, not just under-signposted.** ADR 0006 treated `execute`/`execSql` as
  safe because they "bind parameters" — true, but the default-empty `params` map made *not* binding
  them the path of least resistance, and the default-empty `invalidates` made a real write silently
  invisible to `kormium-observe`. Both are now fixed at the signature level; the annotation is the
  visible marker that a caller is on the hatch where those two footguns used to live, paired with the
  arguments that closed them.

`ERROR`, not the `WARNING` level ADR 0006's "Alternatives considered" weighed and rejected for
`RawExpression` alone — a warning is easy to ignore across a whole codebase; the intent here is one
explicit, permanent acknowledgment per file, not a nag that accumulates as noise.

## Consequences

Positive:

- A raw-SQL call site cannot compile without a visible, grep-able `@OptIn(DelicateKormiumApi::class)`
  — a real backstop where docs-first steering (still the primary lever, per ADR 0006/0007) fails to
  reach.
- One annotation documents the whole raw-SQL risk surface (unparameterized text, untracked writes) at
  its point of use, next to the arguments that already mitigate it.

Negative / costs:

- Breaking, pre-1.0: every existing caller of `execute`/`executeUpdate`/`execSql`/`RawExpression`
  across the repo needs the opt-in added (done as part of this same change — core, tests, samples,
  benchmarks, docs).
- Re-litigates a 3-day-old accepted decision. Recorded here rather than editing ADR 0006, per the
  project's append-only ADR convention.

## Alternatives considered

- **Leave ADR 0006 as-is; ship only the mandatory `params`/`invalidates`.** Closes the two concrete
  footguns without touching the opt-in question. Rejected: it leaves no compile-time signal that a
  particular call has left the typed/parameterized path at all — a reader (or a future refactor) has
  to know `execute`/`executeUpdate`/`execSql` are special by convention alone.
- **Mark `RawExpression` only, leave `execute`/`executeUpdate`/`execSql` unmarked.** The narrower
  reading of ADR 0006's own "the only genuinely unsafe form" line. Rejected: `execute`/`executeUpdate`
  had the exact same silent-default problems as `RawExpression` (params, invalidates) — treating them
  as safe because they *can* bind parameters ignored that the defaults made not doing so the easy path.

## Notes

`AGENTS.md` and its recipes remain the primary steering mechanism (ADR 0006's core point, unchanged);
this ADR adds a compile-time floor under it for the raw-SQL surface specifically. Related:
[[korm-prod-ready-work]].
