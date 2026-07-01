package io.github.kormium

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.resultset.ResultSet
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import csqlite.*

// SQLite's SQLITE_TRANSIENT (a (-1)-cast function pointer) is a macro cinterop can't
// expose, so reconstruct it: it tells sqlite3_bind_text to copy the bound string,
// freeing us from keeping the source alive past the bind call.
@OptIn(ExperimentalForeignApi::class)
private val SQLITE_TRANSIENT: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = (-1L).toCPointer()

@OptIn(ExperimentalForeignApi::class)
actual fun createSqliteDatabase(path: String, poolSize: Int, config: KormiumConfig): SqliteDriver =
    SqliteNativeDriver(path, poolSize, config)

@OptIn(ExperimentalForeignApi::class)
private class SqliteNativeDriver(path: String, private val poolSize: Int, override val config: KormiumConfig) : SqliteDriver, SuspendDatabase<Nothing> {

    init {
        require(poolSize >= 1) { "poolSize must be >= 1, was $poolSize" }
    }

    override val dialect: Dialect = SqliteDialect
    private val typeMapper: TypeMapper = StandardTypeMapper
    override val writeListeners: WriteListeners = WriteListeners()

    private val isMemory = path == ":memory:"

    // Shared cache so a pool of connections all see the same in-memory database; a URI
    // filename then requires SQLITE_OPEN_URI.
    private val filename = if (isMemory) "file::memory:?cache=shared" else path

    // A SQLite connection is handed to exactly one caller at a time via a Channel that
    // doubles as a blocking "free connection" queue — mirroring the libpq driver.
    private val connections: List<CPointer<sqlite3>> = List(poolSize) {
        openConnection(filename, uri = isMemory).also { initPragmas(it, file = !isMemory) }
    }

    private val pool = Channel<CPointer<sqlite3>>(poolSize).also { channel ->
        connections.forEach { channel.trySend(it) }
    }

    // Open/closed state: idempotent close (drains the pool once) + use-after-close guard.
    private val lifecycle = DatabaseLifecycle {
        runBlocking { repeat(poolSize) { sqlite3_close_v2(pool.receive()) } }
        pool.close()
    }

    // One pool, two entry points (blocking usePinned + suspend useConnection). acquire mirrors
    // withConnection's borrow; acquireSuspending overrides the default to take the Channel's
    // natural suspend path (pool.receive()) instead of offloading a blocking borrow.
    private val connectionPool = object : ConnectionPool {
        override fun acquire(): PinnedConnection {
            val connection = pool.tryReceive().getOrNull() ?: try {
                runBlocking { pool.receive() }
            } catch (_: ClosedReceiveChannelException) {
                throw QueryException("SQLite connection pool is closed")
            }
            return SqlitePinnedConnection(connection)
        }

        override suspend fun acquireSuspending(): PinnedConnection {
            val connection = pool.tryReceive().getOrNull() ?: try {
                pool.receive()
            } catch (_: ClosedReceiveChannelException) {
                throw QueryException("SQLite connection pool is closed")
            }
            return SqlitePinnedConnection(connection)
        }
    }

    private inner class SqlitePinnedConnection(private val conn: CPointer<sqlite3>) : PinnedConnection {
        override val executor: SqlExecutor = NativeExecutor(conn)
        private var readOnlyActive = false

        // SQLite has a single isolation level (effectively SERIALIZABLE), so [isolation] is
        // ignored; read-only is honored via the connection-level PRAGMA (sourced from
        // SqliteDialect so the toggle SQL lives in one place), reset on release.
        override fun begin(isolation: TransactionIsolation?, readOnly: Boolean) {
            rawExec(conn, "BEGIN")
            if (readOnly) {
                rawExec(conn, SqliteDialect.readOnlyToggle.enter)
                readOnlyActive = true
            }
        }
        override fun commit() { rawExec(conn, "COMMIT") }
        override fun rollback() { rawExec(conn, "ROLLBACK") }
        // If the pool is already closed (a release racing close()), close the connection
        // directly instead of silently leaking it.
        override fun release() {
            if (readOnlyActive) {
                runCatching { rawExec(conn, SqliteDialect.readOnlyToggle.exit) }
                readOnlyActive = false
            }
            if (pool.trySend(conn).isFailure) sqlite3_close_v2(conn)
        }
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

    // An SqlExecutor bound to the pinned connection for the duration of usePinned.
    private inner class NativeExecutor(private val conn: CPointer<sqlite3>) : SqlExecutor {
        override val dialect = SqliteDialect
        override val typeMapper = StandardTypeMapper

        override fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T) =
            query(conn, sql, mapBinder(namedParameters)).handleResults(handler)

        override fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T) =
            query(conn, sql, sourceBinder(paramSource)).handleResults(handler)

