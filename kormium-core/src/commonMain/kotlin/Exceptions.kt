package io.github.kormium

/** Base type for all Kormium errors. */
public open class KormiumException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when a `usePinned` / `useConnection` (and therefore any `transaction` / `autocommit`)
 * is attempted on a [io.github.kormium.database.Database] /
 * [io.github.kormium.database.SuspendDatabase] whose [close][AutoCloseable.close] has already
 * been called. Every backend reports use-after-close as this single type, so callers can catch
 * it uniformly instead of a backend-specific closed-connection error.
 */
public class DatabaseClosedException(message: String = "database is closed") : KormiumException(message)

/**
 * A SQL statement failed on the server. [sqlState] is the 5-character SQLSTATE code when
 * the backend reports one (e.g. "23505"); subtypes cover the common constraint violations.
 */
public open class QueryException(message: String, public val sqlState: String? = null, cause: Throwable? = null) :
    KormiumException(message, cause)

/** Unique / primary-key constraint violation (SQLSTATE 23505). */
public class UniqueViolationException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/** Foreign-key constraint violation (SQLSTATE 23503). */
public class ForeignKeyViolationException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/** NOT NULL constraint violation (SQLSTATE 23502). */
public class NotNullViolationException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/** CHECK constraint violation (SQLSTATE 23514). */
public class CheckViolationException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/**
 * A transient serialization failure or deadlock (SQLSTATE 40001 / 40P01): the database aborted this
 * transaction to preserve isolation. It is **safe to retry** — re-run the whole transaction. This is
 * expected under `SERIALIZABLE` / `REPEATABLE READ` and on lock-order deadlocks (PostgreSQL reports
 * `40001` / `40P01`; MySQL maps a deadlock to `40001`).
 *
 * Kormium deliberately ships this typed signal but no retry loop: the retry policy — attempt count,
 * backoff — is the application's, and the retried block must be idempotent outside the database since
 * it re-runs. See the retry recipe in `AGENTS.md` and [ADR 0007](../../docs/adr/0007-concurrency-conflict-exception.md).
 */
public class ConcurrencyConflictException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/**
 * The connection pool stayed exhausted for the whole acquire timeout: every pooled connection was
 * busy and none became free in time. This is a capacity/latency signal, not a SQL error — the
 * usual causes are a `poolSize` too small for the concurrency, or long-running
 * transactions/pinned blocks holding connections. Distinct from [QueryException] so callers can
 * respond with load shedding / retry / capacity alerts without string-matching.
 */
public class PoolExhaustedException(message: String) : KormiumException(message)

/**
 * A lock could not be acquired, so the statement gave up instead of waiting (SQLSTATE `55P03`,
 * `lock_not_available`). Two ways to get here, and both are the point of asking:
 *
 *  - [LockWait.NoWait] — the row was held by another transaction and you asked not to wait;
 *  - a server-side lock timeout (PostgreSQL `lock_timeout`, MySQL `innodb_lock_wait_timeout`).
 *
 * This is **not** [ConcurrencyConflictException]: a deadlock or serialization failure aborts the
 * whole transaction and is safe to retry as a unit, whereas here only the statement failed and the
 * transaction is still alive — so the caller decides whether to report the contention (the usual
 * answer for an interactive edit), retry the statement, or take the row a different way.
 *
 * [LockWait.SkipLocked] never produces this: skipping locked rows is not a failure.
 */
public class LockNotAvailableException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/**
 * A statement asked for something the backend's [Dialect] cannot render — e.g. a [RowLock] on
 * SQLite. The typed DSL prevents most of these at compile time (see [RowLockingBackend]); this
 * covers what a type cannot know, such as a MySQL server older than 8.0.1 for `SKIP LOCKED`, and
 * the [KormiumDialectApi] escape hatches.
 */
public class UnsupportedByDialectException(message: String) : KormiumException(message)

/**
 * A database row could not be mapped into an entity. The common case: a column the entity
 * declares non-null came back as SQL `NULL` (a schema mismatch or a bad row). The message names
 * the table and column so the offending row/schema is easy to find.
 */
public class ResultMappingException(message: String, cause: Throwable? = null) : KormiumException(message, cause)

/** Maps a SQLSTATE to the most specific [QueryException] subtype. */
public fun sqlException(message: String, sqlState: String?, cause: Throwable? = null): QueryException = when (sqlState) {
    "23505" -> UniqueViolationException(message, sqlState, cause)
    "23503" -> ForeignKeyViolationException(message, sqlState, cause)
    "23502" -> NotNullViolationException(message, sqlState, cause)
    "23514" -> CheckViolationException(message, sqlState, cause)
    "40001", "40P01" -> ConcurrencyConflictException(message, sqlState, cause)
    // PostgreSQL reports both a refused NOWAIT and an expired `lock_timeout` as lock_not_available.
    "55P03" -> LockNotAvailableException(message, sqlState, cause)
    else -> QueryException(message, sqlState, cause)
}
