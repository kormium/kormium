package io.github.kormium.mysql

import io.github.kormium.ConnectionPool
import io.github.kormium.PoolExhaustedException
import io.github.kormium.DatabaseLifecycle
import io.github.kormium.KormiumConfig
import io.github.kormium.MySqlDialect
import io.github.kormium.MySqlDriver
import io.github.kormium.PinnedConnection
import io.github.kormium.SqlExecutor
import io.github.kormium.SqlParameterSource
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TransactionIsolation
import io.github.kormium.WriteListeners
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.mysql.exception.ConnectionClosedException
import io.github.kormium.mysql.exception.QueryExecutionException
import io.github.kormium.mysql.exception.mysqlException
import io.github.kormium.resultset.ResultSet
import io.github.kormium.runConnection
import io.github.kormium.runPinned
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import mysql.MYSQL
import mysql.MYSQL_BIND
import mysql.MYSQL_RES
import mysql.MYSQL_STMT
import mysql.MYSQL_TYPE_NULL
import mysql.MYSQL_TYPE_STRING
import mysql.mysql_close
import mysql.mysql_error
import mysql.mysql_fetch_fields
import mysql.mysql_init
import mysql.mysql_num_fields
import mysql.mysql_ping
import mysql.mysql_query
import mysql.mysql_real_connect
import mysql.mysql_set_character_set
import mysql.mysql_stmt_affected_rows
import mysql.mysql_stmt_bind_param
import mysql.mysql_stmt_bind_result
import mysql.mysql_stmt_close
import mysql.mysql_stmt_errno
import mysql.mysql_stmt_error
import mysql.mysql_stmt_execute
import mysql.mysql_stmt_fetch
import mysql.mysql_stmt_fetch_column
import mysql.mysql_stmt_init
import mysql.mysql_stmt_num_rows
import mysql.mysql_stmt_prepare
import mysql.mysql_stmt_result_metadata
import mysql.mysql_stmt_sqlstate
import mysql.mysql_stmt_store_result

// libmysql return codes for mysql_stmt_fetch (not always surfaced as cinterop constants).
private const val MYSQL_NO_DATA = 100
private const val MYSQL_DATA_TRUNCATED = 101

// Per-column result buffer size: large enough that numerics, dates, UUIDs and short text never
// truncate (so their length is reported on the first fetch); longer values refetch into an exact buffer.
private const val INLINE_BUFFER = 256

/** Either an update count (no result set) or fully-materialized rows. */
private sealed interface StatementResult {
    class Update(val count: Long) : StatementResult
    class Rows(val columns: Array<String>, val rows: List<Array<ByteArray?>>) : StatementResult
}