        override fun execute(sql: String, namedParameters: Map<String, Any?>) =
            updateOrCount(conn, sql, mapBinder(namedParameters))

        override fun execute(sql: String, paramSource: SqlParameterSource) =
            updateOrCount(conn, sql, sourceBinder(paramSource))

        override fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
            updateOrCount(conn, sql, mapBinder(namedParameters))
    }

    override val isClosed: Boolean get() = lifecycle.isClosed

    override fun close() = lifecycle.close()

    // ---- statement execution -------------------------------------------------------

    // Binds the named placeholders of a prepared statement. A Binder reads the
    // parameter names off the statement (":name") and binds each from its source.
    private fun interface Binder {
        fun bind(stmt: CPointer<sqlite3_stmt>)
    }

    private fun mapBinder(params: Map<String, Any?>) = Binder { stmt ->
        bindByName(stmt, params::containsKey) { params[it] }
    }

    private fun sourceBinder(source: SqlParameterSource) = Binder { stmt ->
        bindByName(stmt, source::hasValue, source::getValue)
    }

    private fun bindByName(
        stmt: CPointer<sqlite3_stmt>,
        hasValue: (String) -> Boolean,
        getValue: (String) -> Any?,
    ) {
        val count = sqlite3_bind_parameter_count(stmt)
        for (i in 1..count) {
            val name = sqlite3_bind_parameter_name(stmt, i)?.toKString()?.removePrefix(":") ?: continue
            // SQLite leaves an unbound placeholder as NULL; a missing key is a typo, not an
            // explicit null, so reject it to fail fast like the JDBC path. Explicit `null`
            // values are still bound (hasValue is true, getValue returns null) below.
            require(hasValue(name)) { "No value supplied for parameter \"$name\"" }
            bindValue(stmt, i, getValue(name))
        }
    }

    private fun bindValue(stmt: CPointer<sqlite3_stmt>, i: Int, value: Any?) {
        when (value) {
            null -> sqlite3_bind_null(stmt, i)
            is Boolean -> sqlite3_bind_int(stmt, i, if (value) 1 else 0)
            is Int -> sqlite3_bind_int(stmt, i, value)
            is Short -> sqlite3_bind_int(stmt, i, value.toInt())
            is Long -> sqlite3_bind_int64(stmt, i, value)
            is Float -> sqlite3_bind_double(stmt, i, value.toDouble())
            is Double -> sqlite3_bind_double(stmt, i, value)
            // cinterop maps const char* to String?; SQLITE_TRANSIENT makes SQLite copy it
            // immediately, so the temporary C string need not outlive this call.
            else -> sqlite3_bind_text(stmt, i, value.toString(), -1, SQLITE_TRANSIENT)
        }
    }

    private fun query(conn: CPointer<sqlite3>, sql: String, binder: Binder): SqliteResultSet = memScoped {
        val stmt = prepare(conn, sql)
        try {
            binder.bind(stmt)
            val colCount = sqlite3_column_count(stmt)
            val names = Array(colCount) { sqlite3_column_name(stmt, it)?.toKString() ?: "" }
            val rows = ArrayList<Array<Any?>>()
            while (true) {
                when (sqlite3_step(stmt)) {
                    SQLITE_ROW -> rows.add(Array(colCount) { readColumn(stmt, it) })
                    SQLITE_DONE -> break
                    else -> throwSqlite(conn)
                }
            }
            SqliteResultSet(names, rows)
        } finally {
            sqlite3_finalize(stmt)
        }
    }

    private fun updateOrCount(conn: CPointer<sqlite3>, sql: String, binder: Binder): Long = memScoped {
        val stmt = prepare(conn, sql)
        try {
            binder.bind(stmt)
            if (sqlite3_column_count(stmt) > 0) {
                // A query: report the number of rows it would yield.
                var n = 0L
                while (true) {
                    when (sqlite3_step(stmt)) {
                        SQLITE_ROW -> n++
                        SQLITE_DONE -> break
                        else -> throwSqlite(conn)
                    }
                }
                n
            } else {
                if (sqlite3_step(stmt) != SQLITE_DONE) throwSqlite(conn)
                sqlite3_changes(conn).toLong()
            }
        } finally {
            sqlite3_finalize(stmt)
        }
    }

    private fun prepare(conn: CPointer<sqlite3>, sql: String): CPointer<sqlite3_stmt> = memScoped {
        val stmtPtr = alloc<CPointerVar<sqlite3_stmt>>()
        if (sqlite3_prepare_v2(conn, sql, -1, stmtPtr.ptr, null) != SQLITE_OK) throwSqlite(conn)
        stmtPtr.value ?: throwSqlite(conn)
    }

    private fun readColumn(stmt: CPointer<sqlite3_stmt>, i: Int): Any? = when (sqlite3_column_type(stmt, i)) {
        SQLITE_NULL -> null
        SQLITE_INTEGER -> sqlite3_column_int64(stmt, i)
        SQLITE_FLOAT -> sqlite3_column_double(stmt, i)
        SQLITE_TEXT -> sqlite3_column_text(stmt, i)?.reinterpret<ByteVar>()?.toKString()
        SQLITE_BLOB -> {
            val size = sqlite3_column_bytes(stmt, i)
            sqlite3_column_blob(stmt, i)?.reinterpret<ByteVar>()?.readBytes(size) ?: ByteArray(0)
        }
        else -> null
    }

    private fun rawExec(conn: CPointer<sqlite3>, sql: String) {
        if (sqlite3_exec(conn, sql, null, null, null) != SQLITE_OK) throwSqlite(conn)
    }

    private fun throwSqlite(conn: CPointer<sqlite3>): Nothing {
        val code = sqlite3_extended_errcode(conn)
        val message = sqlite3_errmsg(conn)?.toKString() ?: "SQLite error"
        throw sqliteException(message, code)
    }

    private fun <T> SqliteResultSet.handleResults(handler: (ResultSet) -> T): List<T> {
        val list = ArrayList<T>()
        while (next()) list.add(handler(this))
        return list
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun openConnection(filename: String, uri: Boolean): CPointer<sqlite3> = memScoped {
    val dbPtr = alloc<CPointerVar<sqlite3>>()
    var flags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX
    if (uri) flags = flags or SQLITE_OPEN_URI
    val rc = sqlite3_open_v2(filename, dbPtr.ptr, flags, null)
    val db = dbPtr.value
    if (rc != SQLITE_OK || db == null) {
        val message = db?.let { sqlite3_errmsg(it)?.toKString() } ?: "cannot open database"
        if (db != null) sqlite3_close_v2(db)
        throw QueryException("Failed to open SQLite database '$filename': $message")
    }
    db
}

// foreign_keys are OFF by default in SQLite; WAL only applies to a real file. busy_timeout
// lets a blocked writer wait instead of failing immediately with SQLITE_BUSY.
@OptIn(ExperimentalForeignApi::class)
private fun initPragmas(conn: CPointer<sqlite3>, file: Boolean) {
    if (file) sqlite3_exec(conn, "PRAGMA journal_mode=WAL", null, null, null)
    sqlite3_exec(conn, "PRAGMA foreign_keys=ON", null, null, null)
    sqlite3_exec(conn, "PRAGMA busy_timeout=5000", null, null, null)
}
