# ADR 0004 — Subqueries: correlated `EXISTS` via `any` / `none`, not a subquery-as-value

- Status: Accepted
- Date: 2026-06-30

## Context

Users need subqueries — most often "is there a related row?" (`EXISTS`), sometimes `IN (SELECT …)`
or a scalar comparison (`price > (SELECT AVG(price))`).

The obvious typed-DSL design is a **subquery value**: build a `SELECT` you can embed, e.g.
`col inSubquery select(Orders.userId) { where { … } }`. Every spelling of this was awkward:

- it nests a whole `SELECT … FROM … WHERE …` as a value inside a predicate, breaking the DSL's
  flat `find { where { } }` shape;
- it forces a wrapper type and a name; `col inSubquery subquery(...)` even stutters;
- the table gets named twice (`Orders.subquery(Orders.userId)`).

This is structural, not a naming problem: representing a nested query as an embeddable value never
reads cleanly in a flat DSL.

## Decision

Do **not** model a subquery-as-value. Model the common case — a correlated existence check — as a
**Kotlin-idiomatic quantifier on the table**:

```kotlin
Table.any { predicate }   // EXISTS (SELECT 1 FROM table WHERE predicate)
Table.none { predicate }  // NOT EXISTS (...)
```

The predicate is an ordinary boolean expression that references the outer query's columns to
correlate:

```kotlin
Users.find { where { Orders.any { (Orders.userId eq Users.id) and (Orders.total gt 100) } } }
Users.find { where { Orders.none { Orders.userId eq Users.id } } }
```

- This also covers **`IN (SELECT …)`**: `id IN (SELECT userId FROM orders WHERE …)` is the same as
  `Orders.any { (Orders.userId eq Users.id) and … }`, and reads clearer (the correlation is visible).
- A **scalar** subquery is written as a typed comparison against a `RawExpression`, which already
  works through the generic `Expression`-to-`Expression` operators:
  `Products.price gt RawExpression("(SELECT AVG(\"price\") FROM \"products\")")`.

Correlation requires both sides qualified (`orders.userId = users.id`). `ParamBuilder.qualifyColumns`
becomes flippable, and the `EXISTS` predicate renders inside `builder.qualified { }` (then restores),
so outer and inner columns don't collide while parameters keep flowing into the one builder in order.

## Consequences

Positive:

- The DSL stays flat and reads like Kotlin (`any` / `none` are everyday idioms); no nested
  query-as-value, no wrapper type, no stutter.
- Covers existence **and** the common `IN (SELECT)` case with one clean construct.
- Correlation is explicit and visible (`Orders.userId eq Users.id`), so the SQL is obvious.

Negative / costs:

- Scalar subqueries are not first-class — they go through `RawExpression`, which is verbatim and
  cannot bind parameters (fine for static aggregate subqueries, on the author for anything with input).
- Subqueries in `SELECT` / `FROM`, `UNION`, CTEs, etc. remain out of scope (raw SQL).
- `qualifyColumns` is now mutable on `ParamBuilder` (a small, internal, save/restore concession).

## Alternatives considered

- **Subquery-as-value** (`col inSubquery select(col) { where }`, scalar `col gt select(...)`).
  Rejected: it never reads cleanly in a flat DSL, names the table twice, and the operator/value
  naming stutters. This was the first direction and it was abandoned after several syntax attempts.
- **Drop subqueries entirely**, leaving everything to `RawExpression`. Seriously considered — it fits
  the project's "deliberate slice + escape hatch" stance — but correlated `EXISTS` is high-value and
  common, and `any` / `none` express it cleanly enough to be worth modeling.
- **A typed scalar-subquery value.** Deferred: scalar subqueries are rarer, and the typed-comparison
  against `RawExpression` covers them without an ugly value type.

## Notes

The `any` / `none` reframe came directly from rejecting the subquery-as-value approach: once the goal
is "is there a related row?" rather than "embed a SELECT", the Kotlin `any`/`none` quantifier is the
natural fit. A first-class scalar subquery can be revisited later if a real need appears.
