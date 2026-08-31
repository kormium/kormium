package io.github.kormium.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.kormium.ConnectionPool
import io.github.kormium.DatabaseLifecycle
import io.github.kormium.Dialect
import io.github.kormium.KormiumConfig
import io.github.kormium.PinnedConnection
import io.github.kormium.PoolExhaustedException
import io.github.kormium.ReadOnlyToggle
import io.github.kormium.SqlExecutor
import io.github.kormium.SqlParameterSource
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TransactionIsolation
import io.github.kormium.TypeMapper
import io.github.kormium.WriteListeners
import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.resultset.ResultSet
import io.github.kormium.runConnection
import io.github.kormium.runPinned
import io.github.kormium.sqlException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Wraps a driver [java.sql.ResultSet] in core's backend-agnostic [ResultSet]. */
public typealias ResultSetWrapper = (java.sql.ResultSet) -> ResultSet

/**
 * Translates a JDBC [SQLException] into a Kormium exception. Backends differ in how
 * they report constraint violations (Postgres via SQLSTATE, SQLite via result
 * codes), so each supplies its own mapping. The default maps the standard SQLSTATE.
 */
public typealias SqlExceptionTranslator = (SQLException) -> Throwable

/** The default translator: maps the JDBC SQLSTATE to a typed core exception. */
public val StandardSqlExceptionTranslator: SqlExceptionTranslator =
    { e -> sqlException(e.message ?: "SQL error", e.sqlState, e) }

/**
 * A generic JDBC-backed [Database], shared by every JDBC backend. It owns a HikariCP
 * connection pool and routes statements through a [JdbcExecutor]; the backend-specific
 * pieces — [dialect], [typeMapper], the [ResultSet] wrapper and the exception
 * [translate]or — are supplied by the concrete driver (Postgres, SQLite, ...).
 *
 * Open so a backend can subclass it purely to add its marker interface (e.g.
 * `class PostgresJdbcDriver(...) : JdbcDatabase(...), PostgresDriver`).
 *
 * @param onConnection run on every connection the pool opens, before it is handed out. Pass it
 *   when a connection needs preparing in code rather than in one SQL statement — SQLite uses it to
 *   install extensions and pragmas. Supplying it switches the pool onto a DataSource this module
 *   owns; leave it null and Hikari gets the plain [jdbcUrl] exactly as before.
 */
public open class JdbcDatabase(
    jdbcUrl: String,
    username: String? = null,
    password: String? = null,
    poolSize: Int,
    acquireTimeout: Duration = 30.seconds,
    override val dialect: Dialect,
    private val typeMapper: TypeMapper,
    private val wrap: ResultSetWrapper,
    private val translate: SqlExceptionTranslator = StandardSqlExceptionTranslator,
    connectionInitSql: String? = null,
    onConnection: ((Connection) -> Unit)? = null,
    override val config: KormiumConfig = KormiumConfig(),
) : Database<Nothing>, SuspendDatabase<Nothing> {

    // Supports change observation (kormium-observe): writes through this database notify here.
    override val writeListeners: WriteListeners = WriteListeners()

    private val poolSize = poolSize
    private val acquireTimeout = acquireTimeout

    private val ds: HikariDataSource = HikariDataSource(HikariConfig().apply {
        if (onConnection == null) {
            this.jdbcUrl = jdbcUrl
            if (username != null) this.username = username
            if (password != null) this.password = password
        } else {
            // Hikari offers no per-connection callback, and connectionInitSql is a single SQL
            // string — too little for work that needs real code (loading a SQLite extension) and
            // that must run on every connection the pool opens, including ones it recreates on
            // maxLifetime. Handing it a DataSource we own is the supported hook; the plain
            // jdbcUrl path above is untouched for callers that don't need it.
            this.dataSource = InitializingDataSource(jdbcUrl, username, password, onConnection)
        }
        this.maximumPoolSize = poolSize
        // HikariCP enforces a 250 ms floor; anything below is silently reset to the 30 s default,
        // so clamp to the floor instead of surprising the caller with a much larger value.
        this.connectionTimeout = acquireTimeout.inWholeMilliseconds.coerceAtLeast(250)
        if (connectionInitSql != null) this.connectionInitSql = connectionInitSql
    })

    // One pool, two entry points: usePinned (blocking) and useConnection (suspend) both
    // run on it. acquireSuspending uses the default (offload the blocking checkout).
    // Hikari reports checkout timeout as SQLTransientConnectionException; surface it as the
    // portable PoolExhaustedException all Kormium pools throw.
    private val pool = object : ConnectionPool {
        override fun acquire(): PinnedConnection {
            val connection = try {
                ds.connection
            } catch (e: SQLTransientConnectionException) {
                throw PoolExhaustedException(
                    "connection pool exhausted: no connection became free within " +
                        "${this@JdbcDatabase.acquireTimeout} (poolSize = ${this@JdbcDatabase.poolSize}, " +
                        "all connections busy). Increase poolSize or shorten the transactions " +
                        "holding connections. [${e.message}]",
                )
            }
            return JdbcPinnedConnection(connection, dialect, typeMapper, wrap, translate)
        }
    }

    private val lifecycle = DatabaseLifecycle { ds.close() }

    override val isClosed: Boolean get() = lifecycle.isClosed

    override fun close(): Unit = lifecycle.close()

    override fun <R> usePinned(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: (SqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        return pool.runPinned(transactional, isolation, readOnly, block)
    }

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        return pool.runConnection(transactional, isolation, readOnly, block)
    }
}