@OptIn(ExperimentalForeignApi::class)
internal class MySqlNativeDriver(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val user: String,
    private val password: String,
    private val poolSize: Int,
    private val acquireTimeout: Duration,
    override val config: KormiumConfig,
) : MySqlDriver, SuspendDatabase<Nothing> {

    init {
        require(poolSize >= 1) { "poolSize must be >= 1, was $poolSize" }
        require(acquireTimeout.isPositive()) { "acquireTimeout must be positive, was $acquireTimeout" }
    }

    // A bounded borrow: a saturated pool fails with a clear, catchable error instead of
    // hanging the caller forever (poolSize connections all pinned by long transactions).
    private fun poolExhausted(): Nothing = throw PoolExhaustedException(
        "connection pool exhausted: no connection became free within $acquireTimeout " +
            "(poolSize = $poolSize, all connections busy). Increase poolSize or shorten " +
            "the transactions/pinned blocks holding connections.",
    )

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect = MySqlDialect

    // A MySQL connection handle is single-threaded: one command at a time. We keep a fixed pool and
    // hand each connection to exactly one caller at a time through a Channel that doubles as the
    // "free connection" queue (the same model as the native Postgres driver).
    private val connections: List<MysqlPtr> = List(poolSize) { openConnection() }
    private val pool = Channel<MysqlPtr>(poolSize).also { ch -> connections.forEach { ch.trySend(it) } }

    // Open/closed state: idempotent close (drains the pool first) + use-after-close guard.
    private val lifecycle = DatabaseLifecycle {
        // Draining the pool first waits for any in-flight statement to return its connection, so
        // we never mysql_close one another thread still uses.
        runBlocking { repeat(poolSize) { mysql_close(pool.receive()) } }
        pool.close()
    }

    private fun openConnection(): MysqlPtr {
        val conn = mysql_init(null) ?: throw QueryExecutionException("mysql_init failed (out of memory)")
        val ok = mysql_real_connect(conn, host, user, password, database, port.convert(), null, 0.convert())
        if (ok == null) {
            val message = mysql_error(conn)?.toKString().orEmpty()
            mysql_close(conn)
            throw QueryExecutionException(message.ifEmpty { "Failed to connect to MySQL" })
        }
        // utf8mb4 so non-ASCII text (and emoji) round-trips; updates the client charset and issues
        // SET NAMES under the hood. mysql-connector-j already defaults to utf8mb4 on the JVM path.
        mysql_set_character_set(conn, "utf8mb4")
        // Pin the session to UTC so a TIMESTAMP round-trips with the UTC datetime text we bind/read.
        mysql_query(conn, "SET time_zone = '+00:00'")
        return conn
    }

    // mysql_ping reconnects a dropped connection when the MYSQL_OPT_RECONNECT default applies; if it
    // still fails the connection is dead, so reopen it from the stored credentials before reuse.
    private fun ensureAlive(conn: MysqlPtr): MysqlPtr {
        if (mysql_ping(conn) == 0) return conn
        mysql_close(conn)
        return openConnection()
    }

    private val connectionPool = object : ConnectionPool {
        override fun acquire(): PinnedConnection {
            val conn = pool.tryReceive().getOrNull() ?: try {
                runBlocking { withTimeoutOrNull(acquireTimeout) { pool.receive() } } ?: poolExhausted()
            } catch (_: ClosedReceiveChannelException) {
                throw ConnectionClosedException()
            }
            return MysqlPinnedConnection(ensureAlive(conn))
        }

        override suspend fun acquireSuspending(): PinnedConnection {
            val conn = pool.tryReceive().getOrNull() ?: try {
                withTimeoutOrNull(acquireTimeout) { pool.receive() } ?: poolExhausted()
            } catch (_: ClosedReceiveChannelException) {
                throw ConnectionClosedException()
            }
            return MysqlPinnedConnection(ensureAlive(conn))
        }
    }

    private inner class MysqlPinnedConnection(private val conn: MysqlPtr) : PinnedConnection {
        override val executor: SqlExecutor = MysqlExecutor(conn)

        // MySQL takes the isolation level via a SET TRANSACTION that applies to the *next*
        // transaction only (so it self-resets), then START TRANSACTION [READ ONLY].
        override fun begin(isolation: TransactionIsolation?, readOnly: Boolean) {
            if (isolation != null) simpleQuery(conn, "SET TRANSACTION ISOLATION LEVEL ${isolation.sql}")
            simpleQuery(conn, if (readOnly) "START TRANSACTION READ ONLY" else "START TRANSACTION")
        }
        override fun commit() { simpleQuery(conn, "COMMIT") }
        override fun rollback() { simpleQuery(conn, "ROLLBACK") }
        override fun release() { pool.trySend(conn) }
    }

    override fun <R> usePinned(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: (SqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        return connectionPool.runPinned(transactional, isolation, readOnly, block)
    }

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        return connectionPool.runConnection(transactional, isolation, readOnly, block)
    }

    override val isClosed: Boolean get() = lifecycle.isClosed

    override fun close() = lifecycle.close()

    private inner class MysqlExecutor(private val conn: MysqlPtr) : SqlExecutor {
        override val dialect = MySqlDialect
        override val typeMapper = MySqlNativeTypeMapper

        override fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T) =
            run(sql, namedParameters).read(handler)

        override fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T) =
            run(sql, paramSource.toMap()).read(handler)

        override fun execute(sql: String, namedParameters: Map<String, Any?>) = run(sql, namedParameters).count()

        override fun execute(sql: String, paramSource: SqlParameterSource) = run(sql, paramSource.toMap()).count()

        override fun executeUpdate(sql: String, namedParameters: Map<String, Any?>) = run(sql, namedParameters).count()

        private fun run(sql: String, namedParameters: Map<String, Any?>): StatementResult {
            val parsed = parseNamed(sql)
            val values = parsed.names.map { name ->
                if (!namedParameters.containsKey(name)) {
                    throw QueryExecutionException("No value supplied for the SQL parameter ':$name'")
                }
                MySqlNativeTypeMapper.toParameter(namedParameters[name])?.toString()
            }
            return runStatement(conn, parsed.sql, values)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private typealias MysqlPtr = kotlinx.cinterop.CPointer<MYSQL>

@OptIn(ExperimentalForeignApi::class)
private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }

