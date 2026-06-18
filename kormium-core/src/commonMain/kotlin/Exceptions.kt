package io.github.kormium

/** Base type for all korm errors. */
open class KormiumException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when a `usePinned` / `useConnection` (and therefore any `transaction` / `autocommit`)
 * is attempted on a [io.github.kormium.database.Database] /
 * [io.github.kormium.database.SuspendDatabase] whose [close][AutoCloseable.close] has already
 * been called. Every backend reports use-after-close as this single type, so callers can catch
 * it uniformly instead of a backend-specific closed-connection error.
 */
class DatabaseClosedException(message: String = "database is closed") : KormiumException(message)

/**
 * A SQL statement failed on the server. [sqlState] is the 5-character SQLSTATE code when
 * the backend reports one (e.g. "23505"); subtypes cover the common constraint violations.
 */
open class QueryException(message: String, val sqlState: String? = null, cause: Throwable? = null) :
    KormiumException(message, cause)

/** Unique / primary-key constraint violation (SQLSTATE 23505). */
class UniqueViolationException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/** Foreign-key constraint violation (SQLSTATE 23503). */
class ForeignKeyViolationException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/** NOT NULL constraint violation (SQLSTATE 23502). */
class NotNullViolationException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/** CHECK constraint violation (SQLSTATE 23514). */
class CheckViolationException(message: String, sqlState: String?, cause: Throwable? = null) :
    QueryException(message, sqlState, cause)

/**
 * A database row could not be mapped into an entity. The common case: a column the entity
 * declares non-null came back as SQL `NULL` (a schema mismatch or a bad row). The message names
 * the table and column so the offending row/schema is easy to find.
 */
class ResultMappingException(message: String, cause: Throwable? = null) : KormiumException(message, cause)

/** Maps a SQLSTATE to the most specific [QueryException] subtype. */
fun sqlException(message: String, sqlState: String?, cause: Throwable? = null): QueryException = when (sqlState) {
    "23505" -> UniqueViolationException(message, sqlState, cause)
    "23503" -> ForeignKeyViolationException(message, sqlState, cause)
    "23502" -> NotNullViolationException(message, sqlState, cause)
    "23514" -> CheckViolationException(message, sqlState, cause)
    else -> QueryException(message, sqlState, cause)
}
