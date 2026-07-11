# ADR 0006 — No idiomatic-path nudge (no `@RequiresOptIn` marker, no detekt rule)

- Status: Partially superseded by [ADR 0009](0009-delicate-raw-sql-optin.md) — the `@RequiresOptIn`
  rejection below no longer holds for `execute` / `executeUpdate` / `execSql` / `RawExpression`. The
  detekt-rule rejection and the "don't gate `.select()`" point still stand.
- Date: 2026-06-30

## Context

A recurring idea (the original trigger was an AI code review that reached for a low-level
`(A innerJoin B).select()` instead of the higher-level `find { }`) was to **nudge** callers toward
the idiomatic high-level API and away from "low-level" forms — via a `@RequiresOptIn`
(`@LowLevelApi`) marker on the escape hatches, and/or a custom detekt rule.

Examining the actual surface, the premise mostly does not hold:

- `(A innerJoin B).select()` + `row[col]` is **not** an escape hatch — it is the first-class typed
  way to read columns / aggregates from a join (the alternative to entity hydration via `find()`).
  Marking it "low-level" would gate normal, recommended API.
- `Scope.execute(sql, params, invalidates)` runs raw SQL but **binds parameters**, and is the
  legitimate path for DDL (`execSql`) and SQL the typed DSL doesn't model. Gating every migration is
  pure friction.
- `RawExpression(string)` is the only genuinely unsafe form: verbatim SQL that does **not** bind
  parameters. It is already named `Raw` and documented as unsafe.

## Decision

Do **not** add an idiomatic-path nudge — no `@RequiresOptIn` marker and no detekt rule.

Reasoning:

- **The nudge is already delivered by documentation, which is the right lever for agents.** `AGENTS.md`
  (canonical copy-ready forms) and its `## Recipes` section steer the model *proactively*, before it
  writes anything. A marker only fires *after the fact* as compile friction; docs prevent the wrong
  reach in the first place.
- **A detekt rule does not reach the audience.** detekt runs in *this* repository's build. It never
  runs in a downstream user's project, and never in the context where an AI agent generates code, so
  as an AI-friendliness lever it is close to useless — it would only keep our own samples tidy.
- **`@RequiresOptIn` is wrong for the things people actually call.** Applied to `.select()` it gates a
  first-class API; applied to `execSql`/`execute` it nags on every legitimate DDL. The only candidate
  where it would make sense — `RawExpression` — already advertises its risk through its name and docs,
  so a warning adds marginal value over noise (our own tests, samples and the documented "drop to
  `RawExpression`" escape hatch would all have to opt in or suppress).
- KISS / "deliberate slice + escape hatch": Kormium intentionally ships a finite typed surface plus a
  small set of clearly-named escape hatches. Adding an opt-in ceremony around them works against that
  clarity without a real safety gain.

## Consequences

Positive:

- No opt-in ceremony, no suppress-noise around the documented escape hatches, no lint that only binds
  our own repo. The escape hatches stay plainly available and plainly named.
- The steering effort stays where it works for agents: `AGENTS.md` + recipes, kept in sync with the API.

Negative / costs:

- There is no compile-time tripwire on `RawExpression`; its safety rests on its name, its KDoc, and the
  `AGENTS.md` note that it is verbatim and unparameterized. Accepted as sufficient.

## Alternatives considered

- **`@RequiresOptIn(level = WARNING)` on `RawExpression` only.** The single defensible marker — a quiet
  "you've left the typed, parameter-binding path." Rejected as marginal: `RawExpression` is already
  named and documented as unsafe, and the warning would fire on our own deliberate uses and on the very
  escape hatch the docs tell agents to use for unmodeled SQL.
- **A detekt rule preferring `find { }` over `(A innerJoin B).select()`.** Rejected: high false-positive
  (the two are not interchangeable — `.select()` exists for columns/aggregates), and it runs only in our
  build, never reaching a user or an agent.
- **Marking `.select()` / `execute` as low-level.** Rejected: these are first-class / legitimate APIs,
  not escape hatches.

## Notes

This closes the post-maturity AI-friendliness item informally tracked as "#4 — idiomatic-path nudge".
The two items before it — golden-pattern snippets and a compile-error-quality audit — did the
substantive work (`AGENTS.md` recipes; typed `findOne` replacing untyped `findById`; clearer predicate
mismatch messages). Related: [[korm-ai-oriented-direction]].
