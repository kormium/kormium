# ADR 0007 — `ConcurrencyConflictException` as a typed signal, not a retry helper

- Status: Accepted
- Date: 2026-07-01

## Context

Under `SERIALIZABLE` / `REPEATABLE READ`, or on a lock-order deadlock, a database aborts one of the
competing transactions with a transient error (PostgreSQL `40001` serialization_failure / `40P01`
deadlock_detected; MySQL maps a deadlock to `40001`). This is **expected** — PostgreSQL's own docs
say applications using `SERIALIZABLE` must be prepared to retry — and the correct response is to
**re-run the whole transaction**, not to fail.

Before this, such failures surfaced as a generic `QueryException`, so a caller could only react by
matching raw SQLSTATE strings (and knowing that MySQL hides a deadlock under `40001`).

The obvious convenience would be a `transactionRetrying(maxAttempts, …) { }` helper in the library.

## Decision

Add a typed exception, **`ConcurrencyConflictException`** (subtype of `QueryException`), and map
SQLSTATE `40001` / `40P01` to it in `sqlException()`. Do **not** ship a retry helper. The retry loop
lives in application code, catching this one type.

Split of responsibility:

- **The library owns the knowledge** — "this database error means a transient, retryable conflict",
  including the per-engine SQLSTATE quirks. A caller can't be expected to know that, so it belongs in
  the typed exception. This is the part with real library value.
- **The application owns the policy** — attempt count, backoff (none / fixed / jittered exponential),
  whether to treat deadlock differently, per-retry metrics/logging, circuit-breaking. Any default the
  library picked (e.g. 3 attempts, no backoff) would be too naive for production yet too opinionated
  to belong in the core.

```kotlin
fun <R> retrying(max: Int = 3, block: () -> R): R {
    repeat(max - 1) {
        try { return block() } catch (_: ConcurrencyConflictException) { /* transient: retry */ }
    }
    return block()
}
retrying { db.transaction(isolation = TransactionIsolation.SERIALIZABLE) { … } }
```

## Consequences

Positive:

- Callers catch one clear, portable type instead of matching SQLSTATE strings across engines.
- No hidden control flow in the library: a transaction block is never silently re-run by Kormium —
  consistent with the project's "no hidden magic / expose primitives" stance.
- The retried block's idempotency requirement (it re-runs, so no pre-commit side effects) stays
  visible in the caller's own loop, instead of being buried in a library helper.
- Sidesteps a real portability snag: there is no portable `sleep` in `commonMain`, so a library
  backoff would be half-baked; the application uses its platform's own (`delay` / `Thread.sleep`).

Negative / costs:

- No turnkey retry — every caller writes (or copies) the small loop. Mitigated by a ready-made recipe
  in `AGENTS.md`, which is also where an agent learns the pattern (docs are the right nudge — see
  [ADR 0006](0006-no-idiomatic-path-nudge.md)).
- `40001` covers both PostgreSQL serialization failures and MySQL deadlocks under one type; a caller
  that needs to tell them apart still reads `sqlState`. Accepted — the actionable fact ("retryable")
  is the same for both.

## Alternatives considered

- **`transactionRetrying(maxAttempts, backoff, …) { }` helper.** Rejected: retry policy is
  application-specific, a fixed default is wrong for many, a configurable one bloats the core, and it
  bakes hidden re-execution (and an idempotency footgun) into the library. The user made this call.
- **Two subtypes (`SerializationFailureException` + `DeadlockException`).** Rejected as more surface
  for no actionable difference — both mean "retry"; and `40001` already conflates the two across
  engines. One `ConcurrencyConflictException` is the thing you catch.
- **Leave it a generic `QueryException`.** Rejected: forces callers to hardcode SQLSTATE strings and
  to know the MySQL-deadlock-as-`40001` quirk — exactly the knowledge the library should encode.

## Notes

Mirrors the reasoning of [ADR 0006](0006-no-idiomatic-path-nudge.md): the library provides the typed
primitive and the documentation steers; it does not wrap application policy in API. Related:
[[korm-prod-ready-work]].
