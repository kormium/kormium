package io.github.kormium

/** Base type for all korm errors. */
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
    else -> QueryException(message, sqlState, cause)
}