/** A [PinnedConnection] over one borrowed JDBC connection. */
private class JdbcPinnedConnection(
    private val conn: Connection,
    private val dialect: Dialect,
    typeMapper: TypeMapper,
    wrap: ResultSetWrapper,
    private val translate: SqlExceptionTranslator,
) : PinnedConnection {
    override val executor: SqlExecutor = JdbcExecutor(conn, dialect, typeMapper, wrap, translate)
    private var previousAutoCommit = true
    // Each is non-null only when begin() changed that setting, recording how to undo it on release:
    private var previousIsolation: Int? = null      // restore the driver isolation level
    private var previousReadOnly: Boolean? = null    // restore the native read-only flag
    private var readOnlyToggle: ReadOnlyToggle? = null // run this toggle's exit SQL (e.g. SQLite PRAGMA)

    override fun begin(isolation: TransactionIsolation?, readOnly: Boolean) {
        // Apply isolation / read-only before turning off autocommit, so the opening
        // transaction inherits them; capture the prior values to restore on release.
        if (isolation != null && dialect.supportsTransactionIsolation) {
            previousIsolation = conn.transactionIsolation
            conn.transactionIsolation = isolation.jdbcLevel
        }
        if (readOnly) {
            val toggle = dialect.readOnlyToggle
            if (toggle != null) {
                // Driver flag is unusable (sqlite-jdbc) — toggle read-only via SQL instead.
                conn.createStatement().use { it.execute(toggle.enter) }
                readOnlyToggle = toggle
            } else {
                previousReadOnly = conn.isReadOnly
                conn.isReadOnly = true
            }
        }
        previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
    }

    override fun commit() {
        try {
            conn.commit()
        } catch (e: SQLException) {
            throw translate(e)
        }
    }

    override fun rollback() {
        try {
            conn.rollback()
        } catch (e: SQLException) {
            throw translate(e)
        }
    }

    override fun release() {
        runCatching { conn.autoCommit = previousAutoCommit }
        // Restore per-borrow transaction state before the connection returns to the pool.
        readOnlyToggle?.let { t -> runCatching { conn.createStatement().use { it.execute(t.exit) } } }
        previousReadOnly?.let { ro -> runCatching { conn.isReadOnly = ro } }
        previousIsolation?.let { iso -> runCatching { conn.transactionIsolation = iso } }
        conn.close()
    }
}

/** Maps a portable [TransactionIsolation] to its `java.sql.Connection.TRANSACTION_*` constant. */
private val TransactionIsolation.jdbcLevel: Int
    get() = when (this) {
        TransactionIsolation.ReadUncommitted -> Connection.TRANSACTION_READ_UNCOMMITTED
        TransactionIsolation.ReadCommitted -> Connection.TRANSACTION_READ_COMMITTED
        TransactionIsolation.RepeatableRead -> Connection.TRANSACTION_REPEATABLE_READ
        TransactionIsolation.Serializable -> Connection.TRANSACTION_SERIALIZABLE
    }

/** An [SqlExecutor] bound to one already-open JDBC connection. */
internal class JdbcExecutor(
    private val conn: Connection,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
    private val wrap: ResultSetWrapper,
    private val translate: SqlExceptionTranslator = StandardSqlExceptionTranslator,
) : SqlExecutor {

    private inline fun <T> translateSql(block: () -> T): T =
        try {
            block()
        } catch (e: SQLException) {
            throw translate(e)
        }

    // Statements are closed after each call so pgjdbc can return the server-prepared
    // statement to its cache (enabling reuse on the next call) instead of leaking it —
    // a leaked statement forces a deferred CloseStatement + an extra protocol round-trip,
    // and defeats reuse (re-Parse) when the same query runs again on the connection.
    override fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> =
        translateSql {
            NamedParamStatement(conn, sql).use { statement ->
                for ((key, value) in namedParameters) statement.setAny(key, value)
                wrap(statement.executeQuery()).handleResults(handler)
            }
        }

    override fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        translateSql {
            NamedParamStatement(conn, sql).use { statement ->
                statement.bind(paramSource)
                wrap(statement.executeQuery()).handleResults(handler)
            }
        }

    override fun execute(sql: String, namedParameters: Map<String, Any?>): Long = translateSql {
        NamedParamStatement(conn, sql).use { statement ->
            for ((key, value) in namedParameters) statement.setAny(key, value)
            statement.preparedStatement.runReturningCount()
        }
    }

    override fun execute(sql: String, paramSource: SqlParameterSource): Long = translateSql {
        NamedParamStatement(conn, sql).use { statement ->
            statement.bind(paramSource)
            statement.preparedStatement.runReturningCount()
        }
    }

    override fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
        translateSql {
            NamedParamStatement(conn, sql).use { statement ->
                for ((key, value) in namedParameters) statement.setAny(key, value)
                statement.executeUpdate().toLong()
            }
        }
}

/**
 * Runs any statement (DDL, DML or query) and returns the row count for queries or the
 * update count otherwise. Uses [PreparedStatement.execute] so it works for statements
 * that do not produce a result set (e.g. CREATE TABLE).
 */
private fun PreparedStatement.runReturningCount(): Long =
    if (execute()) {
        resultSet.use { rs ->
            var size = 0L
            while (rs.next()) size++
            size
        }
    } else {
        updateCount.toLong()
    }

private fun <T> ResultSet.handleResults(handler: (ResultSet) -> T): List<T> {
    val list: MutableList<T> = mutableListOf()
    while (next()) {
        list.add(handler(this))
    }
    return list
}