@OptIn(ExperimentalForeignApi::class)
private fun StatementResult.count(): Long = when (this) {
    is StatementResult.Update -> count
    is StatementResult.Rows -> rows.size.toLong()
}

@OptIn(ExperimentalForeignApi::class)
private fun <T> StatementResult.read(handler: (ResultSet) -> T): List<T> = when (this) {
    is StatementResult.Update -> emptyList()
    is StatementResult.Rows -> {
        val rs = MySqlResultSet(columns, rows)
        ArrayList<T>(rows.size).apply { while (rs.next()) add(handler(rs)) }
    }
}

/** Runs a parameter-less statement (BEGIN/COMMIT/ROLLBACK/SET) via the text protocol. */
@OptIn(ExperimentalForeignApi::class)
private fun simpleQuery(conn: MysqlPtr, sql: String) {
    if (mysql_query(conn, sql) != 0) {
        throw mysqlException(mysql_error(conn)?.toKString().orEmpty().ifEmpty { "MySQL query failed" }, 0u)
    }
}

/**
 * Prepares [sql] (with `?` placeholders), binds [values] as text parameters, executes, and either
 * returns the affected-row count (no result set) or fully materializes the rows. All output columns
 * are bound as `MYSQL_TYPE_STRING`, so reading collapses to text.
 */
@OptIn(ExperimentalForeignApi::class)
private fun runStatement(conn: MysqlPtr, sql: String, values: List<String?>): StatementResult = memScoped {
    val stmt = mysql_stmt_init(conn) ?: throw QueryExecutionException("mysql_stmt_init failed")
    try {
        val sqlBytes = sql.encodeToByteArray()
        if (mysql_stmt_prepare(stmt, sql, sqlBytes.size.convert()) != 0) throwStmtError(stmt)

        if (values.isNotEmpty()) {
            val binds = allocArray<MYSQL_BIND>(values.size)
            values.forEachIndexed { k, v ->
                if (v == null) {
                    binds[k].buffer_type = MYSQL_TYPE_NULL
                } else {
                    val bytes = v.encodeToByteArray()
                    val buf = allocArray<ByteVar>(bytes.size + 1)
                    bytes.forEachIndexed { idx, byte -> buf[idx] = byte }
                    val lenVar = alloc<ULongVar>()
                    lenVar.value = bytes.size.convert()
                    binds[k].buffer_type = MYSQL_TYPE_STRING
                    binds[k].buffer = buf
                    binds[k].buffer_length = bytes.size.convert()
                    binds[k].length = lenVar.ptr
                }
            }
            if (mysql_stmt_bind_param(stmt, binds).toInt() != 0) throwStmtError(stmt)
        }

        if (mysql_stmt_execute(stmt) != 0) throwStmtError(stmt)

        val meta = mysql_stmt_result_metadata(stmt)
        if (meta == null) {
            StatementResult.Update(mysql_stmt_affected_rows(stmt).toLong())
        } else {
            try {
                fetchAll(stmt, meta)
            } finally {
                mysql.mysql_free_result(meta)
            }
        }
    } finally {
        mysql_stmt_close(stmt)
    }
}

