package io.github.kormium.mysql.node

import io.github.kormium.DatabaseLifecycle
import io.github.kormium.KormiumConfig
import io.github.kormium.MySqlDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TransactionIsolation
import io.github.kormium.WriteListeners
import io.github.kormium.database.SuspendDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.withContext

/**
 * An async MySQL/MariaDB [SuspendDatabase] for Node, backed by a mysql2 connection [MyPool]. Each
 * [useConnection] borrows a [PoolConnection] for the block and returns it after — independent calls
 * run on independent connections (real concurrency, no Mutex). Suspend-only.
 */
class MySqlDatabase internal constructor(
    private val pool: MyPool,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect = MySqlDialect

    private val lifecycle = DatabaseLifecycle { pool.end() }

    override val isClosed: Boolean get() = lifecycle.isClosed

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        val connection = pool.getConnection().await<PoolConnection>()
        val exec = MySqlExecutor(connection, MySqlDialect, StandardTypeMapper)
        try {
            if (transactional) {
                // MySQL applies SET TRANSACTION to the next transaction, so set isolation before START.
                isolation?.let { exec.execute("SET TRANSACTION ISOLATION LEVEL ${it.sql}") }
                exec.execute(if (readOnly) "START TRANSACTION READ ONLY" else "START TRANSACTION")
            }
            return try {
                block(exec).also { if (transactional) exec.execute("COMMIT") }
            } catch (e: Throwable) {
                if (transactional) withContext(NonCancellable) { runCatching { exec.execute("ROLLBACK") } }
                throw e
            }
        } finally {
            connection.release()
        }
    }

    override fun close() = lifecycle.close()
}

private val TransactionIsolation.sql: String
    get() = when (this) {
        TransactionIsolation.ReadUncommitted -> "READ UNCOMMITTED"
        TransactionIsolation.ReadCommitted -> "READ COMMITTED"
        TransactionIsolation.RepeatableRead -> "REPEATABLE READ"
        TransactionIsolation.Serializable -> "SERIALIZABLE"
    }

/**
 * Opens a MySQL/MariaDB database over a mysql2 connection pool of [poolSize] connections. Tagged
 * [Nothing]; pin the catalog at the call site.
 */
fun createNodeMysqlDatabase(
    host: String,
    port: Int = 3306,
    database: String,
    user: String,
    password: String,
    poolSize: Int = 10,
    config: KormiumConfig = KormiumConfig(),
): MySqlDatabase = MySqlDatabase(createPool(mysqlPoolConfig(host, port, database, user, password, poolSize)), config)
