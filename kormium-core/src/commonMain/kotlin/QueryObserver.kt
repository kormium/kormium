package io.github.kormium

import io.github.kormium.resultset.ResultSet
import kotlin.time.TimeSource

/** Coarse classification of a statement, derived from its leading SQL keyword. */
public enum class QueryKind {
    Select, Insert, Update, Delete, Other;

    public companion object {
        /** Classifies [sql] by its first keyword. Cheap: scans only the leading token. */
        public fun of(sql: String): QueryKind {
            var i = 0
            val n = sql.length
            // Skip leading whitespace and a single line comment / leading parenthesis (CTEs, etc.).
            while (i < n && sql[i].isWhitespace()) i++
            val start = i
            while (i < n && (sql[i].isLetter())) i++
            return when (sql.substring(start, i).uppercase()) {
                "SELECT", "WITH" -> Select
                "INSERT" -> Insert
                "UPDATE" -> Update
                "DELETE" -> Delete
                else -> Other
            }
        }
    }
}

/**
 * One observed statement execution, handed to a [QueryObserver] after the statement completes
 * (whether it succeeded or threw). Deliberately carries **no parameter values** — only the
 * already-parameterized [sql] template — so enabling observation can never leak secrets or PII.
 *
 * Intended as the single seam for query-level metrics and tracing: tag a timer by [backend] and
 * [kind], count failures by [sqlState], log [sql] for slow queries, etc.
 */
public class QueryEvent(
    /** Backend/dialect tag (the dialect implementation's simple name, e.g. "SqliteDialect"). */
    public val backend: String,
    /** The parameterized SQL template as sent to the driver. Contains placeholders, not values. */
    public val sql: String,
    /** Coarse operation kind derived from [sql]. */
    public val kind: QueryKind,
    /** Wall-clock duration of the statement, measured with a monotonic clock. */
    public val durationNanos: Long,
    /**
     * Rows returned (for queries) or rows affected (for INSERT/UPDATE/DELETE), when the backend
     * reports one; `null` when not applicable or unknown.
     */
    public val rowCount: Long?,
    /** The failure that ended the statement, or `null` on success. */
    public val error: Throwable?,
    /** SQLSTATE / backend error code when the failure carried one (see [QueryException.sqlState]). */
    public val sqlState: String?,
) {
    /** Whether the statement completed without throwing. */
    public val succeeded: Boolean get() = error == null
}

/**
 * Optional hook invoked once per executed statement. Set it on [KormiumConfig.queryObserver] to
 * observe every statement run through a scope — DSL operations, raw `execute`, and migrations —
 * across all backends, without wrapping repository methods.
 *
 * The callback runs synchronously on the executing thread, so keep it cheap (record a metric,
 * enqueue, log). Exceptions thrown by the observer are swallowed and never affect the query.
 */
public fun interface QueryObserver {
    public fun onQuery(event: QueryEvent)
}

/** Backend tag for an executor: the dialect implementation's simple name, stable enough to group by. */
internal fun backendNameOf(dialect: Dialect): String = dialect::class.simpleName ?: "unknown"

/** Emits a [QueryEvent], guarding against a misbehaving observer so it can never break a query. */
internal inline fun QueryObserver.emitGuarded(
    backend: String,
    sql: String,
    durationNanos: Long,
    rowCount: Long?,
    error: Throwable?,
) {
    try {
        onQuery(QueryEvent(backend, sql, QueryKind.of(sql), durationNanos, rowCount, error, (error as? QueryException)?.sqlState))
    } catch (_: Throwable) {
        // An observer must never affect the query path.
    }
}

/**
 * Decorates a blocking [SqlExecutor] so every statement is timed and reported to [observer].
 * Installed by [transaction] / [autocommit] only when [KormiumConfig.queryObserver] is set, so
 * the un-observed path keeps the bare backend executor and pays nothing.
 */
internal class ObservingSqlExecutor(
    private val inner: SqlExecutor,
    private val observer: QueryObserver,
) : SqlExecutor {
    override val dialect: Dialect get() = inner.dialect
    override val typeMapper: TypeMapper get() = inner.typeMapper
    private val backend = backendNameOf(inner.dialect)

    private inline fun <R> timed(sql: String, rows: (R) -> Long?, body: () -> R): R {
        val mark = TimeSource.Monotonic.markNow()
        try {
            val r = body()
            observer.emitGuarded(backend, sql, mark.elapsedNow().inWholeNanoseconds, rows(r), null)
            return r
        } catch (e: Throwable) {
            observer.emitGuarded(backend, sql, mark.elapsedNow().inWholeNanoseconds, null, e)
            throw e
        }
    }

    override fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> =
        timed(sql, { it.size.toLong() }) { inner.execute(sql, namedParameters, handler) }

    override fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        timed(sql, { it.size.toLong() }) { inner.execute(sql, paramSource, handler) }

    override fun execute(sql: String, namedParameters: Map<String, Any?>): Long =
        timed(sql, { it }) { inner.execute(sql, namedParameters) }

    override fun execute(sql: String, paramSource: SqlParameterSource): Long =
        timed(sql, { it }) { inner.execute(sql, paramSource) }

    override fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
        timed(sql, { it }) { inner.executeUpdate(sql, namedParameters) }
}

/** Suspend mirror of [ObservingSqlExecutor] for the async backends. */
internal class ObservingSuspendSqlExecutor(
    private val inner: SuspendSqlExecutor,
    private val observer: QueryObserver,
) : SuspendSqlExecutor {
    override val dialect: Dialect get() = inner.dialect
    override val typeMapper: TypeMapper get() = inner.typeMapper
    private val backend = backendNameOf(inner.dialect)

    private suspend fun <R> timed(sql: String, rows: (R) -> Long?, body: suspend () -> R): R {
        val mark = TimeSource.Monotonic.markNow()
        try {
            val r = body()
            observer.emitGuarded(backend, sql, mark.elapsedNow().inWholeNanoseconds, rows(r), null)
            return r
        } catch (e: Throwable) {
            observer.emitGuarded(backend, sql, mark.elapsedNow().inWholeNanoseconds, null, e)
            throw e
        }
    }

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> =
        timed(sql, { it.size.toLong() }) { inner.execute(sql, namedParameters, handler) }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        timed(sql, { it.size.toLong() }) { inner.execute(sql, paramSource, handler) }

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long =
        timed(sql, { it }) { inner.execute(sql, namedParameters) }

    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long =
        timed(sql, { it }) { inner.execute(sql, paramSource) }

    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
        timed(sql, { it }) { inner.executeUpdate(sql, namedParameters) }
}

/** Wraps [exec] for observation when [config] has an observer; otherwise returns it unchanged. */
internal fun SqlExecutor.observed(config: KormiumConfig): SqlExecutor =
    config.queryObserver?.let { ObservingSqlExecutor(this, it) } ?: this

/** Wraps [exec] for observation when [config] has an observer; otherwise returns it unchanged. */
internal fun SuspendSqlExecutor.observed(config: KormiumConfig): SuspendSqlExecutor =
    config.queryObserver?.let { ObservingSuspendSqlExecutor(this, it) } ?: this