/** Binds every column as a string, stores the result client-side, and materializes all rows. */
@OptIn(ExperimentalForeignApi::class)
private fun fetchAll(stmt: kotlinx.cinterop.CPointer<MYSQL_STMT>, meta: kotlinx.cinterop.CPointer<MYSQL_RES>): StatementResult.Rows =
    memScoped {
        val nFields = mysql_num_fields(meta).toInt()
        val fields = mysql_fetch_fields(meta)!!
        val names = Array(nFields) { fields[it].name!!.toKString() }

        val binds = allocArray<MYSQL_BIND>(nFields)
        val lengths = allocArray<ULongVar>(nFields)
        val isNulls = allocArray<ByteVar>(nFields)
        val errors = allocArray<ByteVar>(nFields)
        // Each column gets a real fixed buffer up front, not a zero-length one. A zero-length result
        // bind does not reliably report the converted length for binary-typed columns (a DOUBLE read
        // as MYSQL_TYPE_STRING comes back empty), so we bind a buffer big enough for numerics, dates,
        // UUIDs and short text, and only fall back to a per-column refetch when a value is truncated.
        val buffers = Array(nFields) { allocArray<ByteVar>(INLINE_BUFFER) }
        for (c in 0 until nFields) {
            binds[c].buffer_type = MYSQL_TYPE_STRING
            binds[c].buffer = buffers[c]
            binds[c].buffer_length = INLINE_BUFFER.convert()
            binds[c].length = lengths + c
            binds[c].is_null = isNulls + c
            binds[c].error = errors + c
        }
        if (mysql_stmt_bind_result(stmt, binds).toInt() != 0) throwStmtError(stmt)
        if (mysql_stmt_store_result(stmt) != 0) throwStmtError(stmt)

        val rows = ArrayList<Array<ByteArray?>>(mysql_stmt_num_rows(stmt).toInt())
        while (true) {
            val rc = mysql_stmt_fetch(stmt)
            if (rc == MYSQL_NO_DATA) break
            if (rc != 0 && rc != MYSQL_DATA_TRUNCATED) throwStmtError(stmt)
            val row = arrayOfNulls<ByteArray>(nFields)
            for (c in 0 until nFields) {
                if (isNulls[c].toInt() != 0) continue
                val len = lengths[c].toInt()
                row[c] = if (len <= INLINE_BUFFER) {
                    buffers[c].readBytes(len)
                } else {
                    // Value longer than the inline buffer (large TEXT/BLOB): refetch this column
                    // alone into an exactly-sized buffer, then restore the inline buffer.
                    val big = allocArray<ByteVar>(len)
                    binds[c].buffer = big
                    binds[c].buffer_length = len.convert()
                    mysql_stmt_fetch_column(stmt, binds[c].ptr, c.convert(), 0.convert())
                    val bytes = big.readBytes(len)
                    binds[c].buffer = buffers[c]
                    binds[c].buffer_length = INLINE_BUFFER.convert()
                    bytes
                }
            }
            rows.add(row)
        }
        StatementResult.Rows(names, rows)
    }

@OptIn(ExperimentalForeignApi::class)
private fun throwStmtError(stmt: kotlinx.cinterop.CPointer<MYSQL_STMT>): Nothing {
    val errno = mysql_stmt_errno(stmt)
    val message = mysql_stmt_error(stmt)?.toKString().orEmpty().ifEmpty { "MySQL statement failed" }
    val sqlState = mysql_stmt_sqlstate(stmt)?.toKString()?.takeIf { it.isNotBlank() && it != "00000" }
    throw mysqlException(message, errno, sqlState)
}
