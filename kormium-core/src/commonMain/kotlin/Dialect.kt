package io.github.kormium

/**
 * Backend-specific SQL rendering. The agnostic core builds statements through a
 * [Dialect] instead of hardcoding one SQL flavour, so other backends (SQLite, ...)
 * can plug in their own rendering.
 */
public interface Dialect {
    /** Quotes a column/table identifier. */
    public fun quoteIdentifier(name: String): String

    /** Renders the bind placeholder for [name], optionally casting based on [value]'s type. */
    public fun renderBind(name: String, value: Any?): String

    /** Renders a `LIMIT` clause (trailing space included), or "" when unbounded. */
    public fun renderLimit(limit: UInt): String

    /** Renders an `OFFSET` clause (trailing space included), or "" when zero. */
    public fun renderOffset(offset: UInt): String

    /**
     * Renders the combined `LIMIT`/`OFFSET` tail. The default composes the two independent
     * [renderLimit] + [renderOffset] clauses, which is correct for backends that accept a bare
     * `OFFSET` — Standard and PostgreSQL. MySQL and SQLite do not: `OFFSET` without `LIMIT` is a
     * syntax error on both, so their dialects override this to emit the two together, with a
     * backend-specific "no limit" sentinel when only an offset is set.
     */
    public fun renderLimitOffset(limit: UInt, offset: UInt): String =
        renderLimit(limit) + renderOffset(offset)

    /**
     * SQL that acquires a transaction-scoped advisory lock keyed by [key], or `null` when the
     * backend has no such mechanism. Used by `kormium-migrate` to serialize a migration run across
     * concurrently-starting application instances; the lock is released automatically when the
     * surrounding transaction commits. The default is `null` (no lock).
     */
    public fun advisoryLockSql(key: Long): String? = null

    // ---- write-path rendering (Postgres/SQLite-flavoured by default; MySQL overrides) ----

    /**
     * Whether the backend supports `INSERT ... RETURNING`. When `false` (MySQL), an
     * `insert(returning = true)` runs the insert without a RETURNING clause and re-selects the
     * stored row by primary key instead. Default `true` (Postgres and SQLite both support it).
     */
    public val supportsReturning: Boolean get() = true

    /**
     * The full `INSERT` statement for a row with no assigned columns (every column takes its DB
     * default). Standard SQL is `INSERT INTO t DEFAULT VALUES`; MySQL has no `DEFAULT VALUES` and
     * spells it `INSERT INTO t () VALUES ()`.
     */
    public fun renderInsertDefaultValues(qualifiedTable: String): String =
        "INSERT INTO $qualifiedTable DEFAULT VALUES"

    /**
     * The conflict/update tail of an upsert, appended to `INSERT INTO t (...) VALUES (...) `.
     * [conflictColumns] are already quoted and [setClause] already rendered (`"col" = :bind, ...`).
     * Standard SQL targets the conflict columns (`ON CONFLICT (...) DO UPDATE SET ...`); MySQL keys
     * on any declared unique/primary index and ignores the column list (`ON DUPLICATE KEY UPDATE`).
     */
    public fun renderUpsertSuffix(conflictColumns: List<String>, setClause: String): String =
        "ON CONFLICT (${conflictColumns.joinToString(", ")}) DO UPDATE SET $setClause"

    /**
     * The conflict tail of an insert-or-ignore, appended to `INSERT INTO t (...) VALUES (...) `.
     * [conflictColumns] are already quoted. Standard SQL is `ON CONFLICT (...) DO NOTHING`; MySQL
     * uses a no-op `ON DUPLICATE KEY UPDATE col = col` (ignores only a duplicate-key conflict).
     */
    public fun renderInsertOrIgnoreSuffix(conflictColumns: List<String>): String =
        "ON CONFLICT (${conflictColumns.joinToString(", ")}) DO NOTHING"

    /**
     * The function that counts **characters** of a string. Standard `LENGTH(...)` counts characters
     * on PostgreSQL and SQLite, but **bytes** on MySQL — so MySQL overrides this with `CHAR_LENGTH`
     * to keep `length()` portable (a multibyte string counts the same everywhere).
     */
    public fun renderCharLength(arg: String): String = "LENGTH($arg)"

    // ---- transaction options (isolation / read-only); see [TransactionIsolation] ----

    /**
     * Whether the backend honors a settable transaction isolation level. When `false` (SQLite,
     * which has a single effective level ≈ `SERIALIZABLE`), a [TransactionIsolation] passed to a
     * transaction is silently ignored rather than emulated or rejected. Default `true`.
     */
    public val supportsTransactionIsolation: Boolean get() = true

    /**
     * SQL a backend runs to enter / leave a read-only transaction, for databases that toggle
     * read-only with a statement rather than a native connection flag. `null` (the default) means
     * there is no such statement, so the JDBC backend falls back to `Connection.setReadOnly`.
     * SQLite has no native flag (and sqlite-jdbc rejects `setReadOnly` after connect), so
     * [SqliteDialect] supplies `PRAGMA query_only` here — the single source the JDBC and the
     * native/Android SQLite backends all read.
     */
    public val readOnlyToggle: ReadOnlyToggle? get() = null
}

/**
 * A pair of connection-level statements that turn read-only mode on ([enter]) and off ([exit]),
 * used by a backend whose database toggles read-only with a statement rather than a native
 * connection flag (see [Dialect.readOnlyToggle]).
 */
public data class ReadOnlyToggle(val enter: String, val exit: String)

/**
 * Standard-SQL rendering: double-quoted identifiers, `:name` placeholders, plain
 * `LIMIT`/`OFFSET`. Serves as the agnostic default (e.g. [Query] debug rendering)
 * and a base that backend dialects can delegate to.
 */
public object StandardDialect : Dialect {
    override fun quoteIdentifier(name: String): String =
        "\"${name.replace("\"", "\"\"")}\""

    override fun renderBind(name: String, value: Any?): String = ":$name"

    override fun renderLimit(limit: UInt): String =
        if (limit != UInt.MAX_VALUE) "LIMIT $limit " else ""

    override fun renderOffset(offset: UInt): String =
        if (offset != 0u) "OFFSET $offset " else ""
}
