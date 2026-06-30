# ADR 0002 — No `ilike`; case-insensitive matching is explicit via `lower()`

- Status: Accepted
- Date: 2026-06-30

## Context

Users need case-insensitive string matching. The obvious SQL spelling is PostgreSQL's `ILIKE`
operator — but it does not exist in MySQL or SQLite, and case-insensitivity is not a uniform
concept across the engines Kormium supports:

- **PostgreSQL** — `LIKE`/`=`/`<` are case-sensitive; `ILIKE` is a Postgres-only operator.
- **MySQL / MariaDB** — no `ILIKE`; the case behavior of `LIKE`/`=` depends on the column
  **collation**, and the default collations (`utf8mb4_..._ci`) are case-insensitive.
- **SQLite** — no `ILIKE`; `LIKE` is case-insensitive for ASCII by default, case-sensitive
  beyond it.

So the *same* typed query — `Users.name eq "Ada"` — already returns different rows on different
engines (matches `"ada"` under a MySQL `_ci` collation, not under PostgreSQL/SQLite). String
comparison semantics are delegated to the engine's collation, which Kormium does not model.

Before this change the typed DSL had no scalar functions at all, so a user could not even write
`LOWER(col)` — case-insensitive matching meant dropping to `RawExpression`, losing parameter
binding and type-safety.

## Decision

Do **not** add an `ilike` predicate. Instead:

1. Expose `lower()` (and `upper()`/`trim()`/`ltrim()`/`rtrim()`) as **explicit, composable
   scalar functions** returning a typed `StringExpr`.
2. Keep `like` rendering native `LIKE` — its case behavior follows the engine collation, by
   design, and is documented as such.
3. Case-insensitive matching is written explicitly, lowering **both sides** where the reader can
   see it: `Users.name.lower() eq "ada"`, `a.lower() like b.lower()`.

`LOWER`/`UPPER`/`TRIM`/`LTRIM`/`RTRIM` are standard SQL and render identically on
PostgreSQL/MySQL/SQLite, so this needs no dialect hook.

## Consequences

Positive:

- **No hidden magic.** What runs is what the code says. There is no operator that silently
  normalizes case, and — importantly — none that silently changes which index the query can use
  (an `ILIKE` or an injected `LOWER` defeats a plain b-tree index; making that explicit keeps the
  index trade-off visible to the author).
- **Deterministic across engines.** `lower()` on both sides settles the case dimension uniformly,
  independent of the engine's default collation.
- **A general primitive, not a one-off.** `lower()` composes (`name.trim().lower()`), works in
  `select(...)` projections, and is reusable in `eq`/comparison — far more than a single `ilike`
  predicate would be. It is the first of the DSL's scalar functions.

Negative / costs:

- More verbose at the call site than `name ilike "a%"`, and the author must remember to lower
  **both** sides (a one-sided `lower(name) like "A%"` simply matches nothing — visibly).
- No terse `ILIKE`-shaped spelling that an LLM or a Postgres user reaches for by reflex; the
  collation note and `AGENTS.md` steer them to `lower()`.

## Alternatives considered

- **Native `ILIKE` on PostgreSQL via a dialect hook, `LOWER(...) LIKE LOWER(...)` elsewhere.**
  Rejected: it reintroduces cross-engine divergence (ILIKE's case-folding and `LOWER()` can
  differ on non-ASCII), and `LOWER(col)` actually indexes *better* for prefix search (a plain
  expression b-tree index, which `ILIKE` cannot use without a trigram GIN index). The supposed
  performance win for native `ILIKE` exists only on unindexed sequential scans.
- **An `ilike` predicate that desugars to `LOWER(lhs) LIKE LOWER(rhs)` everywhere.** Rejected
  once `lower()` exists: it would be a black box hiding the same SQL the user could write
  explicitly, and would hide the index implication — the opposite of the project's
  explicit-over-magic stance.
- **Make plain `like`/`eq` deterministically case-sensitive on every engine.** Deferred, not
  rejected outright: there is no clean per-query way on SQLite (only a connection-global
  `PRAGMA case_sensitive_like`, which would also affect users' raw SQL), and MySQL's `LIKE BINARY`
  over-reaches into accent/byte sensitivity and hurts index usage. A separate decision.

## Notes

`length()` was also deferred from the first scalar-function batch: it returns `Int` (a different
type lane) and is non-portable without a dialect hook (MySQL `LENGTH` counts bytes; character
length needs `CHAR_LENGTH`, absent in SQLite). Ordering by an expression (`ORDER BY lower(name)`)
remains unsupported, so case-insensitive keyset pagination is not yet fully expressible.
